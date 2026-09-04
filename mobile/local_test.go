package mobile

import (
	"strings"
	"testing"

	"github.com/ybediat/OpenNote/internal/store"
)

// prepareLocal monte une application sans serveur, comme au premier lancement
// quand l'utilisateur répond qu'il n'en a pas.
func prepareLocal(t *testing.T) (*App, string) {
	t.Helper()

	dataDir := t.TempDir()
	app, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := app.StartLocal(); err != nil {
		t.Fatalf("StartLocal: %v", err)
	}
	return app, dataDir
}

// Le parcours ordinaire, celui qui doit marcher sans qu'aucun serveur
// n'existe : créer, lire, écrire, renommer, ranger, supprimer.
func TestModeLocalCycleComplet(t *testing.T) {
	app, _ := prepareLocal(t)

	raw, err := app.CreateFolderJSON("", "Carnets")
	if err != nil {
		t.Fatalf("CreateFolderJSON: %v", err)
	}
	var dossier noteRef
	decodeJSON(t, raw, &dossier)

	raw, err = app.CreateNoteJSON("", "Idée du soir", "# Idée du soir\n")
	if err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	var note noteRef
	decodeJSON(t, raw, &note)
	if note.Name != "Idée du soir.md" {
		t.Errorf("Name = %q", note.Name)
	}

	content, err := app.ReadNote(note.Path)
	if err != nil {
		t.Fatalf("ReadNote: %v", err)
	}
	if content != "# Idée du soir\n" {
		t.Errorf("contenu = %q", content)
	}

	if err := app.WriteNote(note.Path, "# Idée du soir\n\nRelue.\n"); err != nil {
		t.Fatalf("WriteNote: %v", err)
	}

	renommee, err := app.Rename(note.Path, "Idée du matin")
	if err != nil {
		t.Fatalf("Rename: %v", err)
	}
	deplacee, err := app.Move(renommee, dossier.Path)
	if err != nil {
		t.Fatalf("Move: %v", err)
	}
	if deplacee != "Carnets/Idée du matin.md" {
		t.Errorf("chemin après déplacement = %q", deplacee)
	}

	// Le contenu a suivi les deux mouvements.
	content, err = app.ReadNote(deplacee)
	if err != nil {
		t.Fatalf("ReadNote après déplacement: %v", err)
	}
	if !strings.Contains(content, "Relue.") {
		t.Errorf("contenu perdu en chemin : %q", content)
	}

	if err := app.Delete(deplacee); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	if _, err := app.ReadNote(deplacee); err == nil {
		t.Error("la note supprimée se lit encore")
	} else if code := ErrorCode(err.Error()); code != CodeContentMissing {
		t.Errorf("code = %q, attendu %s", code, CodeContentMissing)
	}
}

// Le cache n'est pas un repli mais la source : annoncer un listing
// « reconstitué depuis le cache » ferait afficher en permanence un bandeau qui
// prévient d'une panne de réseau inexistante.
func TestModeLocalNAnnoncePasUnRepliDeCache(t *testing.T) {
	app, _ := prepareLocal(t)

	if _, err := app.CreateNoteJSON("", "Note", "texte"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}

	var dossier folderListing
	raw, err := app.ListFolderJSON("")
	if err != nil {
		t.Fatalf("ListFolderJSON: %v", err)
	}
	decodeJSON(t, raw, &dossier)
	if dossier.FromCache {
		t.Error("ListFolderJSON annonce un repli hors connexion en mode local")
	}

	var inventaire folderListing
	raw, err = app.ListAllJSON()
	if err != nil {
		t.Fatalf("ListAllJSON: %v", err)
	}
	decodeJSON(t, raw, &inventaire)
	if inventaire.FromCache {
		t.Error("ListAllJSON annonce un repli hors connexion en mode local")
	}
	if len(inventaire.Entries) != 1 {
		t.Errorf("inventaire = %d entrées, attendu 1", len(inventaire.Entries))
	}
}

// Une installation neuve a un cache vide. Le repli hors connexion refuse de
// servir un listing dans ce cas — il n'apprendrait rien — mais en mode local
// un dossier vide est la bonne réponse, pas un échec.
func TestModeLocalDossierRacineVideSAffiche(t *testing.T) {
	app, _ := prepareLocal(t)

	raw, err := app.ListFolderJSON("")
	if err != nil {
		t.Fatalf("le dossier racine vide devrait s'afficher : %v", err)
	}
	var listing folderListing
	decodeJSON(t, raw, &listing)
	if len(listing.Entries) != 0 {
		t.Errorf("entrées = %d, attendu 0", len(listing.Entries))
	}
}

