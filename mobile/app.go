// Package mobile est la façade exposée à Android par gomobile bind.
//
// Elle ne contient aucune logique métier : elle sérialise, désérialise et
// délègue à internal/. Toute règle qui mérite d'être testée vit en dessous.
//
// # Contraintes de types
//
// gomobile bind n'accepte dans les signatures exportées que : les entiers
// signés, les flottants, string, bool, []byte, les pointeurs vers des structs
// du paquet, les interfaces, et error en dernière position. Ni map, ni slice
// (hors []byte), ni channel, ni générique, ni entier non signé.
//
// Les données composites traversent donc la frontière en JSON. Les charges
// utiles sont petites — listes de fichiers, métadonnées — et cela découple les
// versions Go et Kotlin. Le test TestSignaturesCompatiblesGomobile vérifie que
// cette règle tient, sans avoir besoin du NDK.
//
// # Synchronisation
//
// Aucune goroutine périodique ici. C'est Android qui décide quand
// synchroniser, via WorkManager, parce que lui seul connaît l'état de la
// batterie, du réseau et du cycle de vie de l'application. Le Go se contente
// d'exécuter une passe quand on la lui demande.
package mobile

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path"
	"strings"
	"sync"
	"time"

	"github.com/ybediat/OpenNote/internal/charset"
	"github.com/ybediat/OpenNote/internal/config"
	"github.com/ybediat/OpenNote/internal/markdown"
	"github.com/ybediat/OpenNote/internal/notes"
	"github.com/ybediat/OpenNote/internal/opencloud"
	"github.com/ybediat/OpenNote/internal/store"
)

// requestTimeout borne chaque appel réseau. gomobile ne sait pas transmettre
// un context.Context : la façade fabrique le sien.
const requestTimeout = 30 * time.Second

// refreshTimeout borne le rafraîchissement opportuniste d'une note à
// l'ouverture. Plus court que requestTimeout : l'utilisateur attend devant un
// écran vide, et le cache est là pour prendre le relais.
const refreshTimeout = 8 * time.Second

// offlineBackoff est la durée pendant laquelle on considère le serveur
// injoignable après un échec réseau, pour ne pas refaire l'aller-retour à
// chaque ouverture de note.
const offlineBackoff = 20 * time.Second

// syncPassTimeout borne aussi l'attente d'une passe déjà en cours. C'est une
// variable pour que le test puisse vérifier cette annulation sans attendre cinq
// minutes.
var syncPassTimeout = 5 * time.Minute

// App est le point d'entrée unique pour Android.
type App struct {
	mu       sync.Mutex
	syncPass chan struct{}
	dataDir  string
	// saveConfig est injectable dans les tests pour provoquer une panne disque
	// exactement au point de commit d'une transition de mode.
	saveConfig func(config.Config) error
	cfg        config.Config
	client     *opencloud.Client
	oidcAuth   *opencloud.BearerAuth
	lib        *notes.Library
	cache      *store.Store

	// offlineUntil retient qu'un appel réseau vient d'échouer, pour éviter de
	// réessayer à chaque geste.
	offlineUntil time.Time

	// passeCtx est le contexte dont dérivent les passes serveur — synchro,
	// résolution de conflit — de la session courante. La déconnexion l'annule
	// pour ne pas attendre qu'une passe engagée finisse de parler au serveur,
	// ni la laisser réécrire le cache qu'elle vient de purger.
	passeCtx  context.Context
	passeStop context.CancelFunc

	// edits garde les images retirées des notes ouvertes en saisie, par
	// session d'édition (voir OpenEditJSON). Verrou à part : une écriture ne
	// doit pas attendre une synchronisation qui tient a.mu.
	editsMu  sync.Mutex
	edits    map[string][]string
	nextEdit uint64

	// formats retient l'encodage et la fin de ligne de chaque note lue par
	// ReadNote, pour que WriteNote les lui rende (voir rememberFormat). Sous
	// editsMu, pour la même raison que edits.
	formats map[string]noteFormat
}

// NewApp ouvre l'application dans un dossier de données.
//
// Sur Android, dataDir est context.getFilesDir().getAbsolutePath(), un dossier
// privé à l'application.
func NewApp(dataDir string) (*App, error) {
	cache, err := store.Open(path.Join(dataDir, "cache"))
	if err != nil {
		return nil, err
	}
	cfg, err := config.Load(dataDir)
	if err != nil {
		return nil, err
	}
	if err := alignCacheOnMode(cache, cfg); err != nil {
		return nil, err
	}
	return &App{
		dataDir:    dataDir,
		cfg:        cfg,
		cache:      cache,
		syncPass:   make(chan struct{}, 1),
		saveConfig: func(next config.Config) error { return config.Save(dataDir, next) },
	}, nil
}

func (a *App) persistConfig(next config.Config) error {
	if a.saveConfig != nil {
		return a.saveConfig(next)
	}
	return config.Save(a.dataDir, next)
}

// alignCacheOnMode remet le cache d'accord avec le mode enregistré.
//
// Chaque bascule — StartLocal, AttachJSON, DetachJSON, Disconnect — écrit le
// cache d'abord et la configuration en dernier : c'est la configuration qui
// valide le geste. Tuée entre les deux, l'application redémarrait avec un
// cache et une configuration en désaccord, et le pire des deux sens détruisait
// des notes en silence : en mode serveur sur un cache « stockage unique », une
// écriture n'est ni marquée en attente ni mise en file, et la prochaine
// ouverture la remplace par la version du serveur.
//
// La configuration fait donc foi, et le geste interrompu est défait :
//
//   - mode local, cache doublé d'un serveur (branchement interrompu) : GoLocal
//     vide la file et lève les « en attente » qu'Adopt venait de poser, sans
//     rien retirer — Adopt a fait de l'inventaire exactement ce que
//     l'appareil détient ;
//   - autre mode, cache « stockage unique » (débranchement ou démarrage local
//     interrompu) : le serveur redevient la référence. Rien n'est perdu, le
//     débranchement exigeant une file vide, et l'inventaire se reconstitue au
//     premier listing. Android, lui, n'efface le token qu'une fois DetachJSON
//     revenu : il a encore de quoi rouvrir la session.
func alignCacheOnMode(cache *store.Store, cfg config.Config) error {
	switch {
	case cfg.IsLocal() && !cache.LocalOnly():
		_, err := cache.GoLocal()
		return err
	case !cfg.IsLocal() && cache.LocalOnly():
		return cache.SetLocalOnly(false)
	}
	return nil
}

func (a *App) ctx() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), requestTimeout)
}

// session renvoie d'un seul verrou les deux choses dont chaque geste a besoin :
// la bibliothèque distante, et le fait qu'il n'y en ait pas par choix.
//
// Les deux se lisent ensemble parce qu'elles se répondent : une bibliothèque
// nulle est une erreur en mode serveur — la session n'est pas remontée — et
// l'état normal en mode local.
func (a *App) session() (*notes.Library, bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.lib, a.cfg.IsLocal()
}

// sessionDePasse est session() augmentée du contexte des passes, lu sous le
// même verrou : une passe qui a obtenu la bibliothèque d'une session obtient
// aussi de quoi être annulée avec elle.
func (a *App) sessionDePasse() (*notes.Library, bool, context.Context) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.passeCtx == nil {
		a.passeCtx, a.passeStop = context.WithCancel(context.Background())
	}
	return a.lib, a.cfg.IsLocal(), a.passeCtx
}

// couperPassesLocked annule toute passe engagée sur la session courante.
// L'appelant détient a.mu.
func (a *App) couperPassesLocked() {
	if a.passeStop != nil {
		a.passeStop()
	}
	a.passeCtx, a.passeStop = nil, nil
}

