package mobile

import (
	"crypto/sha256"
	"encoding/hex"
	"io/fs"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// Ces tests établissent ce que le chantier « compte local » suppose : un profil
// local et un profil serveur — deux App sur deux dossiers — ne partagent aucun
// état. Chaque profil Android a son dossier et son cœur Go (invariant 1) ; si
// l'un pouvait atteindre l'autre, « Compte local » à côté d'un serveur serait
// une promesse fausse.

// empreinteDossier photographie un dossier : chemin relatif → SHA-256 du
// contenu. Deux photographies égales disent qu'aucun octet n'a bougé, y compris
// l'index et la configuration, pas seulement les notes.
func empreinteDossier(t *testing.T, dir string) map[string]string {
	t.Helper()
	out := map[string]string{}
	err := filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		octets, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(dir, p)
		somme := sha256.Sum256(octets)
		out[filepath.ToSlash(rel)] = hex.EncodeToString(somme[:])
		return nil
	})
	if err != nil {
		t.Fatalf("empreinte de %s: %v", dir, err)
	}
	return out
}

// lues donne ce que ReadNote rend : le texte sans retour chariot, la fin de
// ligne étant remise à l'écriture.
func lues(m map[string]string) map[string]string {
	out := map[string]string{}
	for k, v := range m {
		out[k] = strings.ReplaceAll(v, "\r\n", "\n")
	}
	return out
}

// deuxProfils monte un profil local avec des notes et un profil serveur avec les
// siennes, sur deux dossiers distincts.
func deuxProfils(t *testing.T) (local *App, dirLocal string, serveurApp *App, srv *fakeServer, dirServeur string, notesLocales map[string]string) {
	t.Helper()

	notesLocales = map[string]string{
		"prive.md":  "# Privé\n\nrien ne doit sortir\n",
		"projet.md": "projet\r\navec fins de ligne Windows\r\n",
	}
	local, dirLocal = prepareLocal(t)
	for chemin, texte := range notesLocales {
		nom := chemin[:len(chemin)-len(".md")]
		if _, err := local.CreateNoteJSON("", nom, texte); err != nil {
			t.Fatalf("CreateNoteJSON(%s): %v", nom, err)
		}
	}

	serveurApp, srv, dirServeur = prepare(t)
	return
}

// Le cas qui compte : le serveur écrit, synchronise, se déconnecte — le dossier
// local ne change pas d'un octet, et rien de local n'est parti sur le serveur.
func TestCohabitationLeServeurNeTouchePasLeLocal(t *testing.T) {
	local, dirLocal, serveurApp, srv, _, notesLocales := deuxProfils(t)
	avant := empreinteDossier(t, dirLocal)

	// Le même chemin existe des deux côtés : c'est le piège d'un état partagé.
	if _, err := serveurApp.CreateNoteJSON("", "prive", "version du serveur"); err != nil {
		t.Fatalf("CreateNoteJSON serveur: %v", err)
	}
	if _, err := serveurApp.CreateNoteJSON("", "reunion", "ordre du jour"); err != nil {
		t.Fatalf("CreateNoteJSON serveur: %v", err)
	}
	if err := serveurApp.WriteNote("reunion.md", "ordre du jour\nrévisé"); err != nil {
		t.Fatalf("WriteNote serveur: %v", err)
	}
	if res := synchroniser(t, serveurApp); res.Error != "" || res.Remaining != 0 {
		t.Fatalf("passe serveur = %+v", res)
	}

	if après := empreinteDossier(t, dirLocal); !reflect.DeepEqual(avant, après) {
		t.Errorf("le dossier local a changé pendant l'activité du serveur\navant : %v\naprès : %v", avant, après)
	}
	verifierCache(t, local, lues(notesLocales))

	// Et dans l'autre sens : le serveur n'a reçu aucune note locale.
	srv.mu.Lock()
	defer srv.mu.Unlock()
	if got := string(srv.files["Notes/prive.md"]); got != "version du serveur" {
		t.Errorf("Notes/prive.md sur le serveur = %q : une note locale a fuité ou écrasé", got)
	}
	if _, ok := srv.files["Notes/projet.md"]; ok {
		t.Errorf("projet.md, note locale, est arrivée sur le serveur : %v", keys(srv.files))
	}
}

