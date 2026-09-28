package store

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"testing"
)

func indexGenerations(t *testing.T, s *Store) []string {
	t.Helper()
	files, err := os.ReadDir(s.indexesDir())
	if err != nil {
		t.Fatalf("lecture des générations: %v", err)
	}
	var paths []string
	for _, file := range files {
		if !file.IsDir() && filepath.Ext(file.Name()) == ".json" {
			paths = append(paths, filepath.Join(s.indexesDir(), file.Name()))
		}
	}
	sort.Strings(paths)
	return paths
}

func TestIndexConserveDixGenerationsEtRevientALaPrecedente(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := s.SetLocalOnly(true); err != nil {
		t.Fatalf("SetLocalOnly: %v", err)
	}
	for i := 0; i < 15; i++ {
		if err := s.Put("carnet.md", []byte{byte('a' + i)}); err != nil {
			t.Fatalf("Put %d: %v", i, err)
		}
	}

	generations := indexGenerations(t, s)
	if len(generations) != indexRetention {
		t.Fatalf("générations = %d, attendu %d", len(generations), indexRetention)
	}
	if err := os.WriteFile(s.indexPath(), []byte("index courant cassé"), 0o600); err != nil {
		t.Fatalf("corruption de l'index courant: %v", err)
	}
	if err := os.WriteFile(generations[len(generations)-1], []byte("génération cassée"), 0o600); err != nil {
		t.Fatalf("corruption de la dernière génération: %v", err)
	}

	reopened, err := Open(dir)
	if err != nil {
		t.Fatalf("Open avec repli: %v", err)
	}
	content, _, ok := reopened.Get("carnet.md")
	if !ok || string(content) != "o" {
		t.Fatalf("note après repli = %q, présente = %v", content, ok)
	}
}

func TestRenommageGardeUnBlobStable(t *testing.T) {
	s := newStore(t)
	if err := s.SetLocalOnly(true); err != nil {
		t.Fatalf("SetLocalOnly: %v", err)
	}
	if err := s.Put("Avant/note.md", []byte("contenu")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	before := s.entries["Avant/note.md"].Cache

	if err := s.RenameOnDevice("Avant/note.md", "Après/note.md"); err != nil {
		t.Fatalf("RenameOnDevice: %v", err)
	}
	after := s.entries["Après/note.md"].Cache
	if after != before {
		t.Fatalf("blob renommé: avant=%q après=%q", before, after)
	}
	if content, _, ok := s.Get("Après/note.md"); !ok || string(content) != "contenu" {
		t.Fatalf("contenu après renommage = %q, présent = %v", content, ok)
	}
}

func TestMigrationConserveEtStabiliseLeNomHistoriqueDuBlob(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "notes"), 0o700); err != nil {
		t.Fatalf("création du dossier notes: %v", err)
	}
	legacyCache := cacheName("Avant/note.md")
	if err := os.WriteFile(filepath.Join(dir, "notes", legacyCache), []byte("ancien format"), 0o600); err != nil {
		t.Fatalf("écriture du blob historique: %v", err)
	}
	state := persisted{
		Version:   3,
		LocalOnly: true,
		Entries: map[string]*Entry{
			"Avant/note.md": {Path: "Avant/note.md", Cache: legacyCache, Size: 13},
		},
	}
	data, err := json.Marshal(state)
	if err != nil {
		t.Fatalf("sérialisation de l'ancien index: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, "index.json"), data, 0o600); err != nil {
		t.Fatalf("écriture de l'ancien index: %v", err)
	}

	s, err := Open(dir)
	if err != nil {
		t.Fatalf("migration: %v", err)
	}
	if err := s.RenameOnDevice("Avant/note.md", "Après/note.md"); err != nil {
		t.Fatalf("renommage après migration: %v", err)
	}
	entry := s.entries["Après/note.md"]
	if entry == nil || entry.Cache != legacyCache {
		t.Fatalf("blob historique après renommage = %+v", entry)
	}
	if content, _, ok := s.Get("Après/note.md"); !ok || string(content) != "ancien format" {
		t.Fatalf("contenu migré = %q, présent = %v", content, ok)
	}
}

func TestModeLocalRecupereUnBlobOrphelinALaRacine(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := s.SetLocalOnly(true); err != nil {
		t.Fatalf("SetLocalOnly: %v", err)
	}
	orphan := "0123456789abcdef0123456789abcdef.md"
	if err := os.WriteFile(s.blobPath(orphan), []byte("texte sauvé"), 0o600); err != nil {
		t.Fatalf("écriture de l'orphelin: %v", err)
	}

	reopened, err := Open(dir)
	if err != nil {
		t.Fatalf("Open de récupération: %v", err)
	}
	content, entry, ok := reopened.Get("Note récupérée 001.md")
	if !ok || string(content) != "texte sauvé" {
		t.Fatalf("note récupérée = %q, présente = %v", content, ok)
	}
	if entry.Cache != orphan {
		t.Errorf("blob récupéré = %q, attendu %q", entry.Cache, orphan)
	}
	if len(reopened.Pending()) != 0 {
		t.Errorf("la récupération locale a créé une file: %+v", reopened.Pending())
	}
}

func TestAucunIndexValideConserveLesBlobsEtRefuseLOuverture(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := s.SetLocalOnly(true); err != nil {
		t.Fatalf("SetLocalOnly: %v", err)
	}
	if err := s.Put("unique.md", []byte("irremplaçable")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	cache := s.entries["unique.md"].Cache

	paths := append(indexGenerations(t, s), s.indexPath())
	for _, path := range paths {
		if err := os.WriteFile(path, []byte("cassé"), 0o600); err != nil {
			t.Fatalf("corruption de %s: %v", path, err)
		}
	}
	if _, err := Open(dir); err == nil {
		t.Fatal("Open devrait refuser de repartir avec un index vide")
	}
	content, err := os.ReadFile(s.blobPath(cache))
	if err != nil || string(content) != "irremplaçable" {
		t.Fatalf("blob après refus = %q, erreur = %v", content, err)
	}
}