// prendrePasse réserve le serveur pour une passe, ou renonce à l'échéance.
func (a *App) prendrePasse(ctx context.Context) (func(), error) {
	select {
	case a.syncPass <- struct{}{}:
		return func() { <-a.syncPass }, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// rememberLastPath retient le dernier dossier consulté, pour rouvrir
// l'application au même endroit. L'échec d'écriture est ignoré : ne pas
// retrouver son dossier est un désagrément, pas une raison de refuser un
// listing qu'on a déjà.
func (a *App) rememberLastPath(dir string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.cfg.LastPath = dir
	_ = a.persistConfig(a.cfg)
}

// --- État -------------------------------------------------------------------

// appState décrit ce que l'interface doit savoir pour choisir son écran.
type appState struct {
	// Mode vaut "", "local" ou "server". Le mode vide est celui d'une
	// installation neuve, qui n'a pas encore choisi : c'est lui qui mène à
	// l'écran de connexion, où le mode local est proposé.
	Mode string `json:"mode"`

	Connected    bool   `json:"connected"`
	HasWorkspace bool   `json:"hasWorkspace"`
	ServerURL    string `json:"serverUrl"`
	Username     string `json:"username"`
	IdentityKey  string `json:"identityKey"`
	DisplayName  string `json:"displayName"`
	DriveID      string `json:"driveId"`
	DriveName    string `json:"driveName"`
	Root         string `json:"root"`
	LastPath     string `json:"lastPath"`
	Pending      int    `json:"pending"`
	AuthMode     string `json:"authMode"`
}

// cacheState est l'état d'espace présenté dans les réglages. Quota vaut zéro
// pour « illimité » ; usage ne compte que les blobs de contenu réellement
// présents, jamais l'inventaire ni la file persistante.
//
// OverQuota dit que l'occupation dépasse un quota qui n'est pas « illimité ».
// En mode local, c'est l'alerte que le quota promet : il n'évince plus rien.
type cacheState struct {
	Quota     int64 `json:"quota"`
	Usage     int64 `json:"usage"`
	OverQuota bool  `json:"overQuota"`
}

// StateJSON renvoie l'état courant.
//
// Connected indique qu'un serveur et un compte sont enregistrés, mais pas que
// le token est en mémoire : après un redémarrage, Android doit rappeler
// Connect avec le token lu dans le Keystore.
func (a *App) StateJSON() (string, error) {
	a.mu.Lock()
	defer a.mu.Unlock()

	return toJSON(appState{
		Mode:         a.cfg.Mode,
		Connected:    a.cfg.IsConnected(),
		HasWorkspace: a.lib != nil,
		ServerURL:    a.cfg.ServerURL,
		Username:     a.cfg.Username,
		IdentityKey:  a.cfg.IdentityKey,
		DisplayName:  a.cfg.DisplayName,
		DriveID:      a.cfg.DriveID,
		DriveName:    a.cfg.DriveName,
		Root:         a.cfg.Root,
		LastPath:     a.cfg.LastPath,
		Pending:      len(a.cache.Pending()),
		AuthMode:     a.cfg.EffectiveAuthMode(),
	})
}

// CacheStateJSON renvoie le quota local, l'occupation réelle du cache et le
// fait qu'elle dépasse le quota — d'un seul relevé, pour que les trois se
// répondent.
func (a *App) CacheStateJSON() (string, error) {
	quota, usage, depasse := a.cache.Occupancy()
	return toJSON(cacheState{Quota: quota, Usage: usage, OverQuota: depasse})
}

// SetCacheQuota applique le quota choisi par l'utilisateur. La préférence
// elle-même appartient à Android ; cette méthode n'en conserve que l'effet sur
// le cache ouvert.
func (a *App) SetCacheQuota(quota int64) error {
	return a.cache.SetQuota(quota)
}

// PruneCache libère l'espace récupérable sans toucher aux brouillons, conflits
// ou opérations en attente.
func (a *App) PruneCache() error {
	return a.cache.Prune()
}

// --- Connexion --------------------------------------------------------------

// StartLocal fait démarrer l'application sans serveur.
//
// Les notes vivent alors sur ce seul appareil : le cache cesse d'être un cache
// pour devenir le stockage, avec tout ce que store.SetLocalOnly implique. Rien
// n'est définitif — brancher un serveur plus tard reste possible, et tout ce
// qui aura été écrit entre-temps y montera.
//
// Refusé si un serveur est déjà enregistré : quitter le mode serveur passe par
// le débranchement, qui rapatrie d'abord ce qui n'est pas encore sur
// l'appareil. Basculer ici sans cela laisserait sur place des notes dont seul
// le nom est connu.
func (a *App) StartLocal() error {
	a.mu.Lock()
	defer a.mu.Unlock()

	if a.cfg.IsConnected() {
		return fmt.Errorf("mobile: [%s] un serveur est déjà enregistré, passer par le débranchement", CodeLocalMode)
	}
	wasLocal := a.cache.LocalOnly()
	if err := a.cache.SetLocalOnly(true); err != nil {
		return err
	}
	if err := a.raiseLocalQuota(); err != nil {
		if !wasLocal {
			return errors.Join(err, a.cache.SetLocalOnly(false))
		}
		return err
	}
	next := config.Config{Mode: config.ModeLocal, LastPath: a.cfg.LastPath}
	if err := a.persistConfig(next); err != nil {
		if !wasLocal {
			return errors.Join(err, a.cache.SetLocalOnly(false))
		}
		return err
	}

	// Appelable aussi pour annuler un branchement en cours depuis le mode
	// local : Connect a pu poser un client authentifié et des identifiants en
	// mémoire sans jamais les persister. Les deux doivent disparaître avec le
	// reste de la configuration, comme le fait DetachJSON — sinon un geste
	// suivant en mode local trouverait un client vivant que plus rien
	// n'attend, avec un token qu'on croyait abandonné.
	a.client, a.oidcAuth, a.lib = nil, nil, nil
	a.cfg = next
	return nil
}

// raiseLocalQuota relève le seuil au plancher du mode local.
//
// En mode local le quota n'évince plus rien : il ne sert qu'à alerter quand
// les notes prennent beaucoup de place. Le laisser à 250 Mo ferait donc crier
// l'interface bien avant que le téléphone ne soit gêné. L'utilisateur peut
// l'abaisser ensuite s'il préfère être averti plus tôt ; « illimité » n'est
// jamais relevé, c'est déjà le plus permissif.
func (a *App) raiseLocalQuota() error {
	quota := a.cache.Quota()
	if quota == store.UnlimitedQuota || quota >= store.MinLocalQuota {
		return nil
	}
	return a.cache.SetQuota(store.MinLocalQuota)
}

// Connect ouvre une session et vérifie les identifiants auprès du serveur.
//
// Le token n'est jamais écrit sur le disque par le Go : il reste en mémoire
// dans le client HTTP. Android le conserve dans des EncryptedSharedPreferences
// et le repasse à chaque démarrage.
//
// Si un espace de travail était déjà choisi, la bibliothèque est reconstituée
// dans la foulée : l'utilisateur retrouve ses notes sans repasser par l'écran
// de sélection.
func (a *App) Connect(serverURL, username, appToken string) error {
	serverURL = config.NormalizeServerURL(serverURL)
	client, err := opencloud.New(serverURL, opencloud.AppTokenAuth{
		Username: username,
		Token:    appToken,
	})
	if err != nil {
		return err
	}

	return a.connectClient(serverURL, username, config.AuthAppToken, client, nil)
}

// ConnectOIDC ouvre et valide une session obtenue par Authorization Code +
// PKCE côté Android. Le refresh token ne traverse jamais la frontière : seul
// l'access token, court, vit en mémoire dans le client Go.
func (a *App) ConnectOIDC(serverURL, accountID, accessToken string) error {
	serverURL = config.NormalizeServerURL(serverURL)
	auth := opencloud.NewBearerAuth(accessToken)
	client, err := opencloud.New(serverURL, auth)
	if err != nil {
		return err
	}
	return a.connectClient(serverURL, accountID, config.AuthOIDC, client, auth)
}

func (a *App) connectClient(serverURL, username, authMode string, client *opencloud.Client, oidcAuth *opencloud.BearerAuth) error {
	ctx, cancel := a.ctx()
	defer cancel()

	// Un appel réel valide les identifiants : sans cela, une erreur
	// d'authentification n'apparaîtrait qu'au premier geste de l'utilisateur.
	drives, err := client.ListDrives(ctx)
	if err != nil {
		return err
	}
	// Le nom n'est qu'un libellé : un serveur qui ne répond pas à /me ne
	// doit pas empêcher la connexion, et le nom déjà connu reste alors.
	var nom string
	if me, err := client.Me(ctx); err == nil {
		nom = me.Name()
	}

	a.mu.Lock()
	ancienne := a.cfg
	a.mu.Unlock()
	identityKey := identityKey(serverURL, drives)
	if err := requireSameAccount(ancienne, serverURL, username, authMode, identityKey); err != nil {
		return err
	}

	nouvelle := ancienne
	nouvelle.ServerURL = serverURL
	nouvelle.Username = username
	// La valeur vide reste la représentation persistée de l'App Token : les
	// installations existantes n'ont ainsi aucune migration à subir et le mot
	// "token" n'apparaît pas inutilement dans la configuration non secrète.
	if authMode == config.AuthOIDC {
		nouvelle.AuthMode = config.AuthOIDC
	} else {
		nouvelle.AuthMode = ""
	}
	// Un serveur ancien peut ne pas exposer owner.user.id. Dans ce cas on garde
	// une identité stable déjà connue au lieu de la remplacer par du vide.
	if identityKey != "" {
		nouvelle.IdentityKey = identityKey
	}
	if nom != "" {
		nouvelle.DisplayName = nom
	}

	// Branchement en cours depuis le mode local : rien n'est écrit tant que
	// l'utilisateur n'a pas choisi son espace **et** le sort de ses notes.
	// AttachJSON est le seul point de bascule. Tué ici, l'appareil redémarre
	// en mode local, avec ses notes — au lieu de se retrouver en mode serveur
	// sans espace, où plus aucun geste ne les atteindrait.
	if ancienne.IsLocal() {
		a.mu.Lock()
		defer a.mu.Unlock()
		if !memeSession(a.cfg, ancienne) {
			return errSessionChanged()
		}
		nouvelle.LastPath = a.cfg.LastPath
		a.client, a.oidcAuth, a.lib, a.cfg = client, oidcAuth, nil, nouvelle
		return nil
	}
	nouvelle.Mode = config.ModeServer

	// Première connexion à un serveur alors que le cache porte déjà des notes :
	// la seule façon d'y arriver est une configuration perdue ou tronquée — la
	// purge d'une déconnexion vide le cache, et le branchement depuis le mode
	// local passe par AttachJSON. Ces notes n'ont alors de copie nulle part. Les
	// laisser « propres » les faisait oublier en silence au premier listing,
	// puisque le serveur ne les connaît pas : on les traite comme un travail
	// créé hors connexion, que pushWrite envoie sans rien écraser.
	if !ancienne.IsConnected() && len(a.cache.Entries()) > 0 {
		if err := a.cache.Adopt(); err != nil {
			return err
		}
	}

	var nouvelleBibliotheque *notes.Library
	if nouvelle.DriveID != "" {
		for _, d := range drives {
			if d.ID == nouvelle.DriveID {
				space, err := client.Space(d)
				if err != nil {
					return err
				}
				lib, err := notes.NewLibrary(space, nouvelle.Root)
				if err != nil {
					return err
				}
				if err := lib.Bootstrap(ctx); err != nil {
					return err
				}
				nouvelleBibliotheque = lib
				nouvelle.DriveName = d.Name
				nouvelle.DriveWebDavURL = d.WebDavURL
				nouvelle.Root = lib.Root()
				break
			}
		}
		if nouvelleBibliotheque == nil {
			// L'espace de l'ancien profil n'est pas accessible avec ces
			// identifiants. Ne jamais laisser l'ancienne bibliothèque en mémoire.
			nouvelle.DriveID, nouvelle.DriveName = "", ""
			nouvelle.DriveWebDavURL, nouvelle.Root = "", ""
		}
	}

	a.mu.Lock()
	defer a.mu.Unlock()
	if !memeSession(a.cfg, ancienne) {
		return errSessionChanged()
	}
	nouvelle.LastPath = a.cfg.LastPath
	if err := a.persistConfig(nouvelle); err != nil {
		return err
	}
	a.client, a.oidcAuth, a.lib, a.cfg = client, oidcAuth, nouvelleBibliotheque, nouvelle
	return nil
}

// memeSession dit si la configuration courante est encore celle sur laquelle
// la connexion a été préparée, hors réseau. LastPath n'en fait pas partie : la
// revalidation d'arrière-plan tourne pendant que l'utilisateur navigue déjà
// dans le cache, et ouvrir un dossier ne change pas de session.
func memeSession(courante, preparee config.Config) bool {
	courante.LastPath, preparee.LastPath = "", ""
	return courante == preparee
}

func errSessionChanged() error {
	return fmt.Errorf("mobile: [%s] la session a changé pendant la connexion", CodeSessionChanged)
}

// requireSameAccount interdit, tant que le sélecteur multi-compte n'existe
// pas, de rattacher le cache courant à une autre identité. Sans clé
// comparable des deux côtés — ancien profil, serveur qui n'expose pas le
// propriétaire —, l'URL, le login et la méthode en tiennent lieu.
func requireSameAccount(current config.Config, serverURL, username, authMode, identityKey string) error {
	if !current.IsConnected() {
		return nil
	}
	stored := current.IdentityKey
	if !strings.HasPrefix(stored, identityKeyVersion) {
		stored = ""
	}
	if stored != "" && identityKey != "" {
		if stored != identityKey {
			return errAccountMismatch()
		}
		return nil
	}
	if current.ServerURL != serverURL || current.Username != username || current.EffectiveAuthMode() != authMode {
		return errAccountMismatch()
	}
	return nil
}

func errAccountMismatch() error {
	return fmt.Errorf("mobile: [%s] ce compte n'est pas celui des notes de cet appareil", CodeAccountMismatch)
}

// identityKeyVersion préfixe la clé d'identité. Une clé d'un autre format
// compte comme absente : la v1, qui dépendait de la méthode
// d'authentification, est ainsi remplacée à la reconnexion suivante.
const identityKeyVersion = "v2:"

// identityKey reconnaît un compte par le propriétaire LibreGraph de son espace
// personnel. Cet identifiant est le même quelle que soit la méthode
// d'authentification — App Token ou OIDC —, contrairement au login ou au
// subject OIDC. Vide si le serveur ne l'expose pas.
//
// Le préfixage en longueur évite toute ambiguïté entre les composantes.
func identityKey(serverURL string, drives []opencloud.Drive) string {
	for _, drive := range drives {
		if drive.Type != opencloud.DrivePersonal || drive.OwnerID == "" {
			continue
		}
		h := sha256.New()
		for _, part := range []string{serverURL, drive.OwnerID} {
			_, _ = fmt.Fprintf(h, "%d:%s", len(part), part)
		}
		return fmt.Sprintf("%s%x", identityKeyVersion, h.Sum(nil))
	}
	return ""
}

// Disconnect efface la session, la configuration et le cache.
//
// Rien de l'utilisateur précédent ne doit rester sur l'appareil : la
// suppression du cache fait partie de la déconnexion, pas d'un ménage
// ultérieur.
//
// En mode local, le même geste supprime toutes les notes de l'appareil — il
// n'y a pas de session à fermer, et le cache est la seule copie. C'est bien la
// même opération, mais l'interface doit l'annoncer pour ce qu'elle est : une
// suppression définitive, pas une déconnexion.
//
// Dans les deux cas l'application revient au mode vide, celui d'une
// installation neuve : le choix entre serveur et mode local se repose.
//
// Une passe en cours est annulée, puis attendue : sans cela, elle continuait
// de répondre au serveur après la purge et réécrivait dans le cache des notes
// du compte qu'on venait de quitter.
func (a *App) Disconnect() error {
	a.mu.Lock()
	a.couperPassesLocked()
	a.client, a.oidcAuth, a.lib, a.cfg = nil, nil, nil, config.Config{}
	a.mu.Unlock()

	// Plus aucune passe ne peut démarrer — la bibliothèque est nulle — et celle
	// qui tourne vient d'être annulée : l'attente est brève.
	a.syncPass <- struct{}{}
	defer func() { <-a.syncPass }()

	a.mu.Lock()
	defer a.mu.Unlock()
	if err := a.cache.Clear(); err != nil {
		return err
	}
	if err := a.cache.SetLocalOnly(false); err != nil {
		return err
	}
	return config.Clear(a.dataDir)
}

// --- Espaces ----------------------------------------------------------------

// driveInfo décrit un espace pour l'écran de sélection.
type driveInfo struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Type     string `json:"type"`
	Usable   bool   `json:"usable"`
	Selected bool   `json:"selected"`
}

// ListDrivesJSON renvoie les espaces accessibles.
//
// Les espaces inutilisables — l'agrégat virtuel « Shares » — sont renvoyés
// avec Usable à faux plutôt qu'omis : l'interface peut alors les afficher
// grisés et expliquer pourquoi, au lieu de les faire disparaître sans raison.
func (a *App) ListDrivesJSON() (string, error) {
	a.mu.Lock()
	client, selected := a.client, a.cfg.DriveID
	a.mu.Unlock()

	if client == nil {
		return "", errNotConnected()
	}

	ctx, cancel := a.ctx()
	defer cancel()

	drives, err := client.ListDrives(ctx)
	if err != nil {
		return "", err
	}

	out := make([]driveInfo, 0, len(drives))
	for _, d := range drives {
		out = append(out, driveInfo{
			ID:       d.ID,
			Name:     d.Name,
			Type:     d.Type,
			Usable:   d.IsStorage(),
			Selected: d.ID == selected,
		})
	}
	return toJSON(out)
}

// SelectWorkspace choisit l'espace et le dossier de notes.
//
// Une racine vide désigne la racine de l'espace, ce qui permet de brancher
// l'application sur un dossier de notes déjà existant. Le dossier est créé
// s'il manque.
func (a *App) SelectWorkspace(driveID, root string) error {
	a.mu.Lock()
	client := a.client
	a.mu.Unlock()

	if client == nil {
		return errNotConnected()
	}

	ctx, cancel := a.ctx()
	defer cancel()

	drives, err := client.ListDrives(ctx)
	if err != nil {
		return err
	}

	for _, d := range drives {
		if d.ID != driveID {
			continue
		}
		if !d.IsStorage() {
			return fmt.Errorf("mobile: l'espace %q ne peut pas héberger de notes", d.Name)
		}

		a.mu.Lock()
		defer a.mu.Unlock()
		if err := a.openWorkspaceLocked(ctx, d, root); err != nil {
			return err
		}
		return a.persistConfig(a.cfg)
	}
	return fmt.Errorf("mobile: espace %q introuvable", driveID)
}

// attachRequest décide du sort des notes locales au branchement d'un serveur.
//
// La décision passe en JSON plutôt qu'en booléen positionnel, comme pour
// ResolveConflictJSON : un appelant qui écrit `"adopt": false` sait ce qu'il
// demande, là où un troisième argument `false` ne dit rien à la relecture.
type attachRequest struct {
	DriveID string `json:"driveId"`
	Root    string `json:"root"`

	// Adopt à vrai fait monter toutes les notes de l'appareil vers le serveur.
	// À faux, elles sont **supprimées** et l'appareil repart de ce que le
	// serveur contient.
	Adopt bool `json:"adopt"`
}

// attachResult rend compte de ce qui a été fait, en nombre de notes.
type attachResult struct {
	Adopted int    `json:"adopted"`
	Deleted int    `json:"deleted"`
	Root    string `json:"root"`
}

// AttachJSON branche un serveur sur une application en mode local.
//
// Deux issues, et une seule est réversible :
//
//   - `adopt: true` — tout monte d'un coup. Les notes deviennent des écritures
//     en attente et la synchronisation ordinaire les propage, en préservant
//     une note distante qui porterait le même nom.
//   - `adopt: false` — les notes locales sont **supprimées** et l'appareil
//     repart du serveur. C'est le cas de celui dont les vraies notes sont déjà
//     en ligne et dont le local n'était que des brouillons.
//
// L'ordre des trois étapes est choisi pour qu'une interruption laisse un état
// récupérable : l'espace d'abord (il peut échouer faute de réseau), le cache
// ensuite, la configuration en dernier. Tué entre les deux dernières, l'appareil
// redémarre en mode local, alignCacheOnMode défait ce que le cache avait déjà
// fait, et le branchement se refait. Avec `adopt: false`, les notes sont
// toutefois déjà supprimées : c'est ce qui avait été demandé.
func (a *App) AttachJSON(requestJSON string) (string, error) {
	var req attachRequest
	if err := json.Unmarshal([]byte(requestJSON), &req); err != nil {
		return "", fmt.Errorf("mobile: requête de branchement illisible: %w", err)
	}

	if _, local := a.session(); !local {
		return "", fmt.Errorf("mobile: [%s] le branchement part du mode local ; passer par SelectWorkspace", CodeLocalMode)
	}

	// Compté avant : Adopt comme Clear rendent la question sans réponse.
	notesLocales := len(a.cache.Entries())

	if err := a.SelectWorkspace(req.DriveID, req.Root); err != nil {
		return "", err
	}

	result := attachResult{}
	if req.Adopt {
		if err := a.cache.Adopt(); err != nil {
			return "", err
		}
		result.Adopted = notesLocales
	} else {
		if err := a.cache.Clear(); err != nil {
			return "", err
		}
		if err := a.cache.SetLocalOnly(false); err != nil {
			return "", err
		}
		result.Deleted = notesLocales
	}

	a.mu.Lock()
	defer a.mu.Unlock()

	// Le seuil relevé par le mode local reste en place, et c'est voulu. Avec
	// `adopt`, l'appareil porte encore toutes ses notes : redescendre au
	// défaut ferait évincer, sitôt synchronisées, des notes qu'il détenait
	// jusque-là. Et rien ne distingue ce plancher d'un gigaoctet choisi par
	// l'utilisateur, qui garde la main dans les réglages.
	serverConfig := a.cfg
	serverConfig.Mode = config.ModeServer
	result.Root = serverConfig.Root
	if err := a.persistConfig(serverConfig); err != nil {
		// La configuration est le commit. Tant qu'elle n'est pas durable, le
		// processus courant doit lui aussi rester local et permettre un nouvel
		// essai sans imposer un redémarrage.
		var rollback error
		if req.Adopt {
			_, rollback = a.cache.GoLocal()
		} else {
			rollback = a.cache.SetLocalOnly(true)
		}
		return "", errors.Join(err, rollback)
	}
	a.cfg = serverConfig
	return toJSON(result)
}

// --- Débranchement ----------------------------------------------------------

// defaultDownloadBatch borne un lot de rapatriement quand l'appelant ne dit
// rien. Assez pour avancer vite, assez court pour que la barre de progression
// bouge et qu'une annulation soit prise en compte rapidement.
const defaultDownloadBatch = 25

// detachPlan dit ce que coûterait un débranchement, avant d'y toucher.
type detachPlan struct {
	// Total est le nombre de notes de l'espace ; Missing celles dont le
	// contenu n'est pas encore sur l'appareil, et Bytes ce qu'elles pèsent.
	Total   int   `json:"total"`
	Missing int   `json:"missing"`
	Bytes   int64 `json:"bytes"`

	Usage int64 `json:"usage"`
	Quota int64 `json:"quota"`

	// Required est l'occupation une fois tout rapatrié. OverQuota dit qu'elle
	// dépasse le seuil : le rapatriement se mordrait la queue — chaque note
	// téléchargée en évincerait une autre — donc il refuse de commencer.
	Required  int64 `json:"required"`
	OverQuota bool  `json:"overQuota"`

	// Pending est le nombre d'écritures qui n'ont pas encore atteint le
	// serveur. Elles doivent partir avant : le débranchement vide la file.
	Pending int `json:"pending"`
}

// DetachPlanJSON décrit ce qu'un débranchement demanderait.
//
// L'inventaire est rafraîchi au passage : on ne peut pas dire ce qu'il reste à
// rapatrier sans savoir ce que le serveur contient. Hors connexion, l'appel
// échoue — et c'est juste, il n'y a pas de débranchement sûr sans réseau.
func (a *App) DetachPlanJSON() (string, error) {
	lib, local := a.session()

	if local {
		return "", errLocalMode("le débranchement")
	}
	if lib == nil {
		return "", errNoWorkspace()
	}
	if err := a.RefreshIndex(); err != nil {
		return "", err
	}
	return toJSON(a.detachPlan())
}

func (a *App) detachPlan() detachPlan {
	manquantes := a.cache.MissingContent()

	plan := detachPlan{
		Total:   len(a.cache.Index()),
		Missing: len(manquantes),
		Usage:   a.cache.Usage(),
		Quota:   a.cache.Quota(),
		Pending: len(a.cache.Pending()),
	}
	for _, k := range manquantes {
		plan.Bytes += k.Size
	}
	plan.Required = plan.Usage + plan.Bytes
	plan.OverQuota = plan.Quota != store.UnlimitedQuota && plan.Required > plan.Quota
	return plan
}

// downloadReport rend compte d'un lot de rapatriement.
type downloadReport struct {
	Downloaded int   `json:"downloaded"`
	Remaining  int   `json:"remaining"`
	Bytes      int64 `json:"bytes"`

	// Failed compte les notes que ce lot n'a pas pu obtenir pour une raison
	// qui leur est propre — pas une panne de réseau, qui arrête la passe.
	// Elles restent comptées dans Remaining.
	Failed int `json:"failed"`

	Error     string `json:"error"`
	ErrorCode string `json:"errorCode"`
}

// DownloadBatchJSON rapatrie jusqu'à max notes dont le contenu manque.
//
// L'appel est volontairement borné plutôt que global : Android le rappelle en
// boucle et affiche une progression, sans qu'aucun appel ne bloque plusieurs
// minutes derrière la frontière ni qu'un rappel ait à la traverser.
//
// **Condition d'arrêt de la boucle : un lot qui ne rapatrie rien.** Remaining
// compte aussi les notes en échec définitif, donc il ne tombe pas forcément à
// zéro ; c'est Downloaded à zéro qui dit qu'insister ne sert plus à rien.
func (a *App) DownloadBatchJSON(max int) (string, error) {
	lib, local := a.session()

	if local {
		return "", errLocalMode("le rapatriement")
	}
	if lib == nil {
		return "", errNoWorkspace()
	}
	if max <= 0 {
		max = defaultDownloadBatch
	}

	// Sans cette garde, le rapatriement se mordrait la queue : les notes
	// reçues sont propres, donc évinçables, et chaque téléchargement au-delà
	// du seuil en supprimerait un précédent. La boucle tournerait sans fin en
	// affichant des progrès.
	if plan := a.detachPlan(); plan.OverQuota {
		return "", fmt.Errorf(
			"mobile: [%s] le seuil de cache (%d octets) ne peut pas contenir les %d octets à rapatrier",
			CodeQuotaTooLow, plan.Quota, plan.Required)
	}

	ctx, cancel := context.WithTimeout(context.Background(), syncPassTimeout)
	defer cancel()

	report := downloadReport{}
	for i, k := range a.cache.MissingContent() {
		if i >= max {
			break
		}
		if err := a.cache.Pull(ctx, lib, k.Path); err != nil {
			if errors.Is(err, opencloud.ErrOffline) {
				// Insister sur les suivantes ne ferait qu'attendre autant de
				// délais réseau : la passe s'arrête et rend compte.
				a.noteNetworkResult(err)
				report.Error = err.Error()
				report.ErrorCode = ErrorCode(report.Error)
				break
			}
			report.Failed++
			continue
		}
		report.Downloaded++
		report.Bytes += k.Size
	}

	report.Remaining = len(a.cache.MissingContent())
	return toJSON(report)
}

// detachResult rend compte d'un débranchement accompli.
type detachResult struct {
	// Kept est le nombre de notes qui vivent désormais sur le seul appareil.
	Kept int `json:"kept"`

	// Abandoned nomme celles qui n'ont pas pu être rapatriées et que
	// l'inventaire vient d'oublier. Elles restent sur le serveur : c'est
	// l'appareil qui les perd, pas l'utilisateur.
	Abandoned []string `json:"abandoned"`
}

// DetachJSON coupe le lien avec le serveur et passe l'appareil en mode local.
//
// Les notes restent sur le serveur : débrancher n'y efface rien, l'application
// l'oublie. À l'inverse, une note que le rapatriement n'a pas obtenue disparaît
// de l'appareil — elle est nommée dans le compte rendu.
//
// Refusé tant que des écritures attendent : le débranchement vide la file, et
// les perdre en silence serait le pire résultat possible. Une passe de
// synchronisation d'abord, ce geste ensuite.
func (a *App) DetachJSON() (string, error) {
	lib, local := a.session()

	if local {
		return "", errLocalMode("le débranchement")
	}
	if lib == nil {
		return "", errNoWorkspace()
	}

	// La file n'est vérifiée qu'une fois le serveur réservé : une passe en
	// cours pouvait encore y remettre une écriture, ou résoudre un conflit
	// dans un cache déjà devenu le seul dépositaire.
	ctx, cancel := context.WithTimeout(context.Background(), syncPassTimeout)
	defer cancel()
	liberer, err := a.prendrePasse(ctx)
	if err != nil {
		return "", err
	}
	defer liberer()

	if n := len(a.cache.Pending()); n > 0 {
		return "", fmt.Errorf(
			"mobile: [%s] %d modification(s) n'ont pas atteint le serveur, synchroniser d'abord",
			CodePendingChanges, n)
	}

	// Le relèvement du seuil est une préparation réversible du point de vue des
	// données. Le faire avant GoLocal évite qu'une erreur d'E/S laisse le cache
	// local alors que la configuration et la session sont encore serveur.
	if err := a.raiseLocalQuota(); err != nil {
		return "", err
	}
	abandonnees, err := a.cache.GoLocal()
	if err != nil {
		return "", err
	}

	a.mu.Lock()
	defer a.mu.Unlock()

	// Le token n'a jamais été écrit ici ; c'est Android qui le retire du
	// Keystore de son côté. La configuration est le point de commit : en cas
	// d'échec, le cache revient immédiatement en mode serveur et cette instance
	// reste utilisable sans redémarrage.
	localConfig := config.Config{Mode: config.ModeLocal, LastPath: a.cfg.LastPath}
	if err := a.persistConfig(localConfig); err != nil {
		return "", errors.Join(err, a.cache.SetLocalOnly(false))
	}
	a.couperPassesLocked()
	a.client, a.oidcAuth, a.lib = nil, nil, nil
	a.cfg = localConfig

	return toJSON(detachResult{
		Kept:      len(a.cache.Entries()),
		Abandoned: abandonnees,
	})
}

// openWorkspaceLocked monte la bibliothèque. L'appelant détient le verrou.
func (a *App) openWorkspaceLocked(ctx context.Context, drive opencloud.Drive, root string) error {
	space, err := a.client.Space(drive)
	if err != nil {
		return err
	}
	lib, err := notes.NewLibrary(space, root)
	if err != nil {
		return err
	}
	if err := lib.Bootstrap(ctx); err != nil {
		return err
	}

	a.lib = lib
	a.cfg.DriveID = drive.ID
	a.cfg.DriveName = drive.Name
	a.cfg.DriveWebDavURL = drive.WebDavURL
	a.cfg.Root = lib.Root()
	return nil
}

// Restore remonte la session depuis la configuration, sans le moindre appel
// réseau.
//
// C'est le chemin de démarrage normal. Connect, lui, valide les identifiants
// auprès du serveur : appelé sans réseau il échoue, et la bibliothèque reste
// nulle — tous les gestes de navigation renvoient alors une erreur, y compris
// ceux qui savent se replier sur le cache. Une application local-first doit
// pouvoir s'ouvrir dans le métro.
//
// L'enchaînement attendu côté Android est donc : Restore au lancement pour
// afficher les notes immédiatement, puis Connect en arrière-plan pour valider
// le token et rafraîchir.
//
// Renvoie une erreur si aucun espace n'a encore été choisi : il faut alors
// passer par Connect puis SelectWorkspace.
func (a *App) Restore(appToken string) error {
	client, err := opencloud.New(a.cfg.ServerURL, opencloud.AppTokenAuth{
		Username: a.cfg.Username,
		Token:    appToken,
	})
	if err != nil {
		return err
	}
	return a.restoreClient(client, nil)
}

// RestoreOIDC remonte une session OIDC sans réseau, comme Restore pour un
// App Token. Un access token expiré reste suffisant pour ouvrir le cache ;
// Android tentera son renouvellement ensuite, en arrière-plan.
func (a *App) RestoreOIDC(accessToken string) error {
	auth := opencloud.NewBearerAuth(accessToken)
	client, err := opencloud.New(a.cfg.ServerURL, auth)
	if err != nil {
		return err
	}
	return a.restoreClient(client, auth)
}

func (a *App) restoreClient(client *opencloud.Client, oidcAuth *opencloud.BearerAuth) error {
	a.mu.Lock()
	defer a.mu.Unlock()

	if !a.cfg.HasWorkspace() {
		return errors.New("mobile: aucun espace enregistré, passer par Connect puis SelectWorkspace")
	}

	space, err := client.Space(opencloud.Drive{
		ID:        a.cfg.DriveID,
		Name:      a.cfg.DriveName,
		Type:      opencloud.DrivePersonal,
		WebDavURL: a.cfg.DriveWebDavURL,
	})
	if err != nil {
		return err
	}

	// Pas de Bootstrap ici : créer le dossier racine est un appel réseau, et
	// il a forcément déjà eu lieu lors de SelectWorkspace.
	lib, err := notes.NewLibrary(space, a.cfg.Root)
	if err != nil {
		return err
	}

	a.client = client
	a.oidcAuth = oidcAuth
	a.lib = lib
	return nil
}

// UpdateOIDCAccessToken applique un refresh effectué par Android au client
// déjà ouvert, sans perdre le cache ni l'espace courant.
func (a *App) UpdateOIDCAccessToken(accessToken string) error {
	a.mu.Lock()
	auth := a.oidcAuth
	a.mu.Unlock()
	if auth == nil {
		return errors.New("mobile: aucune session OIDC ouverte")
	}
	return auth.SetToken(accessToken)
}

// DefaultRoot est le nom du dossier proposé au premier démarrage.
func DefaultRoot() string { return notes.DefaultRoot }

// --- Navigation -------------------------------------------------------------

// folderEntry décrit une ligne du navigateur : dossier ou note.
type folderEntry struct {
	Path    string `json:"path"`
	Name    string `json:"name"`
	Display string `json:"display"`
	IsDir   bool   `json:"isDir"`
	Size    int64  `json:"size"`
	ModTime string `json:"modTime"`

	// Pending indique une modification locale pas encore synchronisée, pour
	// que l'interface puisse l'afficher.
	Pending bool `json:"pending"`

	// ReadOnly signale un format que l'application sait lire mais jamais
	// écrire. La réponse vient du cœur : recopier une liste d'extensions dans
	// l'interface, c'est se préparer à la voir diverger au premier format
	// ajouté.
	ReadOnly bool `json:"readOnly,omitempty"`

	CanWrite        bool `json:"canWrite"`
	CanDelete       bool `json:"canDelete"`
	CanRename       bool `json:"canRename"`
	CanMove         bool `json:"canMove"`
	CanCreateFile   bool `json:"canCreateFile"`
	CanCreateFolder bool `json:"canCreateFolder"`
}

// folderListing est le contenu d'un dossier.
type folderListing struct {
	Path    string        `json:"path"`
	Entries []folderEntry `json:"entries"`

	// FromCache signale un listing servi hors connexion : l'interface peut
	// alors prévenir que la vue peut être incomplète.
	FromCache       bool `json:"fromCache"`
	CanCreateFile   bool `json:"canCreateFile"`
	CanCreateFolder bool `json:"canCreateFolder"`
}

func permissionsFields(permissions string) opencloud.Capabilities {
	return opencloud.CapabilitiesOf(permissions)
}

func folderEntryCapabilities(entry folderEntry, permissions string) folderEntry {
	c := permissionsFields(permissions)
	entry.CanWrite = c.CanWrite
	entry.CanDelete = c.CanDelete
	entry.CanRename = c.CanRename
	entry.CanMove = c.CanMove
	entry.CanCreateFile = c.CanCreateFile
	entry.CanCreateFolder = c.CanCreateFolder
	return entry
}

// ListFolderJSON renvoie le contenu d'un dossier, dossiers d'abord.
//
// En cas d'échec réseau, le listing est reconstruit depuis le cache : le
// navigateur reste utilisable hors connexion, ce qui est tout l'intérêt du
// modèle local-first.
func (a *App) ListFolderJSON(dir string) (string, error) {
	lib, local := a.session()

	if local {
		// Pas de repli : le cache est la source, pas un pis-aller. Un dossier
		// vide est donc une réponse, et non l'aveu qu'on n'a rien pu obtenir —
		// c'est pourquoi la question du cache vierge ne se pose pas ici.
		listing := a.listFromCache(dir, false)
		a.rememberLastPath(listing.Path)
		return toJSON(listing)
	}
	if lib == nil {
		return "", errNoWorkspace()
	}

	ctx, cancel := a.ctx()
	defer cancel()

	listing, err := lib.List(ctx, dir)
	if err != nil {
		a.noteNetworkResult(err)
		if a.cacheConnaitQuelqueChose() {
			return toJSON(a.listFromCache(dir, true))
		}
		return "", err
	}
	a.noteNetworkResult(nil)

	a.rememberLastPath(listing.Path)

	// La slice est initialisée non nulle : encoding/json sérialise une slice
	// nulle en « null », alors que le contrat annonce un tableau. Kotlin n'a
	// pas à gérer deux formes pour un dossier vide.
	out := folderListing{Path: listing.Path, Entries: []folderEntry{}}
	for _, f := range listing.Folders {
		out.Entries = append(out.Entries, folderEntryCapabilities(folderEntry{
			Path: f.Path, Name: f.Name, Display: f.Name, IsDir: true,
		}, f.Permissions))
	}
	for _, n := range listing.Notes {
		entry, cached := a.cache.CachedEntry(n.Path)
		out.Entries = append(out.Entries, folderEntryCapabilities(folderEntry{
			Path:     n.Path,
			Name:     n.Name,
			Display:  n.DisplayName,
			Size:     n.Size,
			ModTime:  n.ModTime.UTC().Format(time.RFC3339),
			Pending:  cached && entry.Dirty,
			ReadOnly: notes.IsReadOnly(n.Name),
		}, n.Permissions))
	}
	caps := permissionsFields(listing.Permissions)
	out.CanCreateFile, out.CanCreateFolder = caps.CanCreateFile, caps.CanCreateFolder
	knownNotes := make([]store.Known, 0, len(listing.Notes))
	knownFolders := make([]store.FolderKnown, 0, len(listing.Folders))
	for _, n := range listing.Notes {
		knownNotes = append(knownNotes, store.Known{Path: n.Path, ETag: n.ETag, Size: n.Size, ModTime: n.ModTime, Permissions: n.Permissions})
	}
	for _, f := range listing.Folders {
		knownFolders = append(knownFolders, store.FolderKnown{Path: f.Path, Permissions: f.Permissions})
	}
	// Le lot retient aussi les dossiers vides pour le mode hors connexion et
	// persiste leurs droits dans la même écriture atomique de l'index.
	if err := a.cache.RememberPermissions(store.FolderKnown{Path: listing.Path, Permissions: listing.Permissions}, knownNotes, knownFolders); err != nil {
		return "", err
	}
	return toJSON(out)
}

// cacheConnaitQuelqueChose dit si le cache a de quoi répondre.
//
// La question ne se pose qu'au repli hors connexion : un cache entièrement
// vierge n'apprendrait rien à l'utilisateur, et annoncer une bibliothèque vide
// serait pire que de remonter la panne réseau. En mode local elle n'a pas de
// sens — un cache vide y veut dire « aucune note », ce qui est une réponse.
func (a *App) cacheConnaitQuelqueChose() bool {
	return len(a.cache.Entries()) > 0 || len(a.cache.Folders()) > 0
}

// listFromCache reconstruit le contenu d'un dossier à partir du cache seul.
//
// repli dit ce qu'on est en train de faire : un pis-aller hors connexion, que
// l'interface signale par un bandeau, ou une lecture de la source en mode
// local, où il n'y a rien à signaler.
func (a *App) listFromCache(dir string, repli bool) folderListing {
	dir = notes.CleanPath(dir)
	prefix := ""
	if dir != "" {
		prefix = dir + "/"
	}

	currentCaps := permissionsFields(a.cache.FolderPermissions(dir))
	out := folderListing{Path: dir, FromCache: repli, Entries: []folderEntry{}, CanCreateFile: currentCaps.CanCreateFile, CanCreateFolder: currentCaps.CanCreateFolder}
	seenDirs := map[string]bool{}

	// Les dossiers connus d'abord : un dossier vide n'apparaîtrait dans aucun
	// chemin de note, et resterait donc invisible hors connexion.
	for _, folder := range a.cache.Folders() {
		if prefix != "" && !strings.HasPrefix(folder, prefix) {
			continue
		}
		rest := strings.TrimPrefix(folder, prefix)
		if rest == "" || strings.Contains(rest, "/") {
			continue
		}
		seenDirs[rest] = true
		out.Entries = append(out.Entries, folderEntryCapabilities(folderEntry{
			Path: folder, Name: rest, Display: rest, IsDir: true,
		}, a.cache.FolderPermissions(folder)))
	}

	for _, entry := range a.cache.Entries() {
		if prefix != "" && len(entry.Path) <= len(prefix) {
			continue
		}
		if entry.Path[:len(prefix)] != prefix {
			continue
		}

		rest := entry.Path[len(prefix):]
		if i := indexByte(rest, '/'); i >= 0 {
			name := rest[:i]
			if !seenDirs[name] {
				seenDirs[name] = true
				out.Entries = append(out.Entries, folderEntryCapabilities(folderEntry{
					Path: prefix + name, Name: name, Display: name, IsDir: true,
				}, a.cache.FolderPermissions(prefix+name)))
			}
			continue
		}

		out.Entries = append(out.Entries, folderEntryCapabilities(folderEntry{
			Path:     entry.Path,
			Name:     rest,
			Display:  notes.DisplayName(rest),
			Size:     entry.Size,
			ModTime:  entry.LocalMod.UTC().Format(time.RFC3339),
			Pending:  entry.Dirty,
			ReadOnly: notes.IsReadOnly(rest),
		}, a.cache.Permissions(entry.Path)))
	}

	return out
}

func indexByte(s string, b byte) int {
	for i := 0; i < len(s); i++ {
		if s[i] == b {
			return i
		}
	}
	return -1
}

// --- Notes ------------------------------------------------------------------

// ReadNote renvoie le contenu d'une note.
//
// Une note propre est d'abord rafraîchie depuis le serveur. Sans ce
// rafraîchissement, l'ETag connu localement vieillit dès que la note est
// modifiée ailleurs — depuis l'interface web, par exemple. La prochaine
// écriture depuis le téléphone part alors avec un ETag périmé, le serveur la
// refuse, et le mécanisme de conflit crée un doublon là où il n'y avait
// pourtant rien à arbitrer.
//
// Une note portant des modifications locales n'est jamais rafraîchie : sa
// version en cache fait foi jusqu'à la synchronisation, qui tranchera.
//
// Le contenu est décodé côté Go — UTF-8, Windows-1252 ou UTF-16 à BOM — et
// son encodage retenu, avec sa fin de ligne, pour que WriteNote les lui rende.
// Rien d'autre que de l'UTF-8 ne doit traverser gomobile en octets bruts : il
// en remplacerait les octets invalides par des « � » en passant à Java, et le
// premier enregistrement écrirait ces remplacements sur le serveur, à la place
// des accents.
//
// Deux cas restent refusés (NOT_UTF8), et s'ouvrent en aperçu par
// RenderFileJSON :
//
//   - l'UTF-16 sans BOM, reconnu à ses octets nuls par une heuristique : on ne
//     réécrit pas un fichier sur une supposition qui peut être fausse ;
//   - un texte qui, lu en Windows-1252, ne ressemble pas à une langue de
//     l'Ouest : c'est sans doute un autre encodage sur 8 bits (polonais,
//     russe, japonais…), qu'y écrire mêlerait au Windows-1252 ;
//   - un contenu que décoder puis réencoder ne rend pas à l'identique — un
//     UTF-16 tronqué, par exemple. L'écrire, même sans rien y changer, en
//     perdrait des octets.
//
// Le texte rendu n'a que des « \n » : la fin de ligne du fichier est retenue
// ici et remise par WriteNote (voir notes.LineEnding).
func (a *App) ReadNote(notePath string) (string, error) {
	content, err := a.readBytes(notePath)
	if err != nil {
		return "", err
	}
	text, enc, err := a.decodeNote(notePath, content)
	if err != nil {
		return "", err
	}
	a.rememberFormat(notePath, noteFormat{encoding: enc, ending: notes.DetectLineEnding(text)})
	return notes.NormalizeLineEndings(text), nil
}

// decodeNote décode le contenu d'une note pour l'éditeur.
//
// L'encodage retenu par le cache passe avant la détection : pour ce contenu
// précis, l'application l'a écrit elle-même, ou l'utilisateur l'a choisi
// (ForceEncoding). La détection, elle, devine — et un texte Windows-1252 tapé
// dans l'éditeur peut ne plus lui paraître plausible à la réouverture, trois
// accents d'affilée suffisent.
func (a *App) decodeNote(notePath string, content []byte) (string, charset.Encoding, error) {
	if enc, ok := a.rememberedEncoding(notePath, content); ok {
		if text, ok := charset.DecodeAs(content, enc); ok {
			return text, enc, nil
		}
	}
	text, enc, ok := detectRewritable(content)
	if !ok {
		return "", enc, fmt.Errorf("mobile: [%s] l'encodage de %s n'est pas reconnu avec certitude : il ne s'ouvre qu'en lecture", CodeNotUTF8, notePath)
	}
	return text, enc, nil
}

// detectRewritable est la décision de la détection seule, sans mémoire :
// l'encodage deviné, et s'il est assez sûr pour réécrire le fichier.
func detectRewritable(content []byte) (string, charset.Encoding, bool) {
	text, enc := charset.Decode(content)
	return text, enc, rewritable(enc, text) && charset.RoundTrips(content)
}

// EncodingOverridden dit si l'encodage d'une note vient de la mémoire du
// cache contre l'avis de la détection : un choix « Ouvrir quand même », ou un
// texte écrit par l'application que la détection ne reconnaîtrait plus. Sans
// réseau.
//
// C'est la condition pour proposer ResetEncoding : quand la détection seule
// arrive au même encodage, y revenir ne changerait rien.
func (a *App) EncodingOverridden(notePath string) bool {
	content, _, cached := a.cache.Get(notePath)
	if !cached {
		return false
	}
	remembered, ok := a.rememberedEncoding(notePath, content)
	if !ok {
		return false
	}
	if _, ok := charset.DecodeAs(content, remembered); !ok {
		return false // la mémoire ne s'applique pas : decodeNote l'ignore aussi
	}
	_, detected, ok := detectRewritable(content)
	return !ok || detected != remembered
}

// ResetEncoding oublie l'encodage retenu pour une note : le prochain ReadNote
// s'en remet de nouveau à la détection. C'est l'annulation de ForceEncoding,
// sans réécrire le fichier. Sans effet sur une note hors du cache.
func (a *App) ResetEncoding(notePath string) error {
	a.editsMu.Lock()
	delete(a.formats, notePath)
	a.editsMu.Unlock()

	if _, _, cached := a.cache.Get(notePath); !cached {
		return nil
	}
	return a.cache.RememberEncoding(notePath, "")
}

// rememberedEncoding renvoie l'encodage que le cache a retenu pour ce contenu.
func (a *App) rememberedEncoding(notePath string, content []byte) (charset.Encoding, bool) {
	label, ok := a.cache.EncodingOf(notePath, content)
	if !ok {
		return charset.Encoding{}, false
	}
	return charset.ParseEncoding(label)
}

// encodingLabel est l'étiquette à retenir dans le cache : vide pour de
// l'UTF-8 simple, que la détection reconnaît sans erreur possible.
func encodingLabel(enc charset.Encoding) string {
	if enc == (charset.Encoding{Name: charset.UTF8}) {
		return ""
	}
	return enc.String()
}

// ForcibleEncoding renvoie l'encodage dans lequel une note refusée par
// ReadNote (NOT_UTF8) peut tout de même s'ouvrir en saisie si l'utilisateur
// le demande, ou "" si aucun ne la rend à l'identique — un fichier tronqué.
// Sans réseau : la note doit être en cache, ce qu'a fait son aperçu.
//
// C'est l'encodage que la détection avait deviné sans oser l'affirmer : du
// Windows-1252 qui ne ressemble pas à une langue de l'Ouest, de l'UTF-16 sans
// BOM.
func (a *App) ForcibleEncoding(notePath string) string {
	content, _, cached := a.cache.Get(notePath)
	if !cached {
		return ""
	}
	enc := charset.Detect(content)
	if _, ok := charset.DecodeAs(content, enc); !ok {
		return ""
	}
	return string(enc.Name)
}

// ForceEncoding retient, pour le contenu actuel de la note, l'encodage que
// propose ForcibleEncoding : le prochain ReadNote l'ouvre en saisie. C'est le
// geste explicite de l'utilisateur qui tranche là où la détection doutait ;
// il vaut jusqu'à ce que le contenu change ailleurs que dans l'application.
func (a *App) ForceEncoding(notePath string) error {
	content, _, cached := a.cache.Get(notePath)
	if !cached {
		return contentNotCachedError(notePath)
	}
	enc := charset.Detect(content)
	if _, ok := charset.DecodeAs(content, enc); !ok {
		return fmt.Errorf("mobile: [%s] %s ne se relit à l'identique dans aucun encodage connu", CodeNotUTF8, notePath)
	}
	return a.cache.RememberEncoding(notePath, enc.String())
}

// rewritable dit si un encodage détecté est assez sûr pour réécrire le
// fichier. Deux détections sont des suppositions : l'UTF-16 sans BOM, et le
// Windows-1252, qui reçoit tout ce qui n'est ni UTF-8 ni UTF-16. Le premier
// n'est jamais réécrit ; le second seulement si le texte ressemble à une
// langue de l'Ouest (charset.Plausible1252) — un fichier polonais en
// ISO-8859-2 lu en Windows-1252 reste en aperçu.
func rewritable(enc charset.Encoding, text string) bool {
	switch enc.Name {
	case charset.UTF16LE, charset.UTF16BE:
		return enc.BOM
	case charset.Windows1252:
		return charset.Plausible1252(text)
	default:
		return true
	}
}

// noteFormat est ce qu'une note doit retrouver à l'écriture : son encodage et
// sa fin de ligne.
type noteFormat struct {
	encoding charset.Encoding
	ending   notes.LineEnding
}

// rememberFormat retient le format d'un fichier au moment où il est lu.
//
// Le relire dans le cache à l'écriture ne suffirait pas. Une note Windows dont
// on efface tous les sauts de ligne est enregistrée sans aucun, et la détection
// suivante, n'y trouvant rien, conclurait à « \n ». De même, une note
// Windows-1252 dont on efface les accents devient du pur ASCII, que la
// détection lit comme de l'UTF-8 : le premier « é » retapé partirait alors en
// UTF-8, et le fichier changerait d'encodage sans que personne l'ait voulu.
func (a *App) rememberFormat(notePath string, f noteFormat) {
	a.editsMu.Lock()
	defer a.editsMu.Unlock()
	if a.formats == nil {
		a.formats = make(map[string]noteFormat)
	}
	a.formats[notePath] = f
}

// formatFor renvoie le format à écrire pour une note : celui retenu à sa
// lecture, sinon celui de sa version en cache, sinon de l'UTF-8 en « \n ».
func (a *App) formatFor(notePath string) noteFormat {
	a.editsMu.Lock()
	f, ok := a.formats[notePath]
	a.editsMu.Unlock()
	if ok {
		return f
	}
	if content, _, cached := a.cache.Get(notePath); cached {
		if enc, ok := a.rememberedEncoding(notePath, content); ok {
			if text, ok := charset.DecodeAs(content, enc); ok {
				return noteFormat{encoding: enc, ending: notes.DetectLineEnding(text)}
			}
		}
		text, enc := charset.Decode(content)
		return noteFormat{encoding: enc, ending: notes.DetectLineEnding(text)}
	}
	return noteFormat{encoding: charset.Encoding{Name: charset.UTF8}, ending: notes.LF}
}

// NoteEncoding renvoie le nom de l'encodage dans lequel une note sera écrite :
// « UTF-8 », « windows-1252 », « UTF-16LE » ou « UTF-16BE ». Sans réseau.
//
// L'éditeur l'affiche quand ce n'est pas de l'UTF-8 : c'est ce qui explique
// d'avance qu'un emoji y soit refusé.
func (a *App) NoteEncoding(notePath string) string {
	return string(a.formatFor(notePath).encoding.Name)
}

// Unrepresentable renvoie le premier caractère de text que l'encodage de la
// note ne sait pas écrire, ou "" s'il n'y en a aucun. Sans réseau, sans rien
// écrire.
//
// L'interface le demande pour nommer le caractère qu'un enregistrement a
// refusé (ENCODING_UNREPRESENTABLE), et avant de quitter l'éditeur : quitter
// après un refus perdrait les modifications qu'aucune écriture n'a su
// enregistrer.
func (a *App) Unrepresentable(notePath, text string) string {
	if r, ok := charset.Unrepresentable(text, a.formatFor(notePath).encoding); ok {
		return string(r)
	}
	return ""
}

// readBytes lit un fichier depuis le cache ou le serveur.
//
// Le repli hors connexion est commun aux notes texte et aux documents. Garder
// ce chemin en octets est indispensable pour les archives : seules les notes
// sont converties en chaîne, à la toute fin de ReadNote.
func (a *App) readBytes(notePath string) ([]byte, error) {
	lib, local := a.session()

	if local {
		content, _, cached := a.cache.Get(notePath)
		if !cached {
			return nil, contentMissingError(notePath)
		}
		return content, nil
	}
	if lib == nil {
		return nil, errNoWorkspace()
	}

	content, entry, cached := a.cache.Get(notePath)
	if cached && (entry.Dirty || a.recentlyOffline()) {
		return content, nil
	}
	if !cached && a.recentlyOffline() {
		return nil, contentNotCachedError(notePath)
	}

	ctx, cancel := context.WithTimeout(context.Background(), refreshTimeout)
	defer cancel()

	if err := a.cache.Pull(ctx, lib, notePath); err != nil {
		a.noteNetworkResult(err)
		if cached {
			// Le réseau manque, mais la note est connue : on l'ouvre telle
			// qu'on la connaît plutôt que d'échouer.
			return content, nil
		}
		if errors.Is(err, opencloud.ErrOffline) {
			return nil, contentNotCachedError(notePath)
		}
		return nil, err
	}
	a.noteNetworkResult(nil)

	fresh, _, ok := a.cache.Get(notePath)
	if !ok {
		// Pull a constaté que la note n'existe plus côté serveur.
		return nil, fmt.Errorf("mobile: %s: %w", notePath, opencloud.ErrNotFound)
	}
	return fresh, nil
}

func contentNotCachedError(notePath string) error {
	return fmt.Errorf("mobile: [CONTENT_NOT_CACHED] le contenu de %s doit être téléchargé avant son ouverture hors connexion", notePath)
}

func contentMissingError(notePath string) error {
	return fmt.Errorf("mobile: [%s] le contenu de %s est introuvable sur l'appareil et aucun serveur ne peut le fournir", CodeContentMissing, notePath)
}

// noteNetworkResult retient qu'un appel réseau vient d'échouer faute de
// connexion.
//
// Sans cette mémoire, chaque ouverture de note hors connexion attendrait
// l'expiration d'un délai réseau avant de servir le cache. L'application
// paraîtrait figée là où elle devrait être instantanée.
func (a *App) noteNetworkResult(err error) {
	a.mu.Lock()
	defer a.mu.Unlock()

	if err != nil && errors.Is(err, opencloud.ErrOffline) {
		a.offlineUntil = time.Now().Add(offlineBackoff)
		return
	}
	if err == nil {
		a.offlineUntil = time.Time{}
	}
}

func (a *App) recentlyOffline() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return time.Now().Before(a.offlineUntil)
}