// Se déconnecter du serveur purge le dossier du serveur et rien d'autre.
func TestCohabitationDisconnectDuServeurLaisseLeLocalIntact(t *testing.T) {
	local, dirLocal, serveurApp, _, dirServeur, notesLocales := deuxProfils(t)

	if _, err := serveurApp.CreateNoteJSON("", "reunion", "à purger"); err != nil {
		t.Fatalf("CreateNoteJSON serveur: %v", err)
	}
	if res := synchroniser(t, serveurApp); res.Error != "" {
		t.Fatalf("passe serveur = %+v", res)
	}
	avant := empreinteDossier(t, dirLocal)
	serveurAvant := empreinteDossier(t, dirServeur)

	if err := serveurApp.Disconnect(); err != nil {
		t.Fatalf("Disconnect: %v", err)
	}

	if après := empreinteDossier(t, dirLocal); !reflect.DeepEqual(avant, après) {
		t.Errorf("Disconnect du serveur a modifié le dossier local\navant : %v\naprès : %v", avant, après)
	}
	verifierCache(t, local, lues(notesLocales))
	if reflect.DeepEqual(serveurAvant, empreinteDossier(t, dirServeur)) {
		t.Error("Disconnect n'a rien purgé côté serveur : le test ne prouve rien")
	}

	// Le profil local reste utilisable : il écrit encore, sans configuration
	// de serveur ni jeton.
	if err := local.WriteNote("prive.md", "# Privé\n\nrelu après la déconnexion\n"); err != nil {
		t.Fatalf("WriteNote local après Disconnect du serveur: %v", err)
	}
	if got, err := local.ReadNote("prive.md"); err != nil || got != "# Privé\n\nrelu après la déconnexion\n" {
		t.Errorf("ReadNote = %q, %v", got, err)
	}
}

// L'inverse : agir sur le profil local ne touche pas au dossier du serveur, ni à
// sa file d'attente.
func TestCohabitationLeLocalNeTouchePasLeServeur(t *testing.T) {
	local, _, serveurApp, srv, dirServeur, _ := deuxProfils(t)

	// Un travail en attente côté serveur : la file doit survivre intacte.
	srv.setOffline(true)
	if _, err := serveurApp.CreateNoteJSON("", "en-attente", "pas encore parti"); err != nil {
		t.Fatalf("CreateNoteJSON serveur hors connexion: %v", err)
	}
	enAttente := serveurApp.PendingCount()
	if enAttente == 0 {
		t.Fatal("le serveur aurait dû garder du travail en file")
	}
	avant := empreinteDossier(t, dirServeur)

	if err := local.WriteNote("prive.md", "# Privé\n\nmodifié\n"); err != nil {
		t.Fatalf("WriteNote local: %v", err)
	}
	if _, err := local.CreateNoteJSON("", "nouvelle", "contenu"); err != nil {
		t.Fatalf("CreateNoteJSON local: %v", err)
	}
	if _, err := local.Rename("projet.md", "projet-renomme"); err != nil {
		t.Fatalf("Rename local: %v", err)
	}
	if err := local.Delete("nouvelle.md"); err != nil {
		t.Fatalf("Delete local: %v", err)
	}

	if après := empreinteDossier(t, dirServeur); !reflect.DeepEqual(avant, après) {
		t.Errorf("l'activité locale a modifié le dossier du serveur\navant : %v\naprès : %v", avant, après)
	}
	if got := serveurApp.PendingCount(); got != enAttente {
		t.Errorf("file du serveur = %d, avant = %d", got, enAttente)
	}

	srv.setOffline(false)
	if res := synchroniser(t, serveurApp); res.Error != "" || res.Remaining != 0 {
		t.Fatalf("passe serveur finale = %+v", res)
	}
}

