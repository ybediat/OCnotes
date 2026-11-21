package mobile

import (
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"unicode/utf16"
)

// cherche lance SearchJSON et rend la réponse décodée.
func cherche(t *testing.T, app *App, requete, dossier string) searchResponse {
	t.Helper()
	raw, err := app.SearchJSON(requete, dossier)
	if err != nil {
		t.Fatalf("SearchJSON(%q, %q) : %v", requete, dossier, err)
	}
	var res searchResponse
	decodeJSON(t, raw, &res)
	return res
}

// chemins rend les chemins des résultats, dans l'ordre.
func chemins(res searchResponse) []string {
	out := make([]string, 0, len(res.Results))
	for _, r := range res.Results {
		out = append(out, r.Path)
	}
	return out
}

// creeNote crée une note par la façade, dans un dossier créé au besoin.
func creeNote(t *testing.T, app *App, dossier, nom, texte string) {
	t.Helper()
	if dossier != "" {
		parent, nomDossier := "", dossier
		if i := strings.LastIndex(dossier, "/"); i >= 0 {
			parent, nomDossier = dossier[:i], dossier[i+1:]
		}
		_, _ = app.CreateFolderJSON(parent, nomDossier)
	}
	if _, err := app.CreateNoteJSON(dossier, nom, texte); err != nil {
		t.Fatalf("CreateNoteJSON(%q, %q) : %v", dossier, nom, err)
	}
}

func TestSearchJSONTrouveDansLeTexte(t *testing.T) {
	app, _ := prepareLocal(t)
	creeNote(t, app, "", "Maison", "# Maison\n\nPayer la Facture EDF de mars\n")
	creeNote(t, app, "", "Courses", "pain, lait\n")

	res := cherche(t, app, "facture edf", "")
	if res.Query != "facture edf" {
		t.Errorf("query = %q, la requête doit revenir telle quelle", res.Query)
	}
	if len(res.Results) != 1 {
		t.Fatalf("résultats : %v", chemins(res))
	}
	r := res.Results[0]
	if r.Path != "Maison.md" || r.Name != "Maison.md" || r.Display != "Maison" || r.Count != 1 {
		t.Errorf("résultat inattendu : %+v", r)
	}
	unites := utf16.Encode([]rune(r.Excerpt))
	if r.Excerpt != "Payer la Facture EDF de mars" || string(utf16.Decode(unites[r.Start:r.End])) != "Facture EDF" {
		t.Errorf("extrait %q [%d, %d)", r.Excerpt, r.Start, r.End)
	}
}

// En arborescence, la portée est le dossier affiché et ses sous-dossiers ;
// un dossier dont le nom commence pareil n'en fait pas partie.
func TestSearchJSONPorteeDossierEtSousDossiers(t *testing.T) {
	app, _ := prepareLocal(t)
	creeNote(t, app, "", "Racine", "lorem")
	creeNote(t, app, "Projets", "Un", "lorem")
	creeNote(t, app, "Projets/2026", "Deux", "lorem")
	creeNote(t, app, "Projets-bis", "Trois", "lorem")

	if got := chemins(cherche(t, app, "lorem", "Projets")); strings.Join(got, ",") != "Projets/2026/Deux.md,Projets/Un.md" {
		t.Errorf("portée Projets : %v", got)
	}
	if got := chemins(cherche(t, app, "lorem", "")); len(got) != 4 {
		t.Errorf("portée racine : %v", got)
	}
}

func TestSearchJSONRequeteTropCourte(t *testing.T) {
	app, _ := prepareLocal(t)
	creeNote(t, app, "", "A", "a b c")
	for _, q := range []string{"", "a", "  b "} {
		if res := cherche(t, app, q, ""); len(res.Results) != 0 || res.Cancelled {
			t.Errorf("requête %q : %+v", q, res)
		}
	}
}

