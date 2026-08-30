package markdown

import (
	"fmt"
	"strings"
	"testing"
)

// bloc renvoie le n-ième bloc, ou fait échouer le test.
func bloc(t *testing.T, blocks []Block, i int) Block {
	t.Helper()
	if i >= len(blocks) {
		t.Fatalf("bloc %d demandé, %d blocs rendus: %+v", i, len(blocks), blocks)
	}
	return blocks[i]
}

func TestRenderTitresEtParagraphes(t *testing.T) {
	blocks := Render("# Titre\n\nUn paragraphe.\n\n## Sous-titre\n")

	if len(blocks) != 3 {
		t.Fatalf("%d blocs, 3 attendus: %+v", len(blocks), blocks)
	}
	if b := bloc(t, blocks, 0); b.Kind != KindHeading || b.Level != 1 || b.Text != "Titre" {
		t.Errorf("bloc 0 = %+v", b)
	}
	if b := bloc(t, blocks, 1); b.Kind != KindParagraph || b.Text != "Un paragraphe." {
		t.Errorf("bloc 1 = %+v", b)
	}
	if b := bloc(t, blocks, 2); b.Kind != KindHeading || b.Level != 2 {
		t.Errorf("bloc 2 = %+v", b)
	}
}

// Chaque marqueur de la barre d'outils doit ressortir en span.
//
// Le barré et le code en ligne ne sont pas du CommonMark de base : sans
// extension.GFM, l'aperçu n'afficherait pas ce que ses propres boutons
// écrivent.
func TestRenderSpansEnLigne(t *testing.T) {
	b := bloc(t, Render("du **gras**, de l'*italique*, du ~~barré~~ et du `code`."), 0)

	if b.Text != "du gras, de l'italique, du barré et du code." {
		t.Fatalf("texte = %q : les marqueurs devraient avoir disparu du texte", b.Text)
	}

	styles := map[Style]string{}
	for _, s := range b.Spans {
		styles[s.Style] = string([]rune(b.Text)[s.Start:s.End])
	}
	attendu := map[Style]string{
		StyleBold:   "gras",
		StyleItalic: "italique",
		StyleStrike: "barré",
		StyleCode:   "code",
	}
	for style, mot := range attendu {
		if styles[style] != mot {
			t.Errorf("span %s = %q, attendu %q (spans: %+v)", style, styles[style], mot, b.Spans)
		}
	}
}

func TestRenderLien(t *testing.T) {
	b := bloc(t, Render("voir [le site](https://exemple.fr/a?b=1) pour la suite"), 0)

	if b.Text != "voir le site pour la suite" {
		t.Fatalf("texte = %q", b.Text)
	}
	if len(b.Spans) != 1 {
		t.Fatalf("%d spans, 1 attendu: %+v", len(b.Spans), b.Spans)
	}
	s := b.Spans[0]
	if s.Style != StyleLink || s.Href != "https://exemple.fr/a?b=1" {
		t.Errorf("span = %+v", s)
	}
	if got := b.Text[s.Start:s.End]; got != "le site" {
		t.Errorf("texte du lien = %q, attendu \"le site\"", got)
	}
}

// Les bornes de span sont en unités UTF-16, comme TextRange dans Compose.
//
// C'est la seule chose que Kotlin ne peut pas rattraper : une borne comptée en
// octets place le gras au mauvais endroit dès la première lettre accentuée, et
// l'écart grandit à chaque emoji. « é » : 2 octets, 1 rune, 1 unité UTF-16.
// « 😀 » : 4 octets, 1 rune, 2 unités.
func TestRenderSpansEnUnitesUTF16(t *testing.T) {
	b := bloc(t, Render("é😀 **gras**"), 0)

	if len(b.Spans) != 1 {
		t.Fatalf("%d spans, 1 attendu: %+v", len(b.Spans), b.Spans)
	}
	// « é » = 1, « 😀 » = 2, « " " » = 1 → le gras commence à 4 et tient 4.
	if got := (b.Spans[0]); got.Start != 4 || got.End != 8 {
		t.Errorf("span = {%d, %d}, attendu {4, 8} — compté en octets, on aurait {7, 11}", got.Start, got.End)
	}
}

func TestRenderListes(t *testing.T) {
	blocks := Render("- un\n- deux\n\n1. premier\n2. second\n")

	if len(blocks) != 4 {
		t.Fatalf("%d blocs, 4 attendus: %+v", len(blocks), blocks)
	}
	if b := bloc(t, blocks, 0); b.Kind != KindBullet || b.Text != "un" || b.Depth != 0 {
		t.Errorf("bloc 0 = %+v", b)
	}
	if b := bloc(t, blocks, 2); b.Kind != KindOrdered || b.Number != 1 || b.Text != "premier" {
		t.Errorf("bloc 2 = %+v", b)
	}
	if b := bloc(t, blocks, 3); b.Kind != KindOrdered || b.Number != 2 {
		t.Errorf("bloc 3 = %+v", b)
	}
}

