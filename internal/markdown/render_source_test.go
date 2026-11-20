package markdown

import (
	"strings"
	"testing"
	"unicode/utf16"
)

// unites découpe un texte en unités UTF-16 : l'unité de toute position qui
// traverse la frontière, donc celle dans laquelle on vérifie Source.
func unites(s string) []uint16 { return utf16.Encode([]rune(s)) }

// sousTexte renvoie n unités UTF-16 de s à partir de pos, ou "" hors bornes.
func sousTexte(s string, pos, n int) string {
	u := unites(s)
	if pos < 0 || pos+n > len(u) {
		return ""
	}
	return string(utf16.Decode(u[pos : pos+n]))
}

// taches renvoie les blocs de tâche d'un rendu, dans l'ordre.
func taches(blocks []Block) []Block {
	var out []Block
	for _, b := range blocks {
		if b.Kind == KindTask {
			out = append(out, b)
		}
	}
	return out
}

// verifierCases contrôle la propriété qui rend la bascule sûre : chaque tâche
// porte une position, et la source y montre « [ », un caractère, « ] ».
func verifierCases(t *testing.T, source string, attendues int) []Block {
	t.Helper()
	tt := taches(Render(source))
	if len(tt) != attendues {
		t.Fatalf("%d tâches, %d attendues", len(tt), attendues)
	}
	for i, b := range tt {
		if b.Source == nil {
			t.Fatalf("tâche %d (%q) sans position", i, b.Text)
		}
		boite := sousTexte(source, *b.Source, 3)
		if len(boite) != 3 || boite[0] != '[' || boite[2] != ']' {
			t.Errorf("tâche %d (%q) : position %d désigne %q, pas une case", i, b.Text, *b.Source, boite)
		}
	}
	return tt
}

func TestSourceTacheSimple(t *testing.T) {
	tt := verifierCases(t, "- [ ] pain\n- [x] lait\n", 2)
	if *tt[0].Source != 2 || *tt[1].Source != 13 {
		t.Errorf("positions = %d, %d ; attendu 2, 13", *tt[0].Source, *tt[1].Source)
	}
}

func TestSourceTacheImbriquee(t *testing.T) {
	verifierCases(t, "- [ ] parent\n  - [x] enfant\n    - [ ] petit-enfant\n", 3)
}

func TestSourceTacheDansUneCitation(t *testing.T) {
	verifierCases(t, "> - [ ] x\n> - [X] y\n", 2)
}

func TestSourceTacheNumerotee(t *testing.T) {
	verifierCases(t, "1. [x] un\n2. [ ] deux\n", 2)
}

// Un emoji compte 4 octets mais 2 unités UTF-16, un « é » 2 octets mais une
// unité : une position restée en octets tomberait à côté de la case.
func TestSourceTacheApresEmojiEtAccent(t *testing.T) {
	src := "# Été 😀\n\n😀 é\n\n- [ ] après\n"
	tt := verifierCases(t, src, 1)
	if *tt[0].Source != 18 {
		t.Errorf("position = %d ; attendu 18 (unités UTF-16)", *tt[0].Source)
	}
}

func TestSourceTacheCRLF(t *testing.T) {
	verifierCases(t, "titre\r\n\r\n- [ ] un\r\n- [x] deux\r\n", 2)
}

func TestSourceTacheTabulation(t *testing.T) {
	verifierCases(t, "- [ ] parent\n\t- [x] enfant\n", 2)
}

func TestSourceTitreATX(t *testing.T) {
	src := "intro\n\n##   Été\n"
	b := bloc(t, Render(src), 1)
	if b.Kind != KindHeading || b.Source == nil {
		t.Fatalf("bloc = %+v", b)
	}
	if got := sousTexte(src, *b.Source, 3); got != "Été" {
		t.Errorf("position %d désigne %q, pas le texte du titre", *b.Source, got)
	}
}

func TestSourceTitreSouligne(t *testing.T) {
	src := "😀\n\nMon titre\n=========\n"
	b := bloc(t, Render(src), 1)
	if b.Kind != KindHeading || b.Source == nil {
		t.Fatalf("bloc = %+v", b)
	}
	if got := sousTexte(src, *b.Source, 3); got != "Mon" {
		t.Errorf("position %d désigne %q, pas le texte du titre", *b.Source, got)
	}
}

