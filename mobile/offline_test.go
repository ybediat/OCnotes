package mobile

import (
	"strings"
	"testing"
)

// Ces tests couvrent le défaut constaté sur un vrai téléphone en mode avion :
// on pouvait modifier une note existante mais pas en créer une. L'infrastructure
// de file d'attente existait déjà ; la façade ne s'en servait que pour les
// écritures.

func TestCreationDeNoteHorsConnexion(t *testing.T) {
	app, server, _ := prepare(t)
	server.setOffline(true)

	raw, err := app.CreateNoteJSON("", "Idée du soir", "# Idée du soir\n")
	if err != nil {
		t.Fatalf("créer une note hors connexion: %v", err)
	}

	var created noteRef
	decodeJSON(t, raw, &created)
	if created.Name != "Idée du soir.md" {
		t.Errorf("Name = %q", created.Name)
	}

	// Elle doit être lisible immédiatement, sans réseau.
	content, err := app.ReadNote(created.Path)
	if err != nil {
		t.Fatalf("ReadNote hors connexion: %v", err)
	}
	if content != "# Idée du soir\n" {
		t.Errorf("contenu = %q", content)
	}
	if app.PendingCount() == 0 {
		t.Error("la création aurait dû être mise en file")
	}

	// Et partir au retour du réseau.
	server.setOffline(false)
	var sync syncResult
	raw, err = app.SyncJSON()
	if err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	decodeJSON(t, raw, &sync)
	if sync.Error != "" {
		t.Fatalf("synchronisation en erreur: %s", sync.Error)
	}
	if server.files["Notes/Idée du soir.md"] == nil {
		t.Errorf("la note n'est pas arrivée sur le serveur: %v", keys(server.files))
	}
}

// Deux notes créées hors connexion depuis le même titre ne doivent pas
// s'écraser : le nom est choisi d'après le cache, seule source disponible.
func TestCreationsMultiplesHorsConnexion(t *testing.T) {
	app, server, _ := prepare(t)
	server.setOffline(true)

	attendus := []string{"Note.md", "Note (2).md", "Note (3).md"}
	for i, attendu := range attendus {
		raw, err := app.CreateNoteJSON("", "Note", "contenu")
		if err != nil {
			t.Fatalf("création %d: %v", i+1, err)
		}
		var ref noteRef
		decodeJSON(t, raw, &ref)
		if ref.Name != attendu {
			t.Errorf("création %d : Name = %q, attendu %q", i+1, ref.Name, attendu)
		}
	}
}

// Le cas dangereux, constaté sur un vrai téléphone : une note est créée hors
// connexion alors que le serveur porte déjà ce nom, et la version du téléphone
// écrase celle du navigateur.
//
// Le serveur factice ignore « If-None-Match: * », comme le vrai : la
// protection doit donc venir d'une vérification explicite d'existence, pas de
// la bonne volonté du serveur.
func TestCreationHorsConnexionNEcrasePasUneNoteDistante(t *testing.T) {
	app, server, _ := prepare(t)
	if server.honorsIfNoneMatch {
		t.Fatal("ce test n'a de sens que si le serveur ignore If-None-Match")
	}

	// Une note apparaît côté serveur, sans que le cache la connaisse.
	server.mu.Lock()
	server.files["Notes/partagee.md"] = []byte("version écrite ailleurs")
	server.etags["Notes/partagee.md"] = `"distant"`
	server.mu.Unlock()

	server.setOffline(true)
	raw, err := app.CreateNoteJSON("", "partagee", "ma version locale")
	if err != nil {
		t.Fatalf("création hors connexion: %v", err)
	}
	var ref noteRef
	decodeJSON(t, raw, &ref)

	server.setOffline(false)
	var sync syncResult
	out, err := app.SyncJSON()
	if err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	decodeJSON(t, out, &sync)

	// La note distante doit être intacte…
	server.mu.Lock()
	distant := string(server.files["Notes/partagee.md"])
	server.mu.Unlock()
	if distant != "version écrite ailleurs" {
		t.Errorf("la note distante a été écrasée : %q", distant)
	}

	// …et la version locale conservée à côté.
	if len(sync.Conflicts) != 1 {
		t.Fatalf("%d conflits signalés, 1 attendu", len(sync.Conflicts))
	}
	copie := sync.Conflicts[0].CopyPath
	if !strings.Contains(copie, "conflit") {
		t.Errorf("CopyPath = %q", copie)
	}

	// CopyPath est relatif au dossier de notes, alors que le serveur factice
	// indexe par chemin d'espace : il faut préfixer par la racine.
	server.mu.Lock()
	sauvegarde := string(server.files["Notes/"+copie])
	server.mu.Unlock()
	if sauvegarde != "ma version locale" {
		t.Errorf("la version locale n'a pas été conservée sous %q : %q", "Notes/"+copie, sauvegarde)
	}
}

