package store

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// photographieIndex rend l'index tel qu'il est sur le disque. Le remettre plus
// tard simule un arrêt survenu après l'écriture d'un blob mais avant celle de
// l'index qui le décrit.
func photographieIndex(t *testing.T, s *Store) []byte {
	t.Helper()
	data, err := os.ReadFile(s.indexPath())
	if err != nil {
		t.Fatalf("lecture de l'index: %v", err)
	}
	return data
}

func remetIndex(t *testing.T, s *Store, data []byte) {
	t.Helper()
	if err := os.WriteFile(s.indexPath(), data, 0o600); err != nil {
		t.Fatalf("restauration de l'index: %v", err)
	}
}

func casseIndex(t *testing.T, dir string) {
	t.Helper()
	if err := writeCorruptIndex(dir); err != nil {
		t.Fatalf("corruption de l'index: %v", err)
	}
}

func rouvre(t *testing.T, dir string) *Store {
	t.Helper()
	s, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	return s
}

func TestIndexPerduEnModeLocalReconstruitNomsEtDossiers(t *testing.T) {
	dir := t.TempDir()
	s := rouvre(t, dir)
	if err := s.SetLocalOnly(true); err != nil {
		t.Fatalf("SetLocalOnly: %v", err)
	}
	if err := s.Put("Carnet/a.md", []byte("alpha")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if err := s.Put("b.md", []byte("bêta")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	// Le double suit le renommage : c'est le nouveau chemin qui doit revenir.
	if err := s.RenameOnDevice("b.md", "Archives/c.md"); err != nil {
		t.Fatalf("RenameOnDevice: %v", err)
	}

	casseIndex(t, dir)
	reopened := rouvre(t, dir)

	for chemin, attendu := range map[string]string{"Carnet/a.md": "alpha", "Archives/c.md": "bêta"} {
		content, _, ok := reopened.Get(chemin)
		if !ok || string(content) != attendu {
			t.Errorf("%s après reconstruction = %q, présente = %v", chemin, content, ok)
		}
	}
	if _, _, ok := reopened.Get("Note récupérée 001.md"); ok {
		t.Error("une note au double valide a été récupérée sous un nom de secours")
	}
	dossiers := map[string]bool{}
	for _, d := range reopened.Folders() {
		dossiers[d] = true
	}
	if !dossiers["Carnet"] || !dossiers["Archives"] {
		t.Errorf("dossiers reconstruits = %v", reopened.Folders())
	}
}

// Le mode n'est plus connu une fois l'index perdu : la reconstruction se place
// du côté prudent — tout est à confronter au serveur. En mode serveur, la
// confrontation règle en silence ce qui n'a pas bougé et envoie le reste.
func TestIndexPerduEnModeServeurConfronteToutAuServeur(t *testing.T) {
	dir := t.TempDir()
	s := rouvre(t, dir)
	if err := s.Accept("telechargee.md", []byte("version serveur"), `"e1"`); err != nil {
		t.Fatalf("Accept: %v", err)
	}
	if err := s.Put("ecrite.md", []byte("écrite hors connexion")); err != nil {
		t.Fatalf("Put: %v", err)
	}

	casseIndex(t, dir)
	reopened := rouvre(t, dir)

	for _, chemin := range []string{"telechargee.md", "ecrite.md"} {
		if _, entry, ok := reopened.Get(chemin); !ok || !entry.Dirty {
			t.Errorf("%s après reconstruction : entrée = %+v, présente = %v", chemin, entry, ok)
		}
	}
	if n := len(reopened.Pending()); n != 2 {
		t.Fatalf("file reconstruite = %+v, attendu deux écritures", reopened.Pending())
	}

	remote := newFakeRemote()
	remote.files["telechargee.md"] = "version serveur"
	remote.etags["telechargee.md"] = `"e1"`
	if _, err := reopened.Push(context.Background(), remote); err != nil {
		t.Fatalf("Push: %v", err)
	}
	if remote.files["ecrite.md"] != "écrite hors connexion" {
		t.Errorf("la note écrite hors connexion n'a pas été envoyée : %q", remote.files["ecrite.md"])
	}
	if remote.etags["telechargee.md"] != `"e1"` {
		t.Errorf("la note inchangée a été réécrite sur le serveur : ETag %s", remote.etags["telechargee.md"])
	}
	if len(remote.files) != 2 {
		t.Errorf("fichiers serveur = %v, aucune copie de conflit attendue", remote.files)
	}
}

// Un index d'une version inconnue — écrit par une version plus récente de
// l'application — ne doit ni bloquer l'ouverture ni la faire partir à vide.
func TestIndexDUneVersionInconnueReconstruit(t *testing.T) {
	dir := t.TempDir()
	s := rouvre(t, dir)
	if err := s.SetLocalOnly(true); err != nil {
		t.Fatalf("SetLocalOnly: %v", err)
	}
	if err := s.Put("unique.md", []byte("irremplaçable")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	// L'entrée fantôme n'a de sens que pour le format futur : la voir
	// apparaître prouverait qu'il a été interprété.
	futur := `{"version": 999, "entries": {"fantome.md": {"path": "fantome.md", "cache": "absent.md", "dirty": true}}}`
	if err := os.WriteFile(s.indexPath(), []byte(futur), 0o600); err != nil {
		t.Fatalf("écriture de l'index futur: %v", err)
	}

	reopened := rouvre(t, dir)
	if content, _, ok := reopened.Get("unique.md"); !ok || string(content) != "irremplaçable" {
		t.Fatalf("note après reconstruction = %q, présente = %v", content, ok)
	}
	for _, entry := range reopened.Entries() {
		if entry.Path == "fantome.md" {
			t.Fatal("l'index d'une version inconnue a été interprété")
		}
	}
}

// L'arrêt entre l'écriture du blob d'une note neuve et celle de l'index :
// l'orphelin retrouve son nom grâce à son double.
func TestOrphelinRetrouveSonCheminParSonDouble(t *testing.T) {
	dir := t.TempDir()
	s := rouvre(t, dir)
	if err := s.Put("existante.md", []byte("déjà là")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	avant := photographieIndex(t, s)
	if err := s.Put("Journal/neuve.md", []byte("écrite juste avant l'arrêt")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	remetIndex(t, s, avant)

	reopened := rouvre(t, dir)
	content, entry, ok := reopened.Get("Journal/neuve.md")
	if !ok || string(content) != "écrite juste avant l'arrêt" {
		t.Fatalf("note neuve après arrêt = %q, présente = %v", content, ok)
	}
	if !entry.Dirty {
		t.Error("la note neuve récupérée n'est pas en attente d'envoi")
	}
	pending := reopened.Pending()
	trouvee := false
	for _, op := range pending {
		if op.Kind == OpWrite && op.Path == "Journal/neuve.md" {
			trouvee = true
		}
	}
	if !trouvee {
		t.Errorf("file après récupération = %+v", pending)
	}
}

// L'arrêt entre l'écriture du blob d'une note propre et celle de l'index : le
// blob porte la modification, l'index dit encore la note propre. Sans rattrapage,
// le prochain rafraîchissement remplacerait la modification par la version du
// serveur.
func TestBlobPlusRecentQueLIndexRepartEnFile(t *testing.T) {
	dir := t.TempDir()
	s := rouvre(t, dir)
	if err := s.Accept("note.md", []byte("version serveur"), `"e1"`); err != nil {
		t.Fatalf("Accept: %v", err)
	}
	avant := photographieIndex(t, s)
	// La date de l'index doit précéder celle du blob, comme dans un vrai arrêt.
	time.Sleep(20 * time.Millisecond)
	if err := s.Put("note.md", []byte("modification locale")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	remetIndex(t, s, avant)

	reopened := rouvre(t, dir)
	content, entry, ok := reopened.Get("note.md")
	if !ok || string(content) != "modification locale" || !entry.Dirty {
		t.Fatalf("note après arrêt = %q, entrée = %+v, présente = %v", content, entry, ok)
	}
	pending := reopened.Pending()
	if len(pending) != 1 || pending[0].Kind != OpWrite || pending[0].Path != "note.md" {
		t.Fatalf("file après arrêt = %+v", pending)
	}
}

func TestSauvegardeNEcritQuUnSeulIndex(t *testing.T) {
	dir := t.TempDir()
	s := rouvre(t, dir)
	for i := 0; i < 3; i++ {
		if err := s.Put("note.md", []byte{byte('a' + i)}); err != nil {
			t.Fatalf("Put: %v", err)
		}
	}
	if _, err := os.Stat(filepath.Join(dir, "indexes")); !os.IsNotExist(err) {
		t.Errorf("un dossier de générations a été créé : %v", err)
	}
}

// Les générations d'index d'une version précédente ne servent plus : la
// migration les retire, et écrit les doubles des notes déjà présentes.
func TestMigrationEcritLesDoublesEtRetireLesGenerations(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "notes"), 0o700); err != nil {
		t.Fatalf("création du dossier notes: %v", err)
	}
	legacyCache := cacheName("Avant/note.md")
	if err := os.WriteFile(filepath.Join(dir, "notes", legacyCache), []byte("ancien format"), 0o600); err != nil {
		t.Fatalf("écriture du blob historique: %v", err)
	}
	generations := filepath.Join(dir, "indexes")
	if err := os.MkdirAll(generations, 0o700); err != nil {
		t.Fatalf("création des générations: %v", err)
	}
	if err := os.WriteFile(filepath.Join(generations, "index-00000000000000000001.json"), []byte("{}"), 0o600); err != nil {
		t.Fatalf("écriture d'une génération: %v", err)
	}
	state := persisted{
		Version:   4,
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

	rouvre(t, dir)
	if _, err := os.Stat(generations); !os.IsNotExist(err) {
		t.Errorf("générations encore présentes après migration : %v", err)
	}

	casseIndex(t, dir)
	reopened := rouvre(t, dir)
	if content, _, ok := reopened.Get("Avant/note.md"); !ok || string(content) != "ancien format" {
		t.Fatalf("note migrée après perte de l'index = %q, présente = %v", content, ok)
	}
}

func TestSuppressionRetireLeDouble(t *testing.T) {
	s := newStore(t)
	if err := s.Put("note.md", []byte("x")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	double := s.sidecarPath(s.entries["note.md"].Cache)
	if _, err := os.Stat(double); err != nil {
		t.Fatalf("double absent après écriture : %v", err)
	}
	if err := s.Delete("note.md"); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	if _, err := os.Stat(double); !os.IsNotExist(err) {
		t.Errorf("double encore présent après suppression : %v", err)
	}
}

// Un double sans blob — arrêt entre les deux écritures d'une création — ne
// décrit rien : l'ouverture le retire.
func TestDoubleSansBlobEstRetire(t *testing.T) {
	dir := t.TempDir()
	s := rouvre(t, dir)
	perdu := s.sidecarPath(localCachePrefix + "0123456789abcdef0123456789abcdef.md")
	if err := os.WriteFile(perdu, []byte(`{"path":"perdue.md"}`), 0o600); err != nil {
		t.Fatalf("écriture du double: %v", err)
	}
	reopened := rouvre(t, dir)
	if _, err := os.Stat(perdu); !os.IsNotExist(err) {
		t.Errorf("double sans blob encore présent : %v", err)
	}
	if _, _, ok := reopened.Get("perdue.md"); ok {
		t.Error("un double sans blob a produit une note")
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

	s := rouvre(t, dir)
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
	s := rouvre(t, dir)
	if err := s.SetLocalOnly(true); err != nil {
		t.Fatalf("SetLocalOnly: %v", err)
	}
	orphan := "0123456789abcdef0123456789abcdef.md"
	if err := os.WriteFile(s.blobPath(orphan), []byte("texte sauvé"), 0o600); err != nil {
		t.Fatalf("écriture de l'orphelin: %v", err)
	}

	reopened := rouvre(t, dir)
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

func TestModeServeurRecupereUnOrphelinLocalEtJetteUnTelechargementOrphelin(t *testing.T) {
	dir := t.TempDir()
	s := rouvre(t, dir)
	if err := s.Accept("serveur.md", []byte("référence"), `"e1"`); err != nil {
		t.Fatalf("Accept: %v", err)
	}
	localOrphan := localCachePrefix + "0123456789abcdef0123456789abcdef.md"
	remoteOrphan := remoteCachePrefix + "fedcba9876543210fedcba9876543210.md"
	if err := os.WriteFile(s.blobPath(localOrphan), []byte("brouillon interrompu"), 0o600); err != nil {
		t.Fatalf("écriture de l'orphelin local: %v", err)
	}
	if err := os.WriteFile(s.blobPath(remoteOrphan), []byte("copie serveur"), 0o600); err != nil {
		t.Fatalf("écriture de l'orphelin distant: %v", err)
	}
	if err := os.WriteFile(s.sidecarPath(remoteOrphan), []byte(`{"path":"copie.md"}`), 0o600); err != nil {
		t.Fatalf("écriture du double distant: %v", err)
	}

	reopened := rouvre(t, dir)
	content, entry, ok := reopened.Get("Note récupérée 001.md")
	if !ok || string(content) != "brouillon interrompu" || !entry.Dirty {
		t.Fatalf("orphelin local = %q, entrée = %+v, présent = %v", content, entry, ok)
	}
	if _, err := os.Stat(reopened.blobPath(remoteOrphan)); !os.IsNotExist(err) {
		t.Fatalf("téléchargement serveur orphelin encore présent: %v", err)
	}
	if _, err := os.Stat(reopened.sidecarPath(remoteOrphan)); !os.IsNotExist(err) {
		t.Fatalf("double du téléchargement orphelin encore présent: %v", err)
	}
	pending := reopened.Pending()
	if len(pending) != 1 || pending[0].Path != "Note récupérée 001.md" {
		t.Fatalf("file de récupération = %+v", pending)
	}
}
