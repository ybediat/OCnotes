package notes

import "testing"

func TestValidateNameAccepte(t *testing.T) {
	// Le serveur accepte davantage encore ; ce sont les noms que
	// l'application autorise à créer, compatibles avec le cache local.
	valides := []string{
		"note.md",
		"Réunion du 15 à relire.md",
		"emoji 😀.md",
		"parenthèses (2026).md",
		"esperluette & compagnie.md",
		"plus + signe.md",
		"apostrophe d'été.md",
		"virgule, point-virgule;.md",
		"egal=arobase@.md",
		"pourcent 100%.md",
		"dièse #1.md",
		"Projets",
	}

	for _, name := range valides {
		if err := ValidateName(name); err != nil {
			t.Errorf("ValidateName(%q) = %v, attendu valide", name, err)
		}
	}
}

func TestValidateNameRefuse(t *testing.T) {
	tests := map[string]string{
		"":                    "nom vide",
		"   ":                 "espaces seuls",
		".":                   "point",
		"..":                  "double point",
		"dossier/note.md":     "slash",
		`dossier\note.md`:     "antislash",
		"note<.md":            "chevron",
		"note>.md":            "chevron fermant",
		"note:titre.md":       "deux-points",
		`note".md`:            "guillemet",
		"note|autre.md":       "barre verticale",
		"note?.md":            "point d'interrogation",
		"note*.md":            "astérisque",
		" note.md":            "espace initiale",
		"note.md ":            "espace finale",
		".cache":              "point initial",
		"note.":               "point final",
		"CON":                 "périphérique réservé",
		"con.md":              "périphérique réservé avec extension",
		"LPT1.md":             "port réservé",
		"note\x00.md":         "caractère nul",
		"note\ttabulation.md": "tabulation",
	}

	for name, why := range tests {
		if err := ValidateName(name); err == nil {
			t.Errorf("ValidateName(%q) accepté, refus attendu (%s)", name, why)
		}
	}
}

func TestValidateNameLongueur(t *testing.T) {
	long := ""
	for len(long) <= maxNameBytes {
		long += "a"
	}
	if err := ValidateName(long); err == nil {
		t.Error("un nom trop long aurait dû être refusé")
	}
}

func TestSanitizeName(t *testing.T) {
	tests := []struct {
		in   string
		want string
	}{
		{"Ma réunion", "Ma réunion"},
		{"Notes: le projet", "Notes- le projet"},
		{"a/b", "a-b"},
		{"  espaces  ", "espaces"},
		{"...", "Sans titre"},
		{"", "Sans titre"},
		{"???", "Sans titre"},
		{"CON", "_CON"},
		{"note\x00nulle", "note-nulle"},
		{"trop<<<>>>de<<<signes", "trop-de-signes"},
	}

	for _, tc := range tests {
		got := SanitizeName(tc.in)
		if got != tc.want {
			t.Errorf("SanitizeName(%q) = %q, attendu %q", tc.in, got, tc.want)
		}
		if err := ValidateName(got); err != nil {
			t.Errorf("SanitizeName(%q) = %q, refusé par ValidateName: %v", tc.in, got, err)
		}
	}
}

// SanitizeName doit toujours produire un nom que ValidateName accepte :
// c'est le contrat qui permet à l'interface de proposer un nom sans exposer
// les contraintes du système de fichiers.
func TestSanitizeNameProduitToujoursUnNomValide(t *testing.T) {
	entrees := []string{
		"", " ", ".", "..", "...", "/", "///", `\\\`,
		"CON", "NUL.md", "aux",
		"<>:\"|?*", "a<b>c:d\"e|f?g*h",
		"\x00\x01\x02", "\t\n\r",
		"note.", ".note", " note ",
		"😀", "———", "-",
	}

	for _, in := range entrees {
		got := SanitizeName(in)
		if err := ValidateName(got); err != nil {
			t.Errorf("SanitizeName(%q) = %q, refusé par ValidateName: %v", in, got, err)
		}
	}
}

func TestIsMarkdown(t *testing.T) {
	tests := map[string]bool{
		"note.md":       true,
		"note.MD":       true,
		"note.markdown": true,
		"note.mkd":      true,
		"note.txt":      false,
		"note":          false,
		"note.md.bak":   false,
		"photo.png":     false,
	}
	for name, want := range tests {
		if got := IsMarkdown(name); got != want {
			t.Errorf("IsMarkdown(%q) = %v, attendu %v", name, got, want)
		}
	}
}

func TestWithExtension(t *testing.T) {
	tests := map[string]string{
		"note":          "note.md",
		"note.md":       "note.md",
		"note.markdown": "note.markdown",
		"note.txt":      "note.txt.md",
		"Ma réunion":    "Ma réunion.md",
	}
	for in, want := range tests {
		if got := WithExtension(in); got != want {
			t.Errorf("WithExtension(%q) = %q, attendu %q", in, got, want)
		}
	}
}

func TestDisplayName(t *testing.T) {
	tests := map[string]string{
		"note.md":       "note",
		"Ma réunion.md": "Ma réunion",
		"note.markdown": "note",
		"Projets":       "Projets",
	}
	for in, want := range tests {
		if got := DisplayName(in); got != want {
			t.Errorf("DisplayName(%q) = %q, attendu %q", in, got, want)
		}
	}
}

func TestCleanPath(t *testing.T) {
	tests := map[string]string{
		"":                    "",
		".":                   "",
		"/":                   "",
		"Notes":               "Notes",
		"/Notes/":             "Notes",
		"Notes//a.md":         "Notes/a.md",
		"Notes/./a.md":        "Notes/a.md",
		"Notes/../a.md":       "a.md",
		"../../hors.md":       "hors.md",
		"/../../../hors.md":   "hors.md",
		"Notes/sous/../a.md":  "Notes/a.md",
		"Notes/sous/dir/../.": "Notes/sous",
	}
	for in, want := range tests {
		if got := CleanPath(in); got != want {
			t.Errorf("CleanPath(%q) = %q, attendu %q", in, got, want)
		}
	}
}
