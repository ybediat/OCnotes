package documents

import (
	"strings"
	"testing"
	"unicode/utf8"

	"github.com/ybediat/OpenNote/internal/markdown"
)

func TestOdtTitres(t *testing.T) {
	blocs := lireOdt(t, "exemple.odt")

	var niveaux []int
	var textes []string
	for _, b := range blocs {
		if b.Kind == markdown.KindHeading {
			niveaux = append(niveaux, b.Level)
			textes = append(textes, b.Text)
		}
	}

	attendus := []int{1, 2, 3, 4, 5, 6, 2, 2}
	if len(niveaux) != len(attendus) {
		t.Fatalf("%d titres, attendu %d : %q", len(niveaux), len(attendus), textes)
	}
	for i, n := range attendus {
		if niveaux[i] != n {
			t.Errorf("titre %d (%q) de niveau %d, attendu %d", i, textes[i], niveaux[i], n)
		}
	}
}

// TestOdtTitreDeNiveau1 fige la découverte la plus coûteuse du lot 0.
//
// Dans un .odt converti, le titre de niveau 1 n'est **pas** un text:h : c'est
// un text:p dont le style parent est « Heading_20_1 ». Un analyseur qui ne lit
// que text:h le rend en paragraphe, sans rien signaler — et le document perd
// son titre principal.
func TestOdtTitreDeNiveau1(t *testing.T) {
	b := blocContenant(t, lireOdt(t, "exemple.odt"), "Titre de niveau 1")

	if b.Kind != markdown.KindHeading {
		t.Fatalf("le titre de niveau 1 est un %s — la chaîne d'héritage de styles n'a pas été remontée", b.Kind)
	}
	if b.Level != 1 {
		t.Errorf("niveau %d, attendu 1", b.Level)
	}
}

func TestOdtMiseEnForme(t *testing.T) {
	b := blocContenant(t, lireOdt(t, "exemple.odt"), "en gras")

	cas := []struct {
		style   markdown.Style
		attendu string
	}{
		{markdown.StyleBold, "en gras"},
		{markdown.StyleItalic, "en italique"},
		{markdown.StyleUnderline, "souligne"},
		{markdown.StyleStrike, "barre"},
		// Côté ODF, le surlignage passe par fo:background-color dans un style
		// de texte automatique — l'indirection du piège n° 5, une entrée de
		// plus dans la table.
		{markdown.StyleHighlight, "surligne"},
		{markdown.StyleLink, "lien vers OpenCloud"},
	}
	for _, c := range cas {
		if got := portee(t, b, c.style); got != c.attendu {
			t.Errorf("%s couvre %q, attendu %q", c.style, got, c.attendu)
		}
	}

	for _, s := range b.Spans {
		if s.Style == markdown.StyleLink && s.Href != "https://opencloud.eu/" {
			t.Errorf("href du lien = %q", s.Href)
		}
	}
}

// TestOdtSautDePage : côté ODF, le saut vient de fo:break-before="page" posé
// dans les propriétés du style de paragraphe, pas dans le paragraphe. Le
// <text:soft-page-break/> que LibreOffice sème pour sa pagination, lui, ne doit
// rien produire — c'est un artefact de rendu, pas une intention.
func TestOdtSautDePage(t *testing.T) {
	verifieSautDePage(t, lireOdt(t, "exemple.odt"))
}

func TestOdtListes(t *testing.T) {
	blocs := lireOdt(t, "exemple.odt")

	attendus := []struct {
		texte  string
		kind   markdown.Kind
		depth  int
		number int
	}{
		{"Premiere puce", markdown.KindBullet, 0, 0},
		{"Deuxieme puce", markdown.KindBullet, 0, 0},
		{"Premier point", markdown.KindOrdered, 0, 1},
		{"Sous-point A", markdown.KindOrdered, 1, 1},
		{"Sous-point B", markdown.KindOrdered, 1, 2},
		{"Deuxieme point", markdown.KindOrdered, 0, 2},
	}
	for _, a := range attendus {
		b := blocContenant(t, blocs, a.texte)
		if b.Kind != a.kind {
			t.Errorf("%q est un %s, attendu %s", a.texte, b.Kind, a.kind)
		}
		if b.Depth != a.depth {
			t.Errorf("%q à la profondeur %d, attendu %d", a.texte, b.Depth, a.depth)
		}
		if b.Number != a.number {
			t.Errorf("%q porte le numéro %d, attendu %d", a.texte, b.Number, a.number)
		}
	}
}

