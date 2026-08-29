package opencloud

import (
	"context"
	"errors"
	"fmt"
)

// DefaultSearchLimit borne le nombre de résultats demandés au service de
// recherche.
//
// Le serveur honore la valeur telle quelle — mesuré : limit=1 renvoie une
// ressource, limit=5 en renvoie cinq. Aucun plafond serveur n'a été observé,
// mais l'arbre de mesure était petit : la borne est donc posée ici, pour que
// la réponse reste d'une taille prévisible sur un espace inconnu.
const DefaultSearchLimit = 5000

// ErrSearchUnavailable signale que le service de recherche n'a pas répondu.
//
// Ce n'est pas une panne : le service peut être désactivé au déploiement
// (OC_EXCLUDE_RUN_SERVICES), et un serveur plus ancien ne l'expose pas du
// tout. L'appelant doit se rabattre sur un parcours PROPFIND, jamais échouer
// là-dessus.
var ErrSearchUnavailable = errors.New("opencloud: service de recherche indisponible")

// searchBody demande au service de recherche toutes les ressources de
// l'espace, avec les mêmes propriétés qu'un PROPFIND.
//
// Le motif « * » est du KQL, le langage de requête du service. Les propriétés
// demandées sont volontairement identiques à celles de propfindBody : la
// réponse est un multistatus ordinaire, que parseMultistatus lit sans savoir
// d'où elle vient.
const searchBody = `<?xml version="1.0" encoding="utf-8"?>
<oc:search-files xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
  <d:prop>
    <d:getlastmodified/>
    <d:getcontentlength/>
    <d:getcontenttype/>
    <d:getetag/>
    <d:resourcetype/>
    <oc:fileid/>
    <oc:permissions/>
  </d:prop>
  <oc:search>
    <oc:pattern>*</oc:pattern>
    <oc:limit>%d</oc:limit>
  </oc:search>
</oc:search-files>`

// SearchAll énumère en une seule requête toutes les ressources de l'espace.
//
// # Pourquoi ça existe
//
// Le serveur refuse PROPFIND Depth: infinity — mesuré sur les six points
// d'entrée WebDAV, HTTP 400 partout. C'est un réglage de déploiement
// (OCDAV_ALLOW_PROPFIND_DEPTH_INFINITY, faux par défaut), donc rien sur quoi
// un client puisse compter. Énumérer un arbre demandait jusqu'ici une requête
// par dossier ; le service de recherche le fait en une.
//
// # Trois pièges, tous constatés
//
//   - **Le chemin de l'URL est ignoré.** Un REPORT envoyé sur un sous-dossier
//     renvoie exactement les mêmes résultats que sur la racine : la recherche
//     porte toujours sur l'espace entier. Le filtrage par sous-arbre est donc
//     à la charge de l'appelant, jamais du serveur.
//
//   - **L'index retarde d'environ une seconde et demie.** Une note écrite est
//     visible immédiatement en PROPFIND, mais n'apparaît ici qu'après ~1,3 s.
//     Une liste construite sur ce seul résultat ferait disparaître la note que
//     l'utilisateur vient d'écrire : ce qui est connu localement doit primer.
//
//   - **La racine de l'espace figure dans les résultats**, avec un chemin
//     vide. Elle est écartée ici : ce n'est pas une ressource de l'arbre.
func (s *Space) SearchAll(ctx context.Context, limit int) ([]Resource, error) {
	if limit <= 0 {
		limit = DefaultSearchLimit
	}

	u := *s.davBase
	body := []byte(fmt.Sprintf(searchBody, limit))

	data, _, err := s.c.do(ctx, "REPORT", &u, body, map[string]string{
		"Content-Type": "application/xml",
	})
	if err != nil {
		// Une panne de transport reste une panne de transport : se rabattre
		// sur un parcours PROPFIND ne ferait qu'échouer une seconde fois, plus
		// lentement.
		if errors.Is(err, ErrOffline) {
			return nil, err
		}
		return nil, fmt.Errorf("%w: %v", ErrSearchUnavailable, err)
	}

	all, err := parseMultistatus(data, s.davBase.Path)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrSearchUnavailable, err)
	}

	out := all[:0]
	for _, r := range all {
		if r.Path == "" {
			continue
		}
		out = append(out, r)
	}
	return out, nil
}
