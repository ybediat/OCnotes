// Package store tient le cache local des notes et la file des opérations en
// attente de synchronisation.
//
// Le principe est « local-first » : une écriture est enregistrée localement et
// visible immédiatement, puis poussée vers le serveur dès que le réseau le
// permet. L'application reste utilisable hors connexion, et une écriture n'est
// jamais perdue parce que le téléphone a changé de réseau au mauvais moment.
package store

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// indexVersion permet de reconnaître un index écrit par une version
// antérieure du format. Un index d'une version inconnue est ignoré plutôt que
// mal interprété : le cache se reconstruit depuis le serveur.
const indexVersion = 1

// Entry est ce que le cache sait d'une note.
type Entry struct {
	// Path est relatif à la racine des notes.
	Path string `json:"path"`

	// Cache est le nom du fichier dans le dossier de cache.
	Cache string `json:"cache"`

	// ETag est la version du serveur sur laquelle le cache est aligné.
	// Vide pour une note créée hors connexion, jamais encore poussée.
	ETag string `json:"etag,omitempty"`

	// Dirty indique une modification locale pas encore acceptée par le serveur.
	Dirty bool `json:"dirty,omitempty"`

	Size     int64     `json:"size"`
	LocalMod time.Time `json:"localMod"`
}

// Store est le cache local.
//
// Toutes les méthodes sont sûres pour un usage concurrent : sur mobile,
// l'éditeur écrit depuis l'interface pendant que le worker de
// synchronisation draine la file.
type Store struct {
	dir string

	mu      sync.Mutex
	entries map[string]*Entry
	queue   []Operation

	// folders retient les dossiers connus. Le cache ne matérialise pas les
	// dossiers sur le disque — seules les notes y sont stockées — mais un
	// dossier vide créé hors connexion doit rester visible dans le
	// navigateur, ce qu'une simple déduction à partir des chemins de notes ne
	// permettrait pas.
	folders map[string]bool
}

// persisted est la forme sérialisée de l'état du cache.
type persisted struct {
	Version int               `json:"version"`
	Entries map[string]*Entry `json:"entries"`
	Queue   []Operation       `json:"queue"`
	Folders map[string]bool   `json:"folders,omitempty"`
}

// Open ouvre — ou crée — un cache dans le dossier indiqué.
//
// Un index illisible ou d'une version inconnue n'est pas une erreur fatale :
// le cache repart vide et se reconstruira depuis le serveur. Perdre le cache
// est bénin ; refuser de démarrer ne l'est pas.
func Open(dir string) (*Store, error) {
	if err := os.MkdirAll(filepath.Join(dir, "notes"), 0o700); err != nil {
		return nil, fmt.Errorf("store: création du cache dans %s: %w", dir, err)
	}

	s := &Store{
		dir:     dir,
		entries: map[string]*Entry{},
		folders: map[string]bool{},
	}

	data, err := os.ReadFile(s.indexPath())
	if err != nil {
		if os.IsNotExist(err) {
			return s, nil
		}
		return nil, fmt.Errorf("store: lecture de l'index: %w", err)
	}

	var state persisted
	if err := json.Unmarshal(data, &state); err != nil || state.Version != indexVersion {
		return s, nil
	}
	if state.Entries != nil {
		s.entries = state.Entries
	}
	if state.Folders != nil {
		s.folders = state.Folders
	}
	s.queue = state.Queue
	return s, nil
}

func (s *Store) indexPath() string        { return filepath.Join(s.dir, "index.json") }
func (s *Store) notesDir() string         { return filepath.Join(s.dir, "notes") }
func (s *Store) blobPath(n string) string { return filepath.Join(s.notesDir(), n) }

