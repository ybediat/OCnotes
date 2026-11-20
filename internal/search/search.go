// Package search cherche un terme dans le texte d'une note.
//
// Recherche de sous-chaîne simple, sans expression régulière ni index : le
// texte est replié (casse, accents, espaces insécables) puis parcouru. Le
// repli est le même que celui de `replie()` côté Kotlin, qui compare les noms ;
// testdata/cas-repli.txt tient les deux ensemble.
package search

import (
	"strings"
	"unicode"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/ybediat/OpenNote/internal/markdown"
)

// Match décrit la première occurrence trouvée dans un texte.
type Match struct {
	// Excerpt est la ligne de l'occurrence, sans son retrait, recentrée et
	// bornée si elle est longue ; une ellipse marque chaque côté coupé.
	Excerpt string
	// Start et End bornent le terme dans Excerpt, en unités UTF-16 : c'est
	// l'unité de Compose, Kotlin pose le gras sans conversion.
	Start int
	End   int
}

// MinQueryLength est le nombre de caractères en dessous duquel une requête ne
// fouille pas le texte : une lettre seule correspondrait à presque tout.
const MinQueryLength = 2

// largeurParDefaut borne l'extrait quand l'appelant ne dit rien : une ligne
// d'écran de téléphone, à peu près.
const largeurParDefaut = 120

// Fold replie un texte pour la comparaison : minuscules, sans accents, espaces
// insécables et tabulations ramenés à une espace simple.
func Fold(s string) string {
	plie, _ := replier(s, false)
	return string(plie)
}

// replier rend le texte replié et, si demandé, pour chacun de ses octets la
// position (en octets) du caractère d'origine dont il provient.
//
// Cette correspondance est ce qui permet de chercher dans le texte replié et
// de découper l'extrait dans l'original : un caractère peut disparaître (une
// marque combinante) ou en donner deux (« œ » → « oe »).
func replier(s string, avecOrigine bool) ([]byte, []int32) {
	plie := make([]byte, 0, len(s))
	var origine []int32
	if avecOrigine {
		origine = make([]int32, 0, len(s))
	}
	ajoute := func(r rune, depuis int) {
		avant := len(plie)
		plie = utf8.AppendRune(plie, r)
		for ; avecOrigine && avant < len(plie); avant++ {
			origine = append(origine, int32(depuis))
		}
	}

	for i, r := range s {
		// Chemin rapide : l'immense majorité d'une note est de l'ASCII.
		if r < utf8.RuneSelf {
			switch {
			case 'A' <= r && r <= 'Z':
				r += 'a' - 'A'
			case r == '\t':
				r = ' '
			}
			ajoute(r, i)
			continue
		}

		// Un accent déjà décomposé suit sa lettre : il disparaît.
		if unicode.Is(unicode.Mn, r) {
			continue
		}
		if base, ok := lettreDeBase[r]; ok {
			r = base
		}
		r = unicode.ToLower(r)

		// Lettres sans décomposition Unicode, qu'un clavier français ou
		// européen ne permet pas toujours de taper : on les écrit comme on les
		// tape à défaut. La même liste vit dans replie() côté Kotlin.
		switch r {
		case 'œ':
			ajoute('o', i)
			ajoute('e', i)
			continue
		case 'æ':
			ajoute('a', i)
			ajoute('e', i)
			continue
		case 'ß':
			ajoute('s', i)
			ajoute('s', i)
			continue
		case 'ø':
			r = 'o'
		case 'ł':
			r = 'l'
		case 'đ':
			r = 'd'
		}
		// Espace insécable, fine insécable du français, etc. Pas les fins de
		// ligne : un terme à cheval sur deux lignes n'est pas un terme.
		if unicode.Is(unicode.Zs, r) {
			r = ' '
		}
		ajoute(r, i)
	}
	return plie, origine
}

