package search

import (
	"bufio"
	"os"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"unicode/utf16"
	"unicode/utf8"
)

// echappe remplace les \uXXXX du fichier de cas par leur caractère.
var echappe = regexp.MustCompile(`\\u([0-9A-Fa-f]{4})`)

func deChappe(s string) string {
	s = strings.ReplaceAll(s, `\t`, "\t")
	return echappe.ReplaceAllStringFunc(s, func(m string) string {
		code, _ := strconv.ParseUint(m[2:], 16, 32)
		return string(rune(code))
	})
}

// Rejoue testdata/cas-repli.txt, commun avec le test Kotlin de replie() : la
// même requête doit trouver un nom (Kotlin) et un texte (Go) de la même façon.
func TestCasRepliPartages(t *testing.T) {
	f, err := os.Open("testdata/cas-repli.txt")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()

	cas := 0
	lignes := bufio.NewScanner(f)
	for n := 1; lignes.Scan(); n++ {
		ligne := lignes.Text()
		if strings.TrimSpace(ligne) == "" || strings.HasPrefix(ligne, "#") {
			continue
		}
		champs := strings.Split(ligne, "¦")
		if len(champs) != 2 {
			t.Fatalf("ligne %d : deux champs attendus : %q", n, ligne)
		}
		entree := deChappe(strings.Trim(champs[0], " "))
		attendu := deChappe(strings.Trim(champs[1], " "))
		if got := Fold(entree); got != attendu {
			t.Errorf("ligne %d : Fold(%q) = %q, attendu %q", n, entree, got, attendu)
		}
		cas++
	}
	if cas == 0 {
		t.Fatal("aucun cas lu")
	}
}

// surligne renvoie le morceau de l'extrait désigné par les bornes UTF-16.
func surligne(t *testing.T, m Match) string {
	t.Helper()
	unites := utf16.Encode([]rune(m.Excerpt))
	if m.Start < 0 || m.End > len(unites) || m.Start >= m.End {
		t.Fatalf("bornes [%d, %d) hors de l'extrait %q (%d unités)", m.Start, m.End, m.Excerpt, len(unites))
	}
	return string(utf16.Decode(unites[m.Start:m.End]))
}

func TestFindIgnoreLaCasse(t *testing.T) {
	n, m := Find("Une Facture EDF de mars", "facture edf", 120)
	if n != 1 {
		t.Fatalf("count = %d, attendu 1", n)
	}
	if m.Excerpt != "Une Facture EDF de mars" || surligne(t, m) != "Facture EDF" {
		t.Errorf("extrait %q, surligné %q", m.Excerpt, surligne(t, m))
	}
}

// Un accent décomposé appartient à sa lettre : le surlignage le couvre, sinon
// le gras s'arrêterait entre le « e » et son accent.
func TestFindAccentsPrecomposesEtDecomposes(t *testing.T) {
	decompose := "Le résumé du jour"
	n, m := Find(decompose, "Résumé", 120)
	if n != 1 || surligne(t, m) != "résumé" {
		t.Errorf("texte décomposé : count %d, surligné %q", n, surligne(t, m))
	}

	n, m = Find("Le résumé du jour", "résumé", 120)
	if n != 1 || surligne(t, m) != "résumé" {
		t.Errorf("requête décomposée : count %d, surligné %q", n, surligne(t, m))
	}

	n, _ = Find("Le résumé du jour", "resume", 120)
	if n != 1 {
		t.Errorf("requête sans accent : count %d", n)
	}
}

// Une lettre qui se replie en deux (œ → oe) est surlignée entière.
func TestFindLigaturesEtLettresSpeciales(t *testing.T) {
	cas := []struct{ texte, requete, surligne string }{
		{"Une œuvre d'art", "oeuvre", "œuvre"},
		{"Une oeuvre d'art", "Œuvre", "oeuvre"},
		{"Die Straße hier", "strasse", "Straße"},
		{"Bonjour İstanbul", "istanbul", "İstanbul"},
		{"facture EDF", "facture edf", "facture EDF"},
	}
	for _, c := range cas {
		n, m := Find(c.texte, c.requete, 120)
		if n != 1 {
			t.Errorf("%q dans %q : count %d", c.requete, c.texte, n)
			continue
		}
		if got := surligne(t, m); got != c.surligne {
			t.Errorf("%q dans %q : surligné %q, attendu %q", c.requete, c.texte, got, c.surligne)
		}
	}
}

// Les bornes sont en unités UTF-16 : un emoji en compte deux.
func TestFindBornesUTF16ApresUnEmoji(t *testing.T) {
	_, m := Find("😀😀 lorem", "lorem", 120)
	if m.Start != 5 || m.End != 10 {
		t.Errorf("bornes [%d, %d), attendu [5, 10)", m.Start, m.End)
	}
}

func TestFindNeFranchitPasUneFinDeLigne(t *testing.T) {
	if n, _ := Find("une facture\nEDF de mars", "facture edf", 120); n != 0 {
		t.Errorf("count = %d, un terme à cheval sur deux lignes ne doit pas être trouvé", n)
	}
}

func TestFindCompteEtGardeLaPremiereLigne(t *testing.T) {
	n, m := Find("Titre\n  - second mot ici\nmot encore, mot", "mot", 120)
	if n != 3 {
		t.Errorf("count = %d, attendu 3", n)
	}
	// La ligne de la première occurrence, sans son retrait ni sa puce.
	if m.Excerpt != "second mot ici" || surligne(t, m) != "mot" {
		t.Errorf("extrait %q, surligné %q", m.Excerpt, surligne(t, m))
	}
}

