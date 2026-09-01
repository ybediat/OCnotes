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
