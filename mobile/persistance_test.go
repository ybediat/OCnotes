package mobile

import (
	"os"
	"testing"

	"github.com/ybediat/OpenNote/internal/config"
)

// Ces tests répondent à une seule question : une session qui disparaît sans que
// l'utilisateur l'ait demandé — token refusé, serveur muet, processus tué — peut-elle
// faire perdre des notes qui n'ont pas encore été envoyées ?
//
// Seul Disconnect purge le cache, et c'est un geste explicite de l'interface.
// Tout le reste doit laisser notes et file d'attente exactement où ils sont.

// travailHorsConnexion produit ce qu'une longue session sans réseau laisse sur
// l'appareil : une note synchronisée puis modifiée, deux créations, une note
// renommée. Renvoie le contenu attendu de chaque note, par chemin.
func travailHorsConnexion(t *testing.T, app *App, server *fakeServer) map[string]string {
	t.Helper()

	if _, err := app.CreateNoteJSON("", "ancienne", "v1"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.CreateNoteJSON("", "a-renommer", "contenu renommé"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("synchronisation initiale: %v", err)
	}

	server.setOffline(true)

	if err := app.WriteNote("ancienne.md", "v2 écrite hors connexion\né ligne deux\n"); err != nil {
		t.Fatalf("WriteNote hors connexion: %v", err)
	}
	if _, err := app.CreateNoteJSON("", "idee", "# Idée\n"); err != nil {
		t.Fatalf("CreateNoteJSON hors connexion: %v", err)
	}
	if _, err := app.CreateNoteJSON("", "journal", "jour 1"); err != nil {
		t.Fatalf("CreateNoteJSON hors connexion: %v", err)
	}
	// Plusieurs écritures de suite sur la même note : la dernière doit gagner.
	for _, v := range []string{"jour 1", "jour 1\njour 2", "jour 1\njour 2\njour 3"} {
		if err := app.WriteNote("journal.md", v); err != nil {
			t.Fatalf("WriteNote journal: %v", err)
		}
	}
	if _, err := app.Rename("a-renommer.md", "renommee"); err != nil {
		t.Fatalf("Rename hors connexion: %v", err)
	}

	return map[string]string{
		"ancienne.md": "v2 écrite hors connexion\né ligne deux\n",
		"idee.md":     "# Idée\n",
		"journal.md":  "jour 1\njour 2\njour 3",
		"renommee.md": "contenu renommé",
	}
}

func verifierCache(t *testing.T, app *App, attendu map[string]string) {
	t.Helper()
	for chemin, contenu := range attendu {
		got, err := app.ReadNote(chemin)
		if err != nil {
			t.Errorf("ReadNote(%q): %v", chemin, err)
			continue
		}
		if got != contenu {
			t.Errorf("%s = %q, attendu %q", chemin, got, contenu)
		}
	}
}

func verifierServeur(t *testing.T, server *fakeServer, attendu map[string]string) {
	t.Helper()
	server.mu.Lock()
	defer server.mu.Unlock()
	for chemin, contenu := range attendu {
		got, ok := server.files["Notes/"+chemin]
		if !ok {
			t.Errorf("%s absente du serveur: %v", chemin, keys(server.files))
			continue
		}
		if string(got) != contenu {
			t.Errorf("serveur %s = %q, attendu %q", chemin, got, contenu)
		}
	}
}

func synchroniser(t *testing.T, app *App) syncResult {
	t.Helper()
	raw, err := app.SyncJSON()
	if err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}
	var res syncResult
	decodeJSON(t, raw, &res)
	return res
}

