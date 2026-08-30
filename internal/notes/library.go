package notes

import (
	"context"
	"errors"
	"fmt"
	"path"
	"sort"
	"strings"
	"time"

	"opennote/internal/markdown"
	"opennote/internal/opencloud"
)

// Codes d'erreur d'arborescence, dans le même esprit que ceux du nommage.
const (
	CodeRootImmutable = "ROOT_IMMUTABLE"
	CodeMoveIntoSelf  = "MOVE_INTO_SELF"
	CodePathEmpty     = "PATH_EMPTY"
	CodeNameNoSlot    = "NAME_NO_SLOT"
)

// Backend est la partie du client OpenCloud dont le modèle de notes a besoin.
// *opencloud.Space l'implémente.
//
// L'interface est déclarée ici, du côté du consommateur : le modèle de notes
// se teste ainsi contre une implémentation en mémoire, sans serveur.
type Backend interface {
	List(ctx context.Context, dir string) ([]opencloud.Resource, error)
	Stat(ctx context.Context, p string) (opencloud.Resource, error)
	Read(ctx context.Context, p string) ([]byte, string, error)
	Write(ctx context.Context, p string, content []byte, ifMatch string) (string, error)
	WriteNew(ctx context.Context, p string, content []byte) (string, error)
	MkdirAll(ctx context.Context, p string) error
	Move(ctx context.Context, from, to string) error
	Remove(ctx context.Context, p string) error
}

// DefaultRoot est le dossier créé par défaut au premier démarrage.
const DefaultRoot = "Notes"

// Note décrit une note dans une liste. Le contenu n'y figure pas : un listing
// ne doit pas télécharger toutes les notes.
type Note struct {
	Path        string // relatif à la racine des notes
	Name        string // nom de fichier, extension comprise
	DisplayName string // nom sans extension, pour l'affichage
	Size        int64
	ModTime     time.Time
	ETag        string
	FileID      string
}

// Folder décrit un sous-dossier.
type Folder struct {
	Path string
	Name string
}

// Listing est le contenu d'un dossier : ses sous-dossiers et ses notes.
type Listing struct {
	Path    string
	Folders []Folder
	Notes   []Note
}

// IsEmpty indique qu'un dossier ne contient rien à afficher.
func (l Listing) IsEmpty() bool {
	return len(l.Folders) == 0 && len(l.Notes) == 0
}

// Library donne accès aux notes d'un dossier racine, dans un espace OpenCloud.
type Library struct {
	backend Backend
	root    string
}

// NewLibrary construit une bibliothèque sur un dossier racine.
//
// Une racine vide désigne la racine de l'espace lui-même, ce qui permet de
// brancher l'application sur un dossier de notes préexistant.
func NewLibrary(backend Backend, root string) (*Library, error) {
	if backend == nil {
		return nil, errors.New("notes: backend non fourni")
	}
	return &Library{backend: backend, root: CleanPath(root)}, nil
}

// Root renvoie le dossier racine, relatif à l'espace.
func (l *Library) Root() string { return l.root }

// resolve convertit un chemin relatif aux notes en chemin relatif à l'espace.
func (l *Library) resolve(p string) string {
	p = CleanPath(p)
	if l.root == "" {
		return p
	}
	if p == "" {
		return l.root
	}
	return l.root + "/" + p
}

// relative fait l'inverse : d'un chemin d'espace vers un chemin de notes.
func (l *Library) relative(spacePath string) string {
	if l.root == "" {
		return spacePath
	}
	if spacePath == l.root {
		return ""
	}
	return strings.TrimPrefix(spacePath, l.root+"/")
}

// Bootstrap crée le dossier racine s'il n'existe pas encore.
// L'opération est idempotente : elle peut être appelée à chaque démarrage.
func (l *Library) Bootstrap(ctx context.Context) error {
	if l.root == "" {
		return nil
	}
	return l.backend.MkdirAll(ctx, l.root)
}

