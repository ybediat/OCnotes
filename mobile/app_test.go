package mobile

import (
	"encoding/json"
	"strings"
	"testing"
)

// prepare monte une application connectée à un serveur factice, avec un espace
// de travail et une note déjà synchronisée.
func prepare(t *testing.T) (*App, *fakeServer, string) {
	t.Helper()

	server := newFakeServer(t)
	dataDir := t.TempDir()

	app, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := app.Connect(server.URL, fakeUser, fakeToken); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	if err := app.SelectWorkspace(fakeSpaceID, "Notes"); err != nil {
		t.Fatalf("SelectWorkspace: %v", err)
	}
	return app, server, dataDir
}

func decodeJSON(t *testing.T, raw string, into any) {
	t.Helper()
	if err := json.Unmarshal([]byte(raw), into); err != nil {
		t.Fatalf("JSON illisible (%s): %v", strings.TrimSpace(raw), err)
	}
}

// Le scénario que le modèle local-first existe pour servir : ouvrir
// l'application sans réseau et retrouver ses notes.
//
// Connect ne peut pas y parvenir — il valide les identifiants auprès du
// serveur — d'où Restore, qui remonte la session depuis la configuration sans
// aucun appel réseau.
func TestRestoreOuvreLApplicationSansReseau(t *testing.T) {
	app, server, dataDir := prepare(t)

	if _, err := app.CreateNoteJSON("", "en cache", "# En cache\n"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}

	// Le serveur devient injoignable, comme dans le métro.
	server.Close()

	offline, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	// Connect échoue, c'est attendu : il fait un appel réseau.
	if err := offline.Connect(server.URL, fakeUser, fakeToken); err == nil {
		t.Fatal("Connect aurait dû échouer sans réseau")
	}

	// Restore, lui, doit réussir.
	if err := offline.Restore(fakeToken); err != nil {
		t.Fatalf("Restore sans réseau: %v", err)
	}

	raw, err := offline.ListFolderJSON("")
	if err != nil {
		t.Fatalf("ListFolderJSON hors connexion: %v", err)
	}

	var listing folderListing
	decodeJSON(t, raw, &listing)
	if !listing.FromCache {
		t.Error("le listing aurait dû être signalé comme venant du cache")
	}
	if len(listing.Entries) != 1 || listing.Entries[0].Display != "en cache" {
		t.Fatalf("entrées = %+v, attendu la note en cache", listing.Entries)
	}

	// Et la note doit s'ouvrir.
	content, err := offline.ReadNote("en cache.md")
	if err != nil {
		t.Fatalf("ReadNote hors connexion: %v", err)
	}
	if content != "# En cache\n" {
		t.Errorf("contenu = %q", content)
	}
}

// Une écriture hors connexion ne doit jamais échouer, et doit survivre à la
// fermeture de l'application.
func TestEcritureHorsConnexionPuisSynchronisation(t *testing.T) {
	app, server, dataDir := prepare(t)

	if _, err := app.CreateNoteJSON("", "brouillon", "initial"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}

	server.Close()

	offline, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := offline.Restore(fakeToken); err != nil {
		t.Fatalf("Restore: %v", err)
	}

	// L'écriture n'atteint que le cache : pas d'erreur réseau possible.
	if err := offline.WriteNote("brouillon.md", "écrit dans le métro"); err != nil {
		t.Fatalf("WriteNote hors connexion: %v", err)
	}
	if offline.PendingCount() != 1 {
		t.Errorf("PendingCount = %d, attendu 1", offline.PendingCount())
	}

	// Une passe de synchronisation sans réseau décrit l'échec sans lever
	// d'exception, et conserve la file.
	raw, err := offline.SyncJSON()
	if err != nil {
		t.Fatalf("SyncJSON ne doit pas échouer sur une panne réseau: %v", err)
	}
	var result syncResult
	decodeJSON(t, raw, &result)
	if result.Error == "" {
		t.Error("la panne réseau aurait dû être décrite dans le champ error")
	}
	if result.Remaining != 1 {
		t.Errorf("Remaining = %d, attendu 1", result.Remaining)
	}
	if offline.PendingCount() != 1 {
		t.Error("l'opération en attente a été perdue")
	}
}