// Une liste numérotée qui ne commence pas à 1 garde son point de départ.
func TestRenderListeNumeroteeDepartDecale(t *testing.T) {
	blocks := Render("5. cinq\n6. six\n")
	if b := bloc(t, blocks, 0); b.Number != 5 {
		t.Errorf("premier numéro = %d, attendu 5", b.Number)
	}
	if b := bloc(t, blocks, 1); b.Number != 6 {
		t.Errorf("second numéro = %d, attendu 6", b.Number)
	}
}

func TestRenderListeImbriquee(t *testing.T) {
	blocks := Render("- parent\n    - enfant\n")

	if len(blocks) != 2 {
		t.Fatalf("%d blocs, 2 attendus: %+v", len(blocks), blocks)
	}
	if b := bloc(t, blocks, 0); b.Depth != 0 || b.Text != "parent" {
		t.Errorf("bloc 0 = %+v", b)
	}
	if b := bloc(t, blocks, 1); b.Depth != 1 || b.Text != "enfant" {
		t.Errorf("bloc 1 = %+v, attendu Depth 1", b)
	}
}

// Une case à cocher devient un état du bloc, pas des crochets dans le texte.
func TestRenderTaches(t *testing.T) {
	blocks := Render("- [x] fait\n- [ ] à faire\n")

	if len(blocks) != 2 {
		t.Fatalf("%d blocs, 2 attendus: %+v", len(blocks), blocks)
	}
	if b := bloc(t, blocks, 0); b.Kind != KindTask || !b.Checked || b.Text != "fait" {
		t.Errorf("bloc 0 = %+v, attendu une tâche cochée « fait »", b)
	}
	if b := bloc(t, blocks, 1); b.Kind != KindTask || b.Checked || b.Text != "à faire" {
		t.Errorf("bloc 1 = %+v, attendu une tâche décochée « à faire »", b)
	}
}

func TestRenderCitation(t *testing.T) {
	blocks := Render("> cité\n>\n> - et une puce\n")

	if b := bloc(t, blocks, 0); b.Quote != 1 || b.Text != "cité" {
		t.Errorf("bloc 0 = %+v, attendu Quote 1", b)
	}
	if b := bloc(t, blocks, 1); b.Quote != 1 || b.Kind != KindBullet {
		t.Errorf("bloc 1 = %+v, attendu une puce citée", b)
	}
}

func TestRenderBlocDeCode(t *testing.T) {
	b := bloc(t, Render("```go\nfmt.Println(\"salut\")\n```\n"), 0)

	if b.Kind != KindCode || b.Lang != "go" {
		t.Fatalf("bloc = %+v", b)
	}
	if b.Text != "fmt.Println(\"salut\")" {
		t.Errorf("texte = %q", b.Text)
	}
	if len(b.Spans) != 0 {
		t.Errorf("un bloc de code ne porte pas de mise en forme: %+v", b.Spans)
	}
}

// Le contenu d'un bloc de code n'est pas interprété.
func TestRenderBlocDeCodeNInterpretePas(t *testing.T) {
	b := bloc(t, Render("```\n# pas un titre\n**pas du gras**\n```\n"), 0)

	if b.Kind != KindCode {
		t.Fatalf("bloc = %+v", b)
	}
	if !strings.Contains(b.Text, "**pas du gras**") {
		t.Errorf("texte = %q : les marqueurs devaient rester tels quels", b.Text)
	}
}

// Une image insérée par l'éditeur web d'OpenCloud est un data: URI de
// plusieurs mégaoctets. Elle doit devenir un repère, et sa source ne doit
// traverser nulle part.
func TestRenderImageNeTransmetPasSaSource(t *testing.T) {
	source := "avant ![une photo](data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQ) après"
	blocks := Render(source)

	if len(blocks) != 3 {
		t.Fatalf("%d blocs, 3 attendus (texte, image, texte): %+v", len(blocks), blocks)
	}
	if b := bloc(t, blocks, 1); b.Kind != KindImage || b.Text != "une photo" {
		t.Errorf("bloc 1 = %+v, attendu une image « une photo »", b)
	}
	for _, b := range blocks {
		if strings.Contains(b.Text, "base64") || strings.Contains(b.Text, "9j/4AAQ") {
			t.Fatalf("la source de l'image a fui dans un bloc: %+v", b)
		}
	}
}

// Le HTML brut n'est pas affiché : l'aperçu n'a pas de moteur pour
// l'interpréter, et une note vient d'un serveur partagé.
func TestRenderIgnoreLeHTMLBrut(t *testing.T) {
	for _, source := range []string{
		"<script>alert(1)</script>\n",
		"un <b>gras</b> en HTML\n",
	} {
		for _, b := range Render(source) {
			if strings.Contains(b.Text, "<") {
				t.Errorf("Render(%q) a laissé passer du HTML: %+v", source, b)
			}
		}
	}
}