// cacheName dérive le nom du fichier de cache d'un chemin de note.
//
// Le nom du serveur n'est délibérément pas réutilisé. Le test d'intégration a
// montré qu'OpenCloud accepte « ? », « * » ou « : » dans un nom de fichier,
// alors que Windows les refuse et que « / » est interdit partout. Recopier les
// noms ferait échouer le cache sur des notes pourtant parfaitement valides.
//
// L'empreinte porte sur le chemin plutôt que sur l'identifiant serveur, car
// une note créée hors connexion n'a pas encore d'identifiant.
func cacheName(notePath string) string {
	sum := sha256.Sum256([]byte(notePath))
	return hex.EncodeToString(sum[:16]) + ".md"
}

// save écrit l'index sur disque. L'appelant doit détenir le verrou.
//
// L'écriture passe par un fichier temporaire renommé : une coupure de courant
// au mauvais moment laisserait sinon un index tronqué, donc un cache perdu.
func (s *Store) save() error {
	state := persisted{Version: indexVersion, Entries: s.entries, Queue: s.queue, Folders: s.folders}
	data, err := json.Marshal(state)
	if err != nil {
		return fmt.Errorf("store: sérialisation de l'index: %w", err)
	}

	tmp := s.indexPath() + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return fmt.Errorf("store: écriture de l'index: %w", err)
	}
	if err := os.Rename(tmp, s.indexPath()); err != nil {
		return fmt.Errorf("store: remplacement de l'index: %w", err)
	}
	return nil
}

// Get renvoie le contenu en cache d'une note.
func (s *Store) Get(notePath string) ([]byte, Entry, bool) {
	s.mu.Lock()
	entry, ok := s.entries[notePath]
	if !ok {
		s.mu.Unlock()
		return nil, Entry{}, false
	}
	copied := *entry
	s.mu.Unlock()

	content, err := os.ReadFile(s.blobPath(copied.Cache))
	if err != nil {
		// L'index connaît la note mais le fichier a disparu : on traite le
		// cache comme absent plutôt que de propager une erreur d'E/S.
		return nil, Entry{}, false
	}
	return content, copied, true
}

// Entries renvoie l'état du cache, trié par chemin.
func (s *Store) Entries() []Entry {
	s.mu.Lock()
	defer s.mu.Unlock()

	out := make([]Entry, 0, len(s.entries))
	for _, e := range s.entries {
		out = append(out, *e)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Path < out[j].Path })
	return out
}

// Put enregistre une modification locale et l'inscrit dans la file d'attente.
//
// L'écriture est immédiate côté cache : l'utilisateur voit son texte tout de
// suite, indépendamment de l'état du réseau.
func (s *Store) Put(notePath string, content []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.putLocked(notePath, content, true)
}

// Store enregistre une version reçue du serveur : le cache est alors aligné,
// donc propre, et rien n'est mis en file.
func (s *Store) Accept(notePath string, content []byte, etag string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if err := s.writeBlob(notePath, content); err != nil {
		return err
	}
	s.entries[notePath] = &Entry{
		Path:     notePath,
		Cache:    cacheName(notePath),
		ETag:     etag,
		Dirty:    false,
		Size:     int64(len(content)),
		LocalMod: time.Now().UTC(),
	}
	return s.save()
}

func (s *Store) putLocked(notePath string, content []byte, enqueue bool) error {
	if err := s.writeBlob(notePath, content); err != nil {
		return err
	}

	entry, ok := s.entries[notePath]
	if !ok {
		entry = &Entry{Path: notePath, Cache: cacheName(notePath)}
		s.entries[notePath] = entry
	}
	entry.Dirty = true
	entry.Size = int64(len(content))
	entry.LocalMod = time.Now().UTC()

	if enqueue {
		s.enqueueLocked(Operation{Kind: OpWrite, Path: notePath})
	}
	return s.save()
}

func (s *Store) writeBlob(notePath string, content []byte) error {
	name := cacheName(notePath)
	tmp := s.blobPath(name) + ".tmp"
	if err := os.WriteFile(tmp, content, 0o600); err != nil {
		return fmt.Errorf("store: écriture du cache de %s: %w", notePath, err)
	}
	if err := os.Rename(tmp, s.blobPath(name)); err != nil {
		return fmt.Errorf("store: remplacement du cache de %s: %w", notePath, err)
	}
	return nil
}

