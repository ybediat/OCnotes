package opencloud

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// spaceID reproduit la forme réelle d'un identifiant OpenCloud :
// {storageID}${spaceID}. Le '$' est le piège principal de ce paquet.
const spaceID = "11111111-1111-4111-8111-111111111111$22222222-2222-4222-8222-222222222222"

func newTestSpace(t *testing.T, handler http.HandlerFunc) (*Space, *httptest.Server) {
	t.Helper()
	srv := httptest.NewTLSServer(handler)
	t.Cleanup(srv.Close)

	c, err := New(srv.URL, testAuth())
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	c.SetHTTPClient(srv.Client())
	sp, err := c.Space(Drive{
		ID:        spaceID,
		Name:      "Admin",
		Type:      DrivePersonal,
		WebDavURL: srv.URL + "/dav/spaces/" + spaceID,
	})
	if err != nil {
		t.Fatalf("Space: %v", err)
	}
	return sp, srv
}

func TestSpaceRefuseUneURLWebDAVNonHTTPS(t *testing.T) {
	c, err := New("https://cloud.exemple.fr", testAuth())
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	if _, err := c.Space(Drive{Name: "Admin", WebDavURL: "http://cloud.exemple.fr/dav/spaces/admin"}); err == nil {
		t.Fatal("Space aurait dû refuser une URL WebDAV en HTTP")
	}
}

// Le '$' de l'identifiant d'espace doit arriver littéral sur le réseau, tandis
// que les accents et les espaces d'un nom de note doivent être percent-encodés.
func TestResourceURLEncodage(t *testing.T) {
	var rawURI, decodedPath string

	sp, _ := newTestSpace(t, func(w http.ResponseWriter, r *http.Request) {
		rawURI = r.RequestURI
		decodedPath = r.URL.Path
		w.Header().Set("ETag", `"nouveau"`)
		w.WriteHeader(http.StatusCreated)
	})

	if _, err := sp.Write(context.Background(), "Notes/Réunion du 15.md", []byte("# Test\n"), ""); err != nil {
		t.Fatalf("Write: %v", err)
	}

	if strings.Contains(rawURI, "%24") {
		t.Errorf("le '$' a été percent-encodé, le serveur ne le reconnaîtra pas: %s", rawURI)
	}
	if !strings.Contains(rawURI, "$") {
		t.Errorf("le '$' a disparu de l'URL: %s", rawURI)
	}
	if !strings.Contains(rawURI, "%C3%A9") {
		t.Errorf("le 'é' n'a pas été encodé en UTF-8 percent: %s", rawURI)
	}
	if !strings.Contains(rawURI, "%20") {
		t.Errorf("les espaces n'ont pas été encodés: %s", rawURI)
	}

	want := "/dav/spaces/" + spaceID + "/Notes/Réunion du 15.md"
	if decodedPath != want {
		t.Errorf("chemin décodé = %q, attendu %q", decodedPath, want)
	}
}

// Un chemin contenant « .. » ne doit jamais viser une URL hors de l'espace :
// path.Join résoudrait la remontée et ferait sortir la requête de la racine.
func TestResourceURLNeSortPasDeLEspace(t *testing.T) {
	base := "/dav/spaces/" + spaceID

	tests := []struct {
		name string
		path string
		want string
	}{
		{"remontée simple", "../secret.md", base + "/secret.md"},
		{"remontée multiple", "../../../etc/passwd", base + "/etc/passwd"},
		{"remontée interne", "Notes/../autre.md", base + "/autre.md"},
		{"slash initial", "/Notes/a.md", base + "/Notes/a.md"},
		{"segments vides", "Notes//a.md", base + "/Notes/a.md"},
		{"chemin courant", ".", base},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			var gotPath string
			sp, _ := newTestSpace(t, func(w http.ResponseWriter, r *http.Request) {
				gotPath = r.URL.Path
				w.WriteHeader(http.StatusCreated)
			})

			if _, err := sp.Write(context.Background(), tc.path, []byte("x"), ""); err != nil {
				t.Fatalf("Write: %v", err)
			}
			if gotPath != tc.want {
				t.Errorf("chemin visé = %q, attendu %q", gotPath, tc.want)
			}
			if !strings.HasPrefix(gotPath, base) {
				t.Errorf("la requête est sortie de l'espace : %q", gotPath)
			}
		})
	}
}

func TestWriteRenvoieLETag(t *testing.T) {
	sp, _ := newTestSpace(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("ETag", `"abc123"`)
		w.WriteHeader(http.StatusCreated)
	})

	etag, err := sp.Write(context.Background(), "note.md", []byte("# Note\n"), "")
	if err != nil {
		t.Fatalf("Write: %v", err)
	}
	if etag != `"abc123"` {
		t.Errorf("ETag = %q, attendu %q (guillemets conservés)", etag, `"abc123"`)
	}
}

