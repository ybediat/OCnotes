package store

import (
	"context"
	"errors"
	"os"
	"slices"
	"testing"

	"github.com/ybediat/OpenNote/internal/opencloud"
)

// lectures compte les GET de contenu adressés au serveur factice.
func lectures(r *fakeRemote) int {
	n := 0
	for _, c := range r.calls {
		if len(c) > 5 && c[:5] == "read " {
			n++
		}
	}
	return n
}

// Une note en cache, propre, que le serveur a toujours dans la même version :
// l'ETag suffit, le contenu n'est pas retéléchargé.
func TestPullEtagIdentiquePasDeLecture(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	if err := s.Accept("a.md", []byte("contenu"), `"e1"`); err != nil {
		t.Fatal(err)
	}
	remote.files["a.md"] = "contenu"
	remote.etags["a.md"] = `"e1"`

	if err := s.Pull(context.Background(), remote, "a.md"); err != nil {
		t.Fatalf("Pull: %v", err)
	}
	if n := lectures(remote); n != 0 {
		t.Errorf("%d GET pour une note inchangée, attendu 0 (appels : %v)", n, remote.calls)
	}
	if !slices.Contains(remote.calls, "stat a.md") {
		t.Errorf("l'ETag n'a pas été demandé (appels : %v)", remote.calls)
	}
	if content, _, _ := s.Get("a.md"); string(content) != "contenu" {
		t.Errorf("contenu = %q", content)
	}
}

// Le même ETag écrit autrement (validation faible, sans guillemets) désigne
// la même version.
func TestPullEtagMemeVersionAutrementEcrite(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	if err := s.Accept("a.md", []byte("contenu"), `"e1"`); err != nil {
		t.Fatal(err)
	}
	remote.files["a.md"] = "contenu"
	remote.etags["a.md"] = `W/"e1"`

	if err := s.Pull(context.Background(), remote, "a.md"); err != nil {
		t.Fatalf("Pull: %v", err)
	}
	if n := lectures(remote); n != 0 {
		t.Errorf("%d GET, attendu 0 : W/\"e1\" et \"e1\" sont la même version", n)
	}
}

// Une version plus récente sur le serveur : le GET a lieu, et le cache prend
// le nouveau contenu et le nouvel ETag.
func TestPullEtagDifferentRelit(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	if err := s.Accept("a.md", []byte("ancienne"), `"e1"`); err != nil {
		t.Fatal(err)
	}
	remote.files["a.md"] = "nouvelle"
	remote.etags["a.md"] = `"e2"`

	if err := s.Pull(context.Background(), remote, "a.md"); err != nil {
		t.Fatalf("Pull: %v", err)
	}
	if n := lectures(remote); n != 1 {
		t.Errorf("%d GET, attendu 1", n)
	}
	content, entry, _ := s.Get("a.md")
	if string(content) != "nouvelle" || entry.ETag != `"e2"` {
		t.Errorf("cache = %q, ETag %q", content, entry.ETag)
	}
}

// Supprimée sur le serveur : même traitement qu'avant, la note quitte le cache.
func TestPullEtagNoteSupprimee(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	if err := s.Accept("a.md", []byte("contenu"), `"e1"`); err != nil {
		t.Fatal(err)
	}

	if err := s.Pull(context.Background(), remote, "a.md"); err != nil {
		t.Fatalf("Pull: %v", err)
	}
	if _, _, ok := s.Get("a.md"); ok {
		t.Error("la note supprimée sur le serveur est restée en cache")
	}
}

// Réseau absent : l'erreur hors ligne remonte, sans seconde requête, et le
// cache reste intact pour le repli de l'ouverture.
func TestPullEtagHorsLigne(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	if err := s.Accept("a.md", []byte("contenu"), `"e1"`); err != nil {
		t.Fatal(err)
	}
	remote.offline = true

	err := s.Pull(context.Background(), remote, "a.md")
	if !errors.Is(err, opencloud.ErrOffline) {
		t.Fatalf("erreur = %v, attendu ErrOffline", err)
	}
	if content, _, ok := s.Get("a.md"); !ok || string(content) != "contenu" {
		t.Errorf("cache après échec réseau = %q, %v", content, ok)
	}
}

// Une modification locale n'est ni confrontée ni écrasée : aucun appel.
func TestPullEtagNoteModifieeLocalement(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	if err := s.Accept("a.md", []byte("base"), `"e1"`); err != nil {
		t.Fatal(err)
	}
	if err := s.Put("a.md", []byte("mon brouillon")); err != nil {
		t.Fatal(err)
	}
	remote.files["a.md"] = "version serveur"
	remote.etags["a.md"] = `"e2"`

	if err := s.Pull(context.Background(), remote, "a.md"); err != nil {
		t.Fatalf("Pull: %v", err)
	}
	if len(remote.calls) != 0 {
		t.Errorf("appels au serveur pour une note modifiée : %v", remote.calls)
	}
	if content, _, _ := s.Get("a.md"); string(content) != "mon brouillon" {
		t.Errorf("cache = %q", content)
	}
}

// L'index connaît la note mais son fichier a disparu : l'ETag ne suffit plus,
// il faut le contenu.
func TestPullEtagBlobManquant(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	if err := s.Accept("a.md", []byte("contenu"), `"e1"`); err != nil {
		t.Fatal(err)
	}
	_, entry, _ := s.Get("a.md")
	if err := os.Remove(s.blobPath(entry.Cache)); err != nil {
		t.Fatal(err)
	}
	remote.files["a.md"] = "contenu"
	remote.etags["a.md"] = `"e1"`

	if err := s.Pull(context.Background(), remote, "a.md"); err != nil {
		t.Fatalf("Pull: %v", err)
	}
	if n := lectures(remote); n != 1 {
		t.Errorf("%d GET, attendu 1 : le blob manquant doit être retéléchargé", n)
	}
	if content, _, ok := s.Get("a.md"); !ok || string(content) != "contenu" {
		t.Errorf("cache = %q, %v", content, ok)
	}
}
