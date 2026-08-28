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

	"opennote/internal/config"
	"opennote/internal/markdown"
	"opennote/internal/notes"
	"opennote/internal/opencloud"
	"opennote/internal/store"
)

// requestTimeout borne chaque appel réseau. gomobile ne sait pas transmettre
// un context.Context : la façade fabrique le sien.
const requestTimeout = 30 * time.Second

// App est le point d'entrée unique pour Android.
type App struct {
	mu      sync.Mutex
	dataDir string
	cfg     config.Config
	client  *opencloud.Client
	lib     *notes.Library
	cache   *store.Store
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
	return &App{dataDir: dataDir, cfg: cfg, cache: cache}, nil
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
		if cached, ok := a.listFromCache(dir); ok {
			return toJSON(cached)
		}
		return "", err
	}

	a.mu.Lock()
	a.cfg.LastPath = listing.Path
	_ = config.Save(a.dataDir, a.cfg)
	a.mu.Unlock()

	// La slice est initialisée non nulle : encoding/json sérialise une slice
	// nulle en « null », alors que le contrat annonce un tableau. Kotlin n'a
	// pas à gérer deux formes pour un dossier vide.
	out := folderListing{Path: listing.Path, Entries: []folderEntry{}}
	for _, f := range listing.Folders {
		out.Entries = append(out.Entries, folderEntry{
			Path: f.Path, Name: f.Name, Display: f.Name, IsDir: true,
		})
	}
	for _, n := range listing.Notes {
		_, entry, cached := a.cache.Get(n.Path)
		out.Entries = append(out.Entries, folderEntry{
			Path:    n.Path,
			Name:    n.Name,
			Display: n.DisplayName,
			Size:    n.Size,
			ModTime: n.ModTime.UTC().Format(time.RFC3339),
			Pending: cached && entry.Dirty,
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
			Path:    entry.Path,
			Name:    rest,
			Display: notes.DisplayName(rest),
			Size:    entry.Size,
			ModTime: entry.LocalMod.UTC().Format(time.RFC3339),
			Pending: entry.Dirty,
		})
	}

	// Le repli vaut dès que le cache contient quelque chose : un dossier
	// réellement vide doit s'afficher vide, pas remonter l'erreur réseau.
	// Un cache entièrement vide, lui, n'apprendrait rien à l'utilisateur.
	return out, len(a.cache.Entries()) > 0
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
// Le cache répond en premier : l'ouverture est instantanée et fonctionne hors
// connexion. Une note absente du cache est téléchargée puis mise en cache.
func (a *App) ReadNote(notePath string) (string, error) {
	a.mu.Lock()
	lib := a.lib
	a.mu.Unlock()

	if lib == nil {
		return "", errNoWorkspace()
	}

	if content, _, ok := a.cache.Get(notePath); ok {
		return string(content), nil
	}

	ctx, cancel := a.ctx()
	defer cancel()

	content, etag, err := lib.Read(ctx, notePath)
	if err != nil {
		return "", err
	}
	if err := a.cache.Accept(notePath, content, etag); err != nil {
		return "", err
	}
	return string(content), nil
}

// WriteNote enregistre une note.
//
// L'écriture n'atteint que le cache : elle est donc immédiate et ne peut pas
// échouer faute de réseau. La propagation vers le serveur a lieu au prochain
// Sync.
func (a *App) WriteNote(notePath, content string) error {
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
	if err != nil {
		return "", err
	}
	if err := a.cache.Accept(note.Path, []byte(content), note.ETag); err != nil {
		return "", err
	}
	return toJSON(noteRef{Path: note.Path, Name: note.Name, Display: note.DisplayName})
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
	if err != nil {
		return "", err
	}
	return toJSON(noteRef{Path: folder.Path, Name: folder.Name, Display: folder.Name})
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
	if err != nil {
		return "", err
	}
	if _, _, ok := a.cache.Get(itemPath); ok {
		if err := a.cache.Rename(itemPath, newPath); err != nil {
			return "", err
		}
	}
	return newPath, nil
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

	if err := lib.Delete(ctx, itemPath); err != nil {
		return err
	}
	return a.cache.Forget(itemPath)
}

// SuggestName propose un nom de fichier valide à partir d'un titre saisi.
func (a *App) SuggestName(title string) string {
	return notes.SanitizeName(title)
}

// TitleOf renvoie le titre à afficher : celui écrit dans le contenu, sinon le
// nom du fichier.
func (a *App) TitleOf(name, content string) string {
	return notes.TitleOf(notes.Note{DisplayName: notes.DisplayName(name)}, []byte(content))
}

// --- Synchronisation --------------------------------------------------------

// conflictInfo signale une note dont la version locale a été mise de côté.
type conflictInfo struct {
	Path     string `json:"path"`
	CopyPath string `json:"copyPath"`
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

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	report, err := a.cache.Push(ctx, lib)

	result := syncResult{
		Pushed:    report.Pushed,
		Deleted:   report.Deleted,
		Moved:     report.Moved,
		Remaining: report.Remaining,
		Conflicts: []conflictInfo{},
	}
	for _, c := range report.Conflicts {
		result.Conflicts = append(result.Conflicts, conflictInfo{Path: c.Path, CopyPath: c.CopyPath})
	}
	if err != nil {
		result.Error = err.Error()
		result.ErrorCode = ErrorCode(result.Error)
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

// --- Erreurs ----------------------------------------------------------------

// ErrorCode extrait l'étiquette de catégorie d'un message d'erreur.
//
// gomobile ne transmet qu'une chaîne : l'erreur typée ne franchit pas la
// frontière. Les erreurs du client portent donc leur catégorie entre crochets,
// ce qui permet à Kotlin de réagir sans dépendre de la formulation française
// du message. Renvoie une chaîne vide si aucune catégorie n'est reconnue.
func ErrorCode(message string) string {
	for _, code := range []string{
		opencloud.CodeUnauthorized,
		opencloud.CodeConflict,
		opencloud.CodeNotFound,
		opencloud.CodeHTTP,
	} {
		if strings.Contains(message, "["+code+"]") {
			return code
		}
	}
	return ""
}

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
