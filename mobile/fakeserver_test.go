package mobile

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path"
	"sort"
	"strings"
	"sync"
	"testing"
)

// fakeServer est un serveur OpenCloud minimal, en mémoire.
//
// Il existe pour deux raisons. D'abord parce qu'un serveur qu'on peut couper à
// volonté est le seul moyen d'éprouver honnêtement le comportement hors
// connexion. Ensuite parce que les tests ne doivent pas dépendre de la
// disponibilité d'une instance réelle.
//
// Il reproduit les particularités constatées sur un vrai OpenCloud 7.0.0 :
// l'identifiant d'espace contenant un « $ », et le double bloc propstat
// 200/404 des collections.
type fakeServer struct {
	*httptest.Server

	mu      sync.Mutex
	files   map[string][]byte
	etags   map[string]string
	folders map[string]bool
	seq     int
	offline bool

	// honorsIfNoneMatch reste faux par défaut : le vrai OpenCloud ne
	// respecte pas cet en-tête, et la protection des notes créées hors
	// connexion ne doit donc pas en dépendre.
	honorsIfNoneMatch bool
}

// setOffline simule la perte du réseau, de façon réversible.
//
// Fermer le serveur serait définitif : on ne pourrait pas éprouver le retour
// de connexion, qui est justement ce qui compte dans un modèle local-first.
func (f *fakeServer) setOffline(v bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.offline = v
}

func (f *fakeServer) isOffline() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.offline
}

const fakeSpaceID = "11111111-1111-4111-8111-111111111111$22222222-2222-4222-8222-222222222222"

const fakeUser = "testuser"
const fakeToken = "test-app-token"

func newFakeServer(t *testing.T) *fakeServer {
	t.Helper()

	f := &fakeServer{
		files:   map[string][]byte{},
		etags:   map[string]string{},
		folders: map[string]bool{"": true},
	}
	f.Server = httptest.NewServer(http.HandlerFunc(f.handle))
	t.Cleanup(f.Server.Close)
	return f
}

// davPrefix est le chemin WebDAV de l'espace.
func davPrefix() string { return "/dav/spaces/" + fakeSpaceID }

func (f *fakeServer) nextETag() string {
	f.seq++
	return fmt.Sprintf("%q", fmt.Sprintf("etag-%d", f.seq))
}

// rel convertit un chemin d'URL en chemin relatif à l'espace.
func rel(rawPath string) (string, bool) {
	decoded, err := url.PathUnescape(rawPath)
	if err != nil || !strings.HasPrefix(decoded, davPrefix()) {
		return "", false
	}
	return strings.Trim(strings.TrimPrefix(decoded, davPrefix()), "/"), true
}

