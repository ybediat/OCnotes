package markdown

import (
	"fmt"
	"reflect"
	"strings"
	"testing"
	"time"
	"unicode/utf16"
)

// longueurUTF16 compte en unités de code, l'unité des bornes de Section.
func longueurUTF16(s string) int {
	return len(utf16.Encode([]rune(s)))
}

// trancheUTF16 découpe comme le fera Kotlin : String.substring y travaille sur
// les mêmes unités. Un découpage en octets ou en runes se décalerait dès la
// première lettre accentuée.
func trancheUTF16(s string, start, end int) string {
	u := utf16.Encode([]rune(s))
	return string(utf16.Decode(u[start:end]))
}

func nombreDeLignes(s string) int {
	if s == "" {
		return 0
	}
	return strings.Count(strings.TrimSuffix(s, "\n"), "\n") + 1
}

// prose fabrique n paragraphes séparés par une ligne vide — un document qu'on
// peut couper partout, pour isoler la règle de taille des règles de structure.
func prose(n int) string {
	var b strings.Builder
	for i := 1; i <= n; i++ {
		fmt.Fprintf(&b, "Paragraphe %d, avec assez de mots pour ressembler à du texte réel.\n\n", i)
	}
	return b.String()
}

// proseRealiste fabrique n paragraphes aux dimensions de la vraie note de
// test : environ 215 octets par paragraphe, deux lignes chacun. Le volume
// compte autant que le nombre de lignes, puisque c'est lui que goldmark doit
// analyser à chaque vérification de coupure.
func proseRealiste(n int) string {
	var b strings.Builder
	for i := 1; i <= n; i++ {
		fmt.Fprintf(&b, "Paragraphe %d. La question de savoir comment découper un document "+
			"en tranches éditables se pose dès que la note dépasse quelques écrans, et "+
			"la réponse tient autant au rendu qu'à la taille des tranches obtenues.\n\n", i)
	}
	return b.String()
}

// corpus rassemble les documents sur lesquels toutes les propriétés doivent
// tenir. Les cas structurés sont là parce qu'une section est rendue **seule** :
// c'est là que se joue le découpage.
func corpus() map[string]string {
	var liste strings.Builder
	liste.WriteString("Avant la liste.\n\n")
	for i := 1; i <= 120; i++ {
		fmt.Fprintf(&liste, "%d. élément %d\n", i, i)
	}
	liste.WriteString("\nAprès la liste.\n")

	var cloture strings.Builder
	cloture.WriteString("Avant le code.\n\n```go\n")
	for i := 0; i < 120; i++ {
		cloture.WriteString("fmt.Println(\"une ligne qui ressemble à du Markdown : # titre\")\n")
	}
	cloture.WriteString("```\n\nAprès le code.\n")

	var tableau strings.Builder
	tableau.WriteString("| a | b |\n|---|---|\n")
	for i := 0; i < 120; i++ {
		fmt.Fprintf(&tableau, "| %d | valeur |\n", i)
	}

	return map[string]string{
		"vide":               "",
		"une ligne":          "Juste une phrase.\n",
		"blancs seulement":   "\n\n   \n\n",
		"sans saut final":    "Pas de saut de ligne à la fin.",
		"prose courte":       prose(3),
		"prose longue":       prose(200),
		"accents et emoji":   "Éléphant 😀 sur la banquise.\n\n" + prose(40),
		"liste numérotée":    liste.String(),
		"clôture de code":    cloture.String(),
		"tableau":            tableau.String(),
		"citation imbriquée": "> premier\n> > second\n> > toujours second\n\n" + prose(40),
		"titre souligné":     "Titre en Setext\n===============\n\n" + prose(40),
	}
}

func decoupe(t *testing.T, doc string) []Section {
	t.Helper()
	secs := Sections(doc)
	if len(secs) == 0 {
		t.Fatal("aucune section : un document, même vide, en a au moins une")
	}
	return secs
}

// Les sections doivent recouvrir le document exactement : c'est ce qui permet
// à l'interface de n'en garder qu'un seul texte et d'y réinsérer une tranche.
func TestSectionsPaventLeDocument(t *testing.T) {
	for nom, doc := range corpus() {
		t.Run(nom, func(t *testing.T) {
			secs := decoupe(t, doc)

			if secs[0].Start != 0 {
				t.Errorf("la première section commence à %d, 0 attendu", secs[0].Start)
			}
			for i := 1; i < len(secs); i++ {
				if secs[i].Start != secs[i-1].End {
					t.Errorf("trou ou recouvrement : section %d finit à %d, section %d commence à %d",
						i-1, secs[i-1].End, i, secs[i].Start)
				}
			}
			if fin := secs[len(secs)-1].End; fin != longueurUTF16(doc) {
				t.Errorf("la dernière section finit à %d, %d attendu", fin, longueurUTF16(doc))
			}
			for i, s := range secs {
				if s.Start > s.End {
					t.Errorf("section %d inversée : %d > %d", i, s.Start, s.End)
				}
			}
		})
	}
}

