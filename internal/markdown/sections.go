package markdown

import (
	"reflect"
	"strings"
	"unicode/utf16"

	"github.com/yuin/goldmark/ast"
	"github.com/yuin/goldmark/text"
)

// MaxSectionLines borne le nombre de lignes qu'une section confie à un champ
// de saisie.
//
// Ce n'est pas un réglage de confort mais une mesure. L'enregistrement de la
// display list d'un TextField coûte 0,14 à 0,24 ms par ligne — constaté sur
// appareil, de 445 octets à 295 ko — et le budget d'une image est de 16 ms.
// Au-delà d'environ 80 lignes, l'éditeur ne tient plus la cadence : à 76
// lignes, 90 % des images sont déjà en retard.
//
// Relevés complets en section 7 bis de docs/ARCHITECTURE.md, protocole dans
// scripts/banc-editeur.ps1.
const MaxSectionLines = 80

// targetSectionLines est la taille visée plutôt que la limite dure : la
// moitié de MaxSectionLines, pour laisser de la marge au reste de l'image
// quand la structure du document permet de choisir librement où couper.
const targetSectionLines = MaxSectionLines / 2

// Section est une tranche éditable du document.
//
// Start et End sont en **unités de code UTF-16**, comme Doc et comme Span.
// C'est l'unité de TextRange dans Compose, et celle de String.substring en
// Kotlin : l'interface découpe donc le texte elle-même, sans conversion, et le
// texte n'a pas à traverser la frontière gomobile.
type Section struct {
	Start int
	End   int
}

// Sections découpe un texte en tranches qu'un champ de saisie peut porter.
//
// Le contrat est décrit par sections_test.go, qui est la spécification de
// cette fonction :
//
//   - les sections pavent le document, sans trou ni recouvrement ;
//   - recoller une section par son propre contenu rend le document identique ;
//   - le rendu section par section est identique au rendu du document entier,
//     ce qui est la formulation générale de « ne pas couper au milieu d'une
//     construction multiligne » ;
//   - une section ne dépasse pas MaxSectionLines lignes, sauf quand la règle
//     précédente l'interdit — un rendu juste passe avant une section rapide.
//
// L'algorithme : le texte est d'abord découpé en lignes, en unités UTF-16.
// Une coupure n'est *candidate* que là où elle tombe juste après une ligne
// vide — jamais au milieu d'un paragraphe. Parmi les candidates qui tiennent
// sous MaxSectionLines, on retient celle la plus proche de targetSectionLines,
// puis on la *vérifie* : la section coupée là, rendue seule, doit produire
// exactement les mêmes blocs que la suite du document rendue seule aussi. Si
// ce n'est pas le cas la coupure candidate suivante est essayée, jusqu'à la
// fin du document au pire : une section trop longue plutôt qu'un rendu faux.
//
// La vérification ne porte que sur une **fenêtre bornée** après la coupure —
// de l'ordre de MaxSectionLines lignes — et non sur le reste du document
// entier : une construction qui enjambe une coupure (liste, clôture, tableau,
// citation) est locale, et le désaccord se voit dans la fenêtre. Rendre tout
// le reste du document à chaque coupure candidate serait quadratique — mesuré
// à plus d'une seconde sur une note de 295 ko.
//
// Les définitions de lien en référence sont l'exception à cette localité :
// une définition peut être à l'autre bout du document. Elles sont donc
// collectées une fois, en dehors de la fenêtre, et préfixées au texte rendu
// pour la vérification — jamais au texte édité, dont les bornes Start/End
// restent la tranche exacte. Une définition seule ne produit aucun bloc, donc
// ce préfixe ne change rien à ce qui est comparé, sinon que les références
// qu'il porte se résolvent. Voir RenderSection, qui applique le même
// préambule à l'affichage.
func Sections(text string) []Section {
	units := utf16.Encode([]rune(text))
	lignes := decoupeEnLignes(units)
	defs := definitionsDuDocument(text)

	var candidats []int
	for k := 1; k < len(lignes); k++ {
		if lignes[k-1].vide {
			candidats = append(candidats, k)
		}
	}

	var sections []Section
	pos := 0
	idxLigne := 0
	for {
		fin, prochainIdx := choisirCoupure(units, lignes, candidats, idxLigne, pos, defs)
		sections = append(sections, Section{Start: pos, End: fin})
		if prochainIdx >= len(lignes) {
			break
		}
		pos = fin
		idxLigne = prochainIdx
	}
	return sections
}