// La file refuse le renommage et la suppression différés d'un dossier, faute
// de savoir les rejouer fidèlement. Sans serveur il n'y a rien à rejouer :
// ces gestes doivent redevenir ordinaires.
func TestModeLocalRenommeEtSupprimeUnDossier(t *testing.T) {
	app, _ := prepareLocal(t)

	if _, err := app.CreateFolderJSON("", "Brouillons"); err != nil {
		t.Fatalf("CreateFolderJSON: %v", err)
	}
	if _, err := app.CreateNoteJSON("Brouillons", "Dedans", "texte"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}

	renomme, err := app.Rename("Brouillons", "Carnets")
	if err != nil {
		t.Fatalf("renommer un dossier en mode local: %v", err)
	}
	if renomme != "Carnets" {
		t.Errorf("chemin = %q", renomme)
	}
	// La note qu'il contenait a suivi.
	if _, err := app.ReadNote("Carnets/Dedans.md"); err != nil {
		t.Errorf("la note du dossier renommé est introuvable : %v", err)
	}

	if err := app.Delete("Carnets"); err != nil {
		t.Fatalf("supprimer un dossier en mode local: %v", err)
	}
	if _, err := app.ReadNote("Carnets/Dedans.md"); err == nil {
		t.Error("la note du dossier supprimé se lit encore")
	}
}

// Rien n'attend d'être envoyé, et la synchronisation se refuse plutôt que de
// prétendre avoir travaillé. Le travailleur de fond peut arriver après la
// bascule : il doit reconnaître la situation à son code.
func TestModeLocalNeSynchronisePas(t *testing.T) {
	app, _ := prepareLocal(t)

	if _, err := app.CreateNoteJSON("", "Note", "texte"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if n := app.PendingCount(); n != 0 {
		t.Errorf("PendingCount = %d, attendu 0 : il n'y a personne à qui pousser", n)
	}

	_, err := app.SyncJSON()
	if err == nil {
		t.Fatal("SyncJSON devrait se refuser en mode local")
	}
	if code := ErrorCode(err.Error()); code != CodeLocalMode {
		t.Errorf("code = %q, attendu %s", code, CodeLocalMode)
	}

	if err := app.RefreshIndex(); err != nil {
		t.Errorf("RefreshIndex devrait être sans effet et sans erreur : %v", err)
	}
}

// Le mode local n'a pas de session à remonter : après un redémarrage,
// l'application est utilisable sans le moindre appel préalable — ni Restore,
// ni Connect.
func TestModeLocalSurvitAuRedemarrageSansRestore(t *testing.T) {
	app, dataDir := prepareLocal(t)

	if _, err := app.CreateNoteJSON("", "Persistante", "# Persistante\n"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}

	rouverte, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp au redémarrage: %v", err)
	}

	var etat appState
	raw, err := rouverte.StateJSON()
	if err != nil {
		t.Fatalf("StateJSON: %v", err)
	}
	decodeJSON(t, raw, &etat)
	if etat.Mode != "local" {
		t.Errorf("mode = %q, attendu local", etat.Mode)
	}
	if etat.Connected {
		t.Error("une application locale ne doit pas se dire connectée")
	}

	content, err := rouverte.ReadNote("Persistante.md")
	if err != nil {
		t.Fatalf("lecture après redémarrage, sans Restore: %v", err)
	}
	if content != "# Persistante\n" {
		t.Errorf("contenu = %q", content)
	}
}

// Le quota n'évince plus rien en mode local : il n'est qu'un seuil d'alerte,
// et le laisser à 250 Mo ferait crier l'interface pour rien.
func TestStartLocalReleveLeSeuil(t *testing.T) {
	app, _ := prepareLocal(t)

	var cache cacheState
	raw, err := app.CacheStateJSON()
	if err != nil {
		t.Fatalf("CacheStateJSON: %v", err)
	}
	decodeJSON(t, raw, &cache)
	if cache.Quota != store.MinLocalQuota {
		t.Errorf("quota = %d, attendu le plancher local %d", cache.Quota, store.MinLocalQuota)
	}

	// Et l'utilisateur garde la main pour l'abaisser.
	if err := app.SetCacheQuota(50 * 1024 * 1024); err != nil {
		t.Fatalf("abaisser le seuil doit rester possible: %v", err)
	}
}

// Quitter le mode serveur passe par le débranchement, qui rapatrie d'abord.
// Basculer directement laisserait sur le serveur des notes dont l'appareil ne
// connaît que le nom.
func TestStartLocalRefuseSiUnServeurEstEnregistre(t *testing.T) {
	app, _, _ := prepare(t)

	err := app.StartLocal()
	if err == nil {
		t.Fatal("StartLocal devrait refuser quand un serveur est enregistré")
	}
	if code := ErrorCode(err.Error()); code != CodeLocalMode {
		t.Errorf("code = %q, attendu %s", code, CodeLocalMode)
	}
}
