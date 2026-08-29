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

// L'aperçu est une fonction pure : aucun espace de travail n'est ouvert ici.
//
// C'est ce qui le rend utilisable hors connexion et sur un brouillon jamais
// enregistré — l'interface passe le texte qu'elle a déjà sous les yeux.
func TestRenderNoteJSONMarkdown(t *testing.T) {
	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	raw, err := app.RenderNoteJSON("note.md", "# Titre\n\né😀 **gras**\n")
	if err != nil {
		t.Fatalf("RenderNoteJSON: %v", err)
	}

	var blocks []noteBlock
	decodeJSON(t, raw, &blocks)
	if len(blocks) != 2 {
		t.Fatalf("%d blocs, 2 attendus: %+v", len(blocks), blocks)
	}
	if b := blocks[0]; b.Kind != "heading" || b.Level != 1 || b.Text != "Titre" {
		t.Errorf("bloc 0 = %+v", b)
	}

	b := blocks[1]
	if b.Kind != "paragraph" || b.Text != "é😀 gras" {
		t.Fatalf("bloc 1 = %+v", b)
	}
	if len(b.Spans) != 1 {
		t.Fatalf("%d spans, 1 attendu: %+v", len(b.Spans), b.Spans)
	}
	// Bornes en unités UTF-16 : « é » 1, « 😀 » 2, espace 1. En octets, la
	// frontière livrerait {7, 11} et Compose graisserait au mauvais endroit.
	if s := b.Spans[0]; s.Style != "bold" || s.Start != 4 || s.End != 8 {
		t.Errorf("span = %+v, attendu {4, 8, bold}", s)
	}
}

// C'est le nom qui décide de l'interprétation, pas le contenu.
func TestRenderNoteJSONTexteBrut(t *testing.T) {
	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	source := "# pas un titre\n- pas une puce"

	raw, err := app.RenderNoteJSON("note.txt", source)
	if err != nil {
		t.Fatalf("RenderNoteJSON: %v", err)
	}
	var brut []noteBlock
	decodeJSON(t, raw, &brut)
	if len(brut) != 1 {
		t.Fatalf("%d blocs pour un .txt, 1 attendu: %+v", len(brut), brut)
	}
	if brut[0].Kind != "plain" || brut[0].Text != source {
		t.Errorf("bloc = %+v, attendu le contenu inchangé", brut[0])
	}

	// Le même contenu sous un nom .md est interprété : sans quoi le test
	// ci-dessus passerait aussi avec un moteur qui ne rend jamais rien.
	raw, err = app.RenderNoteJSON("note.md", source)
	if err != nil {
		t.Fatalf("RenderNoteJSON: %v", err)
	}
	var md []noteBlock
	decodeJSON(t, raw, &md)
	if len(md) != 2 || md[0].Kind != "heading" || md[1].Kind != "bullet" {
		t.Errorf("blocs pour un .md = %+v, attendu un titre puis une puce", md)
	}
}

// L'aller-retour complet, tel que l'interface l'exécute.
//
// C'est le chemin qui peut détruire une note : si l'interface enregistre le
// texte allégé au lieu du texte restitué, l'image disparaît de la vraie note,
// sur le serveur, sans message. Le test le rejoue de bout en bout.
func TestPrepareEditJSONAllerRetour(t *testing.T) {
	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	source := "# Photo\n\n![vacances](data:image/jpeg;base64," +
		strings.Repeat("A", 60000) + ")\n\nUne légende.\n"

	raw, err := app.PrepareEditJSON("note.md", source)
	if err != nil {
		t.Fatalf("PrepareEditJSON: %v", err)
	}
	var prepare preparedEdit
	decodeJSON(t, raw, &prepare)

	if !prepare.Editable {
		t.Errorf("la note allégée devrait être modifiable, plus long mot = %d", prepare.LongestWord)
	}
	if strings.Contains(prepare.Text, "base64") {
		t.Fatal("la donnée est restée dans le texte confié au champ de saisie")
	}
	if len(prepare.Images) != 1 {
		t.Fatalf("%d images retirées, 1 attendue", len(prepare.Images))
	}

	imagesJSON, _ := json.Marshal(prepare.Images)
	restitue, err := app.RestoreImages(prepare.Text, string(imagesJSON))
	if err != nil {
		t.Fatalf("RestoreImages: %v", err)
	}
	if restitue != source {
		t.Error("le contenu restitué diffère de l'original : la note serait écrasée")
	}
}

// Un fichier sans image traverse sans être touché, et « images » reste un
// tableau — jamais null, comme le reste du contrat.
func TestPrepareEditJSONSansImage(t *testing.T) {
	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	source := "# Note\n\nRien de spécial.\n"
	raw, err := app.PrepareEditJSON("note.md", source)
	if err != nil {
		t.Fatalf("PrepareEditJSON: %v", err)
	}
	if !strings.Contains(raw, `"images":[]`) {
		t.Errorf("images devrait être un tableau vide, pas null: %s", raw)
	}

	var prepare preparedEdit
	decodeJSON(t, raw, &prepare)
	if prepare.Text != source || !prepare.Editable {
		t.Errorf("prepare = %+v, attendu le contenu inchangé et modifiable", prepare)
	}
}

// Un mot démesuré qui n'est pas une image ferme quand même l'édition.
func TestPrepareEditJSONMotDemesureResteNonModifiable(t *testing.T) {
	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	source := "début\n" + strings.Repeat("z", 50000) + "\nfin\n"
	for _, nom := range []string{"note.md", "note.txt"} {
		raw, err := app.PrepareEditJSON(nom, source)
		if err != nil {
			t.Fatalf("PrepareEditJSON(%s): %v", nom, err)
		}
		var prepare preparedEdit
		decodeJSON(t, raw, &prepare)

		if prepare.Editable {
			t.Errorf("%s : la note devrait être en lecture seule", nom)
		}
		if prepare.Text != source {
			t.Errorf("%s : le contenu a été modifié alors qu'il n'y avait rien à extraire", nom)
		}
	}
}
