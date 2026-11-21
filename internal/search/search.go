// Package search cherche un terme dans le texte d'une note.
//
// Recherche de sous-chaîne simple, sans expression régulière ni index : le
// texte est replié (casse, accents, espaces insécables) puis parcouru. Le
// repli est le même que celui de `replie()` côté Kotlin, qui compare les noms ;
// testdata/cas-repli.txt tient les deux ensemble.
package search

import (
	"regexp"
	"sort"
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
	// Les adresses des liens ne se voient pas dans la note rendue : un terme
	// qui n'y apparaît que dans « (https://…) » ne la fait pas trouver.
	cachees := adressesDeLiens(text)

	premier, count := -1, 0
	for depuis := 0; ; {
		i := strings.Index(texte[depuis:], cible)
		if i < 0 {
			break
		}
		pos := depuis + i
		depuis = pos + len(cible)
		if dansUneAdresse(cachees, int(origine[pos])) {
			continue
		}
		if premier < 0 {
			premier = pos
		}
		count++
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
	// Le retrait d'une liste ou d'un bloc ne dit rien et coûte de la place,
	// pas plus que la syntaxe Markdown : l'extrait se lit comme la note
	// rendue, sans « - [ ] », « ## », « ** » ni adresse de lien.
	avant := []rune(nettoyerAvant(strings.TrimLeftFunc(text[ligneDebut:debut], unicode.IsSpace)))
	terme := []rune(text[debut:fin])
	apres := []rune(nettoyerApres(strings.TrimRightFunc(text[fin:ligneFin], unicode.IsSpace)))

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

// --- Syntaxe Markdown dans la recherche --------------------------------------

// plage est un intervalle [debut, fin) d'octets du texte d'origine.
type plage struct{ debut, fin int }

// adressesDeLiens repère les adresses des liens et des images en ligne,
// « [texte](adresse) », et la fin des lignes de définition de lien,
// « [id]: adresse ». Ce qui est entre les parenthèses ne s'affiche pas.
//
// Repérage volontairement simple, ligne par ligne : une parenthèse fermante
// absente avant la fin de ligne veut dire que ce n'était pas un lien, et rien
// n'est caché. Mieux vaut trouver une adresse de trop que manquer du texte.
func adressesDeLiens(text string) []plage {
	var out []plage
	ligne := 0
	// Dans un bloc de code clôturé, « ](…) » est du code, affiché tel quel :
	// rien n'y est caché.
	dansClotureCode := false
	for ligne <= len(text) {
		finLigne := strings.IndexByte(text[ligne:], '\n')
		if finLigne < 0 {
			finLigne = len(text)
		} else {
			finLigne += ligne
		}
		l := text[ligne:finLigne]
		if estCloture(l) || dansClotureCode {
			if estCloture(l) {
				dansClotureCode = !dansClotureCode
			}
			ligne = finLigne + 1
			continue
		}
		// La regex n'est essayée que sur une ligne qui commence par « [ » :
		// les autres lignes n'en paient pas le prix.
		if m := definitionPossible(l); m != nil {
			out = append(out, plage{ligne + m[1], finLigne})
		} else {
			for depuis := 0; ; {
				i := strings.Index(l[depuis:], "](")
				if i < 0 {
					break
				}
				ouvre := depuis + i + 2
				ferme := strings.IndexByte(l[ouvre:], ')')
				if ferme < 0 {
					break
				}
				// Un « ](…) » écrit dans du code en ligne reste visible.
				if !dansCodeEnLigne(l, depuis+i) {
					out = append(out, plage{ligne + ouvre, ligne + ouvre + ferme})
				}
				depuis = ouvre + ferme + 1
			}
		}
		ligne = finLigne + 1
	}
	return out
}

// definitionPossible renvoie les bornes de « [id]: » en tête de ligne, ou nil.
func definitionPossible(l string) []int {
	t := strings.TrimLeft(l, " ")
	if t == "" || t[0] != '[' {
		return nil
	}
	return definitionDeLien.FindStringIndex(l)
}

// dansUneAdresse dit si l'octet pos tombe dans une des plages, rangées dans
// l'ordre du texte.
func dansUneAdresse(plages []plage, pos int) bool {
	i := sort.Search(len(plages), func(i int) bool { return plages[i].fin > pos })
	return i < len(plages) && plages[i].debut <= pos
}

var (
	// « [id]: adresse » en début de ligne, jusqu'à l'adresse.
	definitionDeLien = regexp.MustCompile(`^\s{0,3}\[[^\]]+\]:\s*`)
	// Marqueurs de bloc en tête de ligne : citation, titre, puce, numéro,
	// case à cocher. Répétés : « > - [ ] » s'enlève en entier.
	marqueursDeBloc = regexp.MustCompile(`^(?:(?:>\s*)|(?:#{1,6}\s+)|(?:[-*+]\s+)|(?:\d{1,9}[.)]\s+)|(?:\[[ xX]\]\s+))+`)
	// Lien ou image complet : on garde le texte, on retire l'adresse.
	lienComplet = regexp.MustCompile(`!?\[([^\]]*)\]\([^)]*\)`)
	// Fin d'un lien dont le texte contient le terme : « ](adresse) ».
	finDeLien = regexp.MustCompile(`^[^\[]*?\]\([^)]*\)`)
	// Début d'un lien dont le texte contient le terme : « [ » ou « ![ ».
	debutDeLien = regexp.MustCompile(`!?\[[^\]]*$`)
	// Gras, barré, code : marqueurs doubles ou accent grave, sans ambiguïté.
	// L'italique simple (« * », « _ ») reste : « snake_case » n'est pas une
	// mise en forme.
	marqueursEnLigne = regexp.MustCompile("\\*\\*|__|~~|`")
)

// nettoyerAvant retire la syntaxe Markdown de ce qui précède le terme sur sa
// ligne.
func nettoyerAvant(s string) string {
	s = marqueursDeBloc.ReplaceAllString(s, "")
	s = lienComplet.ReplaceAllString(s, "$1")
	// Le « [ » d'un lien qui s'ouvre avant le terme : on garde le texte qui
	// suit le crochet.
	if m := debutDeLien.FindStringIndex(s); m != nil {
		crochet := strings.IndexByte(s[m[0]:], '[')
		s = s[:m[0]] + s[m[0]+crochet+1:]
	}
	return retirerItalique(marqueursEnLigne.ReplaceAllString(s, ""), false, true)
}

// nettoyerApres retire la syntaxe Markdown de ce qui suit le terme.
func nettoyerApres(s string) string {
	// « ](adresse) » qui ferme un lien ouvert avant le terme : on garde le
	// texte qui précède le crochet.
	if m := finDeLien.FindStringIndex(s); m != nil {
		crochet := strings.IndexByte(s, ']')
		s = s[:crochet] + s[m[1]:]
	}
	s = lienComplet.ReplaceAllString(s, "$1")
	return retirerItalique(marqueursEnLigne.ReplaceAllString(s, ""), true, false)
}

// estCloture dit si une ligne ouvre ou ferme un bloc de code clôturé : trois
// accents graves ou trois tildes, après au plus trois espaces.
func estCloture(l string) bool {
	t := strings.TrimLeft(l, " ")
	if len(l)-len(t) > 3 {
		return false
	}
	return strings.HasPrefix(t, "```") || strings.HasPrefix(t, "~~~")
}

// dansCodeEnLigne dit si la position i d'une ligne tombe dans du code en
// ligne : un nombre impair d'accents graves la précède. Approximation qui
// suffit ici : un délimiteur de deux accents graves compte pour deux et s'annule.
func dansCodeEnLigne(l string, i int) bool {
	return strings.Count(l[:i], "`")%2 == 1
}

// retirerItalique enlève les « * » et « _ » qui marquent une emphase simple,
// les doubles ayant déjà été retirés.
//
// Un délimiteur est retiré quand il touche un mot d'un seul côté : « *mot* »
// devient « mot ». Entre deux espaces (« 5 * 3 ») ou au milieu d'un mot
// (« nom_de_fichier »), il est gardé. collAvant et collApres disent si le
// début ou la fin du texte touchent le terme trouvé, qui compte alors comme un
// mot : dans « le *terme* », le texte avant le terme finit par « * ».
func retirerItalique(s string, collAvant, collApres bool) string {
	if !strings.ContainsAny(s, "*_") {
		return s
	}
	r := []rune(s)
	delimiteur := func(c rune) bool { return c == '*' || c == '_' }
	// estMot regarde le premier caractère qui n'est pas un délimiteur, dans
	// le sens pas : « *_mot_* » se juge sur « mot ».
	estMot := func(i, pas int, bord bool) bool {
		for i >= 0 && i < len(r) && delimiteur(r[i]) {
			i += pas
		}
		if i < 0 || i >= len(r) {
			return bord
		}
		return unicode.IsLetter(r[i]) || unicode.IsDigit(r[i])
	}
	var b strings.Builder
	for i, c := range r {
		if delimiteur(c) && estMot(i-1, -1, collAvant) != estMot(i+1, 1, collApres) {
			continue
		}
		b.WriteRune(c)
	}
	return b.String()
}
