package documents

import (
	"fmt"
	"strings"
	"testing"

	"github.com/ybediat/OpenNote/internal/markdown"
)

// TestDocxEtOdtConvergent est le test le plus rentable du paquet.
//
// Les deux fixtures sortent du **même** HTML, converti deux fois par
// LibreOffice. Elles décrivent donc le même document, et les deux analyseurs
// doivent en tirer les mêmes blocs — mêmes textes, mêmes niveaux, mêmes
// profondeurs, mêmes spans.
//
// Un test format par format ne compare l'analyseur qu'à lui-même : il fige ce
// qu'on a compris du format le jour où on l'a écrit, erreurs comprises. Celui-ci
// confronte deux compréhensions indépendantes du même contenu, et c'est ce qui
// attrape les erreurs d'interprétation du modèle.
func TestDocxEtOdtConvergent(t *testing.T) {
	depuisDocx := lireDocx(t, "exemple.docx")
	depuisOdt := lireOdt(t, "exemple.odt")

	// Deux listes vides convergeraient sans rien prouver : la fixture porte
	// huit titres, six éléments de liste, trois lignes de tableau, un saut de
	// page et quelques paragraphes.
	if len(depuisDocx) < 20 {
		t.Fatalf("%d blocs seulement depuis le .docx — la fixture n'a pas été analysée", len(depuisDocx))
	}

	if len(depuisDocx) != len(depuisOdt) {
		t.Errorf("%d blocs depuis le .docx, %d depuis l'.odt", len(depuisDocx), len(depuisOdt))
	}

	n := len(depuisDocx)
	if len(depuisOdt) > n {
		n = len(depuisOdt)
	}
	for i := 0; i < n; i++ {
		a, b := bloc(depuisDocx, i), bloc(depuisOdt, i)
		if resume(a) != resume(b) {
			t.Errorf("bloc %d diverge :\n  .docx : %s\n  .odt  : %s", i, resume(a), resume(b))
		}
	}
}

func bloc(blocs []markdown.Block, i int) markdown.Block {
	if i < len(blocs) {
		return blocs[i]
	}
	return markdown.Block{Kind: "(absent)"}
}

// resume met un bloc à plat pour que la comparaison dise *où* ça diverge, et
// pas seulement que ça diverge.
func resume(b markdown.Block) string {
	var s strings.Builder
	fmt.Fprintf(&s, "%s", b.Kind)
	if b.Level > 0 {
		fmt.Fprintf(&s, " niveau=%d", b.Level)
	}
	if b.Depth > 0 {
		fmt.Fprintf(&s, " profondeur=%d", b.Depth)
	}
	if b.Number > 0 {
		fmt.Fprintf(&s, " numéro=%d", b.Number)
	}
	if b.Header {
		s.WriteString(" en-tête")
	}
	if b.Text != "" {
		fmt.Fprintf(&s, " texte=%q", b.Text)
	}
	if len(b.Cells) > 0 {
		fmt.Fprintf(&s, " cellules=%q", b.Cells)
	}
	for _, span := range b.Spans {
		fmt.Fprintf(&s, " [%s %d:%d", span.Style, span.Start, span.End)
		if span.Href != "" {
			fmt.Fprintf(&s, " -> %s", span.Href)
		}
		s.WriteString("]")
	}
	return s.String()
}