// WriteNote enregistre une note.
//
// L'écriture n'atteint que le cache : elle est donc immédiate et ne peut pas
// échouer faute de réseau. La propagation vers le serveur a lieu au prochain
// Sync.
//
// Le texte reçu est en « \n », comme celui que rend ReadNote : les fins de
// ligne du fichier — « \r\n » pour une note venue de Windows — lui sont
// remises ici, et uniformément, y compris sur un texte collé.
//
// Il est de même réencodé dans l'encodage du fichier. Un caractère que cet
// encodage ne sait pas écrire — un emoji dans un fichier Windows-1252 — fait
// refuser l'écriture entière (ENCODING_UNREPRESENTABLE), et rien n'est écrit.
func (a *App) WriteNote(notePath, content string) error {
	if err := a.requirePermission(notePath, "modifier", func(c opencloud.Capabilities) bool { return c.CanWrite }); err != nil {
		return err
	}
	// Un document ne s'écrit jamais, et le refus est ici plutôt que dans
	// l'interface : c'est le seul appel de la façade qui peut détruire un
	// fichier de l'utilisateur en silence. Une écriture partie sur un .docx le
	// remplacerait par du texte, sur un serveur partagé, sans message.
	if err := notes.EnsureWritable(notePath); err != nil {
		return err
	}
	f := a.formatFor(notePath)
	encoded, err := charset.Encode(notes.ApplyLineEnding(content, f.ending), f.encoding)
	if err != nil {
		// Un caractère que l'encodage du fichier ne sait pas écrire : refuser
		// plutôt que le remplacer par « ? », qui serait une perte sans message.
		return fmt.Errorf("mobile: %s : %w", notePath, err)
	}
	// L'encodage est retenu avec le contenu : à la réouverture, c'est lui qui
	// décide, et non une détection qui pourrait douter du texte qu'on vient
	// d'écrire.
	return a.cache.PutEncoded(notePath, encoded, encodingLabel(f.encoding))
}

