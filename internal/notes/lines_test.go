package notes

import "testing"

func TestDetectLineEnding(t *testing.T) {
	cas := []struct {
		nom   string
		texte string
		veut  LineEnding
	}{
		{"unix", "un\ndeux\n", LF},
		{"windows", "un\r\ndeux\r\n", CRLF},
		{"ancien mac", "un\rdeux\r", CR},
		{"sans saut de ligne", "une seule ligne", LF},
		{"vide", "", LF},
		{"majorité windows", "un\r\ndeux\r\ntrois\nquatre\r\n", CRLF},
		{"majorité unix", "un\ndeux\ntrois\r\n", LF},
		{"égalité", "un\r\ndeux\n", LF},
		// Un « \r\n » ne compte pas aussi pour un « \r » et un « \n ».
		{"windows sur une ligne", "un\r\n", CRLF},
	}
	for _, c := range cas {
		if got := DetectLineEnding(c.texte); got != c.veut {
			t.Errorf("%s : DetectLineEnding(%q) = %q, veut %q", c.nom, c.texte, got, c.veut)
		}
	}
}

func TestNormalizeLineEndings(t *testing.T) {
	cas := map[string]string{
		"un\r\ndeux\r\n":  "un\ndeux\n",
		"un\rdeux\r":      "un\ndeux\n",
		"un\ndeux":        "un\ndeux",
		"a\r\nb\nc\rd":    "a\nb\nc\nd",
		"vide\r\n\r\nfin": "vide\n\nfin",
		"sans saut":       "sans saut",
	}
	for entree, veut := range cas {
		if got := NormalizeLineEndings(entree); got != veut {
			t.Errorf("NormalizeLineEndings(%q) = %q, veut %q", entree, got, veut)
		}
	}
}

// Un fichier homogène ouvert, normalisé puis réécrit sans modification doit
// redonner exactement ses octets : c'est la promesse de ne rien abîmer.
func TestFinsDeLigneAllerRetourExact(t *testing.T) {
	for _, texte := range []string{
		"# Titre\r\n\r\n- un\r\n- deux\r\n",
		"# Titre\n\n- un\n- deux\n",
		"Titre\rligne\r",
		"sans fin de ligne finale\r\nsuite",
		"",
	} {
		ending := DetectLineEnding(texte)
		if got := ApplyLineEnding(NormalizeLineEndings(texte), ending); got != texte {
			t.Errorf("aller-retour de %q : %q", texte, got)
		}
	}
}

// Ce que l'éditeur ajoute — une ligne tapée, un « \r\n » collé — prend la
// convention du fichier : il n'en ressort jamais mélangé.
func TestApplyLineEndingUniformise(t *testing.T) {
	cas := []struct {
		texte  string
		ending LineEnding
		veut   string
	}{
		{"un\ndeux\nnouvelle\n", CRLF, "un\r\ndeux\r\nnouvelle\r\n"},
		{"un\r\ncollé\n", LF, "un\ncollé\n"},
		{"un\r\ncollé\n", CRLF, "un\r\ncollé\r\n"},
		{"un\ndeux", CR, "un\rdeux"},
		{"un\ndeux", "", "un\ndeux"},
	}
	for _, c := range cas {
		if got := ApplyLineEnding(c.texte, c.ending); got != c.veut {
			t.Errorf("ApplyLineEnding(%q, %q) = %q, veut %q", c.texte, c.ending, got, c.veut)
		}
	}
}
