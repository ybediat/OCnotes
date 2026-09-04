package mobile

import (
	"encoding/json"
	"testing"

	"github.com/ybediat/OpenNote/internal/store"
)

// prepareDebranchement monte une application connectée dont le serveur porte
// trois notes, et dont l'appareil n'en détient qu'une : la situation ordinaire
// d'un cache qui ne télécharge qu'à l'ouverture.
func prepareDebranchement(t *testing.T) (*App, *fakeServer) {
	t.Helper()

	app, server, _ := prepare(t)
	seedRemote(t, server, "Notes/Une.md", "# Une\n")
	seedRemote(t, server, "Notes/Deux.md", "# Deux\n")
	seedRemote(t, server, "Notes/Trois.md", "# Trois\n")

	// Constituer l'inventaire : sans lui, l'appareil ne sait pas ce qu'il
	// aurait à rapatrier.
	if _, err := app.ListAllJSON(); err != nil {
		t.Fatalf("ListAllJSON: %v", err)
	}
	// Une seule note est ouverte, donc mise en cache.
	if _, err := app.ReadNote("Une.md"); err != nil {
		t.Fatalf("ReadNote: %v", err)
	}
	return app, server
}

func planDe(t *testing.T, app *App) detachPlan {
	t.Helper()
	raw, err := app.DetachPlanJSON()
	if err != nil {
		t.Fatalf("DetachPlanJSON: %v", err)
	}
	var plan detachPlan
	decodeJSON(t, raw, &plan)
	return plan
}

// rapatrieTout boucle comme le fera Android : un lot, puis un autre, et l'on
// s'arrête quand un lot ne ramène plus rien.
func rapatrieTout(t *testing.T, app *App) downloadReport {
	t.Helper()

	var dernier downloadReport
	for tour := 0; tour < 20; tour++ {
		raw, err := app.DownloadBatchJSON(2)
		if err != nil {
			t.Fatalf("DownloadBatchJSON: %v", err)
		}
		decodeJSON(t, raw, &dernier)
		if dernier.Downloaded == 0 {
			return dernier
		}
	}
	t.Fatal("le rapatriement ne s'arrête pas")
	return dernier
}

func TestDetachRapatrieToutPuisPasseEnLocal(t *testing.T) {
	app, _ := prepareDebranchement(t)

	plan := planDe(t, app)
	if plan.Total != 3 {
		t.Errorf("total = %d, attendu 3", plan.Total)
	}
	if plan.Missing != 2 {
		t.Errorf("missing = %d, attendu 2", plan.Missing)
	}
	if plan.Bytes == 0 {
		t.Error("bytes = 0 : le plan ne dit pas ce qu'il y a à télécharger")
	}
	if plan.OverQuota {
		t.Error("le seuil par défaut suffit largement pour trois notes")
	}

	if rapport := rapatrieTout(t, app); rapport.Remaining != 0 {
		t.Errorf("remaining = %d après rapatriement complet", rapport.Remaining)
	}

	raw, err := app.DetachJSON()
	if err != nil {
		t.Fatalf("DetachJSON: %v", err)
	}
	var result detachResult
	decodeJSON(t, raw, &result)
	if result.Kept != 3 {
		t.Errorf("kept = %d, attendu 3", result.Kept)
	}
	if len(result.Abandoned) != 0 {
		t.Errorf("des notes ont été abandonnées : %v", result.Abandoned)
	}

	// Les trois notes se lisent maintenant sans le moindre serveur.
	for _, chemin := range []string{"Une.md", "Deux.md", "Trois.md"} {
		if _, err := app.ReadNote(chemin); err != nil {
			t.Errorf("lecture locale de %s: %v", chemin, err)
		}
	}

	var etat appState
	raw, err = app.StateJSON()
	if err != nil {
		t.Fatalf("StateJSON: %v", err)
	}
	decodeJSON(t, raw, &etat)
	if etat.Mode != "local" {
		t.Errorf("mode = %q, attendu local", etat.Mode)
	}
	if etat.Connected {
		t.Error("l'application se dit encore connectée après le débranchement")
	}
}

// Une note qu'on n'a pas pu rapatrier ne doit pas rester dans l'inventaire :
// elle y serait visible et impossible à ouvrir, avec un message parlant d'un
// serveur que l'appareil vient d'oublier. Elle est retirée, et nommée.
func TestDetachAbandonneCeQuiNAPasPuEtreRapatrie(t *testing.T) {
	app, server := prepareDebranchement(t)
	server.setOffline(true)

	raw, err := app.DetachJSON()
	if err != nil {
		t.Fatalf("DetachJSON: %v", err)
	}
	var result detachResult
	decodeJSON(t, raw, &result)

	if result.Kept != 1 {
		t.Errorf("kept = %d, attendu 1 : seule Une.md était sur l'appareil", result.Kept)
	}
	if len(result.Abandoned) != 2 {
		t.Fatalf("abandoned = %v, attendu deux chemins", result.Abandoned)
	}
	if result.Abandoned[0] != "Deux.md" || result.Abandoned[1] != "Trois.md" {
		t.Errorf("abandoned = %v", result.Abandoned)
	}

	// Aucun fantôme : la liste plate ne les montre plus.
	var inventaire folderListing
	raw, err = app.ListAllJSON()
	if err != nil {
		t.Fatalf("ListAllJSON: %v", err)
	}
	decodeJSON(t, raw, &inventaire)
	if len(inventaire.Entries) != 1 {
		t.Errorf("inventaire = %d entrées, attendu 1 : %+v", len(inventaire.Entries), inventaire.Entries)
	}
	for _, e := range inventaire.Entries {
		if _, err := app.ReadNote(e.Path); err != nil {
			t.Errorf("%s est listée mais illisible : %v", e.Path, err)
		}
	}
}