// Recoller une section par son propre contenu doit rendre le document
// identique.
//
// C'est la propriété dont dépend l'enregistrement : l'éditeur ne garde qu'un
// texte complet et y réinsère la section éditée. Si ce recollage n'est pas
// l'identité, une frappe anodine réécrit la note de travers — en silence, et
// jusque sur le serveur.
func TestSectionsAllerRetour(t *testing.T) {
	for nom, doc := range corpus() {
		t.Run(nom, func(t *testing.T) {
			n := longueurUTF16(doc)
			for i, s := range decoupe(t, doc) {
				recolle := trancheUTF16(doc, 0, s.Start) +
					trancheUTF16(doc, s.Start, s.End) +
					trancheUTF16(doc, s.End, n)
				if recolle != doc {
					t.Fatalf("section %d [%d:%d] : le recollage modifie le document",
						i, s.Start, s.End)
				}
			}
		})
	}
}

// Une section est rendue **seule**, sans le contexte qui l'entoure. Le
// découpage n'est donc licite que s'il ne change aucun bloc rendu : couper une
// liste la ferait redémarrer à 1, couper une clôture ferait interpréter du code
// comme du Markdown, couper un tableau lui ferait perdre son en-tête.
//
// C'est la formulation générale de la règle « ne pas couper dans une
// construction multiligne », et elle a l'avantage d'être vérifiable.
func TestSectionsRendentCommeLeDocument(t *testing.T) {
	for nom, doc := range corpus() {
		t.Run(nom, func(t *testing.T) {
			var parSection []Block
			for _, s := range decoupe(t, doc) {
				parSection = append(parSection, RenderSection(doc, s)...)
			}
			entier := Render(doc)

			if len(parSection) != len(entier) {
				t.Fatalf("%d blocs par sections, %d pour le document entier",
					len(parSection), len(entier))
			}
			for i := range entier {
				if !reflect.DeepEqual(parSection[i], entier[i]) {
					t.Errorf("bloc %d diffère\n  par sections : %+v\n  document     : %+v",
						i, parSection[i], entier[i])
				}
			}
		})
	}
}

// La borne de taille est ce qui fait tout l'intérêt du découpage : au-delà,
// l'éditeur redevient exactement ce qu'il était.
//
// Le document est de la prose séparée par des lignes vides : il se coupe
// partout, donc aucune contrainte de structure ne peut servir d'excuse.
func TestSectionsBornentLaTaille(t *testing.T) {
	doc := prose(200)
	for i, s := range Sections(doc) {
		tranche := trancheUTF16(doc, s.Start, s.End)
		if n := nombreDeLignes(tranche); n > MaxSectionLines {
			t.Errorf("section %d : %d lignes, %d au plus attendues",
				i, n, MaxSectionLines)
		}
	}
}

// Quand la structure interdit de couper, le rendu juste l'emporte sur la borne
// de taille : une section lente vaut mieux qu'une liste qui redémarre à 1.
//
// Ce test n'impose donc aucune taille — il vérifie seulement qu'on n'a pas
// sacrifié le rendu pour tenir la borne.
func TestSectionsPreferentUnRenduJusteALaBorne(t *testing.T) {
	var b strings.Builder
	b.WriteString("Avant.\n\n")
	for i := 1; i <= 300; i++ {
		fmt.Fprintf(&b, "%d. élément %d\n", i, i)
	}
	b.WriteString("\nAprès.\n")
	doc := b.String()

	var parSection []Block
	for _, s := range Sections(doc) {
		parSection = append(parSection, RenderSection(doc, s)...)
	}
	if !reflect.DeepEqual(parSection, Render(doc)) {
		t.Error("la liste a été coupée : le rendu par sections diffère du rendu entier")
	}
}