// Aucun état de paquet ne passe d'un profil à l'autre. Le format retenu à la
// lecture (encodage, fin de ligne) est le candidat le plus plausible : deux
// notes de même chemin, l'une en CRLF, l'autre en LF, doivent chacune garder le
// leur à l'écriture, quel que soit l'ordre des lectures.
func TestCohabitationAucunEtatDePaquetPartage(t *testing.T) {
	local, _, serveurApp, srv, _, _ := deuxProfils(t)
	transportAvant := http.DefaultTransport

	if _, err := serveurApp.CreateNoteJSON("", "projet", "ligne un\nligne deux\n"); err != nil {
		t.Fatalf("CreateNoteJSON serveur: %v", err)
	}
	if res := synchroniser(t, serveurApp); res.Error != "" {
		t.Fatalf("passe serveur = %+v", res)
	}

	// Lectures croisées : chacune retient un format pour « projet.md ».
	if _, err := local.ReadNote("projet.md"); err != nil {
		t.Fatalf("ReadNote local: %v", err)
	}
	if _, err := serveurApp.ReadNote("projet.md"); err != nil {
		t.Fatalf("ReadNote serveur: %v", err)
	}

	if err := local.WriteNote("projet.md", "projet\nréécrit\n"); err != nil {
		t.Fatalf("WriteNote local: %v", err)
	}
	if err := serveurApp.WriteNote("projet.md", "ligne un\nréécrite\n"); err != nil {
		t.Fatalf("WriteNote serveur: %v", err)
	}
	if res := synchroniser(t, serveurApp); res.Error != "" || res.Remaining != 0 {
		t.Fatalf("passe serveur = %+v", res)
	}

	// Le local, lu en CRLF, doit être rendu en CRLF ; le serveur, en LF.
	octetsLocal, err := local.readBytes("projet.md")
	if err != nil {
		t.Fatalf("readBytes local: %v", err)
	}
	if got := string(octetsLocal); got != "projet\r\nréécrit\r\n" {
		t.Errorf("note locale = %q : sa fin de ligne CRLF a été perdue", got)
	}
	srv.mu.Lock()
	gotServeur := string(srv.files["Notes/projet.md"])
	srv.mu.Unlock()
	if gotServeur != "ligne un\nréécrite\n" {
		t.Errorf("note du serveur = %q : la fin de ligne du profil local a fuité", gotServeur)
	}

	if http.DefaultTransport != transportAvant {
		t.Error("http.DefaultTransport a changé pendant la cohabitation")
	}
}

// Copier une note d'un profil à l'autre, c'est ce que fait l'interface : lire
// par le cœur source, créer par le cœur cible, relire la copie. La source ne
// bouge pas, et un nom déjà pris dans la cible reçoit un suffixe au lieu d'être
// écrasé.
func TestCohabitationCopieEntreProfils(t *testing.T) {
	local, dirLocal, serveurApp, srv, _, _ := deuxProfils(t)

	// « prive » existe déjà côté serveur : la copie ne doit pas l'écraser.
	if _, err := serveurApp.CreateNoteJSON("", "prive", "version du serveur"); err != nil {
		t.Fatalf("CreateNoteJSON serveur: %v", err)
	}
	avant := empreinteDossier(t, dirLocal)

	texte, err := local.ReadNote("prive.md")
	if err != nil {
		t.Fatalf("ReadNote source: %v", err)
	}
	raw, err := serveurApp.CreateNoteJSON("", "prive", texte)
	if err != nil {
		t.Fatalf("CreateNoteJSON cible: %v", err)
	}
	var copie struct {
		Path string `json:"path"`
	}
	decodeJSON(t, raw, &copie)

	if copie.Path == "prive.md" {
		t.Fatalf("la copie a pris le chemin d'une note existante : %s", copie.Path)
	}
	if relu, err := serveurApp.ReadNote(copie.Path); err != nil || relu != texte {
		t.Errorf("copie relue = %q, %v ; attendu %q", relu, err, texte)
	}
	if relu, _ := serveurApp.ReadNote("prive.md"); relu != "version du serveur" {
		t.Errorf("la note existante de la cible a été écrasée : %q", relu)
	}
	if après := empreinteDossier(t, dirLocal); !reflect.DeepEqual(avant, après) {
		t.Error("copier a modifié le dossier de la source")
	}

	if res := synchroniser(t, serveurApp); res.Error != "" || res.Remaining != 0 {
		t.Fatalf("passe serveur = %+v", res)
	}
	srv.mu.Lock()
	defer srv.mu.Unlock()
	if got := string(srv.files["Notes/"+copie.Path]); got != texte {
		t.Errorf("serveur %s = %q, attendu %q", copie.Path, got, texte)
	}
}