// Le débranchement vide la file. Les écritures qui n'ont pas atteint le
// serveur seraient perdues en silence : il faut une passe d'abord.
func TestDetachRefuseSiDesEcrituresAttendent(t *testing.T) {
	app, server := prepareDebranchement(t)

	server.setOffline(true)
	if err := app.WriteNote("Une.md", "# Une, modifiée hors connexion\n"); err != nil {
		t.Fatalf("WriteNote: %v", err)
	}
	if app.PendingCount() == 0 {
		t.Fatal("l'écriture aurait dû être mise en file")
	}

	_, err := app.DetachJSON()
	if err == nil {
		t.Fatal("DetachJSON devrait refuser tant que des écritures attendent")
	}
	if code := ErrorCode(err.Error()); code != CodePendingChanges {
		t.Errorf("code = %q, attendu %s", code, CodePendingChanges)
	}

	// La passe faite, le débranchement passe.
	server.setOffline(false)
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	rapatrieTout(t, app)
	if _, err := app.DetachJSON(); err != nil {
		t.Fatalf("DetachJSON après synchronisation: %v", err)
	}
}

// Au-delà du seuil, chaque note reçue en évincerait une autre : le
// rapatriement tournerait sans fin en annonçant des progrès. Il refuse de
// commencer, et le plan dit de combien relever le seuil.
func TestRapatriementRefuseSiLeSeuilEstTropBas(t *testing.T) {
	app, _ := prepareDebranchement(t)

	if err := app.SetCacheQuota(1); err != nil {
		t.Fatalf("SetCacheQuota: %v", err)
	}

	plan := planDe(t, app)
	if !plan.OverQuota {
		t.Fatal("le plan devrait signaler le dépassement de seuil")
	}
	if plan.Required <= plan.Quota {
		t.Errorf("required = %d, quota = %d : le plan ne dit pas ce qu'il faudrait", plan.Required, plan.Quota)
	}

	_, err := app.DownloadBatchJSON(10)
	if err == nil {
		t.Fatal("le rapatriement devrait refuser de commencer")
	}
	if code := ErrorCode(err.Error()); code != CodeQuotaTooLow {
		t.Errorf("code = %q, attendu %s", code, CodeQuotaTooLow)
	}

	// Seuil relevé, il démarre.
	if err := app.SetCacheQuota(store.MinLocalQuota); err != nil {
		t.Fatalf("SetCacheQuota: %v", err)
	}
	if rapport := rapatrieTout(t, app); rapport.Remaining != 0 {
		t.Errorf("remaining = %d", rapport.Remaining)
	}
}

// L'aller-retour complet, et le piège qu'il tend : les notes rapatriées
// portent l'ETag de l'ancien serveur. Réutilisé tel quel, il partirait en
// If-Match vers un serveur qui ne l'a jamais émis. Adopt les vide, ce qui
// renvoie chaque note par le chemin des créations — celui qui vérifie
// l'existence avant d'écrire.
func TestDetachPuisRebranchementSurUnAutreServeur(t *testing.T) {
	app, _ := prepareDebranchement(t)

	rapatrieTout(t, app)
	if _, err := app.DetachJSON(); err != nil {
		t.Fatalf("DetachJSON: %v", err)
	}

	// Une note écrite pendant la période sans serveur part avec les autres.
	if _, err := app.CreateNoteJSON("", "Écrite hors ligne", "# Écrite hors ligne\n"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}

	autre := newFakeServer(t)
	if err := app.Connect(autre.URL, fakeUser, fakeToken); err != nil {
		t.Fatalf("Connect au second serveur: %v", err)
	}

	requete, err := json.Marshal(attachRequest{DriveID: fakeSpaceID, Root: "Notes", Adopt: true})
	if err != nil {
		t.Fatalf("sérialisation: %v", err)
	}
	if _, err := app.AttachJSON(string(requete)); err != nil {
		t.Fatalf("AttachJSON: %v", err)
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
	if len(sync.Conflicts) != 0 {
		t.Errorf("des conflits sur un serveur vierge : %+v", sync.Conflicts)
	}

	for _, chemin := range []string{
		"Notes/Une.md", "Notes/Deux.md", "Notes/Trois.md", "Notes/Écrite hors ligne.md",
	} {
		if autre.files[chemin] == nil {
			t.Errorf("%s n'est pas arrivée sur le second serveur : %v", chemin, keys(autre.files))
		}
	}
}
