package markdown

import (
	"strings"
	"testing"
	"unicode/utf16"
)

// Les cas de test décrivent la sélection dans le texte lui-même :
//
//	‸  curseur
//	‹…› sélection
//
// Ces caractères n'apparaissent jamais dans du Markdown, contrairement à
// [ ] ou | qui y ont un sens.
const (
	markCursor = '‸'
	markOpen   = '‹'
	markClose  = '›'
)

func utf16Len(r rune) int {
	if r >= 0x10000 {
		return 2
	}
	return 1
}

func parseDoc(t *testing.T, s string) Doc {
	t.Helper()

	var text strings.Builder
	start, end, n := -1, -1, 0

	for _, r := range s {
		switch r {
		case markOpen:
			start = n
		case markClose:
			end = n
		case markCursor:
			start, end = n, n
		default:
			text.WriteRune(r)
			n += utf16Len(r)
		}
	}

	if start == -1 || end == -1 {
		t.Fatalf("le cas %q ne décrit pas de sélection (attendu ‸ ou ‹…›)", s)
	}
	return Doc{Text: text.String(), Start: start, End: end}
}

func renderDoc(d Doc) string {
	units := utf16.Encode([]rune(d.Text))
	start, end := d.Start, d.End
	if start > end {
		start, end = end, start
	}
	start, end = clamp(start, 0, len(units)), clamp(end, 0, len(units))

	var b strings.Builder
	b.WriteString(decodeUnits(units[:start]))
	if start == end {
		b.WriteRune(markCursor)
	} else {
		b.WriteRune(markOpen)
		b.WriteString(decodeUnits(units[start:end]))
		b.WriteRune(markClose)
	}
	b.WriteString(decodeUnits(units[end:]))
	return b.String()
}

func runCases(t *testing.T, cases []struct {
	name   string
	action Action
	in     string
	want   string
}) {
	t.Helper()
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := Apply(parseDoc(t, tc.in), tc.action)
			if err != nil {
				t.Fatalf("Apply: %v", err)
			}
			if rendered := renderDoc(got); rendered != tc.want {
				t.Errorf("\nentrée   %s\nobtenu   %s\nattendu  %s", tc.in, rendered, tc.want)
			}
		})
	}
}