func TestRenderTableau(t *testing.T) {
	blocks := Render("| Nom | Âge |\n|---|---|\n| Zoé | 7 |\n")

	if len(blocks) != 2 {
		t.Fatalf("%d blocs, 2 attendus: %+v", len(blocks), blocks)
	}
	entete := bloc(t, blocks, 0)
	if !entete.Header || len(entete.Cells) != 2 || entete.Cells[1] != "Âge" {
		t.Errorf("en-tête = %+v", entete)
	}
	ligne := bloc(t, blocks, 1)
	if ligne.Header || len(ligne.Cells) != 2 || ligne.Cells[0] != "Zoé" {
		t.Errorf("ligne = %+v", ligne)
	}
}

// Deux lignes tapées l'une sous l'autre restent deux lignes.
//
// CommonMark les recollerait avec une espace. Dans un carnet de notes, ce
// serait donner tort à l'utilisateur sur ce qu'il vient d'écrire.
func TestRenderRetourSimpleResteUnRetour(t *testing.T) {
	b := bloc(t, Render("première ligne\nseconde ligne\n"), 0)
	if b.Text != "première ligne\nseconde ligne" {
		t.Errorf("texte = %q", b.Text)
	}
}

func TestRenderVide(t *testing.T) {
	if blocks := Render("   \n\n  \n"); len(blocks) != 0 {
		t.Errorf("%d blocs pour un contenu vide: %+v", len(blocks), blocks)
	}
}

// Un fichier qui n'est pas du Markdown n'est pas interprété du tout.
func TestRenderPlainNInterpreteRien(t *testing.T) {
	source := "# pas un titre\n- pas une puce\n\n**pas du gras**"
	blocks := RenderPlain(source)

	if len(blocks) != 1 {
		t.Fatalf("%d blocs, 1 attendu: %+v", len(blocks), blocks)
	}
	b := blocks[0]
	if b.Kind != KindPlain {
		t.Errorf("kind = %q, attendu %q", b.Kind, KindPlain)
	}
	if b.Text != source {
		t.Errorf("texte = %q, attendu le contenu inchangé", b.Text)
	}
	if len(b.Spans) != 0 {
		t.Errorf("le texte brut ne porte aucune mise en forme: %+v", b.Spans)
	}
}

func TestRenderPlainVide(t *testing.T) {
	if blocks := RenderPlain("\n \n"); len(blocks) != 0 {
		t.Errorf("%d blocs pour un contenu vide: %+v", len(blocks), blocks)
	}
}

// texteBrut fabrique un fichier texte de n lignes, avec une ligne vide toutes
// les `respiration` lignes — ou aucune si `respiration` vaut zéro.
func texteBrut(n, respiration int) string {
	var b strings.Builder
	for i := 1; i <= n; i++ {
		fmt.Fprintf(&b, "Ligne %d du fichier, avec des mots ordinaires et rien à interpréter.\n", i)
		if respiration > 0 && i%respiration == 0 {
			b.WriteString("\n")
		}
	}
	return b.String()
}

// Le découpage d'un texte brut ne doit rien changer à ce qui s'affiche : tout
// le contrat de ce format est de montrer le fichier tel quel.
func TestRenderPlainNePerdRien(t *testing.T) {
	source := texteBrut(300, 7)

	var recompose strings.Builder
	for _, b := range RenderPlain(source) {
		recompose.WriteString(b.Text)
	}
	if got := recompose.String(); got != strings.TrimRight(source, "\n") {
		t.Errorf("la concaténation des blocs ne rend pas le texte d'entrée (%d contre %d octets)",
			len(got), len(strings.TrimRight(source, "\n")))
	}
}

// Un bloc unique portant tout le fichier a deux défauts mesurés : la
// LazyColumn n'a rien à virtualiser, et sur un .txt de 292 ko la hauteur
// intrinsèque du bloc fait planter l'application. Voir maxPlainLines.
func TestRenderPlainBorneLesBlocs(t *testing.T) {
	blocks := RenderPlain(texteBrut(300, 7))

	if len(blocks) < 2 {
		t.Fatalf("%d bloc pour un fichier de plus de 300 lignes", len(blocks))
	}
	for i, b := range blocks {
		if n := nombreDeLignes(b.Text); n > maxPlainLines {
			t.Errorf("bloc %d : %d lignes, %d au plus attendues", i, n, maxPlainLines)
		}
	}
}

// Le cas qui fait planter l'aperçu est précisément celui d'un fichier sans une
// seule ligne vide : il ne faut donc pas que le découpage en dépende.
func TestRenderPlainDecoupeSansLigneVide(t *testing.T) {
	blocks := RenderPlain(texteBrut(300, 0))

	if len(blocks) < 2 {
		t.Fatalf("%d bloc pour 300 lignes sans ligne vide", len(blocks))
	}
	for i, b := range blocks {
		if n := nombreDeLignes(b.Text); n > maxPlainLines {
			t.Errorf("bloc %d : %d lignes, %d au plus attendues", i, n, maxPlainLines)
		}
	}
}
