package store

import (
	"context"
	"fmt"
	"strings"
	"testing"

	"github.com/ybediat/OpenNote/internal/opencloud"
)

// remoteAvecGeste exécute un geste de l'utilisateur pendant un appel réseau,
// là où la vraie application peut le faire : l'éditeur enregistre seul, et une
// passe de fond ne prévient personne.
type remoteAvecGeste struct {
	*fakeRemote
	pendantRead  func()
	unauthorized bool
}

func (r *remoteAvecGeste) Read(ctx context.Context, p string) ([]byte, string, error) {
	if geste := r.pendantRead; geste != nil {
		r.pendantRead = nil
		geste()
	}
	return r.fakeRemote.Read(ctx, p)
}

func (r *remoteAvecGeste) Save(ctx context.Context, p string, c []byte, ifMatch string) (string, error) {
	if r.unauthorized {
		r.calls = append(r.calls, "save-refusé "+p)
		return "", fmt.Errorf("fake: %w", opencloud.ErrUnauthorized)
	}
	return r.fakeRemote.Save(ctx, p, c, ifMatch)
}

// contenuQuelquePart dit si un texte existe encore, dans le cache ou sur le
// serveur.
func contenuQuelquePart(s *Store, r *fakeRemote, texte string) bool {
	for _, c := range r.files {
		if c == texte {
			return true
		}
	}
	for _, e := range s.Entries() {
		if c, _, ok := s.Get(e.Path); ok && string(c) == texte {
			return true
		}
	}
	return false
}