// List renvoie le contenu d'un dossier, dossiers d'abord puis notes, chacun
// trié par nom.
//
// Les fichiers qui ne sont pas des notes sont ignorés : une image ou un PDF
// posé dans le dossier depuis l'interface web ne doit pas apparaître comme une
// note illisible.
func (l *Library) List(ctx context.Context, dir string) (Listing, error) {
	resources, err := l.backend.List(ctx, l.resolve(dir))
	if err != nil {
		return Listing{}, err
	}

	listing := Listing{Path: CleanPath(dir)}
	for _, r := range resources {
		if isHidden(r.Name) {
			continue
		}
		relative := l.relative(r.Path)
		if r.IsDir {
			listing.Folders = append(listing.Folders, Folder{Path: relative, Name: r.Name})
			continue
		}
		if !IsNote(r.Name) {
			continue
		}
		listing.Notes = append(listing.Notes, Note{
			Path:        relative,
			Name:        r.Name,
			DisplayName: DisplayName(r.Name),
			Size:        r.Size,
			ModTime:     r.ModTime,
			ETag:        r.ETag,
			FileID:      r.FileID,
		})
	}

	sort.Slice(listing.Folders, func(i, j int) bool {
		return lessName(listing.Folders[i].Name, listing.Folders[j].Name)
	})
	sort.Slice(listing.Notes, func(i, j int) bool {
		return lessName(listing.Notes[i].Name, listing.Notes[j].Name)
	})
	return listing, nil
}

// lessName ordonne sans tenir compte de la casse.
//
// Le classement des accents n'est pas celui du français — « élan » se range
// après « zèbre » — car une vraie collation demanderait golang.org/x/text.
// À reprendre si le tri devient gênant à l'usage.
func lessName(a, b string) bool {
	la, lb := strings.ToLower(a), strings.ToLower(b)
	if la == lb {
		return a < b
	}
	return la < lb
}

// Read renvoie le contenu d'une note et son ETag.
func (l *Library) Read(ctx context.Context, notePath string) ([]byte, string, error) {
	return l.backend.Read(ctx, l.resolve(notePath))
}

// Save écrit une note.
//
// ifMatch porte l'ETag de la version connue : si le serveur en a une plus
// récente, l'écriture est refusée et l'erreur satisfait
// errors.Is(err, opencloud.ErrConflict). Une chaîne vide écrase sans contrôle.
func (l *Library) Save(ctx context.Context, notePath string, content []byte, ifMatch string) (string, error) {
	return l.backend.Write(ctx, l.resolve(notePath), content, ifMatch)
}

// Exists indique si un chemin existe sur le serveur.
//
// Sert avant de pousser une note créée hors connexion : « If-None-Match: * »
// n'est pas honoré de façon fiable par tous les serveurs, et s'y fier
// laisserait écraser le travail fait ailleurs.
func (l *Library) Exists(ctx context.Context, itemPath string) (bool, error) {
	_, err := l.backend.Stat(ctx, l.resolve(itemPath))
	if err == nil {
		return true, nil
	}
	if errors.Is(err, opencloud.ErrNotFound) {
		return false, nil
	}
	return false, err
}

// SaveNew écrit une note en exigeant qu'elle n'existe pas encore côté serveur.
//
// Réservé aux notes créées hors connexion, dont on ignore si le nom est déjà
// pris. Si c'est le cas, l'erreur satisfait errors.Is(err, opencloud.ErrConflict)
// et la résolution de conflit habituelle s'applique : les deux versions sont
// conservées.
func (l *Library) SaveNew(ctx context.Context, notePath string, content []byte) (string, error) {
	return l.backend.WriteNew(ctx, l.resolve(notePath), content)
}