// Le serveur accepte des noms que la création refuse. La copie entre profils
// (AppContainer.creerCopie, côté Kotlin) se replie alors sur SuggestName,
// après un refus portant un code NAME_*. Ce test tient les deux appuis de ce
// repli : le refus porte bien un tel code, et le nom suggéré se crée — avec
// l'extension d'origine, puisque Kotlin passe le nom entier.
func TestCohabitationCopieNomRefuseALaCreation(t *testing.T) {
	local, _, serveurApp, srv, _, _ := deuxProfils(t)

	for _, nom := range []string{"Réunion ? 3.md", "a|b<c>.txt", ".plan.md"} {
		srv.mu.Lock()
		srv.files["Notes/"+nom] = []byte("contenu de " + nom + "\n")
		srv.mu.Unlock()

		texte, err := serveurApp.ReadNote(nom)
		if err != nil {
			t.Fatalf("ReadNote(%q) côté serveur: %v", nom, err)
		}

		_, err = local.CreateNoteJSON("", nom, texte)
		if err == nil {
			t.Fatalf("CreateNoteJSON(%q) a réussi : le cas ne prouve plus rien", nom)
		}
		if code := ErrorCode(err.Error()); !strings.HasPrefix(code, "NAME_") {
			t.Fatalf("refus de %q : code %q, le repli Kotlin attend NAME_*", nom, code)
		}

		suggere := local.SuggestName(nom)
		if suggere == nom {
			t.Fatalf("SuggestName(%q) rend le nom inchangé", nom)
		}
		copie := creerJSON(t, local, suggere, texte)
		if path.Ext(copie) != path.Ext(nom) {
			t.Errorf("copie de %q sous %q : l'extension a changé", nom, copie)
		}
		if relu, err := local.ReadNote(copie); err != nil || relu != texte {
			t.Errorf("copie %s relue = %q, %v ; attendu %q", copie, relu, err, texte)
		}
	}
}

// Copier un .txt donne un .txt, dans les deux sens. Kotlin passe le segment
// entier du chemin (AppContainer.copierVersCompte) ; c'est CreateNoteJSON qui
// garde l'extension, parce qu'elle est modifiable. Retirer l'extension avant
// l'appel, comme le faisait la première version, convertissait la note en
// Markdown : un « # » de texte brut y devenait un titre.
func TestCohabitationCopieGardeLeTxt(t *testing.T) {
	local, _, serveurApp, srv, _, _ := deuxProfils(t)
	const texte = "liste de courses\n# pas un titre\n- pas une puce\n"

	srv.mu.Lock()
	srv.files["Notes/courses.txt"] = []byte(texte)
	srv.mu.Unlock()
	if _, err := local.CreateNoteJSON("", "journal.txt", texte); err != nil {
		t.Fatalf("CreateNoteJSON local: %v", err)
	}

	cas := []struct {
		sens          string
		source, cible *App
		chemin        string
	}{
		{"serveur vers local", serveurApp, local, "courses.txt"},
		{"local vers serveur", local, serveurApp, "journal.txt"},
	}
	for _, c := range cas {
		lu, err := c.source.ReadNote(c.chemin)
		if err != nil {
			t.Fatalf("%s : ReadNote(%s): %v", c.sens, c.chemin, err)
		}
		// Deux copies : la seconde trouve le nom pris et reçoit un suffixe,
		// qui doit lui aussi garder l'extension.
		for _, attendu := range []string{c.chemin, strings.TrimSuffix(c.chemin, ".txt") + " (2).txt"} {
			copie := creerJSON(t, c.cible, path.Base(c.chemin), lu)
			if copie != attendu {
				t.Errorf("%s : copie de %s créée sous %q, attendu %q", c.sens, c.chemin, copie, attendu)
			}
			if relu, err := c.cible.ReadNote(copie); err != nil || relu != texte {
				t.Errorf("%s : %s relue = %q, %v ; attendu %q", c.sens, copie, relu, err, texte)
			}
		}
	}

	if res := synchroniser(t, serveurApp); res.Error != "" || res.Remaining != 0 {
		t.Fatalf("passe serveur = %+v", res)
	}
	srv.mu.Lock()
	defer srv.mu.Unlock()
	for _, chemin := range []string{"journal.txt", "journal (2).txt"} {
		if got := string(srv.files["Notes/"+chemin]); got != texte {
			t.Errorf("serveur %s = %q, attendu %q", chemin, got, texte)
		}
	}
	if _, ok := srv.files["Notes/journal.md"]; ok {
		t.Errorf("journal.md est arrivé sur le serveur : la copie a converti le .txt")
	}
}

// creerJSON crée une note par la façade et rend son chemin.
func creerJSON(t *testing.T, app *App, nom, texte string) string {
	t.Helper()
	raw, err := app.CreateNoteJSON("", nom, texte)
	if err != nil {
		t.Fatalf("CreateNoteJSON(%q): %v", nom, err)
	}
	var note struct {
		Path string `json:"path"`
	}
	decodeJSON(t, raw, &note)
	return note.Path
}