// La position vaut pour le texte du champ, c'est-à-dire le texte à jetons :
// une image de plusieurs mégaoctets avant la tâche ne doit pas la décaler.
func TestSourceTacheApresImageEnLigne(t *testing.T) {
	original := "![photo](data:image/png;base64," + strings.Repeat("QUJD", 5000) + ")\n\n- [ ] après l'image\n"
	jetons, images := ExtractInlineData(original)
	if len(images) != 1 {
		t.Fatalf("%d images extraites, 1 attendue", len(images))
	}
	verifierCases(t, jetons, 1)
}

// Une image au milieu d'une tâche la coupe en deux morceaux : les deux
// portent la position de la même case, pour basculer ensemble.
func TestSourceTacheCoupeeParUneImage(t *testing.T) {
	tt := verifierCases(t, "- [ ] avant ![i](ocnotes-image:1) après\n", 2)
	if *tt[0].Source != 2 || *tt[1].Source != 2 {
		t.Errorf("positions = %d, %d ; attendu 2, 2", *tt[0].Source, *tt[1].Source)
	}
}

// Une tâche qui commence par une image n'a pas de premier morceau (il est
// vide) : le morceau qui suit l'image garde quand même la case.
func TestSourceTacheQuiCommenceParUneImage(t *testing.T) {
	tt := verifierCases(t, "- [ ] ![i](ocnotes-image:1) après\n", 1)
	if *tt[0].Source != 2 {
		t.Errorf("position = %d ; attendu 2", *tt[0].Source)
	}
}

// Un paragraphe coupé par une image ne garde sa position que sur le premier
// morceau : la suite commence ailleurs.
func TestSourceParagrapheCoupeParUneImage(t *testing.T) {
	blocs := Render("avant ![i](ocnotes-image:1) après\n")
	if len(blocs) != 3 || blocs[0].Source == nil || blocs[2].Source != nil {
		t.Fatalf("blocs = %+v", blocs)
	}
}

// Un bloc sans position connue n'en porte aucune : zéro est une position
// valide, l'absence se dit par nil.
func TestSourceAbsenteSansPosition(t *testing.T) {
	for _, b := range RenderPlain("- [ ] pas du Markdown\n") {
		if b.Source != nil {
			t.Errorf("bloc brut avec position %d", *b.Source)
		}
	}
	for _, b := range Render("| a | b |\n|---|---|\n| 1 | 2 |\n") {
		if b.Source != nil {
			t.Errorf("ligne de tableau avec position %d", *b.Source)
		}
	}
	if b := bloc(t, Render("- [ ] en tête"), 0); b.Source == nil || *b.Source != 2 {
		t.Errorf("tâche en tête de note : %+v", b)
	}
}

// grosseNote fabrique une note de la taille de celle du banc (≈ 295 ko),
// mêlant titres, tâches, accents et emojis.
func grosseNote() string {
	var sb strings.Builder
	for i := 0; sb.Len() < 295_000; i++ {
		sb.WriteString("## Section été 😀\n\n")
		sb.WriteString("Un paragraphe avec du **gras** et de l'*italique*, é à ü.\n\n")
		sb.WriteString("- [ ] tâche à faire\n- [x] tâche faite 😀\n  - [ ] sous-tâche\n\n")
	}
	return sb.String()
}

// La conversion octets → UTF-16 doit rester linéaire : un recomptage depuis le
// début pour chaque bloc rendrait Render quadratique sur une grosse note.
func TestSourceGrosseNote(t *testing.T) {
	src := grosseNote()
	tt := verifierCases(t, src, len(taches(Render(src))))
	if len(tt) < 1000 {
		t.Fatalf("seulement %d tâches", len(tt))
	}
}

func BenchmarkRenderGrosseNote(b *testing.B) {
	src := grosseNote()
	b.SetBytes(int64(len(src)))
	for i := 0; i < b.N; i++ {
		Render(src)
	}
}