// RenderSection rend une section avec les définitions de lien du document en
// préambule, pour que les références qu'elle utilise se résolvent même si
// leur définition est ailleurs dans le document. Le texte édité, lui, reste
// la tranche exacte que portent Section.Start et Section.End, sans préambule.
//
// Elle réanalyse le document entier pour y retrouver ces définitions : un
// coût raisonnable pour rendre une seule section — après un commit d'édition,
// par exemple — mais ruineux répété pour chaque section d'une note à
// l'ouverture. C'est ce que fait RenderSections, qui calcule les définitions
// une seule fois pour tout le document.
func RenderSection(doc string, s Section) []Block {
	return trancheEtRendu(utf16.Encode([]rune(doc)), s, definitionsDuDocument(doc))
}

// RenderSections découpe un document et rend toutes ses sections en une
// passe : c'est ce que la façade appelle à l'ouverture d'une note.
//
// La distinction avec RenderSection n'est pas cosmétique : les définitions de
// lien en référence sont calculées une seule fois pour tout le document, puis
// réutilisées pour chaque section, plutôt que recalculées à chaque section
// comme le ferait une boucle sur RenderSection. Mesuré sur la note de test de
// 295 ko : 495 ms en boucle naïve contre 228 ms ici, pour les mêmes 67
// sections — la différence est le parcours de goldmark, payé 67 fois dans un
// cas et une seule dans l'autre.
func RenderSections(doc string) ([]Section, [][]Block) {
	sections := Sections(doc)
	units := utf16.Encode([]rune(doc))
	defs := definitionsDuDocument(doc)

	blocs := make([][]Block, 0, len(sections))
	for _, s := range sections {
		blocs = append(blocs, trancheEtRendu(units, s, defs))
	}
	return sections, blocs
}

// trancheEtRendu isole la tranche d'une section dans un texte déjà encodé en
// UTF-16, puis la rend avec le préambule de définitions donné. Partagée par
// RenderSection et RenderSections, pour que les deux découpent et bornent
// leurs indices de la même façon.
func trancheEtRendu(units []uint16, s Section, defs string) []Block {
	n := len(units)
	debut, fin := clamp(s.Start, 0, n), clamp(s.End, 0, n)
	if debut > fin {
		debut, fin = fin, debut
	}
	return rendreAvecDefinitions(defs, unitesVersTexte(units[debut:fin]))
}

// ligne est une tranche de texte entre deux retours à la ligne, en unités
// UTF-16 — les mêmes unités que Section.
type ligne struct {
	start, end int // contenu de la ligne, sans le retour à la ligne
	vide       bool
}

// decoupeEnLignes fabrique la liste des lignes d'un texte déjà encodé en
// UTF-16, avec leurs bornes.
//
// Un retour à la ligne final ne produit pas de ligne vide supplémentaire : un
// texte qui se termine par "\n" a autant de lignes que de contenu, comme le
// compte nombreDeLignes dans sections_test.go — c'est ce comptage que
// TestSectionsBornentLaTaille applique aux sections produites ici, les deux
// doivent donc s'accorder. Un texte entièrement vide produit une ligne vide
// unique, pour qu'un document vide ait toujours au moins une section.
func decoupeEnLignes(units []uint16) []ligne {
	n := len(units)
	var lignes []ligne
	debut := 0
	for i := 0; i < n; i++ {
		if units[i] == '\n' {
			contenu := units[debut:i]
			lignes = append(lignes, ligne{start: debut, end: i, vide: estVide(contenu)})
			debut = i + 1
		}
	}
	if debut < n || len(lignes) == 0 {
		contenu := units[debut:n]
		lignes = append(lignes, ligne{start: debut, end: n, vide: estVide(contenu)})
	}
	return lignes
}

func estVide(u []uint16) bool {
	return strings.TrimSpace(string(utf16.Decode(u))) == ""
}

