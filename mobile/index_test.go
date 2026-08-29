package mobile

import (
	"sort"
	"testing"
)

// arbreDeTest installe la même arborescence par les deux chemins possibles.
func arbreDeTest(t *testing.T, app *App) {
	t.Helper()

	if _, err := app.CreateFolderJSON("", "Projets"); err != nil {
		t.Fatalf("CreateFolderJSON: %v", err)
	}
	if _, err := app.CreateFolderJSON("Projets", "Archives"); err != nil {
		t.Fatalf("CreateFolderJSON: %v", err)
	}
	for _, cas := range []struct{ dir, nom string }{
		{"", "racine.md"},
		{"Projets", "projet.md"},
		{"Projets/Archives", "vieux.md"},
	} {
		if _, err := app.CreateNoteJSON(cas.dir, cas.nom, "# titre\n"); err != nil {
			t.Fatalf("CreateNoteJSON(%s/%s): %v", cas.dir, cas.nom, err)
		}
	}
}

func cheminsPlats(t *testing.T, raw string) []string {
	t.Helper()
	var listing struct {
		Entries []struct {
			Path  string `json:"path"`
			IsDir bool   `json:"isDir"`
		} `json:"entries"`
		FromCache bool `json:"fromCache"`
	}
	decodeJSON(t, raw, &listing)

	out := []string{}
	for _, e := range listing.Entries {
		if e.IsDir {
			t.Errorf("la liste plate contient un dossier : %s", e.Path)
		}
		out = append(out, e.Path)
	}
	sort.Strings(out)
	return out
}

func memesChemins(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// Sans service de recherche — le défaut du serveur factice — l'inventaire
// passe par le parcours PROPFIND.
func TestListAllJSONSansServiceDeRecherche(t *testing.T) {
	app, _, _ := prepare(t)
	arbreDeTest(t, app)

	raw, err := app.ListAllJSON()
	if err != nil {
		t.Fatalf("ListAllJSON: %v", err)
	}

	attendu := []string{"Projets/Archives/vieux.md", "Projets/projet.md", "racine.md"}
	if got := cheminsPlats(t, raw); !memesChemins(got, attendu) {
		t.Errorf("inventaire = %v, attendu %v", got, attendu)
	}
}

// Avec le service de recherche, le résultat doit être **identique**. C'est
// cette égalité qui autorise à basculer d'un chemin à l'autre selon ce que le
// serveur offre, sans que l'utilisateur voie une différence.
func TestListAllJSONAvecServiceDeRechercheDonneLeMemeResultat(t *testing.T) {
	appSans, _, _ := prepare(t)
	arbreDeTest(t, appSans)
	rawSans, err := appSans.ListAllJSON()
	if err != nil {
		t.Fatalf("ListAllJSON sans recherche: %v", err)
	}

	appAvec, serveur, _ := prepare(t)
	serveur.mu.Lock()
	serveur.search = true
	serveur.mu.Unlock()
	arbreDeTest(t, appAvec)
	rawAvec, err := appAvec.ListAllJSON()
	if err != nil {
		t.Fatalf("ListAllJSON avec recherche: %v", err)
	}

	sans, avec := cheminsPlats(t, rawSans), cheminsPlats(t, rawAvec)
	if !memesChemins(sans, avec) {
		t.Errorf("les deux chemins divergent :\n  parcours  %v\n  recherche %v", sans, avec)
	}
}

// L'inventaire doit ouvrir la liste plate sans réseau : c'est sa raison d'être.
func TestListAllJSONHorsConnexionSertLInventaire(t *testing.T) {
	app, serveur, _ := prepare(t)
	arbreDeTest(t, app)

	if _, err := app.ListAllJSON(); err != nil {
		t.Fatalf("ListAllJSON: %v", err)
	}

	serveur.setOffline(true)

	raw, err := app.ListAllJSON()
	if err != nil {
		t.Fatalf("ListAllJSON hors connexion: %v", err)
	}

	var listing struct {
		FromCache bool `json:"fromCache"`
	}
	decodeJSON(t, raw, &listing)
	if !listing.FromCache {
		t.Error("fromCache faux alors que le réseau manquait")
	}

	attendu := []string{"Projets/Archives/vieux.md", "Projets/projet.md", "racine.md"}
	if got := cheminsPlats(t, raw); !memesChemins(got, attendu) {
		t.Errorf("inventaire hors connexion = %v, attendu %v", got, attendu)
	}
}

// Le scénario qui a motivé toute la règle de fusion : l'index du serveur
// retarde de ~1,3 s sur une écriture. Une note créée puis un inventaire
// rafraîchi ne doit jamais faire disparaître la note.
func TestListAllJSONMontreUneNoteCreeeHorsConnexion(t *testing.T) {
	app, serveur, _ := prepare(t)
	arbreDeTest(t, app)
	if _, err := app.ListAllJSON(); err != nil {
		t.Fatalf("ListAllJSON: %v", err)
	}

	serveur.setOffline(true)
	if _, err := app.CreateNoteJSON("", "hors-ligne.md", "# Hors ligne\n"); err != nil {
		t.Fatalf("CreateNoteJSON hors connexion: %v", err)
	}
	serveur.setOffline(false)

	// Le serveur n'a pas encore la note : la synchronisation n'a pas tourné.
	raw, err := app.ListAllJSON()
	if err != nil {
		t.Fatalf("ListAllJSON: %v", err)
	}

	trouvee := false
	for _, chemin := range cheminsPlats(t, raw) {
		if chemin == "hors-ligne.md" {
			trouvee = true
		}
	}
	if !trouvee {
		t.Fatalf("la note créée hors connexion a disparu de l'inventaire : %v", cheminsPlats(t, raw))
	}
}

// Une note supprimée localement ne doit pas revenir parce que le serveur la
// voit encore.
func TestListAllJSONNeRessuscitePasUneNoteSupprimee(t *testing.T) {
	app, serveur, _ := prepare(t)
	arbreDeTest(t, app)
	if _, err := app.ListAllJSON(); err != nil {
		t.Fatalf("ListAllJSON: %v", err)
	}

	serveur.setOffline(true)
	if err := app.Delete("racine.md"); err != nil {
		t.Fatalf("Delete hors connexion: %v", err)
	}
	serveur.setOffline(false)

	raw, err := app.ListAllJSON()
	if err != nil {
		t.Fatalf("ListAllJSON: %v", err)
	}
	for _, chemin := range cheminsPlats(t, raw) {
		if chemin == "racine.md" {
			t.Fatalf("note supprimée revenue dans l'inventaire : %v", cheminsPlats(t, raw))
		}
	}
}

// Le sélecteur de destination doit proposer la racine : y créer une note est
// le cas le plus courant.
func TestFoldersJSONContientLaRacine(t *testing.T) {
	app, _, _ := prepare(t)
	arbreDeTest(t, app)
	if _, err := app.ListAllJSON(); err != nil {
		t.Fatalf("ListAllJSON: %v", err)
	}

	raw, err := app.FoldersJSON()
	if err != nil {
		t.Fatalf("FoldersJSON: %v", err)
	}

	var dossiers []struct {
		Path string `json:"path"`
		Name string `json:"name"`
	}
	decodeJSON(t, raw, &dossiers)

	chemins := []string{}
	for _, d := range dossiers {
		chemins = append(chemins, d.Path)
	}
	sort.Strings(chemins)

	attendu := []string{"", "Projets", "Projets/Archives"}
	if !memesChemins(chemins, attendu) {
		t.Errorf("dossiers = %v, attendu %v", chemins, attendu)
	}
}