// RefreshNote force la relecture d'une note depuis le serveur.
//
// Une modification locale non synchronisée n'est jamais écrasée : elle sera
// confrontée au serveur lors du prochain Sync.
func (a *App) RefreshNote(notePath string) error {
	lib, local := a.session()

	if local {
		return errLocalMode("rafraîchir une note")
	}
	if lib == nil {
		return errNoWorkspace()
	}

	ctx, cancel := a.ctx()
	defer cancel()
	return a.cache.Pull(ctx, lib, notePath)
}

// noteRef identifie une note créée.
type noteRef struct {
	Path    string `json:"path"`
	Name    string `json:"name"`
	Display string `json:"display"`
}

// CreateNoteJSON crée une note.
//
// Le nom reçoit l'extension .md s'il ne l'a pas, et un suffixe numérique si
// une note du même nom existe déjà.
func (a *App) CreateNoteJSON(dir, name, content string) (string, error) {
	lib, local := a.session()

	if local {
		return a.createNoteLocal(dir, name, content)
	}
	if err := a.requirePermission(dir, "créer une note dans", func(c opencloud.Capabilities) bool { return c.CanCreateFile }); err != nil {
		return "", err
	}
	if lib == nil {
		return "", errNoWorkspace()
	}

	ctx, cancel := a.ctx()
	defer cancel()

	note, err := lib.Create(ctx, dir, name, []byte(content))
	if err == nil {
		if err := a.cache.Accept(note.Path, []byte(content), note.ETag); err != nil {
			return "", err
		}
		return toJSON(noteRef{Path: note.Path, Name: note.Name, Display: note.DisplayName})
	}
	if !errors.Is(err, opencloud.ErrOffline) && !errors.Is(err, opencloud.ErrUnauthorized) {
		return "", err
	}

	// Hors connexion : la note est créée dans le cache seul et poussée plus
	// tard. Pouvoir écrire une note existante sans réseau mais pas en créer
	// une n'aurait aucun sens pour l'utilisateur.
	//
	// Un token refusé est traité de même : modifier une note existante reste
	// possible sous une session expirée, créer une note doit l'être aussi. La
	// passe de synchronisation la poussera après la reconnexion.
	return a.createNoteLocal(dir, name, content)
}