func (f *fakeServer) handle(w http.ResponseWriter, r *http.Request) {
	if f.isOffline() {
		// La connexion est coupée sans réponse : le client voit une erreur de
		// transport, comme avec un réseau absent. Répondre un code d'erreur
		// ne conviendrait pas — ce serait un serveur qui répond, donc une
		// tout autre situation.
		if hijacker, ok := w.(http.Hijacker); ok {
			if conn, _, err := hijacker.Hijack(); err == nil {
				_ = conn.Close()
			}
		}
		return
	}

	user, pass, ok := r.BasicAuth()
	if !ok || user != fakeUser || pass != fakeToken {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}

	if r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/graph/v1.0/me/drives") {
		f.writeDrives(w)
		return
	}

	p, ok := rel(r.URL.Path)
	if !ok {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	f.mu.Lock()
	defer f.mu.Unlock()

	switch r.Method {
	case "PROPFIND":
		f.propfind(w, r, p)
	case http.MethodGet:
		f.get(w, p)
	case http.MethodPut:
		f.put(w, r, p)
	case "MKCOL":
		f.mkcol(w, p)
	case "MOVE":
		f.move(w, r, p)
	case http.MethodDelete:
		f.remove(w, p)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (f *fakeServer) writeDrives(w http.ResponseWriter) {
	base := f.Server.URL
	body, _ := json.Marshal(map[string]any{"value": []any{
		map[string]any{
			"id": "3333$3333", "name": "Shares", "driveType": "virtual",
			"root": map[string]any{"webDavUrl": base + "/dav/spaces/3333$3333"},
		},
		map[string]any{
			"id": fakeSpaceID, "name": "Admin", "driveType": "personal",
			"root": map[string]any{"webDavUrl": base + davPrefix()},
		},
	}})
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write(body)
}

func (f *fakeServer) propfind(w http.ResponseWriter, r *http.Request, p string) {
	var entries []string

	switch {
	case f.folders[p]:
		entries = append(entries, f.responseXML(p, true))
		if r.Header.Get("Depth") != "0" {
			prefix := ""
			if p != "" {
				prefix = p + "/"
			}
			var children []string
			for d := range f.folders {
				if d != "" && strings.HasPrefix(d, prefix) && d != p &&
					!strings.Contains(strings.TrimPrefix(d, prefix), "/") {
					children = append(children, f.responseXML(d, true))
				}
			}
			for file := range f.files {
				if strings.HasPrefix(file, prefix) &&
					!strings.Contains(strings.TrimPrefix(file, prefix), "/") {
					children = append(children, f.responseXML(file, false))
				}
			}
			sort.Strings(children)
			entries = append(entries, children...)
		}
	case f.files[p] != nil:
		entries = append(entries, f.responseXML(p, false))
	default:
		w.WriteHeader(http.StatusNotFound)
		return
	}

	w.Header().Set("Content-Type", "application/xml; charset=utf-8")
	w.WriteHeader(http.StatusMultiStatus)
	_, _ = fmt.Fprintf(w,
		`<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">%s</d:multistatus>`,
		strings.Join(entries, ""))
}

// responseXML reproduit le double propstat du vrai serveur : les propriétés
// absentes sur une collection reviennent dans un bloc 404 séparé.
func (f *fakeServer) responseXML(p string, isDir bool) string {
	href := davPrefix() + "/" + escapePath(p)
	if isDir {
		if p == "" {
			href = davPrefix() + "/"
		} else {
			href += "/"
		}
		return fmt.Sprintf(
			`<d:response><d:href>%s</d:href>`+
				`<d:propstat><d:prop><d:getetag>"dir"</d:getetag>`+
				`<d:resourcetype><d:collection/></d:resourcetype>`+
				`<oc:fileid>%s!%s</oc:fileid></d:prop>`+
				`<d:status>HTTP/1.1 200 OK</d:status></d:propstat>`+
				`<d:propstat><d:prop><d:getcontentlength></d:getcontentlength>`+
				`<d:getcontenttype></d:getcontenttype></d:prop>`+
				`<d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>`,
			href, fakeSpaceID, p)
	}

	return fmt.Sprintf(
		`<d:response><d:href>%s</d:href>`+
			`<d:propstat><d:prop>`+
			`<d:getlastmodified>Fri, 28 Aug 2026 07:00:25 GMT</d:getlastmodified>`+
			`<d:getcontentlength>%d</d:getcontentlength>`+
			`<d:getcontenttype>text/markdown</d:getcontenttype>`+
			`<d:getetag>%s</d:getetag><d:resourcetype/>`+
			`<oc:fileid>%s!%s</oc:fileid></d:prop>`+
			`<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`,
		href, len(f.files[p]), strings.ReplaceAll(f.etags[p], `"`, "&quot;"), fakeSpaceID, p)
}

func escapePath(p string) string {
	segments := strings.Split(p, "/")
	for i, s := range segments {
		segments[i] = url.PathEscape(s)
	}
	return strings.Join(segments, "/")
}

func (f *fakeServer) get(w http.ResponseWriter, p string) {
	content, ok := f.files[p]
	if !ok {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	w.Header().Set("ETag", f.etags[p])
	_, _ = w.Write(content)
}

func (f *fakeServer) put(w http.ResponseWriter, r *http.Request, p string) {
	if ifMatch := r.Header.Get("If-Match"); ifMatch != "" && f.etags[p] != ifMatch {
		w.WriteHeader(http.StatusPreconditionFailed)
		return
	}
	// « If-None-Match: * » devrait exiger que la ressource n'existe pas
	// encore. Par défaut ce serveur l'ignore — comme le vrai OpenCloud, où un
	// écrasement a effectivement été constaté sur un téléphone. Honorer un
	// en-tête que le serveur réel ignore ferait passer les tests pour la
	// mauvaise raison.
	if f.honorsIfNoneMatch && r.Header.Get("If-None-Match") == "*" {
		if _, exists := f.files[p]; exists {
			w.WriteHeader(http.StatusPreconditionFailed)
			return
		}
	}

	body := make([]byte, r.ContentLength)
	if r.ContentLength > 0 {
		if _, err := r.Body.Read(body); err != nil && len(body) == 0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
	}

	_, existed := f.files[p]
	etag := f.nextETag()
	f.files[p] = body
	f.etags[p] = etag
	f.folders[path.Dir(p)] = f.folders[path.Dir(p)] || path.Dir(p) == "."

	w.Header().Set("ETag", etag)
	if existed {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	w.WriteHeader(http.StatusCreated)
}

func (f *fakeServer) mkcol(w http.ResponseWriter, p string) {
	if f.folders[p] {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	f.folders[p] = true
	w.WriteHeader(http.StatusCreated)
}

func (f *fakeServer) move(w http.ResponseWriter, r *http.Request, from string) {
	dest, err := url.Parse(r.Header.Get("Destination"))
	if err != nil {
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	to, ok := rel(dest.Path)
	if !ok {
		w.WriteHeader(http.StatusBadRequest)
		return
	}

	if content, exists := f.files[from]; exists {
		f.files[to], f.etags[to] = content, f.etags[from]
		delete(f.files, from)
		delete(f.etags, from)
		w.WriteHeader(http.StatusCreated)
		return
	}
	if f.folders[from] {
		delete(f.folders, from)
		f.folders[to] = true
		w.WriteHeader(http.StatusCreated)
		return
	}
	w.WriteHeader(http.StatusNotFound)
}

func (f *fakeServer) remove(w http.ResponseWriter, p string) {
	if _, ok := f.files[p]; ok {
		delete(f.files, p)
		delete(f.etags, p)
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if f.folders[p] {
		delete(f.folders, p)
		for file := range f.files {
			if strings.HasPrefix(file, p+"/") {
				delete(f.files, file)
			}
		}
		w.WriteHeader(http.StatusNoContent)
		return
	}
	w.WriteHeader(http.StatusNotFound)
}