// Une ligne de 10 000 caractères donne un extrait borné, recentré sur le terme.
func TestFindExtraitBorneSurUneLigneLongue(t *testing.T) {
	ligne := strings.Repeat("lorem ipsum ", 400) + "facture" + strings.Repeat(" dolor sit", 500)
	n, m := Find(ligne, "facture", 120)
	if n != 1 {
		t.Fatalf("count = %d", n)
	}
	if l := utf8.RuneCountInString(m.Excerpt); l > 122 {
		t.Errorf("extrait de %d caractères, attendu au plus 120 et deux ellipses", l)
	}
	if !strings.HasPrefix(m.Excerpt, "…") || !strings.HasSuffix(m.Excerpt, "…") {
		t.Errorf("extrait sans ellipses : %q", m.Excerpt)
	}
	if surligne(t, m) != "facture" {
		t.Errorf("surligné %q dans %q", surligne(t, m), m.Excerpt)
	}
}

// Un terme plus long que l'extrait est coupé avec lui, sans bornes hors champ.
func TestFindTermePlusLongQueLExtrait(t *testing.T) {
	terme := strings.Repeat("abc ", 50)
	_, m := Find("début "+terme+" fin", terme, 40)
	if got := surligne(t, m); !strings.HasPrefix(terme, strings.TrimSuffix(got, "…")) {
		t.Errorf("surligné %q", got)
	}
}

func TestFindRequeteTropCourte(t *testing.T) {
	for _, q := range []string{"", " ", "a", " é "} {
		if n, _ := Find("a é a é", q, 120); n != 0 {
			t.Errorf("requête %q : count %d, attendu 0", q, n)
		}
	}
}

// L'extrait se lit comme la note rendue : ni marqueur de bloc, ni gras, ni
// adresse de lien, et le terme reste bien surligné.
func TestFindExtraitSansSyntaxeMarkdown(t *testing.T) {
	cas := []struct{ texte, requete, extrait string }{
		{"- [ ] appeler le **garage** demain", "garage", "appeler le garage demain"},
		{"> ## Titre de la facture", "facture", "Titre de la facture"},
		{"voir [la facture](https://exemple.fr/f.pdf) de mars", "facture", "voir la facture de mars"},
		{"1. lire ~~ancien~~ `code` facture", "facture", "lire ancien code facture"},
		{"un [lien](http://a) puis facture et [autre](http://b)", "facture", "un lien puis facture et autre"},
	}
	for _, c := range cas {
		n, m := Find(c.texte, c.requete, 120)
		if n != 1 || m.Excerpt != c.extrait || surligne(t, m) != c.requete {
			t.Errorf("%q : n = %d, extrait %q, surligné %q", c.texte, n, m.Excerpt, surligne(t, m))
		}
	}
}

// Un terme qui n'apparaît que dans l'adresse d'un lien ne fait pas trouver la
// note : il ne s'y voit pas.
func TestFindIgnoreLesAdressesDeLiens(t *testing.T) {
	cas := []string{
		"voir [ici](https://facture.exemple.fr)",
		"![photo](images/facture.png)",
		"[f]: https://exemple.fr/facture",
	}
	for _, texte := range cas {
		if n, _ := Find(texte, "facture", 120); n != 0 {
			t.Errorf("%q : count = %d, attendu 0", texte, n)
		}
	}
	// Le texte du lien, lui, compte ; l'adresse non.
	if n, _ := Find("[facture](https://exemple.fr/facture)", "facture", 120); n != 1 {
		t.Errorf("count = %d, attendu 1", n)
	}
	// Une parenthèse sans lien n'est pas une adresse.
	if n, _ := Find("la facture (de mars)", "mars", 120); n != 1 {
		t.Errorf("count = %d, attendu 1", n)
	}
}

// L'italique simple disparaît de l'extrait, pas un astérisque de calcul ni le
// tiret bas d'un nom de fichier.
func TestFindExtraitSansItalique(t *testing.T) {
	cas := []struct{ texte, requete, extrait string }{
		{"le *garage* demain", "garage", "le garage demain"},
		{"voir _la facture_ ici", "facture", "voir la facture ici"},
		{"***très*** important facture", "facture", "très important facture"},
		{"5 * 3 facture", "facture", "5 * 3 facture"},
		{"facture dans nom_de_fichier.txt", "facture", "facture dans nom_de_fichier.txt"},
	}
	for _, c := range cas {
		n, m := Find(c.texte, c.requete, 120)
		if n != 1 || m.Excerpt != c.extrait || surligne(t, m) != c.requete {
			t.Errorf("%q : n = %d, extrait %q, surligné %q", c.texte, n, m.Excerpt, surligne(t, m))
		}
	}
}

// Dans du code, « ](…) » s'affiche tel quel : un terme qui s'y trouve est
// trouvé.
func TestFindTrouveLesLiensEcritsDansDuCode(t *testing.T) {
	cas := []string{
		"```\n[a](facture.md)\n```",
		"~~~\n[a](facture.md)\n~~~",
		"écrire `[a](facture.md)` pour lier",
	}
	for _, texte := range cas {
		if n, _ := Find(texte, "facture", 120); n != 1 {
			t.Errorf("%q : count = %d, attendu 1", texte, n)
		}
	}
	// Après la fermeture du bloc de code, les adresses sont de nouveau cachées.
	if n, _ := Find("```\ncode\n```\n[a](facture.md)", "facture", 120); n != 0 {
		t.Errorf("adresse après un bloc de code : count = %d, attendu 0", n)
	}
}
