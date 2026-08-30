package opencloud

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"path"
	"strings"
)

// Space donne accès aux fichiers d'un espace OpenCloud.
type Space struct {
	c       *Client
	davBase *url.URL
}

// Space construit un accès aux fichiers d'un espace.
func (c *Client) Space(d Drive) (*Space, error) {
	if d.WebDavURL == "" {
		return nil, fmt.Errorf("opencloud: l'espace %q n'expose pas d'URL WebDAV", d.Name)
	}
	u, err := url.Parse(strings.TrimRight(d.WebDavURL, "/"))
	if err != nil {
		return nil, fmt.Errorf("opencloud: URL WebDAV invalide %q: %w", d.WebDavURL, err)
	}
	if u.Scheme != "https" || u.Host == "" {
		return nil, fmt.Errorf("opencloud: URL WebDAV invalide %q: HTTPS obligatoire", d.WebDavURL)
	}
	return &Space{c: c, davBase: u}, nil
}

// resourceURL construit l'URL d'une ressource de l'espace.
//
// Le chemin est affecté à u.Path sous sa forme décodée, et url.URL se charge
// de l'encodage au moment du String(). C'est essentiel ici pour deux raisons :
//
//   - les identifiants d'espace contiennent un '$' que le serveur attend
//     littéral ; '$' est un sub-delim RFC 3986, que Go laisse tel quel dans un
//     chemin ;
//   - les noms de notes en français contiennent des accents et des espaces,
//     que Go percent-encode correctement en UTF-8.
//
// Passer par url.PathEscape sur le chemin complet serait faux : cela
// encoderait aussi les '/' séparateurs.
func (s *Space) resourceURL(p string, dir bool) *url.URL {
	u := *s.davBase
	u.Path = path.Join(u.Path, cleanRelPath(p))
	if dir && !strings.HasSuffix(u.Path, "/") {
		u.Path += "/"
	}
	return &u
}

// cleanRelPath normalise un chemin et le contient dans l'espace.
//
// Sans cette étape, path.Join résoudrait les « .. » et « ../../x » sortirait
// de la racine de l'espace pour viser une autre URL du serveur. Ancrer le
// chemin sur « / » avant de le nettoyer neutralise cette remontée :
// path.Clean("/../../x") vaut "/x".
func cleanRelPath(p string) string {
	cleaned := strings.Trim(path.Clean("/"+p), "/")
	if cleaned == "." {
		return ""
	}
	return cleaned
}

// List renvoie le contenu direct d'un dossier, hors dossier lui-même.
// Un dir vide désigne la racine de l'espace.
func (s *Space) List(ctx context.Context, dir string) ([]Resource, error) {
	data, _, err := s.c.do(ctx, "PROPFIND", s.resourceURL(dir, true), []byte(propfindBody), map[string]string{
		"Depth":        "1",
		"Content-Type": "application/xml",
	})
	if err != nil {
		return nil, err
	}

	all, err := parseMultistatus(data, s.davBase.Path)
	if err != nil {
		return nil, err
	}

	// Un PROPFIND Depth 1 renvoie d'abord le dossier interrogé lui-même :
	// on l'écarte pour ne garder que son contenu.
	//
	// La comparaison se fait sur le chemin normalisé, et non sur dir tel quel :
	// le serveur répond avec des href déjà résolus, donc « Notes/ », « /Notes »
	// ou « a/../Notes » ne correspondraient jamais à « Notes » et le dossier
	// apparaîtrait dans son propre listing.
	self := cleanRelPath(dir)
	out := all[:0]
	for _, r := range all {
		if r.Path == self {
			continue
		}
		out = append(out, r)
	}
	return out, nil
}

// Stat renvoie les métadonnées d'une ressource unique.
func (s *Space) Stat(ctx context.Context, p string) (Resource, error) {
	data, _, err := s.c.do(ctx, "PROPFIND", s.resourceURL(p, false), []byte(propfindBody), map[string]string{
		"Depth":        "0",
		"Content-Type": "application/xml",
	})
	if err != nil {
		return Resource{}, err
	}

	all, err := parseMultistatus(data, s.davBase.Path)
	if err != nil {
		return Resource{}, err
	}
	if len(all) == 0 {
		return Resource{}, fmt.Errorf("opencloud: %s: %w", p, ErrNotFound)
	}
	return all[0], nil
}