// createNoteLocal crée une note dans le cache, et l'inscrit en file s'il y a
// une file — en mode local, enqueueLocked n'inscrit rien.
//
// Le nom est choisi d'après le cache seul. Hors connexion on ne peut pas
// savoir ce que le serveur contient : une collision avec une note distante
// reste possible, et c'est le If-None-Match de la synchronisation qui la
// rattrape — la note distante est préservée et la version locale conservée à
// côté. En mode local la question ne se pose pas, le cache sait tout.
func (a *App) createNoteLocal(dir, name, content string) (string, error) {
	name = notes.WithExtension(strings.TrimSpace(name))
	if err := notes.ValidateName(name); err != nil {
		return "", err
	}

	dir = notes.CleanPath(dir)
	available := a.availableNameFromCache(dir, name)
	notePath := path.Join(dir, available)

	if err := a.cache.Put(notePath, []byte(content)); err != nil {
		return "", err
	}
	return toJSON(noteRef{
		Path:    notePath,
		Name:    available,
		Display: notes.DisplayName(available),
	})
}

// availableNameFromCache ajoute un suffixe numérique tant que le nom est pris
// dans le cache.
func (a *App) availableNameFromCache(dir, name string) string {
	taken := map[string]bool{}
	for _, entry := range a.cache.Entries() {
		taken[entry.Path] = true
	}

	ext := path.Ext(name)
	base := strings.TrimSuffix(name, ext)

	for attempt := 0; ; attempt++ {
		candidate := name
		if attempt > 0 {
			candidate = fmt.Sprintf("%s (%d)%s", base, attempt+1, ext)
		}
		if !taken[path.Join(dir, candidate)] {
			return candidate
		}
	}
}