// Le processus meurt (OOM, mise à jour, MIUI qui tue l'appli) avec des travaux
// en attente, et rien n'a pu être envoyé. Au redémarrage, sans réseau, tout est
// là ; au retour du réseau, tout part.
func TestPersistanceRedemarrageHorsConnexion(t *testing.T) {
	app, server, dataDir := prepare(t)
	attendu := travailHorsConnexion(t, app, server)
	enAttente := app.PendingCount()
	if enAttente == 0 {
		t.Fatal("le travail hors connexion aurait dû être en file")
	}

	// Aucun arrêt propre : on abandonne l'instance, comme un processus tué.
	redemarre, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := redemarre.Restore(fakeToken); err != nil {
		t.Fatalf("Restore sans réseau: %v", err)
	}

	verifierCache(t, redemarre, attendu)
	if got := redemarre.PendingCount(); got != enAttente {
		t.Errorf("file après redémarrage = %d, avant = %d", got, enAttente)
	}

	// Une passe sans réseau n'abîme rien non plus.
	if res := synchroniser(t, redemarre); res.Error == "" {
		t.Error("la passe sans réseau aurait dû signaler une erreur")
	}
	verifierCache(t, redemarre, attendu)
	if got := redemarre.PendingCount(); got != enAttente {
		t.Errorf("file après passe infructueuse = %d, avant = %d", got, enAttente)
	}

	server.setOffline(false)
	if res := synchroniser(t, redemarre); res.Error != "" || res.Remaining != 0 {
		t.Fatalf("passe finale = %+v", res)
	}
	verifierServeur(t, server, attendu)
	if redemarre.PendingCount() != 0 {
		t.Errorf("la file n'est pas vide après envoi: %d", redemarre.PendingCount())
	}
}

// Le cas rapporté : la session est refusée (token révoqué, expiré, serveur
// reconfiguré). L'application « se déconnecte » du point de vue de
// l'utilisateur. Les notes jamais envoyées doivent survivre au refus, au
// redémarrage et à la reconnexion.
func TestPersistanceTokenRefuseNePerdRien(t *testing.T) {
	app, server, dataDir := prepare(t)
	attendu := travailHorsConnexion(t, app, server)
	enAttente := app.PendingCount()

	// Le token a changé côté serveur : Restore (sans réseau) réussit, la
	// première passe se heurte à un 401.
	refuse, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := refuse.Restore("token-revoque"); err != nil {
		t.Fatalf("Restore: %v", err)
	}
	verifierCache(t, refuse, attendu)
	server.setOffline(false)
	res := synchroniser(t, refuse)
	if res.ErrorCode != "AUTH" {
		t.Fatalf("la passe aurait dû être refusée (AUTH), obtenu %+v", res)
	}

	if got := refuse.PendingCount(); got != enAttente {
		t.Errorf("file après refus AUTH = %d, avant = %d", got, enAttente)
	}
	server.mu.Lock()
	nbServeur := len(server.files)
	server.mu.Unlock()
	if nbServeur != 2 { // les deux notes de la synchronisation initiale, rien d'autre
		t.Errorf("le serveur a reçu des écritures malgré le refus: %v", keys(server.files))
	}

	// Le processus est tué pendant que la session est invalide.
	apres, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := apres.Restore("token-revoque"); err != nil {
		t.Fatalf("Restore: %v", err)
	}
	verifierCache(t, apres, attendu)
	if got := apres.PendingCount(); got != enAttente {
		t.Errorf("file après redémarrage sous session invalide = %d, avant = %d", got, enAttente)
	}

	// L'utilisateur se reconnecte avec le même compte : le cache est conservé
	// et le travail part.
	if err := apres.Connect(server.URL, fakeUser, fakeToken); err != nil {
		t.Fatalf("reconnexion: %v", err)
	}
	verifierCache(t, apres, attendu)
	if res := synchroniser(t, apres); res.Error != "" || res.Remaining != 0 {
		t.Fatalf("passe après reconnexion = %+v", res)
	}
	verifierServeur(t, server, attendu)
}