func TestOdtTableau(t *testing.T) {
	var lignes []markdown.Block
	for _, b := range lireOdt(t, "exemple.odt") {
		if b.Kind == markdown.KindTableRow {
			lignes = append(lignes, b)
		}
	}

	if len(lignes) != 3 {
		t.Fatalf("%d lignes de tableau, attendu 3", len(lignes))
	}
	if !lignes[0].Header {
		t.Error("la première ligne n'est pas marquée en-tête")
	}
	if lignes[1].Header || lignes[2].Header {
		t.Error("une ligne de corps est marquée en-tête")
	}

	attendus := [][]string{
		{"Brique", "Role"},
		{"Go", "analyse"},
		{"Compose", "dessin"},
	}
	for i, cellules := range attendus {
		if len(lignes[i].Cells) != len(cellules) {
			t.Fatalf("ligne %d : %d cellules, attendu %d : %q", i, len(lignes[i].Cells), len(cellules), lignes[i].Cells)
		}
		for j, c := range cellules {
			if lignes[i].Cells[j] != c {
				t.Errorf("ligne %d cellule %d = %q, attendu %q", i, j, lignes[i].Cells[j], c)
			}
		}
	}
}

func TestOdtMotLong(t *testing.T) {
	blocs := lireOdt(t, "mot-long.odt")

	var long markdown.Block
	for _, b := range blocs {
		if utf8.RuneCountInString(b.Text) > utf8.RuneCountInString(long.Text) {
			long = b
		}
	}

	if !strings.HasSuffix(long.Text, "…") {
		t.Fatalf("le mot démesuré n'a pas été tronqué : %d caractères", utf8.RuneCountInString(long.Text))
	}
	if long.Spans != nil {
		t.Error("les spans d'un bloc tronqué doivent être abandonnés")
	}

	blocContenant(t, blocs, "Avant le mot")
	blocContenant(t, blocs, "Apres le mot")
}

func TestOdtTexteRogne(t *testing.T) {
	for _, b := range lireOdt(t, "exemple.odt") {
		if b.Text != strings.TrimSpace(b.Text) {
			t.Errorf("bloc %s non rogné : %q", b.Kind, b.Text)
		}
		for _, c := range b.Cells {
			if c != strings.TrimSpace(c) {
				t.Errorf("cellule non rognée : %q", c)
			}
		}
	}
}

func TestOdtArchiveInvalide(t *testing.T) {
	_, err := Odt([]byte("ceci n'est pas une archive"))
	if err == nil {
		t.Fatal("aucune erreur sur un fichier qui n'est pas un ZIP")
	}
	if !strings.Contains(err.Error(), "["+CodeInvalid+"]") {
		t.Errorf("erreur sans le code attendu : %v", err)
	}
}

func TestOdtContenuAbsent(t *testing.T) {
	_, err := Odt(sansEntree(t, fixture(t, "exemple.odt"), "content.xml"))
	if err == nil {
		t.Fatal("aucune erreur sans content.xml")
	}
	if !strings.Contains(err.Error(), "["+CodeInvalid+"]") {
		t.Errorf("erreur sans le code attendu : %v", err)
	}
}

func lireOdt(t *testing.T, nom string) []markdown.Block {
	t.Helper()
	blocs, err := Odt(fixture(t, nom))
	if err != nil {
		t.Fatalf("analyse de %s : %v", nom, err)
	}
	return blocs
}