// CreateFolderJSON crée un sous-dossier.
func (a *App) CreateFolderJSON(dir, name string) (string, error) {
	lib, local := a.session()

	if local {
		return a.createFolderLocal(dir, name)
	}
	if err := a.requirePermission(dir, "créer un dossier dans", func(c opencloud.Capabilities) bool { return c.CanCreateFolder }); err != nil {
		return "", err
	}
	if lib == nil {
		return "", errNoWorkspace()
	}

	ctx, cancel := a.ctx()
	defer cancel()

	folder, err := lib.CreateFolder(ctx, dir, name)
	if err == nil {
		if err := a.cache.RememberFolder(folder.Path); err != nil {
			return "", err
		}
		return toJSON(noteRef{Path: folder.Path, Name: folder.Name, Display: folder.Name})
	}
	if !errors.Is(err, opencloud.ErrOffline) && !errors.Is(err, opencloud.ErrUnauthorized) {
		return "", err
	}

	// Hors connexion, ou sous un token refusé : le dossier est retenu par le
	// cache et créé au prochain passage. Le navigateur l'affiche entre-temps.
	return a.createFolderLocal(dir, name)
}

// createFolderLocal retient un dossier dans le cache seul.
//
// EnsureFolder inscrit une création en file, ce qu'il faut hors connexion et
// qui ne coûte rien en mode local, où la file n'accepte plus rien.
func (a *App) createFolderLocal(dir, name string) (string, error) {
	name = strings.TrimSpace(name)
	if err := notes.ValidateName(name); err != nil {
		return "", err
	}
	folderPath := path.Join(notes.CleanPath(dir), name)
	if err := a.cache.EnsureFolder(folderPath); err != nil {
		return "", err
	}
	return toJSON(noteRef{Path: folderPath, Name: name, Display: name})
}

// Rename renomme une note ou un dossier et renvoie son nouveau chemin.
func (a *App) Rename(itemPath, newName string) (string, error) {
	lib, local := a.session()

	if local {
		return a.renameLocal(itemPath, newName, false)
	}
	if err := a.requirePermission(itemPath, "renommer", func(c opencloud.Capabilities) bool { return c.CanRename }); err != nil {
		return "", err
	}
	if lib == nil {
		return "", errNoWorkspace()
	}

	ctx, cancel := a.ctx()
	defer cancel()

	newPath, err := lib.Rename(ctx, itemPath, newName)
	if err == nil {
		// Le cache suit, sans rien remettre en file : le serveur est déjà à
		// jour.
		if err := a.cache.RenameLocal(itemPath, newPath); err != nil {
			return "", err
		}
		return newPath, nil
	}
	if !errors.Is(err, opencloud.ErrOffline) {
		return "", err
	}

	return a.renameLocal(itemPath, newName, true)
}

// renameLocal applique un renommage au cache seul.
//
// differer sépare les deux appelants, et ce n'est pas un détail de forme : la
// file refuse le renommage différé d'un dossier — STRUCTURAL_OFFLINE_FOLDER —
// parce qu'elle ne saurait pas le rejouer fidèlement sur le serveur. En mode
// local il n'y a rien à rejouer, donc rien à refuser : renommer un dossier y
// est un geste ordinaire.
func (a *App) renameLocal(itemPath, newName string, differer bool) (string, error) {
	target, err := notes.ResolveRename(itemPath, newName)
	if err != nil {
		return "", err
	}
	return a.renameInCache(itemPath, target, differer)
}

// Move déplace une note ou un dossier vers un autre dossier.
//
// Même schéma que Rename : le serveur d'abord, et un repli hors connexion qui
// calcule le même chemin cible que la synchronisation atteindra plus tard —
// ResolveMove porte cette règle, partagée avec Library.Move pour que les deux
// chemins ne puissent pas diverger.
func (a *App) Move(itemPath, targetDir string) (string, error) {
	lib, local := a.session()

	if local {
		return a.moveLocal(itemPath, targetDir, false)
	}
	if err := a.requirePermission(itemPath, "déplacer", func(c opencloud.Capabilities) bool { return c.CanMove }); err != nil {
		return "", err
	}
	if err := a.requirePermission(targetDir, "déplacer un élément vers", func(c opencloud.Capabilities) bool {
		if notes.IsNote(itemPath) {
			return c.CanCreateFile
		}
		return c.CanCreateFolder
	}); err != nil {
		return "", err
	}
	if lib == nil {
		return "", errNoWorkspace()
	}

	ctx, cancel := a.ctx()
	defer cancel()

	newPath, err := lib.Move(ctx, itemPath, targetDir)
	if err == nil {
		// Le cache suit, sans rien remettre en file : le serveur est déjà à
		// jour. `RenameLocal` fait exactement ce qu'il faut ici — c'est le
		// même geste sur le cache, que le chemin ait changé de dossier ou de
		// nom.
		if err := a.cache.RenameLocal(itemPath, newPath); err != nil {
			return "", err
		}
		return newPath, nil
	}
	if !errors.Is(err, opencloud.ErrOffline) {
		return "", err
	}

	return a.moveLocal(itemPath, targetDir, true)
}

// moveLocal applique un déplacement au cache seul. Même partage que
// renameLocal : c'est le même geste sur le cache, seul le chemin cible se
// calcule autrement.
func (a *App) moveLocal(itemPath, targetDir string, differer bool) (string, error) {
	target, err := notes.ResolveMove(itemPath, targetDir)
	if err != nil {
		return "", err
	}
	return a.renameInCache(itemPath, target, differer)
}

// renameInCache applique au cache seul un chemin cible déjà calculé.
//
// Jamais RenameLocal ici : il écrase une cible occupée, ce qui n'est juste que
// lorsque le serveur vient de confirmer qu'elle était libre. Sans serveur — ou
// sans réseau — une cible que le cache connaît est une vraie note, et le geste
// est refusé comme le serveur l'aurait refusé.
func (a *App) renameInCache(itemPath, target string, differer bool) (string, error) {
	itemPath = notes.CleanPath(itemPath)
	var err error
	if differer {
		err = a.cache.Rename(itemPath, target)
	} else {
		err = a.cache.RenameOnDevice(itemPath, target)
	}
	if err != nil {
		return "", err
	}
	return target, nil
}

// CopyJSON duplique une note dans un autre dossier et renvoie la copie créée.
//
// Une copie est une création : elle emprunte le chemin de CreateNoteJSON, donc
// les mêmes contrôles de nom et le même suffixe « (2) » quand la destination
// porte déjà ce nom — y compris quand la destination est le dossier d'origine,
// ce qui revient à dupliquer sur place. L'extension est celle de la source :
// « notes.txt » copié reste un « .txt », jamais converti en « .md ».
//
// Le contenu copié est celui que l'utilisateur voit : sa version locale si elle
// porte des modifications non synchronisées, celle du serveur sinon, le cache
// hors connexion en dernier recours. readBytes tranche exactement comme pour
// l'ouverture d'une note, CONTENT_NOT_CACHED compris.
//
// Hors périmètre, et refusé ici plutôt que dans l'interface : un dossier — sa
// duplication récursive est un geste serveur, étranger au modèle local-first —
// et un document, qu'un PUT de nos octets ne ferait que corrompre.
func (a *App) CopyJSON(itemPath, targetDir string) (string, error) {
	lib, local := a.session()

	if lib == nil && !local {
		return "", errNoWorkspace()
	}
	if !local {
		if err := a.requirePermission(targetDir, "copier une note vers", func(c opencloud.Capabilities) bool { return c.CanCreateFile }); err != nil {
			return "", err
		}
	}

	itemPath = notes.CleanPath(itemPath)
	if itemPath == "" {
		return "", fmt.Errorf("notes: [%s] aucun élément à copier", notes.CodePathEmpty)
	}
	if notes.IsReadOnly(itemPath) {
		return "", fmt.Errorf("mobile: [%s] un fichier %s ne peut pas être copié", CodeUnsupported, path.Ext(itemPath))
	}
	if !notes.IsNote(itemPath) {
		return "", fmt.Errorf("mobile: [%s] seule une note peut être copiée, pas %q", CodeUnsupported, itemPath)
	}

	content, err := a.readBytes(itemPath)
	if err != nil {
		return "", err
	}

	name := path.Base(itemPath)

	if local {
		return a.createNoteLocal(targetDir, name, string(content))
	}

	ctx, cancel := a.ctx()
	defer cancel()

	note, err := lib.Create(ctx, targetDir, name, content)
	if err == nil {
		// Le cache adopte la copie tout de suite : elle s'affiche et s'ouvre
		// sans attendre le prochain listing, comme une note fraîchement créée.
		if err := a.cache.Accept(note.Path, content, note.ETag); err != nil {
			return "", err
		}
		return toJSON(noteRef{Path: note.Path, Name: note.Name, Display: note.DisplayName})
	}
	if !errors.Is(err, opencloud.ErrOffline) {
		return "", err
	}

	// Hors connexion : la copie vit d'abord dans le cache et part à la
	// prochaine passe, comme n'importe quelle note créée sans réseau.
	return a.createNoteLocal(targetDir, name, string(content))
}

// Delete supprime une note ou un dossier.
func (a *App) Delete(itemPath string) error {
	lib, local := a.session()

	if local {
		// Forget plutôt que Delete : rien à inscrire en file, et surtout
		// Delete refuse la suppression différée d'un dossier — une prudence
		// qui n'a plus d'objet quand il n'y a pas de serveur à qui la rejouer.
		return a.cache.Forget(itemPath)
	}
	if err := a.requirePermission(itemPath, "supprimer", func(c opencloud.Capabilities) bool { return c.CanDelete }); err != nil {
		return err
	}
	if lib == nil {
		return errNoWorkspace()
	}

	ctx, cancel := a.ctx()
	defer cancel()

	err := lib.Delete(ctx, itemPath)
	if err == nil {
		return a.cache.Forget(itemPath)
	}
	if errors.Is(err, opencloud.ErrNotFound) {
		// Déjà absent du serveur : le résultat voulu est atteint.
		return a.cache.Forget(itemPath)
	}
	if !errors.Is(err, opencloud.ErrOffline) {
		return err
	}

	// Hors connexion : la suppression est appliquée au cache et rejouée plus
	// tard.
	return a.cache.Delete(itemPath)
}

// SuggestName propose un nom de fichier valide à partir d'un titre saisi.
func (a *App) SuggestName(title string) string {
	return notes.SanitizeName(title)
}

// TitleOf renvoie le titre à afficher : celui écrit dans le contenu, sinon le
// nom du fichier.
func (a *App) TitleOf(name, content string) string {
	return notes.TitleOf(notes.Note{Name: name, DisplayName: notes.DisplayName(name)}, []byte(content))
}

// --- Synchronisation --------------------------------------------------------

// conflictInfo signale une note dont la version locale a été mise de côté.
type conflictInfo struct {
	ID        string `json:"id"`
	Operation string `json:"operation"`
	Path      string `json:"path"`
	CopyPath  string `json:"copyPath"`
	CreatedAt string `json:"createdAt"`
}

// conflictResolutionRequest est la décision explicite prise dans l'interface.
// Server, local et both correspondent aux constantes du Store, mais restent
// des chaînes pour que la façade gomobile n'expose que du JSON.
type conflictResolutionRequest struct {
	ID         string `json:"id"`
	Resolution string `json:"resolution"`
}