// Create crée une note vide, ou avec un contenu initial.
//
// Le nom reçoit l'extension .md s'il ne l'a pas, et un suffixe numérique si
// une note du même nom existe déjà : deux notes créées d'affilée depuis un
// même titre ne doivent pas s'écraser silencieusement.
func (l *Library) Create(ctx context.Context, dir, name string, content []byte) (Note, error) {
	name = WithExtension(strings.TrimSpace(name))
	if err := ValidateName(name); err != nil {
		return Note{}, err
	}

	dir = CleanPath(dir)
	available, err := l.availableName(ctx, dir, name)
	if err != nil {
		return Note{}, err
	}

	notePath := path.Join(dir, available)
	etag, err := l.backend.Write(ctx, l.resolve(notePath), content, "")
	if err != nil {
		return Note{}, err
	}

	return Note{
		Path:        notePath,
		Name:        available,
		DisplayName: DisplayName(available),
		Size:        int64(len(content)),
		ModTime:     time.Now(),
		ETag:        etag,
	}, nil
}

// availableName ajoute un suffixe numérique tant que le nom est pris.
func (l *Library) availableName(ctx context.Context, dir, name string) (string, error) {
	ext := path.Ext(name)
	base := strings.TrimSuffix(name, ext)

	for attempt := 0; attempt < 100; attempt++ {
		candidate := name
		if attempt > 0 {
			candidate = fmt.Sprintf("%s (%d)%s", base, attempt+1, ext)
		}

		_, err := l.backend.Stat(ctx, l.resolve(path.Join(dir, candidate)))
		if errors.Is(err, opencloud.ErrNotFound) {
			return candidate, nil
		}
		if err != nil {
			return "", err
		}
	}
	return "", fmt.Errorf("notes: [%s] impossible de trouver un nom libre pour %q", CodeNameNoSlot, name)
}

// CreateFolder crée un sous-dossier.
func (l *Library) CreateFolder(ctx context.Context, dir, name string) (Folder, error) {
	name = strings.TrimSpace(name)
	if err := ValidateName(name); err != nil {
		return Folder{}, err
	}

	folderPath := path.Join(CleanPath(dir), name)
	if err := l.backend.MkdirAll(ctx, l.resolve(folderPath)); err != nil {
		return Folder{}, err
	}
	return Folder{Path: folderPath, Name: name}, nil
}

// ResolveRename calcule le chemin résultant d'un renommage, sans rien
// contacter.
//
// L'extension est réajoutée si la cible est une note, et c'est *celle du
// fichier renommé* qui est reprise : renommer « journal.txt » en « carnet »
// donne « carnet.txt ». Forcer .md ici changerait le format du fichier au dos
// de l'utilisateur, qui n'a demandé qu'un nouveau nom.
//
// Exposé pour que la façade puisse renommer hors connexion, où elle doit
// aboutir au même chemin que le fera plus tard la synchronisation.
func ResolveRename(itemPath, newName string) (string, error) {
	newName = strings.TrimSpace(newName)
	if IsNote(itemPath) {
		newName = WithExtensionOf(itemPath, newName)
	}
	if err := ValidateName(newName); err != nil {
		return "", err
	}

	itemPath = CleanPath(itemPath)
	if itemPath == "" {
		return "", fmt.Errorf("notes: [%s] la racine ne peut pas être renommée", CodeRootImmutable)
	}
	return path.Join(path.Dir(itemPath), newName), nil
}

// Rename change le nom d'une note ou d'un dossier, sans le déplacer.
func (l *Library) Rename(ctx context.Context, itemPath, newName string) (string, error) {
	target, err := ResolveRename(itemPath, newName)
	if err != nil {
		return "", err
	}

	itemPath = CleanPath(itemPath)
	if target == itemPath {
		return itemPath, nil
	}
	if err := l.backend.Move(ctx, l.resolve(itemPath), l.resolve(target)); err != nil {
		return "", err
	}
	return target, nil
}

