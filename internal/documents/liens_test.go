package documents

import (
	"testing"

	"github.com/ybediat/OpenNote/internal/markdown"
)

// Un .docx ou un .odt reçu sur le serveur porte des liens choisis par son
// auteur. Ils passent la même règle que ceux d'une note : un lien vers un
// fichier de l'appareil reste du texte.
func TestLienDocumentNonOuvrableDevientTexte(t *testing.T) {
	for dest, garde := range map[string]bool{
		"https://opencloud.eu/":      true,
		"file:///sdcard/releve.pdf":  false,
		"content://com.autre/secret": false,
		"Document2.docx":             false,
	} {
		var c constructeur
		c.write("un lien")
		c.span(0, markdown.StyleLink, dest)
		if got := len(c.spans) == 1; got != garde {
			t.Errorf("%s : lien gardé = %v, attendu %v", dest, got, garde)
		}
	}
}
