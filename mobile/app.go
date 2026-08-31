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
	"encoding/json"
	"errors"
	"fmt"
	"path"
	"strings"
	"sync"
	"time"

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
	cfg      config.Config
	client   *opencloud.Client
	lib      *notes.Library
	cache    *store.Store

	// offlineUntil retient qu'un appel réseau vient d'échouer, pour éviter de
	// réessayer à chaque geste.
	offlineUntil time.Time
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
	return &App{dataDir: dataDir, cfg: cfg, cache: cache, syncPass: make(chan struct{}, 1)}, nil
}

func (a *App) ctx() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), requestTimeout)
}

// --- État -------------------------------------------------------------------

// appState décrit ce que l'interface doit savoir pour choisir son écran.
type appState struct {
	Connected    bool   `json:"connected"`
	HasWorkspace bool   `json:"hasWorkspace"`
	ServerURL    string `json:"serverUrl"`
	Username     string `json:"username"`
	DriveID      string `json:"driveId"`
	DriveName    string `json:"driveName"`
	Root         string `json:"root"`
	LastPath     string `json:"lastPath"`
	Pending      int    `json:"pending"`
}

// cacheState est l'état d'espace présenté dans les réglages. Quota vaut zéro
// pour « illimité » ; usage ne compte que les blobs de contenu réellement
// présents, jamais l'inventaire ni la file persistante.
type cacheState struct {
	Quota int64 `json:"quota"`
	Usage int64 `json:"usage"`
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
		Connected:    a.cfg.IsConnected(),
		HasWorkspace: a.lib != nil,
		ServerURL:    a.cfg.ServerURL,
		Username:     a.cfg.Username,
		DriveID:      a.cfg.DriveID,
		DriveName:    a.cfg.DriveName,
		Root:         a.cfg.Root,
		LastPath:     a.cfg.LastPath,
		Pending:      len(a.cache.Pending()),
	})
}

// CacheStateJSON renvoie le quota local et l'occupation réelle du cache.
func (a *App) CacheStateJSON() (string, error) {
	return toJSON(cacheState{Quota: a.cache.Quota(), Usage: a.cache.Usage()})
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

	ctx, cancel := a.ctx()
	defer cancel()

	// Un appel réel valide les identifiants : sans cela, une erreur
	// d'authentification n'apparaîtrait qu'au premier geste de l'utilisateur.
	drives, err := client.ListDrives(ctx)
	if err != nil {
		return err
	}

	a.mu.Lock()
	defer a.mu.Unlock()

	a.client = client
	a.cfg.ServerURL = serverURL
	a.cfg.Username = username

	if a.cfg.DriveID != "" {
		for _, d := range drives {
			if d.ID == a.cfg.DriveID {
				if err := a.openWorkspaceLocked(ctx, d, a.cfg.Root); err != nil {
					return err
				}
				break
			}
		}
	}
	return config.Save(a.dataDir, a.cfg)
}