// ResolveMove calcule le chemin cible d'un déplacement, sans toucher au
// réseau : mêmes règles que Move, dans le même esprit que ResolveRename.
//
// Exposé pour que la façade puisse déplacer hors connexion et aboutir au même
// chemin que le fera plus tard la synchronisation.
func ResolveMove(itemPath, targetDir string) (string, error) {
	itemPath = CleanPath(itemPath)
	if itemPath == "" {
		return "", fmt.Errorf("notes: [%s] la racine ne peut pas être déplacée", CodeRootImmutable)
	}

	targetDir = CleanPath(targetDir)
	if targetDir == itemPath || strings.HasPrefix(targetDir, itemPath+"/") {
		return "", fmt.Errorf("notes: [%s] %q ne peut pas être déplacé dans lui-même", CodeMoveIntoSelf, itemPath)
	}

	return path.Join(targetDir, path.Base(itemPath)), nil
}

// Move déplace une note ou un dossier vers un autre dossier.
func (l *Library) Move(ctx context.Context, itemPath, targetDir string) (string, error) {
	target, err := ResolveMove(itemPath, targetDir)
	if err != nil {
		return "", err
	}

	itemPath = CleanPath(itemPath)
	if target == itemPath {
		return itemPath, nil
	}
	if err := l.backend.Move(ctx, l.resolve(itemPath), l.resolve(target)); err != nil {
		return "", err
	}
	return target, nil
}

// MoveTo déplace une note vers un chemin complet, sans contrôle de nommage.
//
// Contrairement à Move et Rename, qui servent les gestes de l'utilisateur,
// cette méthode sert la synchronisation : elle rejoue un déplacement déjà
// validé et enregistré dans la file d'attente.
func (l *Library) MoveTo(ctx context.Context, from, to string) error {
	from, to = CleanPath(from), CleanPath(to)
	if from == "" || to == "" {
		return fmt.Errorf("notes: [%s] chemin vide", CodePathEmpty)
	}
	return l.backend.Move(ctx, l.resolve(from), l.resolve(to))
}

// EnsureFolder crée un dossier et ses parents s'ils manquent.
func (l *Library) EnsureFolder(ctx context.Context, dir string) error {
	dir = CleanPath(dir)
	if dir == "" {
		return l.Bootstrap(ctx)
	}
	return l.backend.MkdirAll(ctx, l.resolve(dir))
}

// Delete supprime une note ou un dossier. Sur un dossier, la suppression est
// récursive.
func (l *Library) Delete(ctx context.Context, itemPath string) error {
	itemPath = CleanPath(itemPath)
	if itemPath == "" {
		return fmt.Errorf("notes: [%s] la racine ne peut pas être supprimée", CodeRootImmutable)
	}
	return l.backend.Remove(ctx, l.resolve(itemPath))
}

// TitleOf renvoie le titre à afficher pour une note : celui écrit dans le
// contenu s'il y en a un, sinon le nom du fichier.
//
// Le format décide de la lecture, d'où le besoin de Note.Name : dans un .txt
// il n'y a pas de « # » à retirer, seulement une première ligne.
func TitleOf(note Note, content []byte) string {
	title := markdown.Title(string(content))
	if IsPlainText(note.Name) {
		title = markdown.PlainTitle(string(content))
	}
	if title != "" {
		return title
	}
	return note.DisplayName
}

// Render prépare l'affichage d'une note : le format est déduit du nom.
//
// C'est ici, et nulle part ailleurs, que se décide si un contenu doit être
// interprété. Le paquet markdown ne connaît pas les extensions et l'interface
// n'a pas à les connaître : lui laisser ce choix reviendrait à recopier la
// liste des extensions en Kotlin, où elle divergerait au premier ajout.
func Render(name string, content []byte) []markdown.Block {
	if IsPlainText(name) {
		return markdown.RenderPlain(string(content))
	}
	return markdown.Render(string(content))
}

// PrepareEdit allège un contenu avant de le confier à un champ de saisie.
//
// Comme Render, c'est le nom qui décide : un .txt n'est pas du Markdown, il
// n'a pas d'image en ligne à en sortir. Les données retirées reviennent par
// markdown.RestoreInlineData au moment d'enregistrer.
func PrepareEdit(name, content string) (string, []string) {
	if IsPlainText(name) {
		return content, nil
	}
	return markdown.ExtractInlineData(content)
}