// Une définition de lien en référence peut être à l'autre bout du document.
//
// C'était le point d'incertitude du chantier, et il est tranché : une section
// n'est jamais rendue par `Render` sur sa tranche brute, mais par
// `RenderSection`, qui lui préfixe les définitions du document. Une définition
// seule ne produit aucun bloc — elle est invisible à l'affichage — donc ce
// préambule ne coûte rien au rendu.
//
// La tranche éditable, elle, reste exacte : le préambule ne touche que ce qui
// est **rendu**, jamais ce qui est édité ni ce qui sera réécrit.
//
// La première version de ce test appelait `Render` sur la tranche brute, ce qui
// exigeait que la définition soit physiquement dans la même section que son
// usage — donc une seule section pour tout le document, donc l'éditeur restait
// lent. Les deux propriétés étaient contradictoires, et c'est celle-ci qui a
// changé.
func TestSectionsPreserventLesLiensEnReference(t *testing.T) {
	doc := "Voir [le site][ref].\n\n" + prose(120) + "[ref]: https://exemple.test/\n"

	var parSection []Block
	for _, s := range Sections(doc) {
		parSection = append(parSection, RenderSection(doc, s)...)
	}
	entier := Render(doc)

	if len(parSection) == 0 || len(entier) == 0 {
		t.Fatal("rendu vide")
	}
	if !reflect.DeepEqual(parSection[0], entier[0]) {
		t.Errorf("le premier bloc perd sa référence\n  par sections : %+v\n  document     : %+v",
			parSection[0], entier[0])
	}
}

// Le découpage se fait à l'ouverture de la note : son coût s'ajoute au temps
// d'affichage, il ne le remplace pas.
//
// Une vérification qui rend tout le reste du document à chaque coupure
// candidate est quadratique — mesuré à 1,17 s sur la vraie note de 295 ko, sur
// desktop, donc plusieurs secondes sur le téléphone. On aurait échangé 1,4 s de
// dessin contre pire. La borne est large exprès : elle ne vise pas à mesurer
// une machine, seulement à faire échouer un algorithme quadratique.
func TestSectionsRestentRapides(t *testing.T) {
	// Aux dimensions de la vraie note de test : ~2 650 lignes ET ~290 ko. Les
	// deux comptent. Un document de 2 600 lignes courtes ne fait que 86 ko et
	// laisse passer l'algorithme quadratique — c'est la longueur des lignes,
	// donc le volume à rendre, qui fait le coût.
	doc := proseRealiste(1325)
	if len(doc) < 250*1024 {
		t.Fatalf("document de %d octets, au moins 250 ko attendus", len(doc))
	}

	debut := time.Now()
	secs := Sections(doc)
	duree := time.Since(debut)

	if duree > 300*time.Millisecond {
		t.Errorf("découpage en %v, 300ms au plus attendues (%d sections) — "+
			"vérification quadratique ?", duree.Round(time.Millisecond), len(secs))
	}
}

// Une définition de lien en référence ne doit pas désactiver le découpage.
//
// C'est le piège de la règle « une coupure est licite si elle ne change aucun
// bloc rendu » prise au pied de la lettre : la section qui utilise [ref] ne
// rend pas pareil sans la définition, donc aucune coupure ne passe, donc le
// document entier revient en une seule section — et l'éditeur reste lent. Une
// seule ligne en bas d'une note suffit, en silence.
//
// La sortie est connue et vérifiée : une définition seule produit **zéro
// bloc**, et la mettre en préambule d'une section rend exactement comme le
// document entier. Les définitions du document sont donc à collecter une fois
// et à préfixer au texte rendu de chaque section — au rendu seulement, jamais
// au texte éditable, qui reste la tranche exacte.
func TestSectionsDecoupentMalgreUnLienEnReference(t *testing.T) {
	doc := "Voir [le site][ref].\n\n" + prose(120) + "[ref]: https://exemple.test/\n"

	tem := len(Sections("Voir le site.\n\n" + prose(120)))
	got := len(Sections(doc))

	if got < 2 {
		t.Fatalf("%d section pour un document de %d lignes : la définition de lien "+
			"a désactivé le découpage (le même document sans elle donne %d sections)",
			got, nombreDeLignes(doc), tem)
	}
}

// L'ouverture d'une note paie le découpage ET le rendu de toutes ses sections.
//
// Ne mesurer que le découpage laisserait passer une boucle qui réanalyse le
// document entier à chaque section pour y chercher les définitions de lien :
// 495 ms contre 228 sur la note de 295 ko, soit le double du budget
// d'ouverture pour rien.
func TestSectionsRenduesRestentRapides(t *testing.T) {
	doc := proseRealiste(1325)

	debut := time.Now()
	sections, blocs := RenderSections(doc)
	duree := time.Since(debut)

	if len(sections) != len(blocs) {
		t.Fatalf("%d sections mais %d listes de blocs", len(sections), len(blocs))
	}
	if duree > 350*time.Millisecond {
		t.Errorf("découpage et rendu en %v, 350ms au plus attendues (%d sections) — "+
			"les définitions sont-elles recalculées à chaque section ?",
			duree.Round(time.Millisecond), len(sections))
	}
}