type conflictResolutionResult struct {
	// Conflict est rempli seulement si le serveur a encore changé pendant une
	// résolution « garder le local » : l'interface doit alors reproposer le
	// choix sur sa dernière version.
	Conflict *conflictInfo `json:"conflict,omitempty"`
}

func conflictInfoOf(c store.Conflict) conflictInfo {
	return conflictInfo{
		ID:        c.ID,
		Operation: string(c.Operation),
		Path:      c.Path,
		CopyPath:  c.CopyPath,
		CreatedAt: c.CreatedAt.UTC().Format(time.RFC3339Nano),
	}
}

// syncResult résume une passe de synchronisation.
type syncResult struct {
	Pushed    int            `json:"pushed"`
	Deleted   int            `json:"deleted"`
	Moved     int            `json:"moved"`
	Conflicts []conflictInfo `json:"conflicts"`
	Remaining int            `json:"remaining"`

	// Error porte le message d'une panne réseau. La passe reste partiellement
	// utile : ce qui a été poussé l'est, le reste attend. C'est une
	// information, pas un échec de l'appel.
	Error string `json:"error"`

	// ErrorCode est la catégorie de cette erreur, déjà extraite. Sur AUTH,
	// réessayer est inutile : c'est le token qu'il faut renouveler.
	ErrorCode string `json:"errorCode"`
}

// SyncJSON exécute une passe de synchronisation.
//
// Une panne réseau n'est pas remontée comme erreur d'appel : elle est décrite
// dans le résultat, avec ce qui a tout de même été propagé. L'interface peut
// ainsi afficher « 3 notes envoyées, 2 en attente » plutôt qu'un échec sec.
func (a *App) SyncJSON() (string, error) {
	lib, local, base := a.sessionDePasse()

	if local {
		return "", errLocalMode("la synchronisation")
	}
	if lib == nil {
		return "", errNoWorkspace()
	}

	ctx, cancel := context.WithTimeout(base, syncPassTimeout)
	defer cancel()

	// WorkManager, le bouton manuel et tout futur appel de la façade convergent
	// ici. Le Store protège sa mémoire, mais une passe entière doit être seule à
	// parler au serveur : sinon deux appels peuvent rejouer la même tête de file.
	liberer, err := a.prendrePasse(ctx)
	if err != nil {
		return toJSON(syncResult{
			Conflicts: []conflictInfo{},
			Remaining: a.PendingCount(),
			Error:     err.Error(),
			ErrorCode: ErrorCode(err.Error()),
		})
	}
	defer liberer()

	report, err := a.cache.Push(ctx, lib)

	result := syncResult{
		Pushed:    report.Pushed,
		Deleted:   report.Deleted,
		Moved:     report.Moved,
		Remaining: report.Remaining,
		Conflicts: []conflictInfo{},
	}
	for _, c := range report.Conflicts {
		result.Conflicts = append(result.Conflicts, conflictInfoOf(c))
	}
	if err != nil {
		result.Error = err.Error()
		result.ErrorCode = ErrorCode(result.Error)
	}
	return toJSON(result)
}

// ConflictsJSON renvoie les conflits ouverts, y compris ceux créés par une
// passe de synchronisation précédente ou avant le redémarrage de l'appareil.
func (a *App) ConflictsJSON() (string, error) {
	conflicts := a.cache.Conflicts()
	result := make([]conflictInfo, 0, len(conflicts))
	for _, c := range conflicts {
		result = append(result, conflictInfoOf(c))
	}
	return toJSON(result)
}

// ResolveConflictJSON applique une décision explicite sur un conflit ouvert.
// Cette opération partage le verrou d'une passe SyncJSON : elle lit ou écrit
// le serveur et ne doit jamais se superposer à une synchronisation.
func (a *App) ResolveConflictJSON(requestJSON string) (string, error) {
	var request conflictResolutionRequest
	if err := json.Unmarshal([]byte(requestJSON), &request); err != nil {
		return "", fmt.Errorf("résolution de conflit invalide : %w", err)
	}
	if request.ID == "" {
		return "", errors.New("résolution de conflit invalide : id manquant")
	}

	resolution := store.ConflictResolution(request.Resolution)
	if resolution != store.KeepServer && resolution != store.KeepLocal && resolution != store.KeepBoth {
		return "", errors.New("résolution de conflit invalide : décision inconnue")
	}

	lib, local, base := a.sessionDePasse()
	if local {
		return "", errLocalMode("arbitrer un conflit")
	}
	if lib == nil {
		return "", errNoWorkspace()
	}

	ctx, cancel := context.WithTimeout(base, syncPassTimeout)
	defer cancel()
	liberer, err := a.prendrePasse(ctx)
	if err != nil {
		return "", err
	}
	defer liberer()

	next, err := a.cache.ResolveConflict(ctx, lib, request.ID, resolution)
	if err != nil {
		return "", err
	}
	result := conflictResolutionResult{}
	if next != nil {
		info := conflictInfoOf(*next)
		result.Conflict = &info
	}
	return toJSON(result)
}

// PendingCount renvoie le nombre d'opérations en attente de propagation.
func (a *App) PendingCount() int {
	return len(a.cache.Pending())
}

// --- Mise en forme ----------------------------------------------------------

// formatRequest est la demande envoyée par la barre d'outils.
//
// Start et End sont en unités de code UTF-16, comme TextRange de Compose : le
// Kotlin transmet les valeurs telles quelles, sans conversion.
type formatRequest struct {
	Text   string `json:"text"`
	Start  int    `json:"start"`
	End    int    `json:"end"`
	Action string `json:"action"`
}

// formatResult est le nouvel état de l'éditeur.
type formatResult struct {
	Text  string `json:"text"`
	Start int    `json:"start"`
	End   int    `json:"end"`
}

// ApplyFormatJSON applique une action de mise en forme.
func (a *App) ApplyFormatJSON(requestJSON string) (string, error) {
	var req formatRequest
	if err := json.Unmarshal([]byte(requestJSON), &req); err != nil {
		return "", fmt.Errorf("mobile: demande de mise en forme illisible: %w", err)
	}

	doc, err := markdown.Apply(
		markdown.Doc{Text: req.Text, Start: req.Start, End: req.End},
		markdown.Action(req.Action),
	)
	if err != nil {
		return "", err
	}
	return toJSON(formatResult{Text: doc.Text, Start: doc.Start, End: doc.End})
}

// FormatActionsJSON énumère les actions disponibles, dans l'ordre où elles ont
// du sens dans une barre d'outils. L'interface n'a pas à les coder en dur.
func (a *App) FormatActionsJSON() (string, error) {
	actions := markdown.Actions()
	out := make([]string, 0, len(actions))
	for _, action := range actions {
		out = append(out, string(action))
	}
	return toJSON(out)
}

// --- Aperçu -----------------------------------------------------------------

// noteSpan est une portion mise en forme du texte d'un bloc.
//
// Start et End sont en unités de code UTF-16, comme partout ailleurs à la
// frontière : Kotlin les pose tels quels dans un AnnotatedString, sans aucune
// conversion.
type noteSpan struct {
	Start int    `json:"start"`
	End   int    `json:"end"`
	Style string `json:"style"`
	Href  string `json:"href,omitempty"`
}

// noteBlock est une unité d'affichage de l'aperçu : un paragraphe, un titre,
// une puce, une ligne de tableau.
//
// Le modèle est plat : l'imbrication tient dans depth et quote, ce qui évite à
// Kotlin de descendre un arbre pour dessiner une liste.
type noteBlock struct {
	Kind    string     `json:"kind"`
	Text    string     `json:"text,omitempty"`
	Spans   []noteSpan `json:"spans,omitempty"`
	Level   int        `json:"level,omitempty"`
	Depth   int        `json:"depth,omitempty"`
	Quote   int        `json:"quote,omitempty"`
	Number  int        `json:"number,omitempty"`
	Checked bool       `json:"checked,omitempty"`
	Lang    string     `json:"lang,omitempty"`
	Cells   []string   `json:"cells,omitempty"`
	Header  bool       `json:"header,omitempty"`
}

// RenderNoteJSON prépare l'affichage d'une note en lecture seule.
//
// Fonction pure : ni réseau, ni cache, ni session. L'interface passe le
// contenu qu'elle a déjà en main — l'aperçu marche donc hors connexion, et sur
// un brouillon jamais enregistré.
//
// Le **nom** compte autant que le contenu : c'est lui qui décide si le texte
// est interprété comme du Markdown ou affiché tel quel. Un .txt n'est jamais
// interprété.
func (a *App) RenderNoteJSON(name, content string) (string, error) {
	// Un document n'entre pas par ici, et c'est une question de format, pas de
	// politesse : gomobile décode la chaîne en UTF-8 vers un String Java, et un
	// .docx en ressortirait truffé de caractères de remplacement. Le binaire se
	// lit du côté Go — voir RenderFileJSON.
	if notes.IsDocument(name) {
		return "", fmt.Errorf("mobile: [%s] un fichier %s ne traverse pas la frontière en chaîne", CodeUnsupported, path.Ext(name))
	}

	blocks, err := notes.Render(name, []byte(content))
	if err != nil {
		return "", err
	}

	return toJSON(versNoteBlocks(blocks))
}

// RenderFileJSON prépare l'affichage d'un fichier que l'application ne sait
// que lire.
//
// Le fichier est lu et analysé entièrement côté Go : un .docx ou un .odt est
// une archive binaire, qu'il serait destructeur de faire traverser gomobile
// dans une chaîne UTF-8. Seuls les blocs JSON traversent la frontière.
func (a *App) RenderFileJSON(filePath string) (string, error) {
	content, err := a.readBytes(filePath)
	if err != nil {
		return "", err
	}

	blocks, err := notes.Render(filePath, content)
	if err != nil {
		return "", err
	}
	return toJSON(versNoteBlocks(blocks))
}

// ExportFile recopie un fichier, note ou document, vers un chemin du disque
// choisi par l'interface — pour le joindre à un partage.
//
// Le contenu ne traverse pas la frontière : seuls les deux chemins le font.
// C'est ce qui permet de partager un .docx ou un .odt, qu'une chaîne UTF-8
// mutilerait, et d'épargner à une note illustrée l'aller-retour de ses images
// par Kotlin. Les octets écrits sont exactement ceux du cache, avec le même
// repli hors connexion que ReadNote.
func (a *App) ExportFile(filePath, destPath string) error {
	content, err := a.readBytes(filePath)
	if err != nil {
		return err
	}
	if err := os.WriteFile(destPath, content, 0o600); err != nil {
		return fmt.Errorf("mobile: [%s] écriture de la copie de %s : %w", store.CodeStorageIO, filePath, err)
	}
	return nil
}

// versNoteBlocks convertit les blocs du cœur vers la forme sérialisée.
func versNoteBlocks(blocks []markdown.Block) []noteBlock {
	out := make([]noteBlock, 0, len(blocks))
	for _, b := range blocks {
		converti := noteBlock{
			Kind:    string(b.Kind),
			Text:    b.Text,
			Level:   b.Level,
			Depth:   b.Depth,
			Quote:   b.Quote,
			Number:  b.Number,
			Checked: b.Checked,
			Lang:    b.Lang,
			Cells:   b.Cells,
			Header:  b.Header,
		}
		for _, s := range b.Spans {
			converti.Spans = append(converti.Spans, noteSpan{
				Start: s.Start,
				End:   s.End,
				Style: string(s.Style),
				Href:  s.Href,
			})
		}
		out = append(out, converti)
	}
	return out
}

// --- Préparation de l'édition -----------------------------------------------

// preparedEdit est le contenu tel que le champ de saisie doit le recevoir.
type preparedEdit struct {
	Text        string   `json:"text"`
	Images      []string `json:"images"`
	Editable    bool     `json:"editable"`
	LongestWord int      `json:"longestWord"`
}

// PrepareEditJSON allège une note avant de l'ouvrir dans un champ de saisie.
//
// # Pourquoi cette étape existe
//
// L'éditeur web d'OpenCloud insère les images en « data:image/jpeg;base64,… ».
// Confier ce pavé à un champ de texte fait tuer l'application par le système —
// constaté sur appareil : mort du processus, sans exception Java, suivie d'une
// purge mémoire de tout le téléphone. Le coupable n'est pas la taille mais
// l'absence de point de coupure : 285 ko de prose passent, 44 ko de base64 non.
//
// `text` est donc le contenu avec ses données remplacées par des jetons courts,
// et `images` ce qui en a été retiré. **L'interface doit repasser `images` à
// RestoreImages avant tout enregistrement** : sans ça, elle écrirait le texte
// allégé sur le serveur et l'image serait perdue dans la vraie note.
//
// `editable` reste faux quand le texte allégé porte encore un mot démesuré —
// un fichier qui n'a rien à voir avec une image. La note s'ouvre alors en
// lecture seule : l'aperçu, lui, n'a pas cette limite.
func (a *App) PrepareEditJSON(name, content string) (string, error) {
	// Préparer la saisie d'un document n'a pas de sens, et le laisser passer en
	// aurait un mauvais : l'interface ouvrirait un champ de texte, l'utilisateur
	// taperait, et l'enregistrement écraserait le document.
	if err := notes.EnsureWritable(name); err != nil {
		return "", err
	}

	text, images := notes.PrepareEdit(name, content)
	if images == nil {
		images = []string{}
	}
	return toJSON(preparedEdit{
		Text:        text,
		Images:      images,
		Editable:    markdown.Editable(text),
		LongestWord: markdown.LongestWord(text),
	})
}