func TestSuppressionHorsConnexion(t *testing.T) {
	app, server, _ := prepare(t)

	if _, err := app.CreateNoteJSON("", "a-supprimer", "contenu"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}

	server.setOffline(true)
	if err := app.Delete("a-supprimer.md"); err != nil {
		t.Fatalf("suppression hors connexion: %v", err)
	}
	if _, _, ok := app.cache.Get("a-supprimer.md"); ok {
		t.Error("la note est encore en cache")
	}

	server.setOffline(false)
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	server.mu.Lock()
	_, encore := server.files["Notes/a-supprimer.md"]
	server.mu.Unlock()
	if encore {
		t.Error("la note existe encore sur le serveur après synchronisation")
	}
}

func TestRenommageHorsConnexion(t *testing.T) {
	app, server, _ := prepare(t)

	if _, err := app.CreateNoteJSON("", "ancienne", "contenu"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}

	server.setOffline(true)
	nouveau, err := app.Rename("ancienne.md", "nouvelle")
	if err != nil {
		t.Fatalf("renommage hors connexion: %v", err)
	}
	if nouveau != "nouvelle.md" {
		t.Errorf("chemin = %q", nouveau)
	}
	if content, _, ok := app.cache.Get("nouvelle.md"); !ok || string(content) != "contenu" {
		t.Errorf("cache sous le nouveau nom = %q", content)
	}

	server.setOffline(false)
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	server.mu.Lock()
	_, nouvelle := server.files["Notes/nouvelle.md"]
	_, ancienne := server.files["Notes/ancienne.md"]
	server.mu.Unlock()
	if !nouvelle || ancienne {
		t.Errorf("le serveur n'a pas suivi le renommage: %v", keys(server.files))
	}
}

// Un dossier créé hors connexion doit rester visible même vide : le cache ne
// stocke que des notes, il ne pourrait pas le déduire d'un chemin.
func TestDossierCreeHorsConnexionResteVisible(t *testing.T) {
	app, server, _ := prepare(t)
	server.setOffline(true)

	if _, err := app.CreateFolderJSON("", "Archives"); err != nil {
		t.Fatalf("création de dossier hors connexion: %v", err)
	}

	raw, err := app.ListFolderJSON("")
	if err != nil {
		t.Fatalf("ListFolderJSON: %v", err)
	}
	var listing folderListing
	decodeJSON(t, raw, &listing)

	if !listing.FromCache {
		t.Error("le listing devrait venir du cache")
	}
	trouve := false
	for _, e := range listing.Entries {
		if e.Path == "Archives" && e.IsDir {
			trouve = true
		}
	}
	if !trouve {
		t.Fatalf("le dossier créé hors connexion est absent: %+v", listing.Entries)
	}

	server.setOffline(false)
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	server.mu.Lock()
	cree := server.folders["Notes/Archives"]
	server.mu.Unlock()
	if !cree {
		t.Errorf("le dossier n'a pas été créé sur le serveur: %v", keysBool(server.folders))
	}
}

// Une erreur de transport doit être reconnaissable, pour que l'interface ne la
// présente pas comme un échec de l'opération.
func TestErreurHorsConnexionEstCategorisee(t *testing.T) {
	app, server, _ := prepare(t)
	server.setOffline(true)

	_, err := app.ListDrivesJSON()
	if err == nil {
		t.Fatal("une erreur était attendue")
	}
	if !IsOfflineError(err.Error()) {
		t.Errorf("IsOfflineError(%q) = false", err.Error())
	}
	if got := ErrorCode(err.Error()); got != "OFFLINE" {
		t.Errorf("ErrorCode = %q, attendu OFFLINE", got)
	}
}

func keys(m map[string][]byte) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}

func keysBool(m map[string]bool) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}