// Se reconnecter avec un autre compte est refusé : c'est ce qui protège le
// travail en attente d'un écrasement par un autre profil.
func TestPersistanceReconnexionAutreCompteRefuseeSansRienPerdre(t *testing.T) {
	app, server, _ := prepare(t)
	attendu := travailHorsConnexion(t, app, server)
	enAttente := app.PendingCount()
	verifierCache(t, app, attendu)

	server.setOffline(false)
	server.setOwner("55555555-5555-4555-8555-555555555555")
	err := app.Connect(server.URL, fakeUser, fakeToken)
	if err == nil || ErrorCode(err.Error()) != CodeAccountMismatch {
		t.Fatalf("Connect autre propriétaire = %v, attendu %s", err, CodeAccountMismatch)
	}
	verifierCache(t, app, attendu)
	if got := app.PendingCount(); got != enAttente {
		t.Errorf("file = %d, attendu %d", got, enAttente)
	}
}

// Une session invalide ne doit pas empêcher d'écrire : l'utilisateur qui
// continue de taper sur une application « déconnectée » ne perd pas non plus
// ce qu'il écrit pendant ce temps.
func TestPersistanceEcrireSousSessionRefusee(t *testing.T) {
	app, server, dataDir := prepare(t)
	if _, err := app.CreateNoteJSON("", "vivante", "v1"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatalf("SyncJSON: %v", err)
	}

	refuse, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := refuse.Restore("token-revoque"); err != nil {
		t.Fatalf("Restore: %v", err)
	}
	// Une file vide ne contacte pas le serveur : il faut du travail en attente
	// pour que le 401 se produise.
	if err := refuse.WriteNote("vivante.md", "v1 bis"); err != nil {
		t.Fatalf("WriteNote: %v", err)
	}
	if res := synchroniser(t, refuse); res.ErrorCode != "AUTH" {
		t.Fatalf("passe = %+v, AUTH attendu", res)
	}

	if err := refuse.WriteNote("vivante.md", "v2 écrite sous session refusée"); err != nil {
		t.Fatalf("WriteNote sous session refusée: %v", err)
	}
	if _, err := refuse.CreateNoteJSON("", "nouvelle", "créée sous session refusée"); err != nil {
		t.Fatalf("CreateNoteJSON sous session refusée: %v", err)
	}

	redemarre, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := redemarre.Restore("token-revoque"); err != nil {
		t.Fatalf("Restore: %v", err)
	}
	attendu := map[string]string{
		"vivante.md":  "v2 écrite sous session refusée",
		"nouvelle.md": "créée sous session refusée",
	}
	verifierCache(t, redemarre, attendu)

	if err := redemarre.Connect(server.URL, fakeUser, fakeToken); err != nil {
		t.Fatalf("reconnexion: %v", err)
	}
	if res := synchroniser(t, redemarre); res.Error != "" || res.Remaining != 0 {
		t.Fatalf("passe après reconnexion = %+v", res)
	}
	verifierServeur(t, server, attendu)
}

// Documente la seule porte de sortie : Disconnect purge aussi les notes jamais
// envoyées. C'est voulu (« rien de l'utilisateur précédent ne doit rester »),
// et c'est à l'interface d'en prévenir — SettingsScreen le fait quand
// enAttente > 0. Si ce test cesse de passer, quelqu'un a changé ce contrat.
func TestPersistanceDisconnectPurgeLaFileCommeAnnonce(t *testing.T) {
	app, server, _ := prepare(t)
	travailHorsConnexion(t, app, server)
	if app.PendingCount() == 0 {
		t.Fatal("rien en file avant la déconnexion")
	}
	if err := app.Disconnect(); err != nil {
		t.Fatalf("Disconnect: %v", err)
	}
	if n := len(app.cache.Entries()); n != 0 {
		t.Errorf("%d note(s) restent après Disconnect", n)
	}
}

