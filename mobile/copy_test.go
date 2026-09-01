package mobile

import (
	"strings"
	"testing"
)

// La copie en ligne : le serveur reçoit un second fichier, l'original reste en
// place, et le cache connaît la copie sans qu'un nouveau listing soit demandé.
func TestCopyEnLigne(t *testing.T) {
	app, server, _ := prepare(t)

	if _, err := app.CreateFolderJSON("", "Projets"); err != nil {
		t.Fatalf("CreateFolderJSON: %v", err)
	}
	if _, err := app.CreateNoteJSON("", "note.md", "# note\n"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}

	raw, err := app.CopyJSON("note.md", "Projets")
	if err != nil {
		t.Fatalf("CopyJSON: %v", err)
	}
	var copie noteRef
	decodeJSON(t, raw, &copie)
	if copie.Path != "Projets/note.md" {
		t.Fatalf("chemin de la copie = %q, attendu Projets/note.md", copie.Path)
	}

	// L'original n'a pas bougé…
	if _, err := app.ReadNote("note.md"); err != nil {
		t.Errorf("l'original n'est plus lisible: %v", err)
	}
	// …et la copie porte le même contenu, lisible immédiatement.
	if got, err := app.ReadNote("Projets/note.md"); err != nil || got != "# note\n" {
		t.Fatalf("copie : contenu = %q, err = %v", got, err)
	}

	server.mu.Lock()
	_, source := server.files["Notes/note.md"]
	_, cible := server.files["Notes/Projets/note.md"]
	server.mu.Unlock()
	if !source || !cible {
		t.Errorf("serveur : source=%v cible=%v, les deux attendus (%v)", source, cible, keys(server.files))
	}
}

// Copier une note dans son propre dossier revient à la dupliquer : le nom reçoit
// le suffixe « (2) », exactement comme une création qui bute sur un nom pris.
func TestCopyDansLeMemeDossierAjouteUnSuffixe(t *testing.T) {
	app, _, _ := prepare(t)

	if _, err := app.CreateNoteJSON("", "note.md", "# note\n"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}

	raw, err := app.CopyJSON("note.md", "")
	if err != nil {
		t.Fatalf("CopyJSON: %v", err)
	}
	var copie noteRef
	decodeJSON(t, raw, &copie)
	if copie.Name != "note (2).md" {
		t.Fatalf("Name = %q, attendu note (2).md", copie.Name)
	}

	// Une seconde copie ne s'écrase pas sur la première.
	raw, err = app.CopyJSON("note.md", "")
	if err != nil {
		t.Fatalf("seconde CopyJSON: %v", err)
	}
	decodeJSON(t, raw, &copie)
	if copie.Name != "note (3).md" {
		t.Errorf("Name = %q, attendu note (3).md", copie.Name)
	}
}

// L'extension de la source est conservée : une copie ne convertit jamais un
// « .txt » en Markdown, pas plus qu'un renommage.
func TestCopyGardeLExtensionDeLaSource(t *testing.T) {
	app, _, _ := prepare(t)

	if _, err := app.CreateNoteJSON("", "liste.txt", "- pain\n- sel\n"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}

	raw, err := app.CopyJSON("liste.txt", "")
	if err != nil {
		t.Fatalf("CopyJSON: %v", err)
	}
	var copie noteRef
	decodeJSON(t, raw, &copie)
	if copie.Name != "liste (2).txt" {
		t.Errorf("Name = %q, attendu liste (2).txt", copie.Name)
	}
}

