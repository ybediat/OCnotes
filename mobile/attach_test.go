package mobile

import (
	"encoding/json"
	"testing"
)

// seedRemote pose un fichier sur le serveur factice, comme s'il y était déjà
// avant que l'appareil ne s'y branche.
func seedRemote(t *testing.T, f *fakeServer, p, content string) {
	t.Helper()
	f.mu.Lock()
	defer f.mu.Unlock()
	f.files[p] = []byte(content)
	f.etags[p] = f.nextETag()
}

// attache exécute le branchement et rend le compte rendu décodé.
func attache(t *testing.T, app *App, adopt bool) attachResult {
	t.Helper()

	requete, err := json.Marshal(attachRequest{DriveID: fakeSpaceID, Root: "Notes", Adopt: adopt})
	if err != nil {
		t.Fatalf("sérialisation de la requête: %v", err)
	}
	raw, err := app.AttachJSON(string(requete))
	if err != nil {
		t.Fatalf("AttachJSON: %v", err)
	}
	var result attachResult
	decodeJSON(t, raw, &result)
	return result
}

// prepareBranchement monte une application locale portant déjà des notes, puis
// ouvre la session vers un serveur factice sans encore rien y engager.
func prepareBranchement(t *testing.T) (*App, *fakeServer) {
	t.Helper()

	app, _ := prepareLocal(t)
	if _, err := app.CreateFolderJSON("", "Carnets"); err != nil {
		t.Fatalf("CreateFolderJSON: %v", err)
	}
	if _, err := app.CreateNoteJSON("", "Idée du soir", "# Idée du soir\n"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.CreateNoteJSON("Carnets", "Rangée", "# Rangée\n"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}

	server := newFakeServer(t)
	if err := app.Connect(server.URL, fakeUser, fakeToken); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	return app, server
}

func TestAttachMonteLesNotesLocales(t *testing.T) {
	app, server := prepareBranchement(t)

	result := attache(t, app, true)
	if result.Adopted != 2 {
		t.Errorf("adopted = %d, attendu 2", result.Adopted)
	}

	var sync syncResult
	raw, err := app.SyncJSON()
	if err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	decodeJSON(t, raw, &sync)
	if sync.Error != "" {
		t.Fatalf("synchronisation en erreur: %s", sync.Error)
	}

	for _, chemin := range []string{"Notes/Idée du soir.md", "Notes/Carnets/Rangée.md"} {
		if server.files[chemin] == nil {
			t.Errorf("%s n'est pas montée sur le serveur : %v", chemin, keys(server.files))
		}
	}
	if !server.folders["Notes/Carnets"] {
		t.Errorf("le dossier local n'a pas été créé : %v", keysBool(server.folders))
	}
	if n := app.PendingCount(); n != 0 {
		t.Errorf("PendingCount = %d après la montée, attendu 0", n)
	}
}

// Le cas qui décide de la sûreté du branchement : le serveur porte déjà une
// note du même nom, écrite depuis un autre appareil. Elle ne doit pas être
// écrasée — c'est le chemin des créations hors connexion qui protège, en
// vérifiant l'existence avant d'écrire.
func TestAttachPreserveUneNoteDistanteHomonyme(t *testing.T) {
	app, server := prepareBranchement(t)
	seedRemote(t, server, "Notes/Idée du soir.md", "# Version du serveur\n")

	attache(t, app, true)

	var sync syncResult
	raw, err := app.SyncJSON()
	if err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	decodeJSON(t, raw, &sync)

	if got := string(server.files["Notes/Idée du soir.md"]); got != "# Version du serveur\n" {
		t.Errorf("la note distante a été écrasée : %q", got)
	}
	if len(sync.Conflicts) == 0 {
		t.Fatal("la collision aurait dû être signalée comme conflit")
	}

	// Et la version locale n'est pas perdue pour autant : elle vit dans la
	// copie de conflit.
	copie := sync.Conflicts[0].CopyPath
	content, err := app.ReadNote(copie)
	if err != nil {
		t.Fatalf("lecture de la copie de conflit %s: %v", copie, err)
	}
	if content != "# Idée du soir\n" {
		t.Errorf("la copie de conflit ne porte pas la version locale : %q", content)
	}
}

// « Mes vraies notes sont déjà sur le serveur, le local n'était que des
// brouillons. » Le geste est destructeur et assumé : rien ne monte, tout est
// supprimé de l'appareil.
func TestAttachSansAdoptionSupprimeLesNotesLocales(t *testing.T) {
	app, server := prepareBranchement(t)
	seedRemote(t, server, "Notes/Déjà là.md", "# Déjà là\n")

	result := attache(t, app, false)
	if result.Deleted != 2 {
		t.Errorf("deleted = %d, attendu 2", result.Deleted)
	}
	if result.Adopted != 0 {
		t.Errorf("adopted = %d, attendu 0", result.Adopted)
	}

	if n := app.PendingCount(); n != 0 {
		t.Errorf("PendingCount = %d, rien ne devait être mis en file", n)
	}
	if _, err := app.ReadNote("Idée du soir.md"); err == nil {
		t.Error("une note locale a survécu au branchement sans adoption")
	}

	// Et la liste plate ne les montre plus. Ce que Clear oublie vraiment se
	// vérifie dans internal/store — TestClearOublieAussiLInventaireEtLesConflits,
	// où l'inventaire est peuplé, ce qu'un mode local ne fait jamais.
	var inventaire folderListing
	raw, err := app.ListAllJSON()
	if err != nil {
		t.Fatalf("ListAllJSON: %v", err)
	}
	decodeJSON(t, raw, &inventaire)
	for _, e := range inventaire.Entries {
		if e.Path == "Idée du soir.md" || e.Path == "Carnets/Rangée.md" {
			t.Errorf("%s hante encore l'inventaire", e.Path)
		}
	}

	// Le serveur, lui, n'a rien reçu et garde ce qu'il avait.
	if string(server.files["Notes/Déjà là.md"]) != "# Déjà là\n" {
		t.Error("le serveur a été touché par un branchement sans adoption")
	}
	if server.files["Notes/Idée du soir.md"] != nil {
		t.Error("une note locale est montée alors qu'on ne le demandait pas")
	}
}

// Le branchement part du mode local. Depuis le mode serveur, changer d'espace
// reste le geste de SelectWorkspace, qui n'a pas de notes locales à arbitrer.
func TestAttachRefuseHorsModeLocal(t *testing.T) {
	app, _, _ := prepare(t)

	requete, err := json.Marshal(attachRequest{DriveID: fakeSpaceID, Root: "Notes", Adopt: true})
	if err != nil {
		t.Fatalf("sérialisation: %v", err)
	}
	if _, err := app.AttachJSON(string(requete)); err == nil {
		t.Fatal("AttachJSON devrait refuser hors du mode local")
	} else if code := ErrorCode(err.Error()); code != CodeLocalMode {
		t.Errorf("code = %q, attendu %s", code, CodeLocalMode)
	}
}

// Après le branchement, l'application est en mode serveur pour de bon : elle
// synchronise, et un redémarrage la retrouve ainsi.
func TestAttachBasculeLeModeDurablement(t *testing.T) {
	app, _ := prepareBranchement(t)
	attache(t, app, true)

	var etat appState
	raw, err := app.StateJSON()
	if err != nil {
		t.Fatalf("StateJSON: %v", err)
	}
	decodeJSON(t, raw, &etat)
	if etat.Mode != "server" {
		t.Errorf("mode = %q, attendu server", etat.Mode)
	}
	if !etat.Connected {
		t.Error("l'application devrait se dire connectée après le branchement")
	}
	if !etat.HasWorkspace {
		t.Error("l'espace de travail devrait être monté")
	}
}
