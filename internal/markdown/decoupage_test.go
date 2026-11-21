package markdown

import (
	"fmt"
	"strings"
	"testing"
	"unicode/utf16"
)

// entreBornes extrait [debut, fin) d'un texte, en unités UTF-16 comme les spans.
func entreBornes(t *testing.T, texte string, debut, fin int) string {
	t.Helper()
	u := utf16.Encode([]rune(texte))
	if debut < 0 || fin > len(u) || debut > fin {
		t.Fatalf("bornes [%d, %d) hors du texte de %d unités", debut, fin, len(u))
	}
	return string(utf16.Decode(u[debut:fin]))
}

// lignesParagraphe renvoie n lignes accentuées, avec un emoji : les bornes
// UTF-16 n'y coïncident ni avec les octets ni avec les runes.
func lignesParagraphe(n int) []string {
	lignes := make([]string, n)
	for i := range lignes {
		lignes[i] = fmt.Sprintf("Ligne %03d é à 😀 ü", i)
	}
	return lignes
}

func TestDecoupageParagrapheNePerdRien(t *testing.T) {
	lignes := lignesParagraphe(130)
	blocs := Render(strings.Join(lignes, "\n"))

	if len(blocs) < 3 {
		t.Fatalf("un paragraphe de 130 lignes devrait donner au moins 3 morceaux, reçu %d", len(blocs))
	}
	var tout strings.Builder
	for i, b := range blocs {
		if b.Kind != KindParagraph {
			t.Fatalf("morceau %d : genre %q, attendu paragraph", i, b.Kind)
		}
		if got := strings.Count(b.Text, "\n"); got > maxPlainLines {
			t.Errorf("morceau %d : %d retours, au-delà de %d", i, got, maxPlainLines)
		}
		if b.Cont != (i > 0) {
			t.Errorf("morceau %d : Cont=%v", i, b.Cont)
		}
		tout.WriteString(b.Text)
	}
	if tout.String() != strings.Join(lignes, "\n") {
		t.Fatal("la concaténation des morceaux ne rend pas le texte du paragraphe")
	}
}

func TestDecoupageSourceSurLePremierMorceauSeul(t *testing.T) {
	blocs := Render("Intro\n\n" + strings.Join(lignesParagraphe(90), "\n"))

	// Le premier bloc est le paragraphe « Intro » ; le gros paragraphe suit.
	morceaux := blocs[1:]
	if morceaux[0].Source == nil || *morceaux[0].Source != len("Intro\n\n") {
		t.Fatalf("premier morceau : Source=%v, attendu %d", morceaux[0].Source, len("Intro\n\n"))
	}
	for i, m := range morceaux[1:] {
		if m.Source != nil {
			t.Errorf("morceau %d : Source=%d, attendu absente", i+1, *m.Source)
		}
	}
}

func TestDecoupageSpanACheval(t *testing.T) {
	lignes := lignesParagraphe(60)
	// Un gras de la ligne 38 à la ligne 42, donc à cheval sur la coupure à 40.
	lignes[38] = "**" + lignes[38]
	lignes[42] = lignes[42] + "**"
	blocs := Render(strings.Join(lignes, "\n"))
	if len(blocs) != 2 {
		t.Fatalf("attendu 2 morceaux, reçu %d", len(blocs))
	}

	// Ce qui est en gras, morceau par morceau, recollé, doit être exactement
	// le texte des lignes 38 à 42 sans les marqueurs.
	var gras strings.Builder
	for _, b := range blocs {
		n := 0
		for _, s := range b.Spans {
			if s.Style == StyleBold {
				gras.WriteString(entreBornes(t, b.Text, s.Start, s.End))
				n++
			}
		}
		if n != 1 {
			t.Errorf("chaque morceau doit porter une part du gras, en voici %d", n)
		}
	}
	attendu := strings.TrimPrefix(strings.Join(lignes[38:43], "\n"), "**")
	attendu = strings.TrimSuffix(attendu, "**")
	if gras.String() != attendu {
		t.Fatalf("gras recollé :\n%q\nattendu :\n%q", gras.String(), attendu)
	}
}

func TestDecoupageTacheGardeSaCase(t *testing.T) {
	src := "- [x] " + strings.Join(lignesParagraphe(70), "\n  ")
	blocs := Render(src)
	if len(blocs) < 2 {
		t.Fatalf("attendu au moins 2 morceaux, reçu %d", len(blocs))
	}
	for i, b := range blocs {
		if b.Kind != KindTask || !b.Checked {
			t.Fatalf("morceau %d : %q coché=%v", i, b.Kind, b.Checked)
		}
		// Comme après une image : tous les morceaux portent la case, pour
		// que la bascule les coche ensemble.
		if b.Source == nil || *b.Source != 2 {
			t.Errorf("morceau %d : Source=%v, attendu la case en 2", i, b.Source)
		}
	}
}

func TestDecoupageBlocDeCode(t *testing.T) {
	lignes := make([]string, 100)
	for i := range lignes {
		lignes[i] = fmt.Sprintf("x%d := %d // é😀", i, i)
		// Des lignes vides où couper ; pas en dernière ligne, que goldmark
		// retire d'un bloc de code.
		if i%25 == 24 && i < len(lignes)-1 {
			lignes[i] = ""
		}
	}
	code := strings.Join(lignes, "\n")
	blocs := Render("```go\n" + code + "\n```\n")

	var tout strings.Builder
	for i, b := range blocs {
		if b.Kind != KindCode || b.Lang != "go" {
			t.Fatalf("morceau %d : %q lang=%q", i, b.Kind, b.Lang)
		}
		if b.Cont != (i > 0) {
			t.Errorf("morceau %d : Cont=%v", i, b.Cont)
		}
		tout.WriteString(b.Text)
	}
	if len(blocs) < 3 {
		t.Fatalf("attendu au moins 3 morceaux, reçu %d", len(blocs))
	}
	if tout.String() != code {
		t.Fatal("la concaténation des morceaux ne rend pas le code")
	}
	// La coupure se cherche sur une ligne vide : le premier morceau s'arrête
	// juste après la ligne vide d'indice 24.
	if !strings.HasSuffix(blocs[0].Text, "\n\n") {
		t.Errorf("premier morceau coupé hors d'une ligne vide : %q", blocs[0].Text[len(blocs[0].Text)-20:])
	}
}

func TestDecoupageLaissePetitsBlocs(t *testing.T) {
	src := strings.Join(lignesParagraphe(maxPlainLines), "\n")
	blocs := Render(src)
	if len(blocs) != 1 || blocs[0].Cont {
		t.Fatalf("un paragraphe de %d lignes ne doit pas être découpé : %d blocs", maxPlainLines, len(blocs))
	}
}
