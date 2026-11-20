package mobile

import (
	"fmt"
	"strings"
	"testing"
)

// Ordre de grandeur sur desktop d'une recherche sur toute la bibliothèque :
// 500 notes de 4 ko et une de 295 ko, comme l'arborescence de test. Le mot
// absent est le pire cas, toutes les notes sont lues jusqu'au bout. Le seuil
// de l'ordre de travail (500 ms) se juge sur le téléphone, pas ici.
func BenchmarkSearchJSONMotAbsent(b *testing.B) {
	app, err := NewApp(b.TempDir())
	if err != nil {
		b.Fatal(err)
	}
	if err := app.StartLocal(); err != nil {
		b.Fatal(err)
	}
	paragraphe := "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor. "
	for i := 0; i < 500; i++ {
		if _, err := app.CreateNoteJSON("", fmt.Sprintf("Note %03d", i), strings.Repeat(paragraphe, 50)); err != nil {
			b.Fatal(err)
		}
	}
	if _, err := app.CreateNoteJSON("", "Grande", strings.Repeat(paragraphe+"\n", 3600)); err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		if _, err := app.SearchJSON("introuvable", ""); err != nil {
			b.Fatal(err)
		}
	}
}
