package mobile

import (
	"encoding/json"
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

// Reproduction du troisième défaut constaté à l'usage : quatre copies de
// conflit pour un seul vrai conflit.
//
// L'éditeur enregistre en quittant l'écran, sans regarder si le texte a bougé.
// Consulter une note suffisait donc à la marquer sale, et une note sale n'est
// pas rafraîchie à l'ouverture — son ETag vieillissait précisément pendant la
// fenêtre où il ne devait pas. La première modification faite depuis le
// navigateur devenait un conflit, avec sa copie, alors que le téléphone
// n'avait rien à dire. Autant de copies que de notes simplement consultées.
func TestConsulterUneNoteNeLaRendPasConflictuelle(t *testing.T) {
	app, server, _ := prepare(t)

	if _, err := app.CreateNoteJSON("", "partagee", "version initiale"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}

	// L'utilisateur ouvre la note, la lit, et referme l'écran. Le retour
	// arrière enregistre : c'est ce couple d'appels que fait l'éditeur.
	contenu, err := app.ReadNote("partagee.md")
	if err != nil {
		t.Fatalf("ReadNote: %v", err)
	}
	if err := app.WriteNote("partagee.md", contenu); err != nil {
		t.Fatalf("WriteNote: %v", err)
	}
	// Errorf et non Fatalf : si la garde saute, la suite du test montre ce que
	// cette écriture fantôme provoque, ce qui est tout l'intérêt.
	if n := app.PendingCount(); n != 0 {
		t.Errorf("%d opération(s) en attente après une simple consultation", n)
	}

	// Quelqu'un modifie la note depuis l'interface web.
	server.modifierDepuisLeNavigateur("Notes/partagee.md", "modifiée dans le navigateur")

	var sync syncResult
	raw, err := app.SyncJSON()
	if err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	decodeJSON(t, raw, &sync)

	if len(sync.Conflicts) != 0 {
		t.Errorf("%d conflit(s) signalé(s), aucun attendu : la note n'a été que lue ; %+v",
			len(sync.Conflicts), sync.Conflicts)
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

	if nb != 0 {
		t.Errorf("%d doublon(s) « conflit » créé(s) pour une note seulement consultée", nb)
	}
	if final != "modifiée dans le navigateur" {
		t.Errorf("contenu distant = %q, la version du navigateur aurait dû rester", final)
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

// La façade doit exposer les conflits au-delà du seul rapport de la passe et
// transmettre la décision locale au Store, sans que Kotlin ait à manipuler un
// ETag ni une structure Go.
func TestResolveConflictJSONGardeLeLocal(t *testing.T) {
	app, server, _ := prepare(t)

	if _, err := app.CreateNoteJSON("", "partagee", "version initiale"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON initial: %v", err)
	}
	if err := app.WriteNote("partagee.md", "version locale"); err != nil {
		t.Fatalf("WriteNote: %v", err)
	}
	server.modifierDepuisLeNavigateur("Notes/partagee.md", "version serveur")
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON conflit: %v", err)
	}

	raw, err := app.ConflictsJSON()
	if err != nil {
		t.Fatalf("ConflictsJSON: %v", err)
	}
	var conflicts []conflictInfo
	decodeJSON(t, raw, &conflicts)
	if len(conflicts) != 1 || conflicts[0].ID == "" {
		t.Fatalf("conflits ouverts = %+v", conflicts)
	}

	request, err := json.Marshal(conflictResolutionRequest{ID: conflicts[0].ID, Resolution: "local"})
	if err != nil {
		t.Fatalf("requête: %v", err)
	}
	if _, err := app.ResolveConflictJSON(string(request)); err != nil {
		t.Fatalf("ResolveConflictJSON: %v", err)
	}

	raw, err = app.ConflictsJSON()
	if err != nil {
		t.Fatalf("ConflictsJSON après résolution: %v", err)
	}
	decodeJSON(t, raw, &conflicts)
	if len(conflicts) != 0 {
		t.Fatalf("conflits encore ouverts = %+v", conflicts)
	}
	server.mu.Lock()
	defer server.mu.Unlock()
	if got := string(server.files["Notes/partagee.md"]); got != "version locale" {
		t.Errorf("contenu serveur = %q, version locale attendue", got)
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