// Chercher ne touche pas au cache : ni index réécrit, ni date d'accès (l'éviction
// croirait que tout vient d'être ouvert).
func TestSearchJSONNeReecritPasLIndex(t *testing.T) {
	app, dataDir := prepareLocal(t)
	creeNote(t, app, "", "A", "lorem aaaa")
	creeNote(t, app, "", "B", "lorem bbbb")

	var index string
	_ = filepath.WalkDir(dataDir, func(p string, d fs.DirEntry, err error) error {
		if err == nil && d.Name() == "index.json" {
			index = p
		}
		return nil
	})
	avant, err := os.ReadFile(index)
	if err != nil {
		t.Fatal(err)
	}
	acces := map[string]string{}
	for _, e := range app.cache.Entries() {
		acces[e.Path] = e.LastAccess.String()
	}

	if res := cherche(t, app, "lorem", ""); len(res.Results) != 2 {
		t.Fatalf("résultats : %v", chemins(res))
	}

	apres, err := os.ReadFile(index)
	if err != nil {
		t.Fatal(err)
	}
	if string(avant) != string(apres) {
		t.Error("SearchJSON a réécrit l'index")
	}
	for _, e := range app.cache.Entries() {
		if e.LastAccess.String() != acces[e.Path] {
			t.Errorf("%s : date d'accès modifiée par la recherche", e.Path)
		}
	}
}

// En mode serveur, aucune requête ne part, et une note connue mais absente du
// cache est comptée, jamais téléchargée.
func TestSearchJSONSansReseauEtNotesEvinceesComptees(t *testing.T) {
	app, server := prepareDebranchement(t) // Une.md en cache, Deux et Trois connues seulement

	var requetes atomic.Int32
	handler := server.Config.Handler
	server.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requetes.Add(1)
		handler.ServeHTTP(w, r)
	})

	res := cherche(t, app, "une", "")
	if got := chemins(res); len(got) != 1 || got[0] != "Une.md" {
		t.Errorf("résultats : %v", got)
	}
	if res.Skipped != 2 {
		t.Errorf("skipped = %d, attendu 2", res.Skipped)
	}
	if n := requetes.Load(); n != 0 {
		t.Errorf("%d requête(s) envoyée(s) au serveur par une recherche", n)
	}

	// Hors connexion, la même recherche rend la même chose.
	server.setOffline(true)
	if got := chemins(cherche(t, app, "une", "")); len(got) != 1 {
		t.Errorf("hors connexion : %v", got)
	}
}

// Le base64 d'une image n'est jamais fouillé, et l'extrait n'en montre rien.
func TestSearchJSONIgnoreLeBase64(t *testing.T) {
	app, _ := prepareLocal(t)
	image := "data:image/png;base64," + strings.Repeat("QUJDabcxyz", 3000)
	creeNote(t, app, "", "Illustrée", "Une photo du chantier ![photo]("+image+") prise hier\n")

	if res := cherche(t, app, "abcxyz", ""); len(res.Results) != 0 {
		t.Errorf("le base64 a été fouillé : %v", chemins(res))
	}
	res := cherche(t, app, "chantier", "")
	if len(res.Results) != 1 {
		t.Fatalf("résultats : %v", chemins(res))
	}
	if ex := res.Results[0].Excerpt; strings.Contains(ex, "base64") || strings.Contains(ex, "ocnotes-image") || len(ex) > 400 {
		t.Errorf("extrait : %q", ex)
	}
	if res := cherche(t, app, "ocnotes", ""); len(res.Results) != 0 {
		t.Errorf("le jeton d'image a été fouillé : %v", chemins(res))
	}
}

// Les fichiers en lecture seule (documents, configuration) ne sont pas fouillés.
func TestSearchJSONEcarteLesFichiersEnLectureSeule(t *testing.T) {
	app, _ := prepareLocal(t)
	creeNote(t, app, "", "Note", "lorem")
	if err := app.cache.Put("reglages.yaml", []byte("lorem: 1\n")); err != nil {
		t.Fatal(err)
	}
	if got := chemins(cherche(t, app, "lorem", "")); len(got) != 1 || got[0] != "Note.md" {
		t.Errorf("résultats : %v", got)
	}
}

