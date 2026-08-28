package mobile

import (
	"strings"
	"testing"
)

// Reproduction du second défaut constaté à l'usage : modifier une note depuis
// l'interface web faisait systématiquement conclure à un conflit lors de
// l'écriture suivante depuis le téléphone, avec création d'un doublon.
//
// La cause n'était pas la détection de conflit, qui fonctionne, mais l'ETag
// local qui n'était jamais rafraîchi : le téléphone poussait toujours en
// annonçant une version que le serveur avait dépassée.

// modifierDepuisLeNavigateur simule une modification faite ailleurs : le
// contenu change, l'ETag aussi.
func (f *fakeServer) modifierDepuisLeNavigateur(spacePath, contenu string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.seq++
	f.files[spacePath] = []byte(contenu)
	f.etags[spacePath] = `"navigateur-` + itoa(f.seq) + `"`
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b []byte
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	return string(b)
}

func TestModificationDepuisLeNavigateurNeCreePasDeConflit(t *testing.T) {
	app, server, _ := prepare(t)

	if _, err := app.CreateNoteJSON("", "partagee", "version initiale"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}

	// Quelqu'un modifie la note depuis l'interface web.
	server.modifierDepuisLeNavigateur("Notes/partagee.md", "modifiée dans le navigateur")

	// Le téléphone rouvre la note : il doit voir la version à jour.
	contenu, err := app.ReadNote("partagee.md")
	if err != nil {
		t.Fatalf("ReadNote: %v", err)
	}
	if contenu != "modifiée dans le navigateur" {
		t.Fatalf("contenu lu = %q, attendu la version du navigateur ; "+
			"l'ETag local est resté périmé", contenu)
	}

	// Puis l'utilisateur écrit par-dessus depuis le téléphone.
	if err := app.WriteNote("partagee.md", "puis modifiée sur le téléphone"); err != nil {
		t.Fatalf("WriteNote: %v", err)
	}

	var sync syncResult
	raw, err := app.SyncJSON()
	if err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	decodeJSON(t, raw, &sync)

	if len(sync.Conflicts) != 0 {
		t.Errorf("%d conflit(s) signalé(s), aucun attendu : %+v", len(sync.Conflicts), sync.Conflicts)
	}
	if sync.Pushed != 1 {
		t.Errorf("Pushed = %d, attendu 1", sync.Pushed)
	}

	server.mu.Lock()
	final := string(server.files["Notes/partagee.md"])
	nb := 0
	for p := range server.files {
		if strings.Contains(p, "conflit") {
			nb++
		}
	}
	server.mu.Unlock()

	if final != "puis modifiée sur le téléphone" {
		t.Errorf("contenu distant = %q", final)
	}
	if nb != 0 {
		t.Errorf("%d doublon(s) « conflit » créé(s) sans raison", nb)
	}
}

// Le vrai conflit doit rester détecté : si le téléphone a des modifications
// non synchronisées et que la note change ailleurs, les deux versions comptent.
func TestVraiConflitToujoursDetecte(t *testing.T) {
	app, server, _ := prepare(t)

	if _, err := app.CreateNoteJSON("", "partagee", "version initiale"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}

	// Le téléphone passe hors connexion et écrit.
	server.setOffline(true)
	if err := app.WriteNote("partagee.md", "écrit sur le téléphone"); err != nil {
		t.Fatalf("WriteNote: %v", err)
	}

	// Pendant ce temps, la note change côté serveur.
	server.setOffline(false)
	server.modifierDepuisLeNavigateur("Notes/partagee.md", "écrit dans le navigateur")

	var sync syncResult
	raw, err := app.SyncJSON()
	if err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	decodeJSON(t, raw, &sync)

	if len(sync.Conflicts) != 1 {
		t.Fatalf("%d conflit(s), 1 attendu : les deux versions divergent vraiment", len(sync.Conflicts))
	}

	server.mu.Lock()
	defer server.mu.Unlock()
	if got := string(server.files["Notes/partagee.md"]); got != "écrit dans le navigateur" {
		t.Errorf("la note distante = %q, la version du navigateur devait être conservée", got)
	}
	copie := "Notes/" + sync.Conflicts[0].CopyPath
	if got := string(server.files[copie]); got != "écrit sur le téléphone" {
		t.Errorf("la copie %q = %q, la version du téléphone devait y être conservée", copie, got)
	}
}

// Une note modifiée localement ne doit jamais être écrasée par le
// rafraîchissement à l'ouverture : le brouillon de l'utilisateur prime jusqu'à
// la synchronisation.
func TestOuvertureNEcrasePasUnBrouillonLocal(t *testing.T) {
	app, server, _ := prepare(t)

	if _, err := app.CreateNoteJSON("", "brouillon", "version initiale"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}

	if err := app.WriteNote("brouillon.md", "mon travail en cours"); err != nil {
		t.Fatalf("WriteNote: %v", err)
	}
	server.modifierDepuisLeNavigateur("Notes/brouillon.md", "version du navigateur")

	contenu, err := app.ReadNote("brouillon.md")
	if err != nil {
		t.Fatalf("ReadNote: %v", err)
	}
	if contenu != "mon travail en cours" {
		t.Errorf("contenu = %q, le brouillon local a été écrasé", contenu)
	}
}

// Hors connexion, l'ouverture doit rester immédiate : après un premier échec
// réseau, les suivantes ne retentent pas l'aller-retour.
func TestOuvertureHorsConnexionNeRetentePasLeReseau(t *testing.T) {
	app, server, _ := prepare(t)

	if _, err := app.CreateNoteJSON("", "note", "contenu"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}

	server.setOffline(true)

	// Premier accès : il tente, échoue, et sert le cache.
	if _, err := app.ReadNote("note.md"); err != nil {
		t.Fatalf("ReadNote hors connexion: %v", err)
	}
	if !app.recentlyOffline() {
		t.Fatal("l'échec réseau n'a pas été mémorisé")
	}

	// Les suivants servent le cache sans retenter.
	contenu, err := app.ReadNote("note.md")
	if err != nil {
		t.Fatalf("ReadNote: %v", err)
	}
	if contenu != "contenu" {
		t.Errorf("contenu = %q", contenu)
	}
}