// RestoreImages remet les données en ligne à la place de leurs jetons.
//
// À appeler avant chaque écriture, sur le texte sorti du champ de saisie.
// Un jeton que l'utilisateur a effacé ne revient pas : supprimer le repère
// d'une image, c'est supprimer l'image.
func (a *App) RestoreImages(text, imagesJSON string) (string, error) {
	var images []string
	if err := json.Unmarshal([]byte(imagesJSON), &images); err != nil {
		return "", fmt.Errorf("mobile: liste d'images illisible: %w", err)
	}
	return markdown.RestoreInlineData(text, images), nil
}

// openedEdit est une note ouverte en saisie, ses images gardées côté Go.
type openedEdit struct {
	Session     string `json:"session"`
	Text        string `json:"text"`
	Editable    bool   `json:"editable"`
	LongestWord int    `json:"longestWord"`
	Title       string `json:"title"`
}

// OpenEditJSON prépare une note pour la saisie, comme PrepareEditJSON, mais
// **garde les images de ce côté-ci** de la frontière.
//
// Avec PrepareEditJSON, les images faisaient l'aller-retour à chaque
// enregistrement : encodées en JSON par Kotlin, décodées ici, restituées, puis
// le texte complet repartait vers Kotlin pour revenir aussitôt par WriteNote.
// Une photo de 3 Mo coûtait ainsi 100 à 180 ms de thread principal à chaque
// pause de frappe, mesuré sur téléphone (section 7 bis d'ARCHITECTURE.md).
//
// `session` est vide quand la note n'a aucune image : il n'y a alors rien à
// garder, et WriteEditedNote écrit le texte tel quel. Sinon, l'interface la
// repasse à WriteEditedNote pour chaque écriture, puis à CloseEdit en quittant.
//
// `title` évite un second passage du texte complet par TitleOf.
func (a *App) OpenEditJSON(name, content string) (string, error) {
	if err := notes.EnsureWritable(name); err != nil {
		return "", err
	}

	text, images := notes.PrepareEdit(name, content)
	var session string
	if len(images) > 0 {
		a.editsMu.Lock()
		a.nextEdit++
		session = fmt.Sprintf("edit-%d", a.nextEdit)
		if a.edits == nil {
			a.edits = make(map[string][]string)
		}
		a.edits[session] = images
		a.editsMu.Unlock()
	}
	return toJSON(openedEdit{
		Session:     session,
		Text:        text,
		Editable:    markdown.Editable(text),
		LongestWord: markdown.LongestWord(text),
		Title:       a.TitleOf(name, text),
	})
}

// WriteEditedNote restitue les images de la session puis écrit la note, en un
// seul appel : ni les images ni le texte restitué ne traversent la frontière.
//
// Une session inconnue est **refusée**, et rien n'est écrit. Écrire quand même
// enregistrerait le texte à jetons à la place de la note, et l'image serait
// perdue sur le serveur, sans message.
func (a *App) WriteEditedNote(session, notePath, text string) error {
	if session == "" {
		return a.WriteNote(notePath, text)
	}
	a.editsMu.Lock()
	images, ok := a.edits[session]
	a.editsMu.Unlock()
	if !ok {
		return fmt.Errorf("mobile: session d'édition inconnue %q, écriture refusée pour ne pas perdre ses images", session)
	}
	return a.WriteNote(notePath, markdown.RestoreInlineData(text, images))
}

// CloseEdit libère les images d'une session d'édition. Sans effet sur une
// session vide ou déjà fermée.
//
// À appeler **après** la dernière écriture : toute WriteEditedNote qui suit
// est refusée.
func (a *App) CloseEdit(session string) {
	a.editsMu.Lock()
	delete(a.edits, session)
	a.editsMu.Unlock()
}

// MaxEditableWord est la borne exposée à l'interface : au-delà, un mot sans
// espace n'est plus confié à un champ de saisie.
func MaxEditableWord() int { return markdown.MaxEditableWord() }

// --- Erreurs ----------------------------------------------------------------

// CodeUnsupported signale un appel de la façade qui ne sait pas traiter ce
// fichier — pas parce que le contenu est refusé, mais parce que ce chemin-là ne
// convient pas au format.
//
// C'est le seul code né dans mobile/ : il ne décrit pas une règle du cœur mais
// une contrainte du binding, celle qui veut qu'un binaire ne traverse pas
// gomobile dans une chaîne.
const CodeUnsupported = "UNSUPPORTED"

// CodePermissionDenied signale un geste que les capacités WebDAV connues
// interdisent avant même de contacter le serveur.
const CodePermissionDenied = "PERMISSION_DENIED"

func (a *App) requirePermission(itemPath, action string, allowed func(opencloud.Capabilities) bool) error {
	if allowed(opencloud.CapabilitiesOf(a.cache.Permissions(itemPath))) {
		return nil
	}
	return fmt.Errorf("mobile: [%s] droits insuffisants pour %s %s", CodePermissionDenied, action, itemPath)
}

type capabilityInfo struct {
	CanWrite        bool `json:"canWrite"`
	CanDelete       bool `json:"canDelete"`
	CanRename       bool `json:"canRename"`
	CanMove         bool `json:"canMove"`
	CanCreateFile   bool `json:"canCreateFile"`
	CanCreateFolder bool `json:"canCreateFolder"`
}

// CapabilitiesJSON renvoie les droits mémorisés d'un chemin, sans réseau.
func (a *App) CapabilitiesJSON(itemPath string) (string, error) {
	c := opencloud.CapabilitiesOf(a.cache.Permissions(itemPath))
	return toJSON(capabilityInfo(c))
}

// CodeNotUTF8 signale un fichier texte dont l'encodage n'est pas reconnu avec
// assez de certitude pour le réécrire : UTF-16 sans BOM, ou contenu que
// l'aller-retour ne rend pas à l'identique (voir ReadNote). Son nom date du
// temps où tout ce qui n'était pas de l'UTF-8 s'ouvrait en lecture seule ; il
// reste pour ne pas changer le contrat.
//
// Né dans mobile/ comme UNSUPPORTED : c'est la traversée de gomobile, qui
// convertit en String Java, qui rend ce contenu dangereux. Le fichier reste
// lisible en aperçu ; il ne passe simplement jamais par une chaîne qu'on
// pourrait réécrire.
const CodeNotUTF8 = "NOT_UTF8"

// CodeLocalMode signale un geste qui n'a de sens qu'avec un serveur, demandé
// à une application qui n'en a pas.
//
// Il ne décrit pas une panne : synchroniser sans serveur n'est pas un échec,
// c'est une question qui ne se pose pas. L'interface n'a en principe pas à le
// rencontrer — elle masque ces gestes en mode local — mais un travailleur de
// fond programmé avant la bascule, lui, peut très bien arriver après.
const CodeLocalMode = "LOCAL_MODE"

// CodeContentMissing signale une note dont le contenu manque sur l'appareil
// alors qu'aucun serveur ne peut le fournir.
//
// Distinct de CONTENT_NOT_CACHED, qui promet un téléchargement dès le retour
// du réseau : ici il n'y a rien à attendre, et proposer d'attendre serait
// mentir. Ne devrait pas survenir — le débranchement retire de l'inventaire ce
// qu'il n'a pas pu rapatrier — mais un blob perdu reste possible.
const CodeContentMissing = "CONTENT_MISSING"

// CodeQuotaTooLow signale un seuil de cache trop bas pour contenir tout ce
// qu'un débranchement doit rapatrier.
//
// C'est un refus de commencer, pas un échec en cours de route : au-delà du
// seuil, chaque note reçue en évincerait une autre, et la boucle tournerait
// sans fin en annonçant des progrès. L'interface propose de relever le seuil.
const CodeQuotaTooLow = "QUOTA_TOO_LOW"

// CodePendingChanges signale des modifications qui n'ont pas atteint le
// serveur alors qu'on s'apprête à vider la file. Une passe de synchronisation
// les fait partir.
const CodePendingChanges = "PENDING_CHANGES"

// CodeAccountMismatch protège le cache mono-compte jusqu'à l'arrivée du
// sélecteur de profils : une autre identité doit créer un autre dossier.
const CodeAccountMismatch = "ACCOUNT_MISMATCH"

// CodeSessionChanged signale une connexion devenue caduque en vol : une
// déconnexion, un débranchement ou un changement d'espace est passé entre
// l'appel réseau et son application.
const CodeSessionChanged = "SESSION_CHANGED"

// ErrorCode extrait l'étiquette de catégorie d'un message d'erreur.
//
// gomobile ne transmet qu'une chaîne : l'erreur typée ne franchit pas la
// frontière. Les erreurs du client portent donc leur catégorie entre crochets,
// ce qui permet à Kotlin de réagir sans dépendre de la formulation française
// du message. Renvoie une chaîne vide si aucune catégorie n'est reconnue.
func ErrorCode(message string) string {
	// Les codes de transport passent d'abord, et dans cet ordre : une erreur
	// locale peut envelopper une erreur réseau (« store: … opencloud:
	// [NOTFOUND] … »), et c'est la cause profonde qui décide de la réaction
	// d'Android — réessayer, redemander le token, ou renoncer.
	for _, code := range []string{
		opencloud.CodeUnauthorized,
		opencloud.CodeForbidden,
		opencloud.CodeConflict,
		opencloud.CodeNotFound,
		opencloud.CodeOffline,
		opencloud.CodeHTTP,
	} {
		if strings.Contains(message, "["+code+"]") {
			return code
		}
	}
	return codeLocal(message)
}

// codeLocal lit la première étiquette de la forme [NOM_EN_MAJUSCULES].
//
// Reconnaître la forme plutôt qu'une liste fermée évite d'avoir à toucher la
// façade — donc à régénérer le .aar — chaque fois qu'une couche du dessous
// étiquette une nouvelle règle. La façade transmet, Kotlin décide quoi en
// dire ; un code qu'il ne connaît pas encore le fait retomber sur le message
// brut, ce qui dégrade en français plutôt qu'en écran vide.
func codeLocal(message string) string {
	for i := 0; i < len(message); i++ {
		if message[i] != '[' {
			continue
		}
		j := i + 1
		for j < len(message) && (message[j] >= 'A' && message[j] <= 'Z' ||
			message[j] >= '0' && message[j] <= '9' || message[j] == '_') {
			j++
		}
		if j > i+1 && j < len(message) && message[j] == ']' {
			return message[i+1 : j]
		}
	}
	return ""
}

// MaxNameBytes et ForbiddenNameChars exposent les bornes du nommage.
//
// Sans elles, l'interface devrait recopier « 200 » et la liste de caractères
// dans sa propre formulation de la règle : deux sources de vérité pour une
// contrainte qui vit dans internal/notes. C'est ce qui permet à Android de
// rédiger le message d'erreur dans la langue de l'appareil sans rien
// dupliquer.
func MaxNameBytes() int { return notes.MaxNameBytes() }

// ForbiddenNameChars renvoie les caractères refusés à la création d'un nom.
func ForbiddenNameChars() string { return notes.ForbiddenNameChars() }

// IsAuthError indique un token invalide ou expiré : Android doit redemander la
// saisie plutôt que de réessayer.
func IsAuthError(message string) bool {
	return ErrorCode(message) == opencloud.CodeUnauthorized
}

// IsConflictError indique qu'une version distante plus récente a fait échouer
// l'écriture.
func IsConflictError(message string) bool {
	return ErrorCode(message) == opencloud.CodeConflict
}

// IsNotFoundError indique une note ou un dossier absent du serveur.
func IsNotFoundError(message string) bool {
	return ErrorCode(message) == opencloud.CodeNotFound
}

// IsOfflineError indique que le serveur n'a pas pu être joint.
//
// À distinguer d'une erreur applicative : réessayer plus tard a du sens, et
// l'interface ne doit pas présenter cela comme un échec de l'opération.
func IsOfflineError(message string) bool {
	return ErrorCode(message) == opencloud.CodeOffline
}

// IsPlainText indique qu'un nom de fichier désigne du texte brut, à afficher
// tel quel et à modifier sans barre de mise en forme.
//
// *fonction de paquet* — l'interface a besoin de la réponse avant d'avoir un
// contenu à rendre, ne serait-ce que pour un fichier vide. Elle existe pour
// que la liste des extensions ne soit pas recopiée en Kotlin, où elle
// divergerait au premier format ajouté.
func IsPlainText(name string) bool {
	return notes.IsPlainText(name)
}

// IsDocument indique qu'un nom désigne un document bureautique, lu et analysé
// côté Go. Fonction de paquet pour que Kotlin n'ait pas à recopier les
// extensions reconnues par le cœur.
func IsDocument(name string) bool {
	return notes.IsDocument(name)
}

// IsReadOnly indique qu'un nom désigne un fichier lisible mais jamais
// modifiable : un document, ou un fichier texte de configuration ou de
// données. Il s'ouvre par RenderFileJSON, jamais par ReadNote.
func IsReadOnly(name string) bool {
	return notes.IsReadOnly(name)
}

func errNotConnected() error {
	return errors.New("mobile: aucune session ouverte, appeler Connect")
}

func errNoWorkspace() error {
	return errors.New("mobile: aucun espace de travail choisi, appeler SelectWorkspace")
}

func errLocalMode(geste string) error {
	return fmt.Errorf("mobile: [%s] %s exige un serveur, et l'application n'en a pas", CodeLocalMode, geste)
}

func toJSON(v any) (string, error) {
	data, err := json.Marshal(v)
	if err != nil {
		return "", fmt.Errorf("mobile: sérialisation: %w", err)
	}
	return string(data), nil
}

// Interface de contrôle : *notes.Library doit satisfaire store.Remote.
var _ store.Remote = (*notes.Library)(nil)

// Interface de contrôle : *opencloud.Space doit satisfaire notes.Backend.
var _ notes.Backend = (*opencloud.Space)(nil)