// Disconnect efface la session, la configuration et le cache.
//
// Rien de l'utilisateur précédent ne doit rester sur l'appareil : la
// suppression du cache fait partie de la déconnexion, pas d'un ménage
// ultérieur.
func (a *App) Disconnect() error {
	a.mu.Lock()
	defer a.mu.Unlock()

	a.client, a.lib, a.cfg = nil, nil, config.Config{}
	if err := a.cache.Clear(); err != nil {
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
		return config.Save(a.dataDir, a.cfg)
	}
	return fmt.Errorf("mobile: espace %q introuvable", driveID)
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
	a.mu.Lock()
	defer a.mu.Unlock()

	if !a.cfg.HasWorkspace() {
		return errors.New("mobile: aucun espace enregistré, passer par Connect puis SelectWorkspace")
	}

	client, err := opencloud.New(a.cfg.ServerURL, opencloud.AppTokenAuth{
		Username: a.cfg.Username,
		Token:    appToken,
	})
	if err != nil {
		return err
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
	a.lib = lib
	return nil
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
}

// folderListing est le contenu d'un dossier.
type folderListing struct {
	Path    string        `json:"path"`
	Entries []folderEntry `json:"entries"`

	// FromCache signale un listing servi hors connexion : l'interface peut
	// alors prévenir que la vue peut être incomplète.
	FromCache bool `json:"fromCache"`
}

// ListFolderJSON renvoie le contenu d'un dossier, dossiers d'abord.
//
// En cas d'échec réseau, le listing est reconstruit depuis le cache : le
// navigateur reste utilisable hors connexion, ce qui est tout l'intérêt du
// modèle local-first.
func (a *App) ListFolderJSON(dir string) (string, error) {
	a.mu.Lock()
	lib := a.lib
	a.mu.Unlock()

	if lib == nil {
		return "", errNoWorkspace()
	}

	ctx, cancel := a.ctx()
	defer cancel()

	listing, err := lib.List(ctx, dir)
	if err != nil {
		a.noteNetworkResult(err)
		if cached, ok := a.listFromCache(dir); ok {
			return toJSON(cached)
		}
		return "", err
	}
	a.noteNetworkResult(nil)

	a.mu.Lock()
	a.cfg.LastPath = listing.Path
	_ = config.Save(a.dataDir, a.cfg)
	a.mu.Unlock()

	// La slice est initialisée non nulle : encoding/json sérialise une slice
	// nulle en « null », alors que le contrat annonce un tableau. Kotlin n'a
	// pas à gérer deux formes pour un dossier vide.
	out := folderListing{Path: listing.Path, Entries: []folderEntry{}}
	for _, f := range listing.Folders {
		// Mémoriser les dossiers vus permet de les afficher hors connexion,
		// même vides : le cache ne stocke que des notes, il ne pourrait pas
		// les déduire autrement.
		_ = a.cache.RememberFolder(f.Path)
		out.Entries = append(out.Entries, folderEntry{
			Path: f.Path, Name: f.Name, Display: f.Name, IsDir: true,
		})
	}
	for _, n := range listing.Notes {
		entry, cached := a.cache.CachedEntry(n.Path)
		out.Entries = append(out.Entries, folderEntry{
			Path:     n.Path,
			Name:     n.Name,
			Display:  n.DisplayName,
			Size:     n.Size,
			ModTime:  n.ModTime.UTC().Format(time.RFC3339),
			Pending:  cached && entry.Dirty,
			ReadOnly: notes.IsDocument(n.Name),
		})
	}
	return toJSON(out)
}

// listFromCache reconstruit le contenu d'un dossier à partir du cache seul.
func (a *App) listFromCache(dir string) (folderListing, bool) {
	dir = notes.CleanPath(dir)
	prefix := ""
	if dir != "" {
		prefix = dir + "/"
	}

	out := folderListing{Path: dir, FromCache: true, Entries: []folderEntry{}}
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
		out.Entries = append(out.Entries, folderEntry{
			Path: folder, Name: rest, Display: rest, IsDir: true,
		})
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
				out.Entries = append(out.Entries, folderEntry{
					Path: prefix + name, Name: name, Display: name, IsDir: true,
				})
			}
			continue
		}

		out.Entries = append(out.Entries, folderEntry{
			Path:     entry.Path,
			Name:     rest,
			Display:  notes.DisplayName(rest),
			Size:     entry.Size,
			ModTime:  entry.LocalMod.UTC().Format(time.RFC3339),
			Pending:  entry.Dirty,
			ReadOnly: notes.IsDocument(rest),
		})
	}

	// Le repli vaut dès que le cache connaît quelque chose : un dossier
	// réellement vide doit s'afficher vide, pas remonter l'erreur réseau.
	// Un cache entièrement vierge, lui, n'apprendrait rien à l'utilisateur.
	return out, len(a.cache.Entries()) > 0 || len(a.cache.Folders()) > 0
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
func (a *App) ReadNote(notePath string) (string, error) {
	content, err := a.readBytes(notePath)
	if err != nil {
		return "", err
	}
	return string(content), nil
}