// Le cœur de la stratégie de synchronisation : une écriture sur un ETag périmé
// doit être refusée par le serveur et remonter comme ErrConflict.
func TestWriteIfMatchConflit(t *testing.T) {
	const courant = `"etag-courant"`
	var gotIfMatch string

	sp, _ := newTestSpace(t, func(w http.ResponseWriter, r *http.Request) {
		gotIfMatch = r.Header.Get("If-Match")
		if gotIfMatch != "" && gotIfMatch != courant {
			w.WriteHeader(http.StatusPreconditionFailed)
			return
		}
		w.Header().Set("ETag", `"etag-suivant"`)
		w.WriteHeader(http.StatusNoContent)
	})

	ctx := context.Background()

	etag, err := sp.Write(ctx, "note.md", []byte("v2"), courant)
	if err != nil {
		t.Fatalf("écriture avec un ETag à jour: %v", err)
	}
	if gotIfMatch != courant {
		t.Errorf("If-Match transmis = %q, attendu %q", gotIfMatch, courant)
	}
	if etag != `"etag-suivant"` {
		t.Errorf("nouvel ETag = %q", etag)
	}

	_, err = sp.Write(ctx, "note.md", []byte("v3"), `"etag-perime"`)
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("écriture avec un ETag périmé: erreur = %v, attendu ErrConflict", err)
	}
}

// Sans If-Match, l'écriture est inconditionnelle : l'en-tête ne doit pas être
// envoyé du tout, sinon le serveur refuserait la création d'un fichier absent.
func TestWriteSansIfMatch(t *testing.T) {
	var present bool
	sp, _ := newTestSpace(t, func(w http.ResponseWriter, r *http.Request) {
		_, present = r.Header["If-Match"]
		w.WriteHeader(http.StatusCreated)
	})

	if _, err := sp.Write(context.Background(), "note.md", []byte("x"), ""); err != nil {
		t.Fatalf("Write: %v", err)
	}
	if present {
		t.Error("l'en-tête If-Match ne devait pas être envoyé")
	}
}

func multistatusFixture(entries ...string) string {
	return `<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">` +
		strings.Join(entries, "") + `</d:multistatus>`
}

func dirEntry(href string) string {
	return fmt.Sprintf(`<d:response><d:href>%s</d:href>`+
		`<d:propstat><d:prop><d:getetag>"e"</d:getetag>`+
		`<d:resourcetype><d:collection/></d:resourcetype></d:prop>`+
		`<d:status>HTTP/1.1 200 OK</d:status></d:propstat>`+
		`<d:propstat><d:prop><d:getcontentlength></d:getcontentlength></d:prop>`+
		`<d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>`, href)
}

func fileEntry(href string, size int) string {
	return fmt.Sprintf(`<d:response><d:href>%s</d:href>`+
		`<d:propstat><d:prop><d:getetag>"e"</d:getetag><d:resourcetype/>`+
		`<d:getcontentlength>%d</d:getcontentlength>`+
		`<d:getcontenttype>text/markdown</d:getcontenttype></d:prop>`+
		`<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`, href, size)
}

// Un PROPFIND Depth 1 renvoie le dossier interrogé en première position :
// List doit l'écarter pour ne renvoyer que son contenu.
func TestListEcarteLeDossierInterroge(t *testing.T) {
	base := "/dav/spaces/" + spaceID
	var gotDepth string

	sp, _ := newTestSpace(t, func(w http.ResponseWriter, r *http.Request) {
		gotDepth = r.Header.Get("Depth")
		w.WriteHeader(http.StatusMultiStatus)
		_, _ = w.Write([]byte(multistatusFixture(
			dirEntry(base+"/Notes/"),
			dirEntry(base+"/Notes/Archives/"),
			fileEntry(base+"/Notes/a.md", 12),
		)))
	})

	resources, err := sp.List(context.Background(), "Notes")
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if gotDepth != "1" {
		t.Errorf("Depth = %q, attendu 1", gotDepth)
	}
	if len(resources) != 2 {
		t.Fatalf("%d ressources, 2 attendues (le dossier lui-même exclu)", len(resources))
	}
	if resources[0].Path != "Notes/Archives" || !resources[0].IsDir {
		t.Errorf("resources[0] = %+v", resources[0])
	}
	if resources[1].Path != "Notes/a.md" || resources[1].Size != 12 {
		t.Errorf("resources[1] = %+v", resources[1])
	}
}