// Renommée hors connexion, une note ouverte au retour du réseau mais avant la
// synchronisation : le serveur ne connaît encore que l'ancien nom. L'ouverture
// ne doit ni échouer ni faire oublier la note au cache, et le renommage doit
// partir ensuite.
func TestPersistanceNoteRenommeeOuverteAvantLaSynchronisation(t *testing.T) {
	app, server, _ := prepare(t)
	attendu := travailHorsConnexion(t, app, server)

	server.setOffline(false)
	got, err := app.ReadNote("renommee.md")
	if err != nil || got != attendu["renommee.md"] {
		t.Errorf("ouverture avant synchronisation = %q, %v", got, err)
	}

	if res := synchroniser(t, app); res.Error != "" || res.Remaining != 0 {
		t.Fatalf("passe = %+v", res)
	}
	verifierServeur(t, server, attendu)
	server.mu.Lock()
	_, ancienne := server.files["Notes/a-renommer.md"]
	server.mu.Unlock()
	if ancienne {
		t.Error("l'ancien nom existe encore sur le serveur : le renommage n'a pas abouti")
	}
}

// --- Mode local : le cache est la seule copie ---------------------------------

// prepareLocalAvecNotes monte un mode local et y écrit quelques notes, dont une
// dans un dossier, puis renvoie leur contenu attendu.
func prepareLocalAvecNotes(t *testing.T) (string, map[string]string) {
	t.Helper()
	app, dataDir := prepareLocal(t)
	if _, err := app.CreateFolderJSON("", "Carnets"); err != nil {
		t.Fatalf("CreateFolderJSON: %v", err)
	}
	attendu := map[string]string{
		"a.md":         "# A\nrien que moi\n",
		"Carnets/b.md": "# B\né\n",
		"Carnets/c.md": "texte c",
	}
	for chemin, contenu := range attendu {
		dir, nom := "", chemin
		if i := len("Carnets/"); len(chemin) > i && chemin[:i] == "Carnets/" {
			dir, nom = "Carnets", chemin[i:]
		}
		if _, err := app.CreateNoteJSON(dir, nom, contenu); err != nil {
			t.Fatalf("CreateNoteJSON(%s): %v", chemin, err)
		}
	}
	return dataDir, attendu
}

// La configuration est réécrite à chaque dossier ouvert (LastPath). Si elle
// est perdue ou tronquée — coupure de courant, mort du processus —, Load rend
// une configuration vide sans erreur : l'application se croit neuve. Les notes,
// elles, sont toujours sur le disque et ne doivent pas disparaître pour autant.
func TestPersistanceModeLocalConfigurationPerdue(t *testing.T) {
	for nom, abime := range map[string]func(t *testing.T, dataDir string){
		"absente": func(t *testing.T, dataDir string) {
			if err := os.Remove(config.Path(dataDir)); err != nil {
				t.Fatal(err)
			}
		},
		"vide": func(t *testing.T, dataDir string) {
			if err := os.WriteFile(config.Path(dataDir), nil, 0o600); err != nil {
				t.Fatal(err)
			}
		},
		"tronquée": func(t *testing.T, dataDir string) {
			if err := os.WriteFile(config.Path(dataDir), []byte(`{"version":1,"mo`), 0o600); err != nil {
				t.Fatal(err)
			}
		},
	} {
		t.Run(nom, func(t *testing.T) {
			dataDir, attendu := prepareLocalAvecNotes(t)
			abime(t, dataDir)

			app, err := NewApp(dataDir)
			if err != nil {
				t.Fatalf("NewApp: %v", err)
			}
			// Aucun geste : le cœur se reconnaît local à son cache, sans
			// écran de départ et sans attendre le registre Android.
			var state appState
			raw, err := app.StateJSON()
			if err != nil {
				t.Fatalf("StateJSON: %v", err)
			}
			decodeJSON(t, raw, &state)
			if state.Mode != config.ModeLocal {
				t.Errorf("mode après perte de configuration = %q, attendu local", state.Mode)
			}
			verifierCache(t, app, attendu)

			// Et la configuration est réécrite : le registre Android, s'il
			// devait être reconstruit, la lirait locale.
			cfg, err := config.Load(dataDir)
			if err != nil || !cfg.IsLocal() {
				t.Errorf("configuration réécrite = %+v, %v ; attendu le mode local", cfg, err)
			}
		})
	}
}