// Delete retire une note ou un dossier du cache et inscrit la suppression en
// file. Sur un dossier, la descendance part avec lui, comme côté serveur.
func (s *Store) Delete(itemPath string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.dropLocked(itemPath)
	s.enqueueLocked(Operation{Kind: OpDelete, Path: itemPath})
	return s.save()
}

// dropLocked retire du cache un chemin et tout ce qu'il contient.
func (s *Store) dropLocked(itemPath string) {
	for p, entry := range s.entries {
		if p == itemPath || strings.HasPrefix(p, itemPath+"/") {
			_ = os.Remove(s.blobPath(entry.Cache))
			delete(s.entries, p)
		}
	}
	s.forgetFolderLocked(itemPath)
}

// Rename déplace une note dans le cache et inscrit le déplacement en file.
// À utiliser quand le renommage n'a pas encore atteint le serveur.
func (s *Store) Rename(from, to string) error {
	return s.rename(from, to, true)
}

// RenameLocal déplace une note dans le cache sans rien inscrire en file.
// À utiliser quand le serveur a déjà appliqué le renommage : le rejouer
// échouerait, la source n'existant plus là-bas.
func (s *Store) RenameLocal(from, to string) error {
	return s.rename(from, to, false)
}

func (s *Store) rename(from, to string, enqueue bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.folders[from] {
		s.forgetFolderLocked(from)
		s.rememberFolderLocked(to)
	}

	entry, ok := s.entries[from]
	if ok {
		content, err := os.ReadFile(s.blobPath(entry.Cache))
		if err == nil {
			if err := s.writeBlob(to, content); err != nil {
				return err
			}
			_ = os.Remove(s.blobPath(entry.Cache))
		}
		entry.Path = to
		entry.Cache = cacheName(to)
		delete(s.entries, from)
		s.entries[to] = entry
	}

	if enqueue {
		s.enqueueLocked(Operation{Kind: OpMove, Path: from, Target: to})
	}
	return s.save()
}

// EnsureFolder retient un dossier et inscrit sa création en file d'attente.
func (s *Store) EnsureFolder(dir string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.rememberFolderLocked(dir)
	s.enqueueLocked(Operation{Kind: OpMkdir, Path: dir})
	return s.save()
}

// RememberFolder retient un dossier vu sur le serveur, sans rien mettre en
// file : il existe déjà là-bas.
func (s *Store) RememberFolder(dir string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.rememberFolderLocked(dir)
	return s.save()
}

// rememberFolderLocked retient un dossier et tous ses parents.
func (s *Store) rememberFolderLocked(dir string) {
	dir = strings.Trim(dir, "/")
	current := ""
	for _, segment := range strings.Split(dir, "/") {
		if segment == "" {
			continue
		}
		if current == "" {
			current = segment
		} else {
			current += "/" + segment
		}
		s.folders[current] = true
	}
}

// Folders renvoie les dossiers connus, triés.
func (s *Store) Folders() []string {
	s.mu.Lock()
	defer s.mu.Unlock()

	out := make([]string, 0, len(s.folders))
	for d := range s.folders {
		out = append(out, d)
	}
	sort.Strings(out)
	return out
}

// forgetFolderLocked oublie un dossier et sa descendance.
func (s *Store) forgetFolderLocked(dir string) {
	for d := range s.folders {
		if d == dir || strings.HasPrefix(d, dir+"/") {
			delete(s.folders, d)
		}
	}
}

// Forget retire un chemin du cache sans rien inscrire en file. Sert quand le
// serveur signale qu'une note ou un dossier a disparu.
func (s *Store) Forget(itemPath string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.dropLocked(itemPath)
	return s.save()
}