// Un dossier réellement vide, consulté hors connexion, doit s'afficher vide et
// non remonter l'erreur réseau.
func TestListingHorsConnexionDossierVide(t *testing.T) {
	app, server, dataDir := prepare(t)

	if _, err := app.CreateNoteJSON("", "ailleurs", "x"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.CreateFolderJSON("", "Vide"); err != nil {
		t.Fatalf("CreateFolderJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}

	server.Close()

	offline, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := offline.Restore(fakeToken); err != nil {
		t.Fatalf("Restore: %v", err)
	}

	raw, err := offline.ListFolderJSON("Vide")
	if err != nil {
		t.Fatalf("ListFolderJSON sur un dossier vide hors connexion: %v", err)
	}

	var listing folderListing
	decodeJSON(t, raw, &listing)
	if !listing.FromCache {
		t.Error("FromCache devrait être vrai")
	}
	if len(listing.Entries) != 0 {
		t.Errorf("entrées = %+v, attendu aucune", listing.Entries)
	}
}

// Le contrat annonce des tableaux : encoding/json sérialise une slice nulle en
// « null », ce que Kotlin devrait alors gérer comme un second cas.
func TestLesTableauxNeSontJamaisNull(t *testing.T) {
	app, _, _ := prepare(t)

	raw, err := app.ListFolderJSON("")
	if err != nil {
		t.Fatalf("ListFolderJSON: %v", err)
	}
	if strings.Contains(raw, `"entries":null`) {
		t.Errorf("entries sérialisé en null: %s", raw)
	}

	raw, err = app.SyncJSON()
	if err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	if strings.Contains(raw, `"conflicts":null`) {
		t.Errorf("conflicts sérialisé en null: %s", raw)
	}

	raw, err = app.ListDrivesJSON()
	if err != nil {
		t.Fatalf("ListDrivesJSON: %v", err)
	}
	if raw == "null" {
		t.Error("la liste des espaces est sérialisée en null")
	}
}

func TestRestoreSansEspaceEnregistre(t *testing.T) {
	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := app.Restore("un-token"); err == nil {
		t.Error("Restore aurait dû échouer sans espace enregistré")
	}
}

// Un token invalide doit être reconnaissable par sa catégorie, jamais par le
// texte français du message.
func TestTokenInvalideEstCategorise(t *testing.T) {
	server := newFakeServer(t)

	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	err = app.Connect(server.URL, fakeUser, "mauvais-token")
	if err == nil {
		t.Fatal("Connect aurait dû échouer")
	}
	if !IsAuthError(err.Error()) {
		t.Errorf("IsAuthError(%q) = false", err.Error())
	}
	if got := ErrorCode(err.Error()); got != "AUTH" {
		t.Errorf("ErrorCode = %q, attendu AUTH", got)
	}
}

// Une synchronisation refusée pour cause de token expiré doit être signalée
// comme telle : réessayer indéfiniment ne servirait à rien.
func TestSyncSignaleLaCategorieDErreur(t *testing.T) {
	app, server, dataDir := prepare(t)

	if _, err := app.CreateNoteJSON("", "note", "v1"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}

	// On remonte la session avec un token que le serveur refuse.
	expired, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := expired.Restore("token-expire"); err != nil {
		t.Fatalf("Restore: %v", err)
	}
	if err := expired.WriteNote("note.md", "v2"); err != nil {
		t.Fatalf("WriteNote: %v", err)
	}

	raw, err := expired.SyncJSON()
	if err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	var result syncResult
	decodeJSON(t, raw, &result)

	if result.ErrorCode != "AUTH" {
		t.Errorf("ErrorCode = %q, attendu AUTH (message: %s)", result.ErrorCode, result.Error)
	}
	_ = server
}

// Après SelectWorkspace, la configuration doit contenir de quoi remonter la
// session sans réseau.
func TestConfigurationPermetLeDemarrageHorsConnexion(t *testing.T) {
	app, _, _ := prepare(t)

	raw, err := app.StateJSON()
	if err != nil {
		t.Fatalf("StateJSON: %v", err)
	}
	var state appState
	decodeJSON(t, raw, &state)

	if !state.HasWorkspace {
		t.Fatal("HasWorkspace devrait être vrai")
	}
	if state.DriveID != fakeSpaceID {
		t.Errorf("DriveID = %q", state.DriveID)
	}
}

func TestParcoursCompletSurServeurFactice(t *testing.T) {
	app, _, _ := prepare(t)

	var created noteRef
	raw, err := app.CreateNoteJSON("", "Réunion du 15", "# Réunion\n")
	if err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	decodeJSON(t, raw, &created)
	if created.Name != "Réunion du 15.md" {
		t.Errorf("Name = %q", created.Name)
	}

	if err := app.WriteNote(created.Path, "# Réunion\n\n- [x] salle réservée 😀\n"); err != nil {
		t.Fatalf("WriteNote: %v", err)
	}

	var sync syncResult
	raw, err = app.SyncJSON()
	if err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	decodeJSON(t, raw, &sync)
	if sync.Error != "" {
		t.Fatalf("erreur de synchronisation: %s", sync.Error)
	}
	if sync.Pushed < 1 {
		t.Errorf("Pushed = %d", sync.Pushed)
	}

	newPath, err := app.Rename(created.Path, "Réunion reportée")
	if err != nil {
		t.Fatalf("Rename: %v", err)
	}
	if newPath != "Réunion reportée.md" {
		t.Errorf("chemin après renommage = %q", newPath)
	}
	if err := app.Delete(newPath); err != nil {
		t.Fatalf("Delete: %v", err)
	}
}

func TestApplyFormatJSON(t *testing.T) {
	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	req, _ := json.Marshal(formatRequest{Text: "été 😀 mot", Start: 7, End: 10, Action: "bold"})
	raw, err := app.ApplyFormatJSON(string(req))
	if err != nil {
		t.Fatalf("ApplyFormatJSON: %v", err)
	}

	var got formatResult
	decodeJSON(t, raw, &got)
	if got.Text != "été 😀 **mot**" {
		t.Errorf("texte = %q", got.Text)
	}

	if _, err := app.ApplyFormatJSON(`{"action":"inexistante"}`); err == nil {
		t.Error("une action inconnue devrait produire une erreur")
	}
	if _, err := app.ApplyFormatJSON("pas du json"); err == nil {
		t.Error("un JSON invalide devrait produire une erreur")
	}
}
