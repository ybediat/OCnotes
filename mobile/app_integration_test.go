package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	"opennote/internal/opencloud"
)

// Test vertical de la façade contre un vrai serveur : configuration, client
// WebDAV, modèle de notes, cache et sérialisation JSON, tous traversés par les
// mêmes appels que ceux que fera Kotlin.
//
// Ignoré tant que OPENNOTE_IT_SERVER, OPENNOTE_IT_USER et OPENNOTE_IT_TOKEN ne
// sont pas définis.
func integrationEnv(t *testing.T) (server, user, token string) {
	t.Helper()

	server = os.Getenv("OPENNOTE_IT_SERVER")
	user = os.Getenv("OPENNOTE_IT_USER")
	token = os.Getenv("OPENNOTE_IT_TOKEN")

	if server == "" || user == "" || token == "" {
		t.Skip("intégration ignorée : définir OPENNOTE_IT_SERVER, OPENNOTE_IT_USER et OPENNOTE_IT_TOKEN")
	}
	if testing.Short() {
		t.Skip("intégration ignorée en mode court")
	}
	return server, user, token
}

// cleanupWorkspace supprime le dossier de test, que la façade elle-même refuse
// de toucher puisque c'est sa racine.
func cleanupWorkspace(t *testing.T, server, user, token, root string) {
	t.Helper()

	client, err := opencloud.New(server, opencloud.AppTokenAuth{Username: user, Token: token})
	if err != nil {
		t.Errorf("nettoyage impossible: %v", err)
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	drives, err := client.ListDrives(ctx)
	if err != nil {
		t.Errorf("nettoyage impossible: %v", err)
		return
	}
	drive, ok := opencloud.PersonalDrive(drives)
	if !ok {
		return
	}
	space, err := client.Space(drive)
	if err != nil {
		t.Errorf("nettoyage impossible: %v", err)
		return
	}
	if err := space.Remove(ctx, root); err != nil {
		t.Errorf("dossier %s à supprimer à la main: %v", root, err)
	}
}

func TestIntegrationFacadeParcoursComplet(t *testing.T) {
	server, user, token := integrationEnv(t)
	root := fmt.Sprintf("opennote-facade-%d", time.Now().UnixNano())

	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	// 1. Connexion --------------------------------------------------------
	if err := app.Connect(server, user, token); err != nil {
		t.Fatalf("Connect: %v", err)
	}

	var state appState
	decode(t, mustCall(t, app.StateJSON), &state)
	if !state.Connected {
		t.Error("l'état devrait être connecté")
	}
	if state.HasWorkspace {
		t.Error("aucun espace ne devrait être choisi à ce stade")
	}

	// 2. Choix de l'espace ------------------------------------------------
	var drives []driveInfo
	decode(t, mustCall(t, app.ListDrivesJSON), &drives)

	var personal string
	for _, d := range drives {
		if d.Type == opencloud.DrivePersonal && d.Usable {
			personal = d.ID
		}
		// L'espace virtuel doit être signalé comme inutilisable, pas masqué.
		if d.Type == opencloud.DriveVirtual && d.Usable {
			t.Errorf("l'espace virtuel %q est annoncé comme utilisable", d.Name)
		}
	}
	if personal == "" {
		t.Fatal("aucun espace personnel utilisable")
	}

	if err := app.SelectWorkspace(personal, root); err != nil {
		t.Fatalf("SelectWorkspace: %v", err)
	}
	t.Cleanup(func() { cleanupWorkspace(t, server, user, token, root) })

	// 3. Création d'une note ----------------------------------------------
	var created noteRef
	raw, err := app.CreateNoteJSON("", "Réunion du 15", "# Réunion du 15\n")
	if err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	decode(t, raw, &created)
	if created.Name != "Réunion du 15.md" {
		t.Errorf("nom = %q", created.Name)
	}

	// 4. Écriture locale, puis synchronisation ----------------------------
	const contenu = "# Réunion du 15\n\n- [ ] préparer l'ordre du jour\n- [x] réserver la salle 😀\n"
	if err := app.WriteNote(created.Path, contenu); err != nil {
		t.Fatalf("WriteNote: %v", err)
	}
	if app.PendingCount() == 0 {
		t.Error("l'écriture aurait dû être mise en file d'attente")
	}

	var sync syncResult
	decode(t, mustCall(t, app.SyncJSON), &sync)
	if sync.Error != "" {
		t.Fatalf("SyncJSON a signalé une erreur: %s", sync.Error)
	}
	if sync.Pushed < 1 {
		t.Errorf("Pushed = %d, au moins 1 attendu", sync.Pushed)
	}
	if app.PendingCount() != 0 {
		t.Errorf("%d opérations toujours en attente", app.PendingCount())
	}

	// 5. Relecture depuis un cache neuf : le contenu vient bien du serveur.
	fresh, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := fresh.Connect(server, user, token); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	if err := fresh.SelectWorkspace(personal, root); err != nil {
		t.Fatalf("SelectWorkspace: %v", err)
	}

	relu, err := fresh.ReadNote(created.Path)
	if err != nil {
		t.Fatalf("ReadNote: %v", err)
	}
	if relu != contenu {
		t.Errorf("contenu relu depuis le serveur :\n%q\nattendu :\n%q", relu, contenu)
	}

	// 6. Listing -----------------------------------------------------------
	var listing folderListing
	raw, err = fresh.ListFolderJSON("")
	if err != nil {
		t.Fatalf("ListFolderJSON: %v", err)
	}
	decode(t, raw, &listing)
	if listing.FromCache {
		t.Error("le listing devrait venir du serveur")
	}

	found := false
	for _, e := range listing.Entries {
		if e.Path == created.Path {
			found = true
			if e.IsDir {
				t.Error("la note est annoncée comme un dossier")
			}
			if e.Display != "Réunion du 15" {
				t.Errorf("Display = %q", e.Display)
			}
		}
	}
	if !found {
		t.Errorf("la note est absente du listing: %+v", listing.Entries)
	}

	// 7. Titre et mise en forme -------------------------------------------
	if got := fresh.TitleOf(created.Name, contenu); got != "Réunion du 15" {
		t.Errorf("TitleOf = %q", got)
	}

	req, _ := json.Marshal(formatRequest{Text: "mot", Start: 0, End: 3, Action: "bold"})
	var formatted formatResult
	raw, err = fresh.ApplyFormatJSON(string(req))
	if err != nil {
		t.Fatalf("ApplyFormatJSON: %v", err)
	}
	decode(t, raw, &formatted)
	if formatted.Text != "**mot**" {
		t.Errorf("texte mis en forme = %q", formatted.Text)
	}

	// 8. Sous-dossier et suppression --------------------------------------
	if _, err := fresh.CreateFolderJSON("", "Archives"); err != nil {
		t.Fatalf("CreateFolderJSON: %v", err)
	}
	newPath, err := fresh.Rename(created.Path, "Réunion reportée")
	if err != nil {
		t.Fatalf("Rename: %v", err)
	}
	if newPath != "Réunion reportée.md" {
		t.Errorf("chemin après renommage = %q", newPath)
	}
	if err := fresh.Delete(newPath); err != nil {
		t.Fatalf("Delete: %v", err)
	}
}

// Une erreur d'authentification doit être reconnaissable par Kotlin sans qu'il
// ait à analyser un message.
func TestIntegrationFacadeTokenInvalide(t *testing.T) {
	server, user, _ := integrationEnv(t)

	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	err = app.Connect(server, user, "token-invalide-pour-le-test")
	if err == nil {
		t.Fatal("Connect aurait dû échouer avec un token invalide")
	}
	if !IsAuthError(err.Error()) {
		t.Errorf("IsAuthError(%q) = false, attendu true", err.Error())
	}

	// Rien ne doit avoir été enregistré après un échec d'authentification.
	var state appState
	decode(t, mustCall(t, app.StateJSON), &state)
	if state.Connected {
		t.Error("l'état ne devrait pas être connecté après un échec")
	}
}

// Le comportement hors connexion n'est PAS testable ici.
//
// Un test d'intégration a besoin d'un serveur joignable pour préparer son
// état, et il ne peut pas le rendre injoignable ensuite. Une première version
// de ce fichier contenait un test nommé « ListingHorsConnexion » qui, faute de
// pouvoir couper le réseau, se reconnectait au vrai serveur et listait en
// ligne : il passait sans jamais exercer le repli sur cache.
//
// La couverture du hors-connexion vit donc dans app_test.go, contre un serveur
// factice que l'on peut fermer à volonté.

func mustCall(t *testing.T, fn func() (string, error)) string {
	t.Helper()
	out, err := fn()
	if err != nil {
		t.Fatalf("appel: %v", err)
	}
	return out
}

func decode(t *testing.T, raw string, into any) {
	t.Helper()
	if err := json.Unmarshal([]byte(raw), into); err != nil {
		t.Fatalf("JSON illisible (%s): %v", strings.TrimSpace(raw), err)
	}
}
