package opencloud

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func testAuth() AppTokenAuth {
	return AppTokenAuth{Username: "testuser", Token: "test-app-token"}
}

// newTestClient monte un client pointant sur un serveur httptest.
func newTestClient(t *testing.T, handler http.HandlerFunc) (*Client, *httptest.Server) {
	t.Helper()
	srv := httptest.NewTLSServer(handler)
	t.Cleanup(srv.Close)

	c, err := New(srv.URL, testAuth())
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	c.SetHTTPClient(srv.Client())
	return c, srv
}

func TestNewRejetteURLInvalide(t *testing.T) {
	tests := []struct {
		name string
		url  string
	}{
		{"schéma manquant", "cloud.exemple.fr"},
		{"schéma inattendu", "ftp://cloud.exemple.fr"},
		{"HTTP interdit", "http://cloud.exemple.fr"},
		{"hôte manquant", "https://"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := New(tc.url, testAuth()); err == nil {
				t.Errorf("New(%q) aurait dû échouer", tc.url)
			}
		})
	}
}

func TestClientRefuseUneRedirectionVersHTTP(t *testing.T) {
	var cibleAtteinte bool
	cible := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		cibleAtteinte = true
	}))
	t.Cleanup(cible.Close)

	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, cible.URL, http.StatusFound)
	})

	if _, err := c.ListDrives(context.Background()); err == nil {
		t.Fatal("la redirection vers HTTP aurait dû être refusée")
	}
	if cibleAtteinte {
		t.Fatal("la requête ne doit jamais atteindre la cible HTTP")
	}
}

func TestNewExigeUneAuthentification(t *testing.T) {
	if _, err := New("https://cloud.exemple.fr", nil); err == nil {
		t.Error("New sans authentification aurait dû échouer")
	}
}

func TestAppTokenAuthEnvoieBasicAuth(t *testing.T) {
	var gotUser, gotPass string
	var ok bool

	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		gotUser, gotPass, ok = r.BasicAuth()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"value":[]}`))
	})

	if _, err := c.ListDrives(context.Background()); err != nil {
		t.Fatalf("ListDrives: %v", err)
	}
	if !ok {
		t.Fatal("aucun en-tête Basic auth reçu")
	}
	if gotUser != "testuser" || gotPass != "test-app-token" {
		t.Errorf("identifiants = %q/%q, attendu testuser/test-app-token", gotUser, gotPass)
	}
}

func TestListDrives(t *testing.T) {
	var gotPath string
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(readFixture(t, "drives.json"))
	})

	drives, err := c.ListDrives(context.Background())
	if err != nil {
		t.Fatalf("ListDrives: %v", err)
	}

	if gotPath != "/graph/v1.0/me/drives" {
		t.Errorf("chemin interrogé = %q", gotPath)
	}
	if len(drives) != 2 {
		t.Fatalf("%d espaces, 2 attendus", len(drives))
	}

	// Le premier est l'espace virtuel « Shares ».
	if drives[0].Type != DriveVirtual {
		t.Errorf("drives[0].Type = %q, attendu %q", drives[0].Type, DriveVirtual)
	}
	if drives[0].IsStorage() {
		t.Error("l'espace virtuel ne doit pas être considéré comme un stockage")
	}

	personal := drives[1]
	if personal.Type != DrivePersonal {
		t.Errorf("drives[1].Type = %q, attendu %q", personal.Type, DrivePersonal)
	}
	// L'identifiant réel contient un '$' séparant stockage et espace.
	if personal.ID != "11111111-1111-4111-8111-111111111111$22222222-2222-4222-8222-222222222222" {
		t.Errorf("drives[1].ID = %q", personal.ID)
	}
	if personal.WebDavURL == "" {
		t.Error("drives[1].WebDavURL est vide")
	}
}

// PersonalDrive doit écarter l'espace virtuel « Shares », qui n'est pas un
// emplacement où l'on peut créer des notes.
func TestPersonalDrive(t *testing.T) {
	tests := []struct {
		name   string
		drives []Drive
		want   string
		found  bool
	}{
		{
			name: "espace personnel prioritaire",
			drives: []Drive{
				{Name: "Shares", Type: DriveVirtual, WebDavURL: "https://h/dav/spaces/v"},
				{Name: "Projet", Type: DriveProject, WebDavURL: "https://h/dav/spaces/p"},
				{Name: "Admin", Type: DrivePersonal, WebDavURL: "https://h/dav/spaces/a"},
			},
			want: "Admin", found: true,
		},
		{
			name: "repli sur un espace projet",
			drives: []Drive{
				{Name: "Shares", Type: DriveVirtual, WebDavURL: "https://h/dav/spaces/v"},
				{Name: "Projet", Type: DriveProject, WebDavURL: "https://h/dav/spaces/p"},
			},
			want: "Projet", found: true,
		},
		{
			name:   "aucun espace exploitable",
			drives: []Drive{{Name: "Shares", Type: DriveVirtual, WebDavURL: "https://h/dav/spaces/v"}},
			found:  false,
		},
		{
			name:   "espace personnel sans URL WebDAV",
			drives: []Drive{{Name: "Admin", Type: DrivePersonal}},
			found:  false,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, ok := PersonalDrive(tc.drives)
			if ok != tc.found {
				t.Fatalf("trouvé = %v, attendu %v", ok, tc.found)
			}
			if ok && got.Name != tc.want {
				t.Errorf("espace = %q, attendu %q", got.Name, tc.want)
			}
		})
	}
}

func TestHTTPErrorSeTraduitEnSentinelle(t *testing.T) {
	tests := []struct {
		status int
		want   error
	}{
		{http.StatusNotFound, ErrNotFound},
		{http.StatusPreconditionFailed, ErrConflict},
		{http.StatusUnauthorized, ErrUnauthorized},
		{http.StatusForbidden, ErrUnauthorized},
	}

	for _, tc := range tests {
		c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(tc.status)
		})
		_, err := c.ListDrives(context.Background())
		if !errors.Is(err, tc.want) {
			t.Errorf("HTTP %d: erreur = %v, attendu errors.Is(_, %v)", tc.status, err, tc.want)
		}
	}
}

// 405 ne doit pas être traduit globalement : sa signification dépend de la
// méthode. Seul Mkdir en fait une erreur ErrExists.
func TestHTTPError405NonTraduit(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusMethodNotAllowed)
	})
	_, err := c.ListDrives(context.Background())
	if err == nil {
		t.Fatal("une erreur était attendue")
	}
	if errors.Is(err, ErrExists) {
		t.Error("405 ne doit pas se traduire en ErrExists hors de Mkdir")
	}
}