// Brancher un serveur après la perte : c'est un branchement ordinaire depuis le
// mode local. Connect n'engage rien — ni configuration ni adoption — tant que
// l'utilisateur n'a pas choisi le sort de ses notes ; c'est ce choix qui
// manquait quand la connexion les adoptait d'office sous le mode vide.
func TestPersistanceModeLocalConfigurationPerdueEtServeurBranche(t *testing.T) {
	dataDir, attendu := prepareLocalAvecNotes(t)
	if err := os.Remove(config.Path(dataDir)); err != nil {
		t.Fatal(err)
	}

	server := newFakeServer(t)
	app, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := app.Connect(server.URL, fakeUser, fakeToken); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	if !app.cache.LocalOnly() || app.PendingCount() != 0 {
		t.Fatalf("Connect a engagé le cache avant le choix : local=%v, file=%d", app.cache.LocalOnly(), app.PendingCount())
	}
	if res := attache(t, app, true); res.Adopted != len(attendu) {
		t.Errorf("adopted = %d, attendu %d", res.Adopted, len(attendu))
	}
	if res := synchroniser(t, app); res.Error != "" || res.Remaining != 0 {
		t.Fatalf("passe après branchement = %+v", res)
	}

	verifierCache(t, app, attendu)
	verifierServeur(t, server, attendu)
}

