package notes

import (
	"strings"
	"testing"
	"unicode/utf8"
)

func TestExportSegmentValideInchange(t *testing.T) {
	valides := []string{
		"note.md", "a--b.md", "Réunion du 15 à relire.md", "emoji 😀.md",
		"parenthèses (2026).md", "pourcent 100%.md", "dièse #1.md", "Projets",
		"rapport.docx", "journal.txt", "a.b.c.md", "console.md.bak", "CONSOLE2.md",
		"x - y.md", "ÉÈÀ.md", strings.Repeat("é", 90) + ".md",
		"Note.md", "note.md", "COM10.md", "AUXILIAIRE.md",
	}
	for _, n := range valides {
		if err := ValidateName(n); err != nil {
			t.Fatalf("échantillon invalide %q : %v", n, err)
		}
		if got := ExportSegment(n); got != n {
			t.Errorf("ExportSegment(%q) = %q, un nom valide doit rester inchangé", n, got)
		}
	}
}

func TestExportSegmentRegles(t *testing.T) {
	cas := []struct{ in, want string }{
		{`a<b>c:d"e|f?g*h\i.md`, "a_b_c_d_e_f_g_h_i.md"},
		{"a\x00b\tc.md", "a_b_c.md"},
		{"note.md ", "note.md"},
		{"note.md. .", "note.md"},
		{".gitignore", ".gitignore"},
		{".caché.md", ".caché.md"},
		{"CON", "_CON"},
		{"nul.txt", "_nul.txt"},
		{"Com1.md", "_Com1.md"},
		{"LPT9 .md", "_LPT9 .md"},
		{"", "_"},
		{"   ", "_"},
		{".", "_"},
		{"..", "_"},
		{"...", "_"},
	}
	for _, c := range cas {
		if got := ExportSegment(c.in); got != c.want {
			t.Errorf("ExportSegment(%q) = %q, attendu %q", c.in, got, c.want)
		}
	}
}

func TestExportSegmentLongueurGardeLExtension(t *testing.T) {
	in := strings.Repeat("a", 300) + ".docx"
	got := ExportSegment(in)
	if len(got) > maxNameBytes {
		t.Fatalf("longueur %d > %d", len(got), maxNameBytes)
	}
	if !strings.HasSuffix(got, ".docx") {
		t.Errorf("extension perdue : %q", got[len(got)-10:])
	}

	// Caractères de 4 octets : jamais coupés en deux.
	in = strings.Repeat("😀", 100) + ".md"
	got = ExportSegment(in)
	if !utf8.ValidString(got) || len(got) > maxNameBytes || !strings.HasSuffix(got, ".md") {
		t.Errorf("troncature fautive : %q (%d octets)", got, len(got))
	}

	// Extension démesurée : on borne quand même.
	got = ExportSegment("a." + strings.Repeat("x", 300))
	if len(got) > maxNameBytes {
		t.Errorf("extension démesurée non bornée : %d", len(got))
	}
}

func TestExportSiblingsCasse(t *testing.T) {
	got := ExportSiblings([]string{"note.md", "Note.md"})
	// L'ordre trié sert « Note.md » le premier.
	if got[1] != "Note.md" || got[0] != "note (2).md" {
		t.Errorf("got %q", got)
	}
}

func TestExportSiblingsLeNomValideGagne(t *testing.T) {
	// « a.md␠ » s'assainit en « a.md » : il ne doit pas déloger le vrai.
	got := ExportSiblings([]string{"a.md ", "a.md"})
	if got[1] != "a.md" || got[0] != "a (2).md" {
		t.Errorf("got %q", got)
	}
}

func TestExportSiblingsFichierEtDossier(t *testing.T) {
	got := ExportSiblings([]string{"Projets", "projets"})
	if got[0] != "Projets" || got[1] != "projets (2)" {
		t.Errorf("got %q", got)
	}
}

func TestExportSiblingsSuffixesSuccessifsEtOccupes(t *testing.T) {
	// « a (2).md » existe déjà : le suffixe saute au suivant.
	got := ExportSiblings([]string{"a.md", "A.md", "a (2).md", "a.md."})
	seen := map[string]bool{}
	for _, n := range got {
		k := strings.ToLower(n)
		if seen[k] {
			t.Fatalf("collision sur %q dans %q", n, got)
		}
		seen[k] = true
	}
	if got[2] != "a (2).md" {
		t.Errorf("un nom valide a bougé : %q", got)
	}
}

func TestExportSiblingsDeterministe(t *testing.T) {
	a := ExportSiblings([]string{"x.md", "X.md", "x.md ", "y"})
	b := ExportSiblings([]string{"x.md", "X.md", "x.md ", "y"})
	for i := range a {
		if a[i] != b[i] {
			t.Fatalf("non déterministe : %q / %q", a, b)
		}
	}
}

func TestExportSiblingsSuffixeSurNomLong(t *testing.T) {
	long := strings.Repeat("a", 200)
	got := ExportSiblings([]string{long, strings.ToUpper(long)})
	for _, n := range got {
		if len(n) > maxNameBytes {
			t.Errorf("%d octets", len(n))
		}
	}
	if strings.EqualFold(got[0], got[1]) {
		t.Errorf("collision : %q", got)
	}
}