// La copie prend le contenu que l'utilisateur a sous les yeux : sa version
// locale non synchronisée, pas celle encore présente sur le serveur.
func TestCopyPrendLaVersionLocaleNonSynchronisee(t *testing.T) {
	app, _, _ := prepare(t)

	if _, err := app.CreateNoteJSON("", "note.md", "version serveur\n"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	// Modification locale, pas encore poussée.
	if err := app.WriteNote("note.md", "version locale\n"); err != nil {
		t.Fatalf("WriteNote: %v", err)
	}

	raw, err := app.CopyJSON("note.md", "")
	if err != nil {
		t.Fatalf("CopyJSON: %v", err)
	}
	var copie noteRef
	decodeJSON(t, raw, &copie)

	if got, err := app.ReadNote(copie.Path); err != nil || got != "version locale\n" {
		t.Fatalf("copie : contenu = %q, err = %v — la version vue par l'utilisateur était attendue", got, err)
	}
}

// Copier hors connexion ne peut pas échouer sur une note en cache : la copie
// vit dans le cache et part à la première synchronisation.
func TestCopyHorsConnexion(t *testing.T) {
	app, server, _ := prepare(t)

	if _, err := app.CreateNoteJSON("", "note.md", "# note\n"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}

	server.setOffline(true)
	raw, err := app.CopyJSON("note.md", "")
	if err != nil {
		t.Fatalf("CopyJSON hors connexion: %v", err)
	}
	var copie noteRef
	decodeJSON(t, raw, &copie)
	if copie.Name != "note (2).md" {
		t.Fatalf("Name = %q, attendu note (2).md", copie.Name)
	}
	if app.PendingCount() == 0 {
		t.Error("la copie aurait dû être mise en file")
	}
	if got, err := app.ReadNote(copie.Path); err != nil || got != "# note\n" {
		t.Fatalf("copie hors connexion : contenu = %q, err = %v", got, err)
	}

	server.setOffline(false)
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	server.mu.Lock()
	_, arrivee := server.files["Notes/note (2).md"]
	server.mu.Unlock()
	if !arrivee {
		t.Errorf("la copie n'est pas arrivée sur le serveur: %v", keys(server.files))
	}
}

// Une note dont le contenu a été évincé du cache ne peut pas être copiée hors
// connexion : CopyJSON le signale comme ReadNote, ce qui laisse une copie de
// sélection partiellement en cache rapporter ses échecs note par note.
func TestCopyHorsConnexionSansContenuEnCache(t *testing.T) {
	app, server, dataDir := prepare(t)

	if _, err := app.CreateNoteJSON("", "ancienne.md", "aaaa"); err != nil {
		t.Fatalf("CreateNoteJSON ancienne: %v", err)
	}
	if _, err := app.CreateNoteJSON("", "recente.md", "bbbb"); err != nil {
		t.Fatalf("CreateNoteJSON recente: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	// Quota minuscule : la plus ancienne note perd son contenu, mais reste à
	// l'inventaire.
	if err := app.SetCacheQuota(4); err != nil {
		t.Fatalf("SetCacheQuota: %v", err)
	}
	if _, _, ok := app.cache.Get("ancienne.md"); ok {
		t.Fatal("ancienne.md aurait dû être évincée")
	}

	server.Close()
	offline, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := offline.Restore(fakeToken); err != nil {
		t.Fatalf("Restore: %v", err)
	}

	_, err = offline.CopyJSON("ancienne.md", "Projets")
	if err == nil {
		t.Fatal("copier une note au contenu évincé aurait dû échouer")
	}
	if code := ErrorCode(err.Error()); code != "CONTENT_NOT_CACHED" {
		t.Errorf("code = %q, attendu CONTENT_NOT_CACHED (erreur: %v)", code, err)
	}
}

// Un document ne se copie pas : un PUT de nos octets le corromprait. Le refus
// est décidé sur l'extension, avant tout appel réseau.
func TestCopyRefuseUnDocument(t *testing.T) {
	app, _, _ := prepare(t)

	_, err := app.CopyJSON("rapport.docx", "Projets")
	if err == nil {
		t.Fatal("copier un .docx aurait dû être refusé")
	}
	if code := ErrorCode(err.Error()); code != CodeUnsupported {
		t.Errorf("code = %q, attendu %s (erreur: %v)", code, CodeUnsupported, err)
	}
}

// Un dossier n'est pas une note : la copie récursive est hors périmètre.
func TestCopyRefuseUnDossier(t *testing.T) {
	app, _, _ := prepare(t)

	if _, err := app.CreateFolderJSON("", "Projets"); err != nil {
		t.Fatalf("CreateFolderJSON: %v", err)
	}
	if _, err := app.CopyJSON("Projets", ""); err == nil {
		t.Error("copier un dossier aurait dû être refusé")
	}
}

// Copier « la racine » n'a pas de sens : le chemin vide est refusé.
func TestCopyRefuseLaRacine(t *testing.T) {
	app, _, _ := prepare(t)

	_, err := app.CopyJSON("", "Projets")
	if err == nil {
		t.Fatal("copier la racine aurait dû être refusé")
	}
	if code := ErrorCode(err.Error()); code != "PATH_EMPTY" {
		t.Errorf("code = %q, attendu PATH_EMPTY (erreur: %v)", code, err)
	}
}

// La copie d'une note jamais synchronisée — créée hors connexion — fonctionne :
// son contenu est dans le cache, c'est tout ce qu'il faut.
func TestCopyDuneNoteCreeeHorsConnexion(t *testing.T) {
	app, server, _ := prepare(t)
	server.setOffline(true)

	if _, err := app.CreateNoteJSON("", "brouillon.md", "jeté sur le papier\n"); err != nil {
		t.Fatalf("CreateNoteJSON hors connexion: %v", err)
	}

	raw, err := app.CopyJSON("brouillon.md", "")
	if err != nil {
		t.Fatalf("CopyJSON: %v", err)
	}
	var copie noteRef
	decodeJSON(t, raw, &copie)
	if copie.Name != "brouillon (2).md" {
		t.Errorf("Name = %q, attendu brouillon (2).md", copie.Name)
	}

	server.setOffline(false)
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	server.mu.Lock()
	original := strings.TrimSpace(string(server.files["Notes/brouillon.md"]))
	copieServeur := strings.TrimSpace(string(server.files["Notes/brouillon (2).md"]))
	server.mu.Unlock()
	if original == "" || copieServeur == "" {
		t.Errorf("après synchro : original=%q copie=%q, les deux attendus", original, copieServeur)
	}
}