// Au-delà de 50 notes trouvées, la recherche s'arrête et le signale.
func TestSearchJSONTronqueA50(t *testing.T) {
	app, _ := prepareLocal(t)
	for i := 0; i < 52; i++ {
		creeNote(t, app, "", fmt.Sprintf("N%02d", i), "lorem")
	}
	res := cherche(t, app, "lorem", "")
	if len(res.Results) != 50 || !res.Truncated {
		t.Errorf("%d résultats, truncated = %v", len(res.Results), res.Truncated)
	}
}

// Exactement 50 notes trouvées : rien n'est coupé, la liste n'est pas
// signalée tronquée.
func TestSearchJSONCinquanteNeTronquePas(t *testing.T) {
	app, _ := prepareLocal(t)
	for i := 0; i < 50; i++ {
		creeNote(t, app, "", fmt.Sprintf("N%02d", i), "lorem")
	}
	creeNote(t, app, "", "Autre", "rien à voir")
	res := cherche(t, app, "lorem", "")
	if len(res.Results) != 50 || res.Truncated {
		t.Errorf("%d résultats, truncated = %v", len(res.Results), res.Truncated)
	}
}

// Au-delà de 50, on garde les notes qui contiennent le plus le terme, même
// si leur nom les rangerait en dernier.
func TestSearchJSONGardeLesPlusPertinentes(t *testing.T) {
	app, _ := prepareLocal(t)
	for i := 0; i < 55; i++ {
		creeNote(t, app, "", fmt.Sprintf("A%02d", i), "lorem")
	}
	creeNote(t, app, "", "Zz", "lorem lorem lorem")
	res := cherche(t, app, "lorem", "")
	if len(res.Results) != 50 || !res.Truncated {
		t.Fatalf("%d résultats, truncated = %v", len(res.Results), res.Truncated)
	}
	if res.Results[0].Display != "Zz" || res.Results[0].Count != 3 {
		t.Errorf("premier résultat : %+v", res.Results[0])
	}
}

// CancelSearch rend périmée la passe en cours : elle revient annulée.
func TestCancelSearchAnnuleLaPasseEnCours(t *testing.T) {
	app, _ := prepareLocal(t)
	for i := 0; i < 5; i++ {
		creeNote(t, app, "", fmt.Sprintf("N%d", i), "lorem")
	}
	var fait atomic.Bool
	app.apresNoteFouillee = func() {
		if fait.CompareAndSwap(false, true) {
			app.CancelSearch()
		}
	}
	res := cherche(t, app, "lorem", "")
	if !res.Cancelled || len(res.Results) != 0 {
		t.Errorf("réponse : %+v", res)
	}
}

// Une recherche lancée pendant une autre rend la première périmée : celle-ci
// revient vide et annulée, la seconde complète.
func TestSearchJSONUneRecherchePlusRecenteAnnuleLaPrecedente(t *testing.T) {
	app, _ := prepareLocal(t)
	for i := 0; i < 5; i++ {
		creeNote(t, app, "", fmt.Sprintf("N%d", i), "lorem ipsum")
	}

	var lancee atomic.Bool
	var seconde searchResponse
	app.apresNoteFouillee = func() {
		if lancee.CompareAndSwap(false, true) {
			seconde = cherche(t, app, "ipsum", "")
		}
	}

	premiere := cherche(t, app, "lorem", "")
	if !premiere.Cancelled || len(premiere.Results) != 0 || premiere.Query != "lorem" {
		t.Errorf("première : %+v", premiere)
	}
	if seconde.Cancelled || len(seconde.Results) != 5 || seconde.Query != "ipsum" {
		t.Errorf("seconde : %+v", seconde)
	}
}
