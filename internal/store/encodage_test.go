package store

import (
	"context"
	"testing"
)

// L'encodage retenu à l'écriture survit à l'envoi, au rafraîchissement qui
// rapporte le même contenu, et à la réouverture du cache : c'est tout son
// intérêt, puisque ReadNote rafraîchit chaque note propre qu'il ouvre.
func TestLEncodageRetenuSurvitAuServeurEtALaReouverture(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	remote := newFakeRemote()
	ctx := context.Background()
	contenu := []byte("\xc9 \xe9\x9c\x9c \xf8\n")

	if err := s.PutEncoded("a.txt", contenu, "windows-1252"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Push(ctx, remote); err != nil {
		t.Fatal(err)
	}
	if err := s.Pull(ctx, remote, "a.txt"); err != nil {
		t.Fatal(err)
	}

	s, err = Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if enc, ok := s.EncodingOf("a.txt", contenu); !ok || enc != "windows-1252" {
		t.Errorf("EncodingOf après envoi, rafraîchissement et réouverture = %q, %v", enc, ok)
	}
}

// Un contenu changé sur le serveur n'a plus d'encodage connu : ce n'est plus
// nous qui l'avons écrit.
func TestLEncodageRetenuNeVautPlusPourUnAutreContenu(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	ctx := context.Background()

	if err := s.PutEncoded("a.txt", []byte("\xe9t\xe9"), "windows-1252"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Push(ctx, remote); err != nil {
		t.Fatal(err)
	}
	remote.files["a.txt"] = "Za\xbf\xf3\xb3\xe6"
	remote.etags["a.txt"] = `"ailleurs"`
	if err := s.Pull(ctx, remote, "a.txt"); err != nil {
		t.Fatal(err)
	}

	contenu, _, _ := s.Get("a.txt")
	if enc, ok := s.EncodingOf("a.txt", contenu); ok {
		t.Errorf("EncodingOf = %q pour un contenu écrit ailleurs", enc)
	}
}

// Une écriture en UTF-8 efface la mémoire ; un contenu inchangé la pose quand
// même, sans réécrire ni remettre en file.
func TestPutEncodedPoseEtEfface(t *testing.T) {
	s := newStore(t)
	contenu := []byte("ete\n")

	if err := s.Put("a.txt", contenu); err != nil {
		t.Fatal(err)
	}
	enAttente := len(s.Pending())
	if err := s.PutEncoded("a.txt", contenu, "windows-1252"); err != nil {
		t.Fatal(err)
	}
	if enc, ok := s.EncodingOf("a.txt", contenu); !ok || enc != "windows-1252" {
		t.Errorf("contenu inchangé : EncodingOf = %q, %v", enc, ok)
	}
	if n := len(s.Pending()); n != enAttente {
		t.Errorf("file : %d opérations, %d avant", n, enAttente)
	}

	if err := s.Put("a.txt", []byte("été\n")); err != nil {
		t.Fatal(err)
	}
	if enc, ok := s.EncodingOf("a.txt", []byte("été\n")); ok {
		t.Errorf("après une écriture UTF-8 : EncodingOf = %q", enc)
	}
}

// Le choix explicite de l'utilisateur porte sur le contenu en cache, sans le
// réécrire, survit à la réouverture, et suit la note quand elle est renommée.
func TestRememberEncodingEtRenommage(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	contenu := []byte("o\x00k\x00")

	if err := s.Accept("a.txt", contenu, `"e1"`); err != nil {
		t.Fatal(err)
	}
	if err := s.RememberEncoding("a.txt", "UTF-16LE"); err != nil {
		t.Fatal(err)
	}
	if _, entry, _ := s.Get("a.txt"); entry.Dirty {
		t.Error("retenir un encodage a marqué la note modifiée")
	}
	if s, err = Open(dir); err != nil {
		t.Fatal(err)
	}
	if err := s.RenameLocal("a.txt", "b.txt"); err != nil {
		t.Fatal(err)
	}
	if enc, ok := s.EncodingOf("b.txt", contenu); !ok || enc != "UTF-16LE" {
		t.Errorf("après renommage : EncodingOf = %q, %v", enc, ok)
	}
	if err := s.RememberEncoding("absente.txt", "UTF-16LE"); err == nil {
		t.Error("RememberEncoding accepte une note absente du cache")
	}
}
