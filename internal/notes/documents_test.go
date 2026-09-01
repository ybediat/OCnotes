package notes

import (
	"strings"
	"testing"

	"github.com/ybediat/OpenNote/internal/documents"
	"github.com/ybediat/OpenNote/internal/markdown"
)

func TestIsDocument(t *testing.T) {
	cas := []struct {
		nom     string
		attendu bool
	}{
		{"rapport.docx", true},
		{"rapport.odt", true},
		{"RAPPORT.DOCX", true},
		{"note.md", false},
		{"note.txt", false},
		{"archive.zip", false},
		{"rapport.doc", false}, // Word 97 : hors périmètre, et pour longtemps
	}
	for _, c := range cas {
		if got := IsDocument(c.nom); got != c.attendu {
			t.Errorf("IsDocument(%q) = %v", c.nom, got)
		}
	}
}

// TestIsNoteAfficheLesDocuments fige la raison d'être de l'élargissement :
// sans elle, un .docx posé depuis l'interface web n'apparaîtrait pas du tout
// dans la liste.
func TestIsNoteAfficheLesDocuments(t *testing.T) {
	for _, nom := range []string{"rapport.docx", "rapport.odt", "note.md", "note.txt"} {
		if !IsNote(nom) {
			t.Errorf("IsNote(%q) = false : le fichier serait invisible dans le listing", nom)
		}
	}
}

// TestWithExtensionNeCreeJamaisUnDocument protège la création de notes.
//
// C'est le défaut qu'élargir IsNote introduit si l'on n'y prend pas garde :
// WithExtension répondait « rien à faire » dès que IsNote était vrai, donc une
// note créée sous le nom « rapport.docx » aurait gardé cette extension. Le
// fichier aurait contenu du Markdown et se serait relu comme une archive OOXML
// — cassé des deux côtés.
func TestWithExtensionNeCreeJamaisUnDocument(t *testing.T) {
	cas := map[string]string{
		"rapport.docx": "rapport.docx.md",
		"rapport.odt":  "rapport.odt.md",
		"carnet":       "carnet.md",
		"carnet.md":    "carnet.md",
		"carnet.txt":   "carnet.txt",
	}
	for nom, attendu := range cas {
		if got := WithExtension(nom); got != attendu {
			t.Errorf("WithExtension(%q) = %q, attendu %q", nom, got, attendu)
		}
	}
}

// TestWithExtensionOfProtegeLesDocuments complète
// TestWithExtensionOfPreserveLeFormat, qui couvre les formats modifiables.
//
// Un document est traité plus strictement qu'une note : entre .md et .txt,
// saisir l'autre extension est une conversion demandée, et l'application sait
// la faire. Vers ou depuis un document, elle ne sait pas — donc elle ne fait
// pas semblant.
func TestWithExtensionOfProtegeLesDocuments(t *testing.T) {
	cas := []struct {
		ref      string
		nom      string
		attendu  string
		pourquoi string
	}{
		{"rapport.docx", "bilan", "bilan.docx", "un document garde son extension"},
		{"rapport.docx", "bilan.docx", "bilan.docx", "l'extension n'est pas doublée"},
		{"rapport.docx", "bilan.DOCX", "bilan.DOCX", "la comparaison ignore la casse"},
		{"rapport.docx", "bilan.odt", "bilan.odt.docx", "l'application ne convertit pas"},
		{"journal.md", "rapport.docx", "rapport.docx.md", "une note ne se déguise pas en document"},
	}
	for _, c := range cas {
		if got := WithExtensionOf(c.ref, c.nom); got != c.attendu {
			t.Errorf("WithExtensionOf(%q, %q) = %q, attendu %q — %s", c.ref, c.nom, got, c.attendu, c.pourquoi)
		}
	}
}

func TestDisplayNameGardeLExtensionDunDocument(t *testing.T) {
	if got := DisplayName("rapport.docx"); got != "rapport.docx" {
		t.Errorf("DisplayName(\"rapport.docx\") = %q : l'extension prévient qu'on ne pourra pas le modifier", got)
	}
	if got := DisplayName("note.md"); got != "note" {
		t.Errorf("DisplayName(\"note.md\") = %q", got)
	}
}

func TestEnsureWritableRefuseUnDocument(t *testing.T) {
	err := EnsureWritable("Projets/rapport.docx")
	if err == nil {
		t.Fatal("un .docx est déclaré modifiable")
	}
	if !strings.Contains(err.Error(), "["+CodeReadOnly+"]") {
		t.Errorf("erreur sans le code attendu : %v", err)
	}

	for _, nom := range []string{"note.md", "note.txt", "sans-extension"} {
		if err := EnsureWritable(nom); err != nil {
			t.Errorf("EnsureWritable(%q) refuse à tort : %v", nom, err)
		}
	}
}

// TestRenderDispatcheSurLeNom vérifie que c'est bien le **nom** qui décide, ici
// et nulle part ailleurs.
func TestRenderDispatcheSurLeNom(t *testing.T) {
	blocs, err := Render("note.md", []byte("# Titre"))
	if err != nil {
		t.Fatalf("Markdown : %v", err)
	}
	if len(blocs) != 1 || blocs[0].Kind != markdown.KindHeading {
		t.Errorf("le Markdown n'a pas été interprété : %+v", blocs)
	}

	blocs, err = Render("note.txt", []byte("# pas un titre"))
	if err != nil {
		t.Fatalf("texte brut : %v", err)
	}
	if len(blocs) != 1 || blocs[0].Kind != markdown.KindPlain {
		t.Errorf("le texte brut a été interprété : %+v", blocs)
	}

	// Le même contenu, sous un nom de document, part chez l'analyseur d'archive
	// et échoue — ce qui prouve l'aiguillage.
	if _, err := Render("rapport.docx", []byte("# pas une archive")); err == nil {
		t.Error("un .docx illisible passe sans erreur")
	} else if !strings.Contains(err.Error(), "["+documents.CodeInvalid+"]") {
		t.Errorf("erreur sans le code attendu : %v", err)
	}
}