// Find compte les occurrences de query dans text et décrit la première.
//
// max borne la longueur de l'extrait, en caractères (120 si nul). Une requête
// de moins de MinQueryLength caractères ne trouve rien. Un terme ne franchit
// jamais une fin de ligne.
func Find(text, query string, max int) (int, Match) {
	if max <= 0 {
		max = largeurParDefaut
	}
	query = strings.TrimSpace(query)
	if utf8.RuneCountInString(query) < MinQueryLength {
		return 0, Match{}
	}
	cible := Fold(query)
	if cible == "" {
		return 0, Match{}
	}

	plie, origine := replier(text, true)
	texte := string(plie)

	premier, count := -1, 0
	for depuis := 0; ; {
		i := strings.Index(texte[depuis:], cible)
		if i < 0 {
			break
		}
		if premier < 0 {
			premier = depuis + i
		}
		count++
		depuis += i + len(cible)
	}
	if count == 0 {
		return 0, Match{}
	}

	// Bornes du terme dans l'original. La fin couvre le dernier caractère et
	// les accents décomposés qui le suivent : sans eux, le gras s'arrêterait
	// entre une lettre et son accent.
	debut := int(origine[premier])
	fin := int(origine[premier+len(cible)-1])
	_, taille := utf8.DecodeRuneInString(text[fin:])
	fin += taille
	for fin < len(text) {
		r, taille := utf8.DecodeRuneInString(text[fin:])
		if !unicode.Is(unicode.Mn, r) {
			break
		}
		fin += taille
	}

	return count, extrait(text, debut, fin, max)
}

// extrait découpe la ligne qui contient [debut, fin) et y situe le terme.
func extrait(text string, debut, fin, largeur int) Match {
	ligneDebut := strings.LastIndexByte(text[:debut], '\n') + 1
	ligneFin := len(text)
	if n := strings.IndexByte(text[debut:], '\n'); n >= 0 {
		ligneFin = debut + n
	}
	// Le retrait d'une liste ou d'un bloc ne dit rien et coûte de la place.
	avant := []rune(strings.TrimLeftFunc(text[ligneDebut:debut], unicode.IsSpace))
	terme := []rune(text[debut:fin])
	apres := []rune(strings.TrimRightFunc(text[fin:ligneFin], unicode.IsSpace))

	ligne := make([]rune, 0, len(avant)+len(terme)+len(apres))
	ligne = append(append(append(ligne, avant...), terme...), apres...)
	termeDebut, termeFin := len(avant), len(avant)+len(terme)

	// Fenêtre de largeur caractères, le terme au milieu autant que possible ; un
	// terme plus long que la fenêtre en occupe le début.
	fenDebut := 0
	if len(ligne) > largeur {
		fenDebut = termeDebut - (largeur-len(terme))/2
		if len(terme) >= largeur {
			fenDebut = termeDebut
		}
		fenDebut = min(fenDebut, len(ligne)-largeur)
		fenDebut = max(fenDebut, 0)
	}
	fenFin := min(fenDebut+largeur, len(ligne))

	var b strings.Builder
	start := 0
	if fenDebut > 0 {
		b.WriteString("…")
		start = 1
	}
	b.WriteString(string(ligne[fenDebut:fenFin]))
	if fenFin < len(ligne) {
		b.WriteString("…")
	}
	start += unites(ligne[fenDebut:termeDebut])
	end := start + unites(ligne[termeDebut:min(termeFin, fenFin)])

	// Garde-fou du mot sans espace (CLAUDE.md) : la fenêtre est trop étroite
	// pour qu'un mot démesuré y tienne, mais si cela arrivait, l'extrait
	// raccourci perdrait ses bornes, on renonce au gras plutôt qu'à la borne.
	m := Match{Excerpt: b.String(), Start: start, End: end}
	if court, coupe := markdown.ShortenLongWords(m.Excerpt); coupe {
		return Match{Excerpt: court}
	}
	return m
}

// unites compte les unités UTF-16 d'une suite de caractères.
func unites(r []rune) int {
	n := 0
	for _, c := range r {
		n += utf16.RuneLen(c)
	}
	return n
}