// Un profil serveur dont la configuration est perdue n'a, lui, rien à adopter :
// ses notes propres sont déjà sur son serveur, et son travail en attente est
// déjà dans la file. Les adopter les faisait monter sur le premier compte venu
// — celui que l'utilisateur saisit devant l'écran de connexion, qui n'est pas
// forcément le sien, puisque la configuration ne peut plus le dire.
//
// Le cas d'un cache qui ne sait pas non plus à qui il appartient — index
// reconstruit depuis les doubles — : la connexion passe, mais rien ne monte.
func TestPersistanceConfigurationPerdueProfilServeurNeMonteRienAilleurs(t *testing.T) {
	app, _, dataDir := prepare(t)
	if _, err := app.CreateNoteJSON("", "privee-de-A", "contenu du compte A"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if res := synchroniser(t, app); res.Error != "" || res.Remaining != 0 {
		t.Fatalf("passe sur A = %+v", res)
	}
	if err := app.cache.SetOwner(""); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(config.Path(dataDir)); err != nil {
		t.Fatal(err)
	}

	autre := newFakeServer(t)
	apres, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := apres.Connect(autre.URL, fakeUser, fakeToken); err != nil {
		t.Fatalf("Connect sur B: %v", err)
	}
	if got := apres.PendingCount(); got != 0 {
		t.Errorf("file après connexion à B = %d, attendu 0", got)
	}
	if err := apres.SelectWorkspace(fakeSpaceID, "Notes"); err != nil {
		t.Fatalf("SelectWorkspace: %v", err)
	}
	if _, err := apres.ListFolderJSON(""); err != nil {
		t.Fatalf("ListFolderJSON: %v", err)
	}
	synchroniser(t, apres)

	autre.mu.Lock()
	defer autre.mu.Unlock()
	if _, ok := autre.files["Notes/privee-de-A.md"]; ok {
		t.Errorf("la note du compte A a été envoyée sur B : %v", keys(autre.files))
	}
}

// Même perte, même compte : rien ne doit repartir. Adopter faisait passer
// chaque note du cache par le chemin des créations, et toute note modifiée
// ailleurs entre-temps revenait en copie de conflit.
func TestPersistanceConfigurationPerdueProfilServeurMemeCompteRienARenvoyer(t *testing.T) {
	app, server, dataDir := prepare(t)
	if _, err := app.CreateNoteJSON("", "synchronisee", "v1"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if res := synchroniser(t, app); res.Error != "" || res.Remaining != 0 {
		t.Fatalf("passe initiale = %+v", res)
	}
	if err := os.Remove(config.Path(dataDir)); err != nil {
		t.Fatal(err)
	}

	apres, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	if err := apres.Connect(server.URL, fakeUser, fakeToken); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	if got := apres.PendingCount(); got != 0 {
		t.Errorf("file après reconnexion = %d, attendu 0", got)
	}
}

// Le travail en attente, lui, est dans la file — et ne doit partir que vers le
// compte qui l'a produit. Sans configuration, seul le cache sait encore lequel :
// un autre compte est refusé comme si la configuration était intacte, et le bon
// compte retrouve tout.
func TestPersistanceConfigurationPerdueTravailEnAttenteResteAuCompte(t *testing.T) {
	app, server, dataDir := prepare(t)
	server.setOffline(true)
	if _, err := app.CreateNoteJSON("", "attente-de-A", "écrit hors connexion sur A"); err != nil {
		t.Fatalf("CreateNoteJSON hors connexion: %v", err)
	}
	enAttente := app.PendingCount()
	if enAttente == 0 {
		t.Fatal("aucune écriture en attente : le scénario ne teste rien")
	}
	if err := os.Remove(config.Path(dataDir)); err != nil {
		t.Fatal(err)
	}
	server.setOffline(false)

	apres, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	autreServeur := newFakeServer(t)
	err = apres.Connect(autreServeur.URL, fakeUser, fakeToken)
	if err == nil || ErrorCode(err.Error()) != CodeAccountMismatch {
		t.Fatalf("Connect sur un autre serveur = %v, attendu %s", err, CodeAccountMismatch)
	}

	// Même serveur, autre propriétaire : un autre compte aussi.
	server.setOwner("55555555-5555-4555-8555-555555555555")
	err = apres.Connect(server.URL, fakeUser, fakeToken)
	if err == nil || ErrorCode(err.Error()) != CodeAccountMismatch {
		t.Fatalf("Connect autre propriétaire = %v, attendu %s", err, CodeAccountMismatch)
	}
	if got := apres.PendingCount(); got != enAttente {
		t.Errorf("file après les refus = %d, attendu %d", got, enAttente)
	}
	autreServeur.mu.Lock()
	if _, ok := autreServeur.files["Notes/attente-de-A.md"]; ok {
		t.Error("le travail en attente de A a atteint un autre serveur")
	}
	autreServeur.mu.Unlock()

	server.setOwner(fakeOwnerID)
	if err := apres.Connect(server.URL, fakeUser, fakeToken); err != nil {
		t.Fatalf("Connect sur le compte d'origine: %v", err)
	}
	if err := apres.SelectWorkspace(fakeSpaceID, "Notes"); err != nil {
		t.Fatalf("SelectWorkspace: %v", err)
	}
	if res := synchroniser(t, apres); res.Error != "" || res.Remaining != 0 {
		t.Fatalf("passe sur le compte d'origine = %+v", res)
	}
	verifierServeur(t, server, map[string]string{"attente-de-A.md": "écrit hors connexion sur A"})
}

// Une installation antérieure n'a pas de propriétaire dans son cache. Le
// premier démarrage avec une configuration intacte le reprend de celle-ci :
// la protection vaut dès la première perte qui suit la mise à jour.
func TestPersistanceProprietaireReprisDeLaConfiguration(t *testing.T) {
	app, server, dataDir := prepare(t)
	server.setOffline(true)
	if _, err := app.CreateNoteJSON("", "attente", "en file"); err != nil {
		t.Fatalf("CreateNoteJSON hors connexion: %v", err)
	}
	if err := app.cache.SetOwner(""); err != nil {
		t.Fatal(err)
	}

	if _, err := NewApp(dataDir); err != nil {
		t.Fatalf("NewApp avec configuration intacte: %v", err)
	}
	if err := os.Remove(config.Path(dataDir)); err != nil {
		t.Fatal(err)
	}
	apres, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}
	autreServeur := newFakeServer(t)
	err = apres.Connect(autreServeur.URL, fakeUser, fakeToken)
	if err == nil || ErrorCode(err.Error()) != CodeAccountMismatch {
		t.Fatalf("Connect sur un autre serveur = %v, attendu %s", err, CodeAccountMismatch)
	}
}