// Le dossier interrogé doit être écarté quelle que soit la forme du chemin
// fourni : le serveur répond toujours avec des href résolus, alors que
// l'appelant peut passer un slash de fin, un slash initial ou un « .. ».
func TestListEcarteLeDossierQuelQueSoitLeChemin(t *testing.T) {
	base := "/dav/spaces/" + spaceID

	for _, dir := range []string{
		"Notes",
		"Notes/",
		"/Notes",
		"/Notes/",
		"Notes/Archives/..",
		"Autre/../Notes",
		"Notes//",
	} {
		t.Run(dir, func(t *testing.T) {
			sp, _ := newTestSpace(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(http.StatusMultiStatus)
				_, _ = w.Write([]byte(multistatusFixture(
					dirEntry(base+"/Notes/"),
					fileEntry(base+"/Notes/a.md", 12),
				)))
			})

			resources, err := sp.List(context.Background(), dir)
			if err != nil {
				t.Fatalf("List(%q): %v", dir, err)
			}
			if len(resources) != 1 {
				t.Fatalf("List(%q) = %d ressources, 1 attendue ; le dossier interrogé n'a pas été écarté", dir, len(resources))
			}
			if resources[0].Path != "Notes/a.md" {
				t.Errorf("List(%q)[0].Path = %q", dir, resources[0].Path)
			}
		})
	}
}

func TestListRacine(t *testing.T) {
	base := "/dav/spaces/" + spaceID

	sp, _ := newTestSpace(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusMultiStatus)
		_, _ = w.Write([]byte(multistatusFixture(
			dirEntry(base+"/"),
			dirEntry(base+"/Notes/"),
		)))
	})

	resources, err := sp.List(context.Background(), "")
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(resources) != 1 || resources[0].Path != "Notes" {
		t.Fatalf("ressources = %+v, attendu le seul dossier Notes", resources)
	}
}

// MkdirAll doit créer chaque niveau et considérer un dossier déjà présent
// (405) comme un succès.
func TestMkdirAll(t *testing.T) {
	var created []string

	sp, _ := newTestSpace(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "MKCOL" {
			t.Errorf("méthode = %s, attendu MKCOL", r.Method)
		}
		p := strings.TrimPrefix(r.URL.Path, "/dav/spaces/"+spaceID+"/")
		p = strings.TrimSuffix(p, "/")
		created = append(created, p)

		// « Notes » existe déjà côté serveur.
		if p == "Notes" {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		w.WriteHeader(http.StatusCreated)
	})

	if err := sp.MkdirAll(context.Background(), "Notes/Projets/2026"); err != nil {
		t.Fatalf("MkdirAll: %v", err)
	}

	want := []string{"Notes", "Notes/Projets", "Notes/Projets/2026"}
	if len(created) != len(want) {
		t.Fatalf("dossiers créés = %v, attendu %v", created, want)
	}
	for i, w := range want {
		if created[i] != w {
			t.Errorf("created[%d] = %q, attendu %q", i, created[i], w)
		}
	}
}

func TestMkdirSurDossierExistant(t *testing.T) {
	sp, _ := newTestSpace(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusMethodNotAllowed)
	})

	err := sp.Mkdir(context.Background(), "Notes")
	if !errors.Is(err, ErrExists) {
		t.Errorf("erreur = %v, attendu ErrExists", err)
	}
}

func TestMove(t *testing.T) {
	var destination string

	sp, srv := newTestSpace(t, func(w http.ResponseWriter, r *http.Request) {
		destination = r.Header.Get("Destination")
		w.WriteHeader(http.StatusCreated)
	})

	if err := sp.Move(context.Background(), "Notes/a.md", "Notes/b.md"); err != nil {
		t.Fatalf("Move: %v", err)
	}

	want := srv.URL + "/dav/spaces/" + spaceID + "/Notes/b.md"
	if destination != want {
		t.Errorf("Destination = %q, attendu %q", destination, want)
	}
}

func TestReadRenvoieContenuEtETag(t *testing.T) {
	sp, _ := newTestSpace(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("ETag", `"lu"`)
		_, _ = w.Write([]byte("# Bonjour\n"))
	})

	content, etag, err := sp.Read(context.Background(), "note.md")
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if string(content) != "# Bonjour\n" {
		t.Errorf("contenu = %q", content)
	}
	if etag != `"lu"` {
		t.Errorf("ETag = %q", etag)
	}
}

func TestReadNoteAbsente(t *testing.T) {
	sp, _ := newTestSpace(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	})

	if _, _, err := sp.Read(context.Background(), "absente.md"); !errors.Is(err, ErrNotFound) {
		t.Errorf("erreur = %v, attendu ErrNotFound", err)
	}
}