func TestMiseEnFormeEnLigne(t *testing.T) {
	runCases(t, []struct {
		name   string
		action Action
		in     string
		want   string
	}{
		{"gras sur sélection", ActionBold, "Bonjour ‹monde›", "Bonjour **‹monde›**"},
		{"gras sur curseur seul", ActionBold, "Bonjour ‸", "Bonjour **‸**"},
		{"gras retiré, marqueurs sélectionnés", ActionBold, "Bonjour ‹**monde**›", "Bonjour ‹monde›"},
		{"gras retiré, marqueurs autour", ActionBold, "Bonjour **‹monde›**", "Bonjour ‹monde›"},
		{"italique", ActionItalic, "‹mot›", "*‹mot›*"},
		{"italique retiré", ActionItalic, "*‹mot›*", "‹mot›"},
		{"barré", ActionStrikethrough, "‹mot›", "~~‹mot›~~"},
		{"code en ligne", ActionInlineCode, "‹var›", "`‹var›`"},
		{"code en ligne retiré", ActionInlineCode, "`‹var›`", "‹var›"},

		// Le gras est deux astérisques, l'italique un seul : appliquer
		// l'italique à du gras ne doit pas défaire le gras.
		{"italique sur du gras", ActionItalic, "**‹mot›**", "***‹mot›***"},

		// Curseur au milieu d'une mise en forme : l'appui la retire au lieu
		// d'ajouter une seconde paire (le bouton est surligné à cet endroit).
		{"gras retiré, curseur au milieu", ActionBold, "Un **gr‸as** ici", "Un gr‸as ici"},
		{"gras retiré, sélection partielle", ActionBold, "Un **‹gr›as** ici", "Un ‹gr›as ici"},
		{"barré retiré, curseur au milieu", ActionStrikethrough, "~~ba‸rré~~", "ba‸rré"},
		{"code retiré, curseur au milieu", ActionInlineCode, "a `x‸y` b", "a x‸y b"},
		{"seconde paire de la ligne", ActionBold, "**a** et **b‸c**", "**a** et b‸c"},
		{"curseur entre deux paires", ActionBold, "**a** et‸ **b**", "**a** et**‸** **b**"},
		{"italique ignore le gras", ActionItalic, "**gr‸as**", "**gr*‸*as**"},
		{"astérisques isolés", ActionItalic, "2 * 3‸ * 4", "2 * 3*‸* * 4"},
		{"pas d'une ligne à l'autre", ActionBold, "**a\nb‸**", "**a\nb**‸****"},

		// Espaces de bord sélectionnées (double toucher) : elles restent hors
		// des marqueurs, sans quoi « **printemps ** » ne serait pas en gras.
		{"gras, espace finale exclue", ActionBold, "un ‹printemps ›ici", "un **‹printemps›** ici"},
		{"gras, espaces des deux bords exclues", ActionBold, "un‹ printemps\t›ici", "un **‹printemps›**\tici"},
		{"gras retiré malgré l'espace finale", ActionBold, "‹**printemps** ›ici", "‹printemps› ici"},
		{"italique, espace finale exclue", ActionItalic, "‹mot ›", "*‹mot›* "},
		{"barré, espace initiale exclue", ActionStrikethrough, "a‹ mot›", "a ~~‹mot›~~"},
		{"sélection d'espaces seules", ActionBold, "a‹ ›b", "a**‹ ›**b"},
		{"code en ligne garde ses espaces", ActionInlineCode, "‹x ›", "`‹x ›`"},

		// Le curseur se compte en unités UTF-16 : un emoji en occupe deux.
		{"sélection après un emoji", ActionBold, "😀 ‹mot›", "😀 **‹mot›**"},
		{"sélection d'un emoji", ActionBold, "‹😀›", "**‹😀›**"},
		{"sélection après un accent", ActionBold, "été ‹mot›", "été **‹mot›**"},
	})
}

func TestLien(t *testing.T) {
	runCases(t, []struct {
		name   string
		action Action
		in     string
		want   string
	}{
		// Avec une sélection, elle devient le libellé et le curseur va dans
		// l'URL : c'est ce qu'il reste à saisir.
		{"lien sur sélection", ActionLink, "voir ‹la doc›", "voir [la doc](‸)"},
		{"lien sur curseur", ActionLink, "voir ‸", "voir [‸]()"},
	})
}

func TestPrefixesDeLigne(t *testing.T) {
	runCases(t, []struct {
		name   string
		action Action
		in     string
		want   string
	}{
		{"titre 1", ActionH1, "‸Mon titre", "# ‸Mon titre"},
		{"titre 1 retiré", ActionH1, "# ‸Mon titre", "‸Mon titre"},
		{"titre 2 remplace titre 1", ActionH2, "# ‸Mon titre", "## ‸Mon titre"},
		{"puce", ActionBullet, "‸élément", "- ‸élément"},
		{"puce retirée", ActionBullet, "- ‸élément", "‸élément"},
		{"puce remplace titre", ActionBullet, "# ‸Titre", "- ‸Titre"},
		{"case à cocher", ActionTask, "‸tâche", "- [ ] ‸tâche"},
		{"case à cocher depuis une puce", ActionTask, "- ‸tâche", "- [ ] ‸tâche"},
		{"case à cocher retirée", ActionTask, "- [ ] ‸tâche", "‸tâche"},
		{"citation", ActionQuote, "‸citation", "> ‸citation"},
		{"indentation préservée", ActionBullet, "  ‸élément", "  - ‸élément"},

		// Un curseur posé dans le préfixe est ramené au début du contenu.
		{"curseur dans le préfixe", ActionH2, "#‸ Titre", "## ‸Titre"},
	})
}

