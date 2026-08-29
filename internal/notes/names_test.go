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
		// Depuis la prise en charge du texte brut, une extension .txt
		// explicitement saisie est respectée : « note.txt.md » était le
		// résultat d'une époque où .txt n'était pas un format connu.
		"note.txt":   "note.txt",
		"Ma réunion": "Ma réunion.md",
	}
	for in, want := range tests {
		if got := WithExtension(in); got != want {
			t.Errorf("WithExtension(%q) = %q, attendu %q", in, got, want)
		}
	}
}

// WithExtensionOf préserve le format du fichier renommé.
//
// C'est la règle qui empêche un renommage de transformer silencieusement un
// texte brut en Markdown, et réciproquement.
func TestWithExtensionOfPreserveLeFormat(t *testing.T) {
	tests := []struct {
		ref, name, want string
	}{
		{"Projets/journal.txt", "carnet", "carnet.txt"},
		{"Projets/journal.md", "carnet", "carnet.md"},
		{"Projets/journal.markdown", "carnet", "carnet.markdown"},
		// Une extension explicitement saisie l'emporte sur celle d'origine :
		// c'est une conversion demandée, pas un accident.
		{"journal.txt", "carnet.md", "carnet.md"},
		{"journal.md", "carnet.txt", "carnet.txt"},
		// Une référence qui n'est pas une note — un dossier — retombe sur
		// l'extension d'écriture.
		{"Projets", "carnet", "carnet.md"},
	}
	for _, tc := range tests {
		if got := WithExtensionOf(tc.ref, tc.name); got != tc.want {
			t.Errorf("WithExtensionOf(%q, %q) = %q, attendu %q", tc.ref, tc.name, got, tc.want)
		}
	}
}

func TestIsNoteEtIsMarkdownNeRepondentPasALaMemeQuestion(t *testing.T) {
	tests := []struct {
		name           string
		note, md, brut bool
	}{
		{"journal.md", true, true, false},
		{"journal.markdown", true, true, false},
		{"journal.MD", true, true, false},
		{"journal.txt", true, false, true},
		{"journal.TXT", true, false, true},
		{"photo.jpg", false, false, false},
		{"Projets", false, false, false},
	}
	for _, tc := range tests {
		if got := IsNote(tc.name); got != tc.note {
			t.Errorf("IsNote(%q) = %v, attendu %v", tc.name, got, tc.note)
		}
		if got := IsMarkdown(tc.name); got != tc.md {
			t.Errorf("IsMarkdown(%q) = %v, attendu %v", tc.name, got, tc.md)
		}
		if got := IsPlainText(tc.name); got != tc.brut {
			t.Errorf("IsPlainText(%q) = %v, attendu %v", tc.name, got, tc.brut)
		}
	}
}

func TestDisplayName(t *testing.T) {
	tests := map[string]string{
		"note.md":       "note",
		"Ma réunion.md": "Ma réunion",
		"note.markdown": "note",
		"Projets":       "Projets",
		// Le texte brut garde son extension : « notes.txt » et « notes.md »
		// peuvent cohabiter dans un dossier, et deux lignes « notes » y
		// désigneraient deux fichiers différents.
		"notes.txt": "notes.txt",
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
