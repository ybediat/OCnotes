package notes

import (
	"testing"
	"unicode/utf16"

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

// Un texte du champ qui commence par U+FEFF (collé depuis Windows) garde ses
// positions, recalées d'une unité : cases et plan restent utilisables.
func TestUneBOMEnTeteDuChampRecaleLesPositions(t *testing.T) {
	champ := "\ufeff# Titre\n\n- [ ] tâche\n"
	blocs, err := Render("note.md", []byte(champ))
	if err != nil {
		t.Fatal(err)
	}
	unites := utf16.Encode([]rune(champ))
	vus := 0
	for _, b := range blocs {
		if b.Source == nil {
			t.Errorf("bloc %s sans position", b.Kind)
			continue
		}
		vus++
		p := *b.Source
		switch b.Kind {
		case markdown.KindHeading:
			if got := string(utf16.Decode(unites[p : p+5])); got != "Titre" {
				t.Errorf("titre : position sur %q", got)
			}
		case markdown.KindTask:
			if got := string(utf16.Decode(unites[p : p+3])); got != "[ ]" {
				t.Errorf("tâche : position sur %q", got)
			}
		}
	}
	if vus != 2 {
		t.Errorf("%d blocs positionnés, attendu 2", vus)
	}
}

// Une fin de ligne « \r\n » change d'autres positions : là, on se tait.
func TestDesFinsDeLigneWindowsTaisentLesPositions(t *testing.T) {
	blocs, _ := Render("note.md", []byte("# Titre\r\n\r\n- [ ] tâche\r\n"))
	for _, b := range blocs {
		if b.Source != nil {
			t.Errorf("bloc %s positionné malgré les \\r\\n", b.Kind)
		}
	}
}
