package store

import (
	"os"
	"strings"
	"testing"
	"time"
)

func fixeAcces(t *testing.T, s *Store, notePath string, at time.Time) {
	t.Helper()
	s.mu.Lock()
	defer s.mu.Unlock()
	entry, ok := s.entries[notePath]
	if !ok {
		t.Fatalf("entrée absente : %s", notePath)
	}
	entry.LastAccess = at
	if err := s.save(); err != nil {
		t.Fatalf("save: %v", err)
	}
}

func accepteSansQuota(t *testing.T, s *Store, path, content string) {
	t.Helper()
	if err := s.Accept(path, []byte(content), `"etag"`); err != nil {
		t.Fatalf("Accept(%s): %v", path, err)
	}
}

func TestPruneEvinceLesNotesPropresSelonLRU(t *testing.T) {
	s := newStore(t)
	if err := s.SetQuota(UnlimitedQuota); err != nil {
		t.Fatalf("SetQuota illimité: %v", err)
	}
	accepteSansQuota(t, s, "ancienne.md", "aaaa")
	accepteSansQuota(t, s, "recente.md", "bbbb")
	fixeAcces(t, s, "ancienne.md", time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC))
	fixeAcces(t, s, "recente.md", time.Date(2026, 8, 2, 0, 0, 0, 0, time.UTC))

	if err := s.SetQuota(4); err != nil {
		t.Fatalf("SetQuota: %v", err)
	}
	if _, _, ok := s.Get("ancienne.md"); ok {
		t.Error("la note LRU est restée dans le cache")
	}
	if content, _, ok := s.Get("recente.md"); !ok || string(content) != "bbbb" {
		t.Errorf("note récente = %q, présente = %v", content, ok)
	}
	if !indexContains(s.Index(), "ancienne.md") {
		t.Error("la note évincée a disparu de l'inventaire")
	}
}

func TestPruneProtegeUneNoteDirtyEtUneCopieDeConflit(t *testing.T) {
	s := newStore(t)
	if err := s.SetQuota(UnlimitedQuota); err != nil {
		t.Fatalf("SetQuota illimité: %v", err)
	}
	if err := s.Put("brouillon.md", []byte("aaaa")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	accepteSansQuota(t, s, "copie (conflit).md", "bbbb")
	if err := s.MarkConflict("copie (conflit).md"); err != nil {
		t.Fatalf("MarkConflict: %v", err)
	}

	err := s.SetQuota(1)
	if err == nil {
		t.Fatal("un quota sous les contenus protégés doit être signalé")
	}
	if !strings.Contains(err.Error(), "["+CodeStorageIO+"]") {
		t.Errorf("erreur = %q, étiquette %s attendue", err, CodeStorageIO)
	}
	for _, path := range []string{"brouillon.md", "copie (conflit).md"} {
		if _, _, ok := s.Get(path); !ok {
			t.Errorf("%s a été évincée", path)
		}
	}
}

func TestPruneMesureLesFichiersEtSurvitAuRedemarrage(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := s.SetQuota(UnlimitedQuota); err != nil {
		t.Fatalf("SetQuota: %v", err)
	}
	accepteSansQuota(t, s, "a.md", "aaaa")
	accepteSansQuota(t, s, "b.md", "bbbb")
	fixeAcces(t, s, "a.md", time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC))
	fixeAcces(t, s, "b.md", time.Date(2026, 8, 2, 0, 0, 0, 0, time.UTC))

	// Entry.Size est volontairement faux : seule la taille réelle décide.
	s.mu.Lock()
	s.entries["a.md"].Size = 0
	if err := s.save(); err != nil {
		s.mu.Unlock()
		t.Fatalf("save: %v", err)
	}
	s.mu.Unlock()
	if got := s.Usage(); got != 8 {
		t.Fatalf("Usage = %d, attendu 8", got)
	}

	if err := s.SetQuota(4); err != nil {
		t.Fatalf("SetQuota: %v", err)
	}
	if _, _, ok := s.Get("a.md"); ok {
		t.Error("a.md aurait dû être évincée selon sa taille réelle")
	}

	reopened, err := Open(dir)
	if err != nil {
		t.Fatalf("Open après éviction: %v", err)
	}
	if content, entry, ok := reopened.Get("b.md"); !ok || string(content) != "bbbb" || entry.LastAccess.IsZero() {
		t.Errorf("b.md après redémarrage = %q, entrée = %+v, présente = %v", content, entry, ok)
	}
	if _, err := os.Stat(reopened.blobPath(cacheName("a.md"))); !os.IsNotExist(err) {
		t.Errorf("blob évincé encore présent: %v", err)
	}
}

func TestOuvertureRepareUnBlobPropreManquantEtUnOrphelin(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	accepteSansQuota(t, s, "absente.md", "contenu")
	if err := os.Remove(s.blobPath(cacheName("absente.md"))); err != nil {
		t.Fatalf("suppression du blob: %v", err)
	}
	orphelin := s.blobPath("orphelin.md")
	if err := os.WriteFile(orphelin, []byte("inutile"), 0o600); err != nil {
		t.Fatalf("écriture de l'orphelin: %v", err)
	}

	reopened, err := Open(dir)
	if err != nil {
		t.Fatalf("Open de réparation: %v", err)
	}
	if _, _, ok := reopened.Get("absente.md"); ok {
		t.Error("une entrée propre sans blob devrait redevenir un Known")
	}
	if !indexContains(reopened.Index(), "absente.md") {
		t.Error("la note sans blob a disparu de l'inventaire")
	}
	if _, err := os.Stat(orphelin); !os.IsNotExist(err) {
		t.Errorf("orphelin encore présent: %v", err)
	}
}

func indexContains(entries []Known, path string) bool {
	for _, entry := range entries {
		if entry.Path == path {
			return true
		}
	}
	return false
}
