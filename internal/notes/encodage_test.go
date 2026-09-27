package notes

import (
	"testing"

	"github.com/ybediat/OpenNote/internal/markdown"
)

// L'aperçu et le titre décodent le fichier : un .txt Latin-1 venu de Windows
// se lit avec ses accents.
func TestTitreEtApercuDecodentLeFichier(t *testing.T) {
	latin1 := []byte("r\xe9sum\xe9 de l'\xe9t\xe9\nsuite")
	note := Note{Name: "journal.txt", DisplayName: DisplayName("journal.txt")}
	if got := TitleOf(note, latin1); got != "résumé de l'été" {
		t.Errorf("TitleOf = %q", got)
	}

	blocs, err := Render("journal.txt", latin1)
	if err != nil {
		t.Fatalf("Render : %v", err)
	}
	if len(blocs) == 0 || blocs[0].Text != "résumé de l'été\nsuite" {
		t.Errorf("Render = %+v", blocs)
	}
}

// Une BOM UTF-8 en tête — Windows en pose volontiers — décalait le « # » : la
// note perdait son titre, et son aperçu son premier intertitre.
func TestUneBOMUTF8NeMasquePasLeTitre(t *testing.T) {
	contenu := []byte("\xef\xbb\xbf# Titre\n\ntexte")
	note := Note{Name: "note.md", DisplayName: DisplayName("note.md")}
	if got := TitleOf(note, contenu); got != "Titre" {
		t.Errorf("TitleOf = %q, attendu %q", got, "Titre")
	}

	blocs, err := Render("note.md", contenu)
	if err != nil {
		t.Fatalf("Render : %v", err)
	}
	if len(blocs) == 0 || blocs[0].Kind != markdown.KindHeading {
		t.Errorf("le titre n'est pas reconnu derrière la BOM : %+v", blocs)
	}
}
