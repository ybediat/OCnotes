package mobile

import "testing"

// Le serveur répond : le nouveau chemin est celui que le cœur calcule, et le
// cache doit le suivre sans rien remettre en file — le serveur est déjà à
// jour.
func TestMoveEnLigne(t *testing.T) {
	app, _, _ := prepare(t)

	if _, err := app.CreateFolderJSON("", "Projets"); err != nil {
		t.Fatalf("CreateFolderJSON: %v", err)
	}
	if _, err := app.CreateNoteJSON("", "note.md", "# note\n"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}

	newPath, err := app.Move("note.md", "Projets")
	if err != nil {
		t.Fatalf("Move: %v", err)
	}
	if newPath != "Projets/note.md" {
		t.Fatalf("chemin = %q, attendu Projets/note.md", newPath)
	}

	raw, err := app.ReadNote("Projets/note.md")
	if err != nil {
		t.Fatalf("ReadNote au nouveau chemin: %v", err)
	}
	if raw != "# note\n" {
		t.Errorf("contenu = %q", raw)
	}
	if _, err := app.ReadNote("note.md"); err == nil {
		t.Error("l'ancien chemin est encore lisible")
	}
}

// Même schéma que le repli de Rename : le chemin cible se calcule sans
// réseau, et il doit être **celui que la synchronisation atteindra plus
// tard** — sinon la note se retrouve à deux endroits différents selon qu'on
// regarde le cache ou le résultat de la prochaine synchronisation.
func TestMoveHorsConnexion(t *testing.T) {
	app, server, _ := prepare(t)

	if _, err := app.CreateFolderJSON("", "Projets"); err != nil {
		t.Fatalf("CreateFolderJSON: %v", err)
	}
	if _, err := app.CreateNoteJSON("", "note.md", "# note\n"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}

	server.setOffline(true)
	newPath, err := app.Move("note.md", "Projets")
	if err != nil {
		t.Fatalf("Move hors connexion: %v", err)
	}
	if newPath != "Projets/note.md" {
		t.Fatalf("chemin = %q, attendu Projets/note.md", newPath)
	}

	raw, err := app.ReadNote("Projets/note.md")
	if err != nil {
		t.Fatalf("ReadNote au nouveau chemin: %v", err)
	}
	if raw != "# note\n" {
		t.Errorf("contenu = %q", raw)
	}

	server.setOffline(false)
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	if raw, err := app.ReadNote("Projets/note.md"); err != nil || raw != "# note\n" {
		t.Fatalf("après synchronisation: contenu = %q, err = %v", raw, err)
	}
}

// Un dossier déplacé dans son propre sous-arbre doit être refusé avant
// d'atteindre le serveur — Library.Move porte la règle, la façade ne doit pas
// la contourner.
func TestMoveDansSoiMemeEstRefuse(t *testing.T) {
	app, _, _ := prepare(t)

	if _, err := app.CreateFolderJSON("", "Projets"); err != nil {
		t.Fatalf("CreateFolderJSON: %v", err)
	}

	if _, err := app.Move("Projets", "Projets"); err == nil {
		t.Error("déplacer un dossier dans lui-même aurait dû être refusé")
	}
}