// Read renvoie le contenu d'une note et son ETag.
func (s *Space) Read(ctx context.Context, p string) ([]byte, string, error) {
	data, hdr, err := s.c.do(ctx, http.MethodGet, s.resourceURL(p, false), nil, nil)
	if err != nil {
		return nil, "", err
	}
	return data, hdr.Get("ETag"), nil
}

// Write écrit le contenu d'une note et renvoie le nouvel ETag.
//
// Si ifMatch n'est pas vide, l'écriture n'aboutit que si l'ETag distant est
// toujours celui-ci ; sinon le serveur répond 412 et l'erreur renvoyée
// satisfait errors.Is(err, ErrConflict).
//
// C'est le seul garde-fou disponible contre l'écrasement d'une version plus
// récente : le serveur n'annonce pas la classe WebDAV 2, donc LOCK/UNLOCK
// n'existent pas. Passer une chaîne vide écrase inconditionnellement.
func (s *Space) Write(ctx context.Context, p string, content []byte, ifMatch string) (string, error) {
	headers := map[string]string{"Content-Type": "text/markdown"}
	if ifMatch != "" {
		headers["If-Match"] = ifMatch
	}

	_, hdr, err := s.c.do(ctx, http.MethodPut, s.resourceURL(p, false), content, headers)
	if err != nil {
		return "", err
	}
	return hdr.Get("ETag"), nil
}

// WriteNew écrit une note en exigeant qu'elle n'existe pas encore.
//
// Sert aux notes créées hors connexion : au moment de la création, on ne peut
// pas savoir si le serveur porte déjà ce nom. Un PUT inconditionnel écraserait
// alors une note écrite ailleurs entre-temps.
//
// L'en-tête « If-None-Match: * » demande au serveur de refuser si la ressource
// existe. Le refus arrive en 412, donc comme un conflit ordinaire — et la
// résolution de conflit, qui conserve les deux versions, s'applique telle
// quelle.
func (s *Space) WriteNew(ctx context.Context, p string, content []byte) (string, error) {
	_, hdr, err := s.c.do(ctx, http.MethodPut, s.resourceURL(p, false), content, map[string]string{
		"Content-Type":  "text/markdown",
		"If-None-Match": "*",
	})
	if err != nil {
		return "", err
	}
	return hdr.Get("ETag"), nil
}

// Mkdir crée un dossier. Si le dossier existe déjà, l'erreur renvoyée
// satisfait errors.Is(err, ErrExists).
func (s *Space) Mkdir(ctx context.Context, p string) error {
	_, _, err := s.c.do(ctx, "MKCOL", s.resourceURL(p, true), nil, nil)

	// Sur un chemin déjà occupé, OpenCloud répond 405. Ce code n'est pas
	// traduit dans HTTPError.Unwrap car sa signification dépend de la
	// méthode : la traduction se fait donc ici.
	var he *HTTPError
	if errors.As(err, &he) && he.Status == http.StatusMethodNotAllowed {
		return fmt.Errorf("opencloud: %s: %w", p, ErrExists)
	}
	return err
}

// MkdirAll crée un dossier et tous ses parents manquants.
// Un dossier déjà présent n'est pas une erreur.
func (s *Space) MkdirAll(ctx context.Context, p string) error {
	p = cleanRelPath(p)
	if p == "" {
		return nil
	}

	current := ""
	for _, segment := range strings.Split(p, "/") {
		current = path.Join(current, segment)
		if err := s.Mkdir(ctx, current); err != nil && !errors.Is(err, ErrExists) {
			return err
		}
	}
	return nil
}

// Move renomme ou déplace une ressource. La destination ne doit pas exister.
func (s *Space) Move(ctx context.Context, from, to string) error {
	destination := s.resourceURL(to, false)
	_, _, err := s.c.do(ctx, "MOVE", s.resourceURL(from, false), nil, map[string]string{
		"Destination": destination.String(),
		"Overwrite":   "F",
	})
	return err
}

// Remove supprime une ressource. Sur un dossier, la suppression est récursive.
func (s *Space) Remove(ctx context.Context, p string) error {
	_, _, err := s.c.do(ctx, http.MethodDelete, s.resourceURL(p, false), nil, nil)
	return err
}