func TestPrefixesSurPlusieursLignes(t *testing.T) {
	runCases(t, []struct {
		name   string
		action Action
		in     string
		want   string
	}{
		{
			"puce sur trois lignes",
			ActionBullet,
			"‹un\ndeux\ntrois›",
			"‹- un\n- deux\n- trois›",
		},
		{
			"puce retirée des trois lignes",
			ActionBullet,
			"‹- un\n- deux\n- trois›",
			"‹un\ndeux\ntrois›",
		},
		{
			// Une seule ligne sur trois porte la puce : l'action l'applique
			// partout plutôt que de la retirer.
			"application partielle",
			ActionBullet,
			"‹- un\ndeux\ntrois›",
			"‹- un\n- deux\n- trois›",
		},
		{
			"liste numérotée",
			ActionNumbered,
			"‹un\ndeux\ntrois›",
			"‹1. un\n2. deux\n3. trois›",
		},
		{
			"liste numérotée retirée",
			ActionNumbered,
			"‹1. un\n2. deux\n3. trois›",
			"‹un\ndeux\ntrois›",
		},
		{
			// Les lignes vides ne décident pas de la bascule et ne sont pas
			// numérotées à part : elles reçoivent le préfixe comme les autres.
			"ligne hors sélection intacte",
			ActionBullet,
			"‹un\ndeux›\nintacte",
			"‹- un\n- deux›\nintacte",
		},
		{
			"ligne avant la sélection intacte",
			ActionBullet,
			"intacte\n‹un\ndeux›",
			"intacte\n‹- un\n- deux›",
		},
	})
}

func TestBlocDeCode(t *testing.T) {
	runCases(t, []struct {
		name   string
		action Action
		in     string
		want   string
	}{
		{"bloc ajouté", ActionCodeBlock, "‹go build›", "```\n‹go build›\n```"},
		{"bloc retiré", ActionCodeBlock, "```\n‹go build›\n```", "‹go build›"},
	})
}

func TestApplyActionInconnue(t *testing.T) {
	if _, err := Apply(NewDoc("texte"), Action("inexistante")); err == nil {
		t.Error("une action inconnue devrait produire une erreur")
	}
}

// Une action ne doit jamais produire une sélection hors du texte : Compose
// lèverait une exception en l'appliquant.
func TestSelectionToujoursValide(t *testing.T) {
	docs := []Doc{
		{Text: "", Start: 0, End: 0},
		{Text: "abc", Start: 0, End: 3},
		{Text: "abc", Start: 3, End: 3},
		{Text: "ligne 1\nligne 2", Start: 2, End: 10},
		{Text: "😀😀", Start: 0, End: 4},
		// Sélection inversée : Compose en produit quand on sélectionne de
		// droite à gauche.
		{Text: "abcdef", Start: 5, End: 1},
		// Bornes aberrantes : elles doivent être ramenées dans le texte.
		{Text: "abc", Start: -4, End: 99},
	}

	for _, action := range Actions() {
		for i, d := range docs {
			got, err := Apply(d, action)
			if err != nil {
				t.Fatalf("Apply(%s): %v", action, err)
			}
			n := got.Length()
			if got.Start < 0 || got.Start > n || got.End < 0 || got.End > n {
				t.Errorf("%s sur le document %d : sélection (%d,%d) hors du texte de longueur %d",
					action, i, got.Start, got.End, n)
			}
		}
	}
}