// readBytes lit un fichier depuis le cache ou le serveur.
//
// Le repli hors connexion est commun aux notes texte et aux documents. Garder
// ce chemin en octets est indispensable pour les archives : seules les notes
// sont converties en chaîne, à la toute fin de ReadNote.
func (a *App) readBytes(notePath string) ([]byte, error) {
	a.mu.Lock()
	lib := a.lib
	a.mu.Unlock()

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
func (a *App) WriteNote(notePath, content string) error {
	// Un document ne s'écrit jamais, et le refus est ici plutôt que dans
	// l'interface : c'est le seul appel de la façade qui peut détruire un
	// fichier de l'utilisateur en silence. Une écriture partie sur un .docx le
	// remplacerait par du texte, sur un serveur partagé, sans message.
	if err := notes.EnsureWritable(notePath); err != nil {
		return err
	}
	return a.cache.Put(notePath, []byte(content))
}

// RefreshNote force la relecture d'une note depuis le serveur.
//
// Une modification locale non synchronisée n'est jamais écrasée : elle sera
// confrontée au serveur lors du prochain Sync.
func (a *App) RefreshNote(notePath string) error {
	a.mu.Lock()
	lib := a.lib
	a.mu.Unlock()

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
	a.mu.Lock()
	lib := a.lib
	a.mu.Unlock()

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
	if !errors.Is(err, opencloud.ErrOffline) {
		return "", err
	}

	// Hors connexion : la note est créée dans le cache seul et poussée plus
	// tard. Pouvoir écrire une note existante sans réseau mais pas en créer
	// une n'aurait aucun sens pour l'utilisateur.
	return a.createNoteOffline(dir, name, content)
}

// createNoteOffline crée une note dans le cache et l'inscrit en file.
//
// Le nom est choisi d'après le cache seul : on ne peut pas savoir ce que le
// serveur contient. Une collision avec une note distante reste donc possible,
// et c'est le If-None-Match de la synchronisation qui la rattrape — la note
// distante est préservée et la version locale conservée à côté.
func (a *App) createNoteOffline(dir, name, content string) (string, error) {
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
	a.mu.Lock()
	lib := a.lib
	a.mu.Unlock()

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
	if !errors.Is(err, opencloud.ErrOffline) {
		return "", err
	}

	// Hors connexion : le dossier est retenu par le cache et créé au prochain
	// passage. Le navigateur l'affiche entre-temps.
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
	a.mu.Lock()
	lib := a.lib
	a.mu.Unlock()

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

	target, err := notes.ResolveRename(itemPath, newName)
	if err != nil {
		return "", err
	}
	if err := a.cache.Rename(itemPath, target); err != nil {
		return "", err
	}
	return target, nil
}

// Move déplace une note ou un dossier vers un autre dossier.
//
// Même schéma que Rename : le serveur d'abord, et un repli hors connexion qui
// calcule le même chemin cible que la synchronisation atteindra plus tard —
// ResolveMove porte cette règle, partagée avec Library.Move pour que les deux
// chemins ne puissent pas diverger.
func (a *App) Move(itemPath, targetDir string) (string, error) {
	a.mu.Lock()
	lib := a.lib
	a.mu.Unlock()

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

	target, err := notes.ResolveMove(itemPath, targetDir)
	if err != nil {
		return "", err
	}
	if err := a.cache.Rename(itemPath, target); err != nil {
		return "", err
	}
	return target, nil
}

// Delete supprime une note ou un dossier.
func (a *App) Delete(itemPath string) error {
	a.mu.Lock()
	lib := a.lib
	a.mu.Unlock()

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
	a.mu.Lock()
	lib := a.lib
	a.mu.Unlock()

	if lib == nil {
		return "", errNoWorkspace()
	}

	ctx, cancel := context.WithTimeout(context.Background(), syncPassTimeout)
	defer cancel()

	// WorkManager, le bouton manuel et tout futur appel de la façade convergent
	// ici. Le Store protège sa mémoire, mais une passe entière doit être seule à
	// parler au serveur : sinon deux appels peuvent rejouer la même tête de file.
	select {
	case a.syncPass <- struct{}{}:
		defer func() { <-a.syncPass }()
	case <-ctx.Done():
		err := ctx.Err()
		return toJSON(syncResult{
			Conflicts: []conflictInfo{},
			Remaining: a.PendingCount(),
			Error:     err.Error(),
			ErrorCode: ErrorCode(err.Error()),
		})
	}

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

	a.mu.Lock()
	lib := a.lib
	a.mu.Unlock()
	if lib == nil {
		return "", errNoWorkspace()
	}

	ctx, cancel := context.WithTimeout(context.Background(), syncPassTimeout)
	defer cancel()
	select {
	case a.syncPass <- struct{}{}:
		defer func() { <-a.syncPass }()
	case <-ctx.Done():
		return "", ctx.Err()
	}

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

// IsDocument indique qu'un nom désigne un fichier lisible mais jamais
// modifiable. Fonction de paquet pour que Kotlin n'ait pas à recopier les
// extensions reconnues par le cœur.
func IsDocument(name string) bool {
	return notes.IsDocument(name)
}

func errNotConnected() error {
	return errors.New("mobile: aucune session ouverte, appeler Connect")
}

func errNoWorkspace() error {
	return errors.New("mobile: aucun espace de travail choisi, appeler SelectWorkspace")
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