// choisirCoupure trouve où finir la section qui commence à la ligne idxLigne
// (offset pos). Elle renvoie l'offset UTF-16 de fin et l'indice de la ligne
// suivante — égal à len(lignes) quand c'est la fin du document.
//
// Parmi les coupures candidates qui tiennent sous MaxSectionLines, on part de
// la plus proche de targetSectionLines et on la vérifie par le rendu ; en cas
// d'échec on essaie la candidate suivante, plus loin dans le document — jamais
// une plus proche, puisqu'une section trop longue est acceptable et un rendu
// faux ne l'est pas. La fin du document est toujours une candidate de repli,
// et elle est toujours valide.
func choisirCoupure(units []uint16, lignes []ligne, candidats []int, idxLigne, pos int, defs string) (int, int) {
	total := len(lignes)
	cible := idxLigne + targetSectionLines
	max := idxLigne + MaxSectionLines

	var utilisables []int
	for _, c := range candidats {
		if c > idxLigne {
			utilisables = append(utilisables, c)
		}
	}
	utilisables = append(utilisables, total) // repli : fin de document

	depart := 0
	trouve := false
	for i, c := range utilisables {
		if c > max {
			break
		}
		if !trouve || abs(c-cible) < abs(utilisables[depart]-cible) {
			depart, trouve = i, true
		}
	}

	for _, c := range utilisables[depart:] {
		fin := len(units)
		if c < total {
			fin = lignes[c].start
		}
		if c >= total || coupureValide(units, lignes, pos, fin, c, defs) {
			return fin, c
		}
	}
	// Jamais atteint : "total" est toujours une candidate valide, et c'est la
	// dernière de la liste.
	return len(units), total
}

// coupureValide vérifie qu'une coupure ne change aucun bloc rendu, dans une
// fenêtre bornée après la coupure : la section rendue seule, suivie du début
// du reste du document rendu seul aussi, doit produire exactement les blocs
// que produirait cette même fenêtre rendue d'un bloc. C'est la formulation
// générale de « ne pas couper une construction multiligne » — elle couvre les
// listes, les clôtures de code, les tableaux, et tout ce qu'on n'aurait pas
// pensé à énumérer. Une construction qui enjambe la coupure diverge dès la
// fenêtre : si elle continue au-delà, la moitié qui reste dans la fenêtre est
// déjà assez pour que la comparaison des deux côtés ne concorde plus.
//
// Les définitions de lien en référence sont préfixées aux trois rendus
// comparés — jamais au texte édité — pour que les références qu'utilise la
// section se résolvent même si leur définition est hors fenêtre.
func coupureValide(units []uint16, lignes []ligne, pos, cut, idxCut int, defs string) bool {
	finFenetreIdx := idxCut + MaxSectionLines
	finFenetre := len(units)
	if finFenetreIdx < len(lignes) {
		finFenetre = lignes[finFenetreIdx].start
	}
	if cut >= finFenetre {
		return true
	}

	avant := rendreAvecDefinitions(defs, unitesVersTexte(units[pos:cut]))
	apres := rendreAvecDefinitions(defs, unitesVersTexte(units[cut:finFenetre]))
	ensemble := rendreAvecDefinitions(defs, unitesVersTexte(units[pos:finFenetre]))

	combine := append(append([]Block{}, avant...), apres...)
	return reflect.DeepEqual(combine, ensemble)
}

// rendreAvecDefinitions rend un texte de section avec les définitions de lien
// du document en préambule.
//
// Une définition rendue seule ne produit aucun bloc — elle est de la matière
// d'analyse, pas d'affichage — donc ce préambule ne fait qu'ajouter la
// résolution des références que la section utilise, sans introduire de bloc
// qui n'existerait pas dans le rendu de la section seule.
func rendreAvecDefinitions(defs, section string) []Block {
	if defs == "" {
		return Render(section)
	}
	return Render(defs + "\n" + section)
}

// definitionsDuDocument analyse le document une fois et en extrait les
// définitions de lien en référence, sous une forme réduite à ce qui compte
// pour la résolution — étiquette et destination, sans le titre optionnel que
// Span ne porte pas.
//
// Une définition de lien en référence peut être n'importe où dans le document
// — souvent en bas, loin de ses usages — donc la collecter exige de regarder
// le document entier une fois. C'est un coût linéaire, payé une seule fois par
// appel à Sections ou RenderSection, pas à chaque coupure candidate : c'est
// ce qui le distingue de la vérification par fenêtre, qui elle est locale et
// répétée.
func definitionsDuDocument(source string) string {
	if strings.TrimSpace(source) == "" {
		return ""
	}
	src := []byte(source)
	doc := parser.Parser().Parse(text.NewReader(src))

	var b strings.Builder
	var parcourir func(n ast.Node)
	parcourir = func(n ast.Node) {
		for c := n.FirstChild(); c != nil; c = c.NextSibling() {
			if d, ok := c.(*ast.LinkReferenceDefinition); ok {
				b.WriteByte('[')
				b.Write(d.Label)
				b.WriteString("]: ")
				b.Write(d.Destination)
				b.WriteByte('\n')
				continue
			}
			parcourir(c)
		}
	}
	parcourir(doc)
	return b.String()
}

func unitesVersTexte(u []uint16) string {
	return string(utf16.Decode(u))
}

func abs(x int) int {
	if x < 0 {
		return -x
	}
	return x
}