// Les actions en ligne sont des bascules : les appliquer deux fois doit
// restituer le texte de départ.
func TestBasculeInline(t *testing.T) {
	for _, action := range []Action{ActionBold, ActionItalic, ActionStrikethrough, ActionInlineCode} {
		t.Run(string(action), func(t *testing.T) {
			origin := parseDoc(t, "Bonjour ‹monde› !")

			once, err := Apply(origin, action)
			if err != nil {
				t.Fatalf("Apply: %v", err)
			}
			twice, err := Apply(once, action)
			if err != nil {
				t.Fatalf("Apply: %v", err)
			}

			if twice.Text != origin.Text {
				t.Errorf("texte = %q, attendu %q", twice.Text, origin.Text)
			}
			if twice.Start != origin.Start || twice.End != origin.End {
				t.Errorf("sélection = (%d,%d), attendue (%d,%d)",
					twice.Start, twice.End, origin.Start, origin.End)
			}
		})
	}
}

func TestSelected(t *testing.T) {
	d := parseDoc(t, "Bonjour ‹le monde› !")
	if got := d.Selected(); got != "le monde" {
		t.Errorf("Selected() = %q", got)
	}
}

func TestNewDocPlaceLeCurseurALaFin(t *testing.T) {
	d := NewDoc("été 😀")
	if d.Start != d.End {
		t.Error("NewDoc devrait produire un curseur, pas une sélection")
	}
	// « été 😀 » : 3 lettres + 1 espace + 2 unités UTF-16 pour l'emoji.
	if d.Start != 6 {
		t.Errorf("curseur en %d, attendu 6 unités UTF-16", d.Start)
	}
}

// Le bouton « liste numérotée » reprend la liste qui précède immédiatement la
// sélection, au même retrait, au lieu de repartir de 1. Il ne lit que la ligne
// d'avant : c'est la ligne de contexte que la fenêtre de l'éditeur fournit.
func TestListeNumeroteeReprendLaPrecedente(t *testing.T) {
	runCases(t, []struct {
		name   string
		action Action
		in     string
		want   string
	}{
		{
			"suite d'une liste",
			ActionNumbered,
			"1. a\n2. b\n3. c\n‹d\ne›",
			"1. a\n2. b\n3. c\n‹4. d\n5. e›",
		},
		{
			"curseur sur la ligne suivante",
			ActionNumbered,
			"7. a\nb‸",
			"7. a\n8. b‸",
		},
		{
			"délimiteur parenthèse conservé",
			ActionNumbered,
			"3) a\n‹b›",
			"3) a\n‹4) b›",
		},
		{
			"retrait différent : nouvelle liste",
			ActionNumbered,
			"1. a\n  b‸",
			"1. a\n  1. b‸",
		},
		{
			"liste coupée par une ligne vide : repart de 1",
			ActionNumbered,
			"1. a\n\n‹b›",
			"1. a\n\n‹1. b›",
		},
		{
			"ligne précédente non numérotée : repart de 1",
			ActionNumbered,
			"- a\n‹b›",
			"- a\n‹1. b›",
		},
		{
			// La suite qui part de 4 est reconnue comme déjà numérotée : le
			// bouton la retire au lieu de la renuméroter.
			"suite à partir de 4 retirée",
			ActionNumbered,
			"‹4. a\n5. b\n6. c›",
			"‹a\nb\nc›",
		},
		{
			"suite retirée sous une liste",
			ActionNumbered,
			"3. x\n‹4. a\n5. b›",
			"3. x\n‹a\nb›",
		},
		{
			// Numéros non consécutifs : ce n'est pas ce que le bouton écrit,
			// il renumérote.
			"numéros non consécutifs renumérotés",
			ActionNumbered,
			"‹1. a\n3. b›",
			"‹1. a\n2. b›",
		},
		{
			"délimiteurs mêlés renumérotés",
			ActionNumbered,
			"‹1. a\n2) b›",
			"‹1. a\n2. b›",
		},
		{
			// Au-delà de neuf chiffres, ce n'est plus une liste pour
			// CommonMark : le texte est gardé, numéroté à partir de 1.
			"numéro démesuré",
			ActionNumbered,
			"‹1234567890. a›",
			"‹1. 1234567890. a›",
		},
	})
}
