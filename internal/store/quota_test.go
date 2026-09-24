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

// Une écriture qui fait dépasser le quota évince, sans attendre Prune.
//
// Le chemin d'écriture additionne Entry.Size pour éviter un Stat par note à
// chaque enregistrement ; ce raccourci ne doit dispenser que d'une éviction
// inutile, jamais d'une nécessaire.
func TestPutAuDelaDuQuotaEvinceSelonLRU(t *testing.T) {
	s := newStore(t)
	if err := s.SetQuota(10); err != nil {
		t.Fatalf("SetQuota: %v", err)
	}
	accepteSansQuota(t, s, "ancienne.md", "aaaa")
	accepteSansQuota(t, s, "recente.md", "bbbb")
	fixeAcces(t, s, "ancienne.md", time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC))
	fixeAcces(t, s, "recente.md", time.Date(2026, 8, 2, 0, 0, 0, 0, time.UTC))

	// Pas de Get : il compte comme un accès et fausserait l'ordre LRU.
	enCache := func(notePath string) bool {
		s.mu.Lock()
		defer s.mu.Unlock()
		_, ok := s.entries[notePath]
		return ok
	}

	// 8 octets en cache, 2 de plus tiennent : rien ne part.
	if err := s.Put("petite.md", []byte("cc")); err != nil {
		t.Fatalf("Put sous le quota: %v", err)
	}
	if !enCache("ancienne.md") {
		t.Fatal("éviction sans dépassement")
	}

	// 4 de plus dépassent : la plus ancienne note propre part.
	if err := s.Put("nouvelle.md", []byte("dddd")); err != nil {
		t.Fatalf("Put au-delà du quota: %v", err)
	}
	if enCache("ancienne.md") {
		t.Error("la note LRU est restée dans le cache malgré le dépassement")
	}
	if !enCache("recente.md") {
		t.Error("la note récente a été évincée")
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

// cacheDetient dit si le cache porte le contenu d'une note, sans passer par
// Get : une lecture compte comme un accès et fausserait l'ordre LRU.
func cacheDetient(s *Store, notePath string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	entry, ok := s.entries[notePath]
	if !ok {
		return false
	}
	_, err := os.Stat(s.blobPath(entry.Cache))
	return err == nil
}

// Un dossier renommé sur le serveur, dans un cache plein : c'est l'état
// ordinaire d'un cache LRU après quelques semaines.
//
// Le renommage recopiait chaque blob par writeBlob, donc sous quota, et la
// copie comptait en plus de la source. L'éviction ainsi provoquée emportait une
// note sœur que la boucle n'avait pas encore traitée, et la boucle déréférençait
// ensuite son entrée disparue : panique, donc mort du processus sous gomobile.
func TestRenommageDeDossierSousQuotaNEvinceRien(t *testing.T) {
	s := newStore(t)
	if err := s.SetQuota(UnlimitedQuota); err != nil {
		t.Fatalf("SetQuota illimité: %v", err)
	}
	accepteSansQuota(t, s, "d/a.md", "aaaa")
	accepteSansQuota(t, s, "d/b.md", "bbbb")
	// b est la plus ancienne : c'est elle que l'éviction choisirait, alors
	// que la boucle ne l'a pas encore déplacée.
	fixeAcces(t, s, "d/a.md", time.Date(2026, 8, 2, 0, 0, 0, 0, time.UTC))
	fixeAcces(t, s, "d/b.md", time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC))
	if err := s.SetQuota(8); err != nil {
		t.Fatalf("SetQuota au ras du cache: %v", err)
	}

	if err := s.RenameLocal("d", "e"); err != nil {
		t.Fatalf("RenameLocal: %v", err)
	}
	for chemin, attendu := range map[string]string{"e/a.md": "aaaa", "e/b.md": "bbbb"} {
		if !cacheDetient(s, chemin) {
			t.Errorf("%s a perdu son contenu au renommage", chemin)
			continue
		}
		if contenu, _, _ := s.Get(chemin); string(contenu) != attendu {
			t.Errorf("%s = %q, attendu %q", chemin, contenu, attendu)
		}
	}
	for _, ancien := range []string{"d/a.md", "d/b.md"} {
		if cacheDetient(s, ancien) {
			t.Errorf("%s est resté sous l'ancien chemin", ancien)
		}
	}
	if usage := s.Usage(); usage != 8 {
		t.Errorf("usage = %d après renommage, attendu 8 : un déplacement ne change pas l'occupation", usage)
	}
}

// Renommer une note ne doit pas en évincer une autre : l'occupation ne change
// pas, il n'y a rien à libérer.
func TestRenommageSousQuotaNEvincePasUneAutreNote(t *testing.T) {
	s := newStore(t)
	if err := s.SetQuota(UnlimitedQuota); err != nil {
		t.Fatalf("SetQuota illimité: %v", err)
	}
	accepteSansQuota(t, s, "ancienne.md", "xxxx")
	accepteSansQuota(t, s, "a.md", "aaaa")
	fixeAcces(t, s, "ancienne.md", time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC))
	fixeAcces(t, s, "a.md", time.Date(2026, 8, 2, 0, 0, 0, 0, time.UTC))
	if err := s.SetQuota(8); err != nil {
		t.Fatalf("SetQuota au ras du cache: %v", err)
	}

	if err := s.RenameLocal("a.md", "b.md"); err != nil {
		t.Fatalf("RenameLocal: %v", err)
	}
	if !cacheDetient(s, "ancienne.md") {
		t.Error("le renommage a évincé une note sans rapport")
	}
	if !cacheDetient(s, "b.md") {
		t.Error("la note renommée a perdu son contenu")
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
