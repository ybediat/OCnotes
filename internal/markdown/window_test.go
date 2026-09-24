package markdown

import (
	"math/rand"
	"strings"
	"testing"
	"unicode/utf16"
)

// fenetreLignes est la règle de fenêtrage que l'éditeur Android applique avant
// d'appeler ApplyFormatJSON (`fenetreMiseEnForme`, dans `FenetreMiseEnForme.kt`).
//
// La fenêtre va du début de la ligne qui précède celle de la borne basse de la
// sélection jusqu'à la fin de la ligne qui suit celle de la borne haute, sauts
// de ligne de bord exclus. Positions en unités UTF-16.
//
// Les deux implémentations doivent rester identiques : ce test prouve que la
// règle est sûre, pas que Kotlin la suit — c'est le rôle de
// `FenetreMiseEnFormeTest`, qui rejoue les mêmes cas limites.
func fenetreLignes(units []uint16, start, end int) (debut, fin int) {
	if start > end {
		start, end = end, start
	}
	debut = debutLigne(units, start)
	if debut > 0 {
		debut = debutLigne(units, debut-1)
	}
	fin = finLigne(units, end)
	if fin < len(units) {
		fin = finLigne(units, fin+1)
	}
	return debut, fin
}

func debutLigne(units []uint16, pos int) int {
	for pos > 0 && units[pos-1] != '\n' {
		pos--
	}
	return pos
}

func finLigne(units []uint16, pos int) int {
	for pos < len(units) && units[pos] != '\n' {
		pos++
	}
	return pos
}

// appliquerParFenetre rejoue le chemin de l'éditeur : extraire la fenêtre,
// mettre en forme la fenêtre seule, recoller.
func appliquerParFenetre(t *testing.T, d Doc, action Action) Doc {
	t.Helper()
	units := utf16.Encode([]rune(d.Text))
	start, end := clamp(d.Start, 0, len(units)), clamp(d.End, 0, len(units))
	debut, fin := fenetreLignes(units, start, end)

	fenetre, err := Apply(Doc{
		Text:  decodeUnits(units[debut:fin]),
		Start: start - debut,
		End:   end - debut,
	}, action)
	if err != nil {
		t.Fatalf("Apply(fenêtre, %s): %v", action, err)
	}
	return Doc{
		Text:  decodeUnits(units[:debut]) + fenetre.Text + decodeUnits(units[fin:]),
		Start: fenetre.Start + debut,
		End:   fenetre.End + debut,
	}
}

// Le fenêtrage est la seule optimisation qui puisse écrire autre chose que ce
// que l'utilisateur a demandé : une fenêtre trop étroite pour codeBlock, par
// exemple, rate le délimiteur de la ligne voisine et ajoute un bloc au lieu de
// le retirer (carnet : ANDROID-EDITEUR). D'où une égalité exacte, texte et
// sélection, sur des documents tirés au hasard dans un alphabet choisi pour
// provoquer les cas limites : marqueurs collés, lignes vides, délimiteurs de
// bloc, listes numérotées, accents et paires de substitution.
func TestFenetreEquivautAuDocumentEntier(t *testing.T) {
	morceaux := []string{
		"a", "mot", " ", "é", "😀", "\n", "\n", "\n", "*", "**", "~~", "`",
		"```", "# ", "## ", "- ", "- [ ] ", "1. ", "2. ", "> ", "[", "](", ")",
		"\t", "  ",
	}
	iterations := 20000
	if testing.Short() {
		iterations = 4000
	}
	alea := rand.New(rand.NewSource(24092026))

	for i := 0; i < iterations; i++ {
		var b strings.Builder
		for n := alea.Intn(40); n > 0; n-- {
			b.WriteString(morceaux[alea.Intn(len(morceaux))])
		}
		texte := b.String()
		longueur := len(utf16.Encode([]rune(texte)))
		start, end := alea.Intn(longueur+1), alea.Intn(longueur+1)
		if alea.Intn(3) == 0 {
			end = start // simple curseur, le cas le plus courant
		}
		d := Doc{Text: texte, Start: start, End: end}

		for _, action := range Actions() {
			entier, err := Apply(d, action)
			if err != nil {
				t.Fatalf("Apply(%s): %v", action, err)
			}
			fenetre := appliquerParFenetre(t, d, action)
			if fenetre != entier {
				t.Fatalf("%s sur %q [%d,%d] :\nentier  = %q [%d,%d]\nfenêtre = %q [%d,%d]",
					action, texte, start, end,
					entier.Text, entier.Start, entier.End,
					fenetre.Text, fenetre.Start, fenetre.End)
			}
		}
	}
}

// Sans la ligne de contexte, la propriété tombe : c'est ce qui justifie la
// règle, et ce qui montre que le test sait voir un fenêtrage trop étroit.
func TestFenetreSansContexteEchoueSurUnBlocDeCode(t *testing.T) {
	d := Doc{Text: "avant\n```\ncode\n```\naprès", Start: 11, End: 11}
	entier, err := Apply(d, ActionCodeBlock)
	if err != nil {
		t.Fatal(err)
	}
	// Fenêtre réduite à la seule ligne du curseur.
	etroite, err := Apply(Doc{Text: "code", Start: 1, End: 1}, ActionCodeBlock)
	if err != nil {
		t.Fatal(err)
	}
	recolle := "avant\n```\n" + etroite.Text + "\n```\naprès"
	if recolle == entier.Text {
		t.Fatal("une fenêtre sans contexte devrait rater le retrait du bloc")
	}
	if entier.Text != "avant\ncode\naprès" {
		t.Errorf("retrait du bloc sur le document entier = %q", entier.Text)
	}
}