func TestFrappePendantUnConflitNEstPasPerdue(t *testing.T) {
	s := newStore(t)
	r := &remoteAvecGeste{fakeRemote: newFakeRemote()}
	r.files["a.md"], r.etags["a.md"] = "base", `"e0"`
	if err := s.Accept("a.md", []byte("base"), `"e0"`); err != nil {
		t.Fatal(err)
	}
	if err := s.Put("a.md", []byte("local v1")); err != nil {
		t.Fatal(err)
	}
	r.files["a.md"], r.etags["a.md"] = "serveur", `"e9"`

	const v2 = "local v2, tapée pendant la passe"
	r.pendantRead = func() {
		if err := s.Put("a.md", []byte(v2)); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := s.Push(context.Background(), r); err != nil {
		t.Fatalf("Push: %v", err)
	}

	c, e, _ := s.Get("a.md")
	if string(c) != v2 || !e.Dirty {
		t.Fatalf("la frappe a été remplacée : %q (dirty=%v)", c, e.Dirty)
	}
	if len(s.Pending()) == 0 {
		t.Fatal("la frappe n'est réclamée par aucune écriture en file")
	}

	// La passe suivante confronte la frappe au serveur : elle doit survivre.
	if _, err := s.Push(context.Background(), r); err != nil {
		t.Fatalf("Push: %v", err)
	}
	if !contenuQuelquePart(s, r.fakeRemote, v2) {
		t.Fatal("la frappe n'existe plus nulle part")
	}
	if r.files["a.md"] != "serveur" {
		t.Fatalf("la version du serveur a été écrasée : %q", r.files["a.md"])
	}
}

func TestFrappePendantUnPullNEstPasEcrasee(t *testing.T) {
	s := newStore(t)
	r := &remoteAvecGeste{fakeRemote: newFakeRemote()}
	r.files["a.md"], r.etags["a.md"] = "serveur", `"e1"`
	if err := s.Accept("a.md", []byte("base"), `"e0"`); err != nil {
		t.Fatal(err)
	}
	r.pendantRead = func() {
		if err := s.Put("a.md", []byte("frappe")); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.Pull(context.Background(), r, "a.md"); err != nil {
		t.Fatalf("Pull: %v", err)
	}
	c, e, _ := s.Get("a.md")
	if string(c) != "frappe" || !e.Dirty {
		t.Fatalf("la frappe a été écrasée : %q (dirty=%v)", c, e.Dirty)
	}
}

func TestFrappePendantUnPullSurNoteSupprimeeNEstPasOubliee(t *testing.T) {
	s := newStore(t)
	r := &remoteAvecGeste{fakeRemote: newFakeRemote()}
	if err := s.Accept("a.md", []byte("base"), `"e0"`); err != nil {
		t.Fatal(err)
	}
	r.pendantRead = func() {
		if err := s.Put("a.md", []byte("frappe")); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.Pull(context.Background(), r, "a.md"); err != nil {
		t.Fatalf("Pull: %v", err)
	}
	if c, _, ok := s.Get("a.md"); !ok || string(c) != "frappe" {
		t.Fatalf("la frappe a été oubliée avec la note : %q, %v", c, ok)
	}
}

func TestServeurInchangeSaufETagLaisseGagnerLeLocal(t *testing.T) {
	s := newStore(t)
	r := newFakeRemote()
	r.files["a.md"], r.etags["a.md"] = "base", `"e0"`
	if err := s.Accept("a.md", []byte("base"), `"e0"`); err != nil {
		t.Fatal(err)
	}
	if err := s.Put("a.md", []byte("local")); err != nil {
		t.Fatal(err)
	}
	// Même contenu, nouvel ETag : déplacement, réindexation…
	r.etags["a.md"] = `"e5"`

	report, err := s.Push(context.Background(), r)
	if err != nil {
		t.Fatalf("Push: %v", err)
	}
	if len(report.Conflicts) != 0 || len(r.files) != 1 {
		t.Fatalf("copie de conflit inutile : %d conflit(s), fichiers %v", len(report.Conflicts), r.files)
	}
	if r.files["a.md"] != "local" {
		t.Fatalf("la version locale n'a pas été poussée : %q", r.files["a.md"])
	}
	if _, e, _ := s.Get("a.md"); e.Dirty || e.ETag != r.etags["a.md"] {
		t.Fatalf("cache non aligné : %+v", e)
	}
}

func TestRenommageVersUnNomPrisSurLeServeurNeBloquePasLaFile(t *testing.T) {
	s := newStore(t)
	r := newFakeRemote()
	r.files["a.md"], r.etags["a.md"] = "A", `"e0"`
	if err := s.Accept("a.md", []byte("A"), `"e0"`); err != nil {
		t.Fatal(err)
	}
	if err := s.Rename("a.md", "b.md"); err != nil {
		t.Fatal(err)
	}
	if err := s.Put("b.md", []byte("A modifiée")); err != nil {
		t.Fatal(err)
	}
	r.files["b.md"], r.etags["b.md"] = "B d'un autre appareil", `"e1"`

	if _, err := s.Push(context.Background(), r); err != nil {
		t.Fatalf("Push: %v", err)
	}
	if n := len(s.Pending()); n != 0 {
		t.Fatalf("%d opération(s) restée(s) en file : %+v", n, s.Pending())
	}
	if r.files["b.md"] != "B d'un autre appareil" {
		t.Fatalf("la note de l'autre appareil a été touchée : %q", r.files["b.md"])
	}
	if r.files["b (2).md"] != "A modifiée" {
		t.Fatalf("la note renommée n'a pas atterri sous un nom libre : %v", r.files)
	}
	if _, ok := r.files["a.md"]; ok {
		t.Fatal("l'ancien nom subsiste sur le serveur")
	}
	if c, _, ok := s.Get("b (2).md"); !ok || string(c) != "A modifiée" {
		t.Fatalf("le cache n'a pas suivi : %q, %v", c, ok)
	}
}

func TestPurgePendantUnePasseNeRemplitPasLeCache(t *testing.T) {
	s := newStore(t)
	r := &remoteAvecGeste{fakeRemote: newFakeRemote()}
	r.files["secret.md"], r.etags["secret.md"] = "base", `"e0"`
	if err := s.Accept("secret.md", []byte("base"), `"e0"`); err != nil {
		t.Fatal(err)
	}
	if err := s.Put("secret.md", []byte("local")); err != nil {
		t.Fatal(err)
	}
	r.files["secret.md"], r.etags["secret.md"] = "serveur", `"e9"`
	r.pendantRead = func() {
		if err := s.Clear(); err != nil {
			t.Fatal(err)
		}
	}
	_, _ = s.Push(context.Background(), r)

	// La copie de conflit est une création : elle n'a pas d'état antérieur à
	// comparer. C'est la façade qui empêche une passe de chevaucher la purge ;
	// ici, seule la note purgée est vérifiée.
	if _, _, ok := s.Get("secret.md"); ok {
		t.Fatal("la note purgée a été réécrite dans le cache")
	}
}

func TestCopiesDeConflitNeSEcrasentPas(t *testing.T) {
	s := newStore(t)
	r := newFakeRemote()
	chemin := conflictPath("a.md", testTime())
	r.files[chemin], r.etags[chemin] = "copie déjà là", `"e0"`
	s.mu.Lock()
	s.known[chemin] = &Known{Path: chemin}
	s.mu.Unlock()

	nom, _, err := s.saveConflictCopy(context.Background(), r, "a.md", []byte("nouvelle"))
	if err != nil {
		t.Fatal(err)
	}
	if nom == chemin || r.files[chemin] != "copie déjà là" {
		t.Fatalf("copie existante écrasée : %q", nom)
	}
	if !strings.HasSuffix(nom, ".md") {
		t.Fatalf("extension perdue : %q", nom)
	}
}

func TestJetonRefuseArreteLaPasse(t *testing.T) {
	s := newStore(t)
	r := &remoteAvecGeste{fakeRemote: newFakeRemote(), unauthorized: true}
	for i := 0; i < 5; i++ {
		p := fmt.Sprintf("n%d.md", i)
		r.files[p], r.etags[p] = "base", `"e0"`
		if err := s.Accept(p, []byte("base"), `"e0"`); err != nil {
			t.Fatal(err)
		}
		if err := s.Put(p, []byte("local")); err != nil {
			t.Fatal(err)
		}
	}
	report, err := s.Push(context.Background(), r)
	if err == nil {
		t.Fatal("un jeton refusé doit être remonté")
	}
	tentatives := 0
	for _, c := range r.calls {
		if strings.HasPrefix(c, "save-refusé") {
			tentatives++
		}
	}
	if tentatives != 1 {
		t.Fatalf("%d écritures tentées avec un jeton refusé, une seule attendue", tentatives)
	}
	if report.Remaining != 5 || s.Pending()[0].Path != "n0.md" {
		t.Fatalf("l'ordre de la file doit rester intact : %+v", s.Pending())
	}
}

func TestFrappePendantLaPasseResteEnFile(t *testing.T) {
	s := newStore(t)
	r := &remoteAvecGeste{fakeRemote: newFakeRemote()}
	r.files["a.md"], r.etags["a.md"] = "serveur", `"e9"`
	if err := s.Accept("a.md", []byte("base"), `"e0"`); err != nil {
		t.Fatal(err)
	}
	if err := s.Put("a.md", []byte("v1")); err != nil {
		t.Fatal(err)
	}
	// Le serveur a divergé : la résolution lit sa version, et la frappe
	// arrive pendant cette lecture.
	r.pendantRead = func() {
		if err := s.Put("a.md", []byte("v2")); err != nil {
			t.Fatal(err)
		}
	}
	report, err := s.Push(context.Background(), r)
	if err != nil {
		t.Fatal(err)
	}
	// La file ne se dit pas vide tant qu'une note attend : c'est ce que
	// consulte le débranchement avant d'oublier le serveur.
	if report.Remaining == 0 || len(s.Pending()) == 0 {
		t.Fatal("la frappe arrivée pendant la passe n'est plus en file")
	}
}

func BenchmarkPushApresAdoption(b *testing.B) {
	for i := 0; i < b.N; i++ {
		b.StopTimer()
		s, err := Open(b.TempDir())
		if err != nil {
			b.Fatal(err)
		}
		for j := 0; j < 2000; j++ {
			_ = s.Put(fmt.Sprintf("d%d/n%d.md", j%20, j), []byte("contenu de note"))
		}
		if _, err := s.GoLocal(); err != nil {
			b.Fatal(err)
		}
		if err := s.Adopt(); err != nil {
			b.Fatal(err)
		}
		r := newFakeRemote()
		b.StartTimer()
		if _, err := s.Push(context.Background(), r); err != nil {
			b.Fatal(err)
		}
	}
}
