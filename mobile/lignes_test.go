package mobile

import (
	"strings"
	"testing"
)

// Une note écrite sous Windows garde ses « \r\n » : l'éditeur ne reçoit que des
// « \n », et ce qu'il y ajoute part sur le serveur dans la convention du
// fichier. Avant, une ligne tapée partait en « \n » au milieu de lignes en
// « \r\n ».
func TestUneNoteWindowsGardeSesFinsDeLigne(t *testing.T) {
	app, server, _ := prepare(t)

	const nom = "windows.md"
	server.mu.Lock()
	server.files["Notes/"+nom] = []byte("# Titre\r\n\r\n- un\r\n")
	server.etags["Notes/"+nom] = server.nextETag()
	server.mu.Unlock()

	texte, err := app.ReadNote(nom)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(texte, "\r") {
		t.Fatalf("l'éditeur reçoit des « \\r » : %q", texte)
	}

	if err := app.WriteEditedNote("", nom, texte+"- deux\n"); err != nil {
		t.Fatal(err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatal(err)
	}

	server.mu.Lock()
	recu := string(server.files["Notes/"+nom])
	server.mu.Unlock()
	if veut := "# Titre\r\n\r\n- un\r\n- deux\r\n"; recu != veut {
		t.Errorf("le serveur a reçu %q, veut %q", recu, veut)
	}
}

// Effacer tous les sauts de ligne puis en retaper ne change pas la convention
// du fichier : elle a été retenue à la lecture, pas redevinée dans un cache
// qui, entre-temps, n'en montre plus aucun.
func TestLaFinDeLigneSurvitAUnTexteSansSautDeLigne(t *testing.T) {
	app, server, _ := prepare(t)

	const nom = "windows.txt"
	server.mu.Lock()
	server.files["Notes/"+nom] = []byte("un\r\ndeux\r\n")
	server.etags["Notes/"+nom] = server.nextETag()
	server.mu.Unlock()

	if _, err := app.ReadNote(nom); err != nil {
		t.Fatal(err)
	}
	if err := app.WriteNote(nom, "undeux"); err != nil {
		t.Fatal(err)
	}
	if err := app.WriteNote(nom, "un\ndeux\n"); err != nil {
		t.Fatal(err)
	}

	contenu, _, ok := app.cache.Get(nom)
	if !ok {
		t.Fatal("la note n'est pas dans le cache")
	}
	if got := string(contenu); got != "un\r\ndeux\r\n" {
		t.Errorf("cache : %q, veut les « \\r\\n » d'origine", got)
	}
}

// Une note créée par l'application, jamais lue, s'écrit en « \n ».
func TestUneNoteNeuveSEcritEnLF(t *testing.T) {
	app, _, _ := prepare(t)

	if err := app.WriteNote("neuve.md", "# Neuve\r\n\r\ncollé\n"); err != nil {
		t.Fatal(err)
	}
	contenu, _, _ := app.cache.Get("neuve.md")
	if got := string(contenu); got != "# Neuve\n\ncollé\n" {
		t.Errorf("cache : %q", got)
	}
}

// L'aperçu d'un texte Windows ne montre aucun « \r » : Compose ne sait pas le
// dessiner.
func TestLApercuNeMontrePasDeRetourChariot(t *testing.T) {
	app, server, _ := prepare(t)

	const nom = "config.ini"
	server.mu.Lock()
	server.files["Notes/"+nom] = []byte("[section]\r\ncle=valeur\r\n")
	server.etags["Notes/"+nom] = server.nextETag()
	server.mu.Unlock()

	sortie, err := app.RenderFileJSON(nom)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(sortie, `\r`) {
		t.Errorf("l'aperçu porte un « \\r » : %s", sortie)
	}
}
