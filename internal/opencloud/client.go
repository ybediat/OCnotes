package opencloud

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"path"
	"strings"
	"time"
)

// maxBodySize borne la lecture d'une réponse. Une note Markdown pèse quelques
// kilo-octets ; cette limite protège d'une réponse aberrante sur mobile.
const maxBodySize = 32 << 20 // 32 Mio

// Authenticator ajoute les informations d'authentification à une requête.
//
// AppTokenAuth l'implémente aujourd'hui. Un porteur OIDC viendra s'y brancher
// plus tard sans modifier le reste du paquet.
type Authenticator interface {
	Apply(req *http.Request) error
}

// AppTokenAuth authentifie avec un App Token OpenCloud, transmis en Basic auth.
//
// Le token remplace le mot de passe. Si le fournisseur d'identité est en mode
// autoprovisioning, Username doit être l'UUID du compte et non le login.
type AppTokenAuth struct {
	Username string
	Token    string
}

func (a AppTokenAuth) Apply(req *http.Request) error {
	if a.Username == "" || a.Token == "" {
		return fmt.Errorf("opencloud: nom d'utilisateur ou App Token manquant")
	}
	req.SetBasicAuth(a.Username, a.Token)
	return nil
}

// Client dialogue avec un serveur OpenCloud.
type Client struct {
	base *url.URL
	auth Authenticator
	hc   *http.Client
}

// rejectInsecureRedirect interdit une dégradation HTTPS vers HTTP. Le jeton
// d'application étant porté par l'en-tête Authorization, il ne doit jamais
// accompagner une requête en clair.
func rejectInsecureRedirect(req *http.Request, _ []*http.Request) error {
	if req.URL.Scheme != "https" {
		return fmt.Errorf("opencloud: redirection refusée vers une URL non-HTTPS: %s", req.URL.Redacted())
	}
	return nil
}

// New construit un client pour l'URL racine du serveur, par exemple
// https://cloud.exemple.fr (sans chemin).
func New(serverURL string, auth Authenticator) (*Client, error) {
	if auth == nil {
		return nil, fmt.Errorf("opencloud: authentification non fournie")
	}

	u, err := url.Parse(strings.TrimRight(serverURL, "/"))
	if err != nil {
		return nil, fmt.Errorf("opencloud: URL de serveur invalide %q: %w", serverURL, err)
	}
	if u.Scheme != "https" {
		return nil, fmt.Errorf("opencloud: URL de serveur invalide %q: HTTPS obligatoire", serverURL)
	}
	if u.Host == "" {
		return nil, fmt.Errorf("opencloud: URL de serveur invalide %q: hôte manquant", serverURL)
	}

	return &Client{
		base: u,
		auth: auth,
		hc: &http.Client{
			Timeout:       30 * time.Second,
			CheckRedirect: rejectInsecureRedirect,
		},
	}, nil
}

// SetHTTPClient remplace le client HTTP sous-jacent (tests, réglages réseau
// spécifiques à Android).
func (c *Client) SetHTTPClient(hc *http.Client) {
	if hc != nil {
		copy := *hc
		// Ce garde-fou ne doit pas pouvoir être neutralisé par un transport
		// spécialisé (par exemple celui utilisé sur Android ou dans les tests).
		copy.CheckRedirect = rejectInsecureRedirect
		c.hc = &copy
	}
}

// resolve construit une URL absolue à partir d'un chemin serveur.
func (c *Client) resolve(p string) *url.URL {
	u := *c.base
	u.Path = path.Join(u.Path, p)
	return &u
}

// do exécute une requête et renvoie le corps lu et les en-têtes.
//
// Toute réponse hors 2xx devient une *HTTPError. Les codes 207 (Multi-Status)
// et 204 (No Content) sont donc traités comme des succès.
func (c *Client) do(ctx context.Context, method string, u *url.URL, body []byte, headers map[string]string) ([]byte, http.Header, error) {
	var r io.Reader
	if body != nil {
		r = bytes.NewReader(body)
	}

	req, err := http.NewRequestWithContext(ctx, method, u.String(), r)
	if err != nil {
		return nil, nil, fmt.Errorf("opencloud: requête %s invalide: %w", method, err)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	if err := c.auth.Apply(req); err != nil {
		return nil, nil, err
	}

	resp, err := c.hc.Do(req)
	if err != nil {
		// Le transport a échoué : la requête n'a jamais atteint le serveur.
		// L'erreur porte ErrOffline pour que les couches hautes puissent
		// basculer sur le cache au lieu de remonter un échec.
		return nil, nil, fmt.Errorf("opencloud: [%s] %s %s: %v: %w",
			CodeOffline, method, u.Redacted(), err, ErrOffline)
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(io.LimitReader(resp.Body, maxBodySize))
	if err != nil {
		return nil, resp.Header, fmt.Errorf("opencloud: lecture de la réponse %s %s: %w", method, u.Redacted(), err)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, resp.Header, &HTTPError{
			Method: method,
			URL:    u.Redacted(),
			Status: resp.StatusCode,
			Body:   snippet(data),
		}
	}
	return data, resp.Header, nil
}

// snippet réduit un corps d'erreur à une ligne exploitable dans un message.
func snippet(data []byte) string {
	s := strings.TrimSpace(string(data))
	s = strings.Join(strings.Fields(s), " ")
	if len(s) > 200 {
		return s[:200] + "…"
	}
	return s
}

// driveListResponse reflète la réponse de /graph/v1.0/me/drives.
type driveListResponse struct {
	Value []struct {
		ID        string `json:"id"`
		Name      string `json:"name"`
		DriveType string `json:"driveType"`
		Root      struct {
			WebDavURL string `json:"webDavUrl"`
		} `json:"root"`
	} `json:"value"`
}

// ListDrives renvoie les espaces accessibles à l'utilisateur.
func (c *Client) ListDrives(ctx context.Context) ([]Drive, error) {
	data, _, err := c.do(ctx, http.MethodGet, c.resolve("/graph/v1.0/me/drives"), nil, nil)
	if err != nil {
		return nil, err
	}

	var parsed driveListResponse
	if err := json.Unmarshal(data, &parsed); err != nil {
		return nil, fmt.Errorf("opencloud: liste des espaces illisible: %w", err)
	}

	drives := make([]Drive, 0, len(parsed.Value))
	for _, d := range parsed.Value {
		drives = append(drives, Drive{
			ID:        d.ID,
			Name:      d.Name,
			Type:      d.DriveType,
			WebDavURL: d.Root.WebDavURL,
		})
	}
	return drives, nil
}

// PersonalDrive choisit l'espace où créer les notes.
//
// L'espace personnel est préféré ; à défaut, le premier espace de stockage.
// Les espaces virtuels sont toujours écartés : « Shares » est un agrégat de
// partages, pas un emplacement où l'on peut créer un dossier.
func PersonalDrive(drives []Drive) (Drive, bool) {
	for _, d := range drives {
		if d.Type == DrivePersonal && d.IsStorage() {
			return d, true
		}
	}
	for _, d := range drives {
		if d.IsStorage() {
			return d, true
		}
	}
	return Drive{}, false
}
