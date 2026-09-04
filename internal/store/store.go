// Package store tient le cache local des notes et la file des opérations en
// attente de synchronisation.
//
// Le principe est « local-first » : une écriture est enregistrée localement et
// visible immédiatement, puis poussée vers le serveur dès que le réseau le
// permet. L'application reste utilisable hors connexion, et une écriture n'est
// jamais perdue parce que le téléphone a changé de réseau au mauvais moment.
package store

import (
	"bytes"
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

// CodeStorageIO étiquette les pannes du support local : disque plein, cache
// illisible, remplacement atomique refusé.
//
// Même valeur que config.CodeStorageIO, et volontairement redéclarée : les
// deux paquets sont indépendants et aucun n'importe l'autre. TestCodesLocaux
// dans mobile/ vérifie que les deux ne divergent pas.
const CodeStorageIO = "STORAGE_IO"

// indexVersion permet de reconnaître un index écrit par une version
// antérieure du format. Un index d'une version inconnue est ignoré plutôt que
// mal interprété : le cache se reconstruit depuis le serveur.
const indexVersion = 3

// DefaultQuotaBytes est la limite appliquée tant que l'interface n'a pas
// chargé la préférence de l'appareil.
const DefaultQuotaBytes int64 = 250 * 1024 * 1024

// UnlimitedQuota désactive l'éviction liée au quota. Le disque peut toujours
// refuser une écriture : cette erreur reste une STORAGE_IO normale.
const UnlimitedQuota int64 = 0

// MinLocalQuota est le plancher appliqué en entrant en mode local.
//
// En mode local le quota n'évince plus rien — voir SetLocalOnly — mais il
// reste affiché comme seuil d'alerte. Le laisser à 250 Mo ferait crier
// l'interface bien avant que le téléphone ne soit gêné. L'utilisateur peut
// l'abaisser ensuite s'il préfère être averti plus tôt.
const MinLocalQuota int64 = 1 << 30

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

	// BaseHash est l'empreinte du dernier contenu sur lequel le cache et le
	// serveur étaient d'accord — la « base » au sens des trois versions d'un
	// conflit. Dirty dit qu'une écriture reste à propager ; BaseHash dit si
	// elle a quelque chose à propager, ce qui n'est pas la même question.
	//
	// Vide pour une entrée écrite par une version antérieure du format :
	// l'index n'est pas invalidé pour autant — il porte la file d'attente, et
	// la jeter perdrait des écritures hors connexion. Chaque lecture retombe
	// donc sur l'ancien comportement quand l'empreinte manque.
	BaseHash string `json:"baseHash,omitempty"`

	// Conflict protège une copie créée lors d'un conflit. Elle reste locale tant
	// que l'utilisateur ne l'a pas supprimée : être déjà synchronisée ne la rend
	// pas moins importante.
	Conflict bool `json:"conflict,omitempty"`

	Size       int64     `json:"size"`
	LocalMod   time.Time `json:"localMod"`
	LastAccess time.Time `json:"lastAccess,omitempty"`
}

// Store est le cache local.
//
// Toutes les méthodes sont sûres pour un usage concurrent : sur mobile,
// l'éditeur écrit depuis l'interface pendant que le worker de
// synchronisation draine la file.
type Store struct {
	dir string

	mu        sync.Mutex
	entries   map[string]*Entry
	queue     []Operation
	quota     int64
	conflicts map[string]Conflict

	// known est l'inventaire : toutes les notes de l'espace, y compris celles
	// dont le contenu n'a jamais été téléchargé. Voir index.go — c'est ce qui
	// permet à la liste plate de s'ouvrir hors connexion.
	known map[string]*Known

	// indexed distingue « inventaire vide » de « inventaire jamais fait ».
	indexed bool

	// folders retient les dossiers connus. Le cache ne matérialise pas les
	// dossiers sur le disque — seules les notes y sont stockées — mais un
	// dossier vide créé hors connexion doit rester visible dans le
	// navigateur, ce qu'une simple déduction à partir des chemins de notes ne
	// permettrait pas.
	folders map[string]bool

	// localOnly dit qu'aucun serveur ne double ce cache : il n'est plus un
	// cache mais le stockage. Voir SetLocalOnly pour ce que cela change.
	localOnly bool
}

// persisted est la forme sérialisée de l'état du cache.
type persisted struct {
	Version   int                 `json:"version"`
	Entries   map[string]*Entry   `json:"entries"`
	Queue     []Operation         `json:"queue"`
	Folders   map[string]bool     `json:"folders,omitempty"`
	Known     map[string]*Known   `json:"known,omitempty"`
	Indexed   bool                `json:"indexed,omitempty"`
	Conflicts map[string]Conflict `json:"conflicts,omitempty"`

	// LocalOnly n'a pas demandé de version d'index : un champ dont la valeur
	// nulle est le comportement d'avant ne casse aucune lecture.
	LocalOnly bool `json:"localOnly,omitempty"`
}

// Open ouvre — ou crée — un cache dans le dossier indiqué.
//
// Un index illisible ou d'une version inconnue n'est pas une erreur fatale :
// le cache repart vide et se reconstruira depuis le serveur. Perdre le cache
// est bénin ; refuser de démarrer ne l'est pas.
func Open(dir string) (*Store, error) {
	if err := os.MkdirAll(filepath.Join(dir, "notes"), 0o700); err != nil {
		return nil, fmt.Errorf("store: [%s] création du cache dans %s: %w", CodeStorageIO, dir, err)
	}

	s := &Store{
		dir:       dir,
		entries:   map[string]*Entry{},
		folders:   map[string]bool{},
		known:     map[string]*Known{},
		quota:     DefaultQuotaBytes,
		conflicts: map[string]Conflict{},
	}

	data, err := os.ReadFile(s.indexPath())
	if err != nil {
		if os.IsNotExist(err) {
			return s, nil
		}
		return nil, fmt.Errorf("store: [%s] lecture de l'index: %w", CodeStorageIO, err)
	}

	var state persisted
	if err := json.Unmarshal(data, &state); err != nil || (state.Version != 1 && state.Version != 2 && state.Version != indexVersion) {
		return s, nil
	}
	if state.Entries != nil {
		s.entries = state.Entries
	}
	if state.Folders != nil {
		s.folders = state.Folders
	}
	if state.Known != nil {
		s.known = state.Known
	}
	if state.Conflicts != nil {
		s.conflicts = state.Conflicts
	}
	s.indexed = state.Indexed
	s.queue = state.Queue
	s.localOnly = state.LocalOnly
	migrated := state.Version != indexVersion
	for _, entry := range s.entries {
		// Les index de la version 1 ne portaient pas LastAccess. LocalMod est
		// une valeur de repli stable : aucune note n'est soudain considérée
		// comme plus ancienne parce que l'application a été mise à jour.
		if entry.LastAccess.IsZero() {
			entry.LastAccess = entry.LocalMod
			migrated = true
		}
	}
	if s.repairBlobsLocked() {
		migrated = true
	}

	// Après la réparation des blobs : elle peut supprimer des entrées, et une
	// entrée disparue n'a rien à envoyer.
	if s.requeueOrphanWritesLocked() {
		migrated = true
	}

	if migrated {
		if err := s.save(); err != nil {
			return nil, err
		}
	}
	return s, nil
}

func (s *Store) indexPath() string        { return filepath.Join(s.dir, "index.json") }
func (s *Store) notesDir() string         { return filepath.Join(s.dir, "notes") }
func (s *Store) blobPath(n string) string { return filepath.Join(s.notesDir(), n) }

// SetLocalOnly dit au cache qu'aucun serveur ne le double.
//
// Il cesse alors d'être un cache : c'est le stockage, et chaque note qu'il
// porte est la seule copie qui existe. Six règles en découlent, et elles ne se
// séparent pas :
//
//   - rien n'est mis en file — il n'y a personne à qui pousser ;
//   - rien n'est marqué Dirty — « en attente d'envoi » ne veut plus rien dire ;
//   - tout est protégé de l'éviction — évincer, ici, c'est supprimer ;
//   - le quota n'évince plus ; il ne sert qu'à alerter ;
//   - Index() remonte toutes les entrées, plus seulement les sales — sans quoi
//     la liste plate serait vide, puisque plus rien n'est sale ;
//   - HasIndex() est vrai — l'inventaire, c'est le disque, il n'y a rien à
//     attendre d'un serveur.
//
// Les deux dernières sont la conséquence des deux premières : ne plus armer
// Dirty sans corriger Index() viderait la bibliothèque à l'écran, et corriger
// Index() sans protéger de l'éviction laisserait le quota supprimer des notes
// que rien ne pourrait retélécharger.
func (s *Store) SetLocalOnly(local bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.localOnly == local {
		return nil
	}
	s.localOnly = local
	return s.save()
}

// LocalOnly dit si le cache est l'unique dépositaire des notes.
func (s *Store) LocalOnly() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.localOnly
}

// repairBlobsLocked remet l'index en accord avec le dossier de blobs au
// démarrage. Un contenu propre manquant redevient un simple Known, tandis que
// tout blob orphelin est supprimé seulement après avoir vérifié qu'aucune
// entrée ne le référence. Une entrée protégée et illisible est conservée : la
// supprimer ferait perdre la trace d'un travail local à récupérer.
func (s *Store) repairBlobsLocked() bool {
	changed := false
	referenced := make(map[string]bool, len(s.entries))
	for notePath, entry := range s.entries {
		referenced[entry.Cache] = true
		if _, err := os.Stat(s.blobPath(entry.Cache)); err == nil || !os.IsNotExist(err) || s.protectedLocked(notePath, entry) {
			continue
		}
		if _, known := s.known[notePath]; !known {
			s.known[notePath] = &Known{Path: notePath, ETag: entry.ETag, Size: entry.Size, ModTime: entry.LocalMod}
		}
		delete(s.entries, notePath)
		changed = true
	}

	files, err := os.ReadDir(s.notesDir())
	if err != nil {
		return changed
	}
	for _, file := range files {
		if file.IsDir() || filepath.Ext(file.Name()) != ".md" || referenced[file.Name()] {
			continue
		}
		if os.Remove(s.blobPath(file.Name())) == nil {
			changed = true
		}
	}
	return changed
}

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

// contentHash est l'empreinte d'un contenu, telle qu'elle est retenue dans
// Entry.BaseHash. Empreinte entière, contrairement à cacheName : ici une
// collision ferait taire un vrai conflit, là elle ne ferait que confondre deux
// fichiers de cache.
func contentHash(content []byte) string {
	sum := sha256.Sum256(content)
	return hex.EncodeToString(sum[:])
}

// save écrit l'index sur disque. L'appelant doit détenir le verrou.
//
// L'écriture passe par un fichier temporaire renommé : une coupure de courant
// au mauvais moment laisserait sinon un index tronqué, donc un cache perdu.
func (s *Store) save() error {
	state := persisted{
		Version:   indexVersion,
		Entries:   s.entries,
		Queue:     s.queue,
		Folders:   s.folders,
		Known:     s.known,
		Indexed:   s.indexed,
		Conflicts: s.conflicts,
		LocalOnly: s.localOnly,
	}
	data, err := json.Marshal(state)
	if err != nil {
		return fmt.Errorf("store: [%s] sérialisation de l'index: %w", CodeStorageIO, err)
	}

	tmp := s.indexPath() + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return fmt.Errorf("store: [%s] écriture de l'index: %w", CodeStorageIO, err)
	}
	if err := os.Rename(tmp, s.indexPath()); err != nil {
		return fmt.Errorf("store: [%s] remplacement de l'index: %w", CodeStorageIO, err)
	}
	return nil
}

// Get renvoie le contenu en cache d'une note.
func (s *Store) Get(notePath string) ([]byte, Entry, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	entry, ok := s.entries[notePath]
	if !ok {
		return nil, Entry{}, false
	}

	content, err := os.ReadFile(s.blobPath(entry.Cache))
	if err != nil {
		// L'index connaît la note mais le fichier a disparu : on traite le
		// cache comme absent plutôt que de propager une erreur d'E/S.
		return nil, Entry{}, false
	}
	entry.LastAccess = time.Now().UTC()
	// Le contenu lu reste valable même si une persistance de sa date d'accès
	// échoue. La prochaine écriture de l'index la rendra durable ; ne pas rendre
	// une note illisible pour une information de classement non critique.
	_ = s.save()
	return content, *entry, true
}

// CachedEntry indique si le contenu est disponible sans le lire ni modifier
// son rang LRU. Les listes l'utilisent pour afficher l'état Dirty : les
// parcourir ne doit pas faire croire que toutes les notes ont été ouvertes.
func (s *Store) CachedEntry(notePath string) (Entry, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()

	entry, ok := s.entries[notePath]
	if !ok {
		return Entry{}, false
	}
	if _, err := os.Stat(s.blobPath(entry.Cache)); err != nil {
		return Entry{}, false
	}
	return *entry, true
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
//
// Une écriture qui n'écrit rien est ignorée, et ce n'est pas une optimisation.
// L'éditeur enregistre à la sortie de l'écran, y compris quand la note n'a été
// qu'ouverte et refermée : sans ce filtre, lire une note suffit à la marquer
// sale. Elle est alors renvoyée au serveur pour rien, et — bien pire — ReadNote
// refuse de rafraîchir une note sale, donc son ETag vieillit précisément
// pendant la fenêtre où il ne devrait pas. La moindre modification faite
// ailleurs devient un conflit, avec sa copie, alors que le téléphone n'avait
// rien à dire. C'est ce qui rendait les copies de conflit envahissantes.
//
// La garde vit ici plutôt que dans l'interface parce que c'est la seule couche
// que tous les chemins d'écriture traversent, et la seule qui se teste.
func (s *Store) Put(notePath string, content []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.unchangedLocked(notePath, content) {
		return nil
	}
	return s.putLocked(notePath, content, true)
}

// unchangedLocked dit si réenregistrer ce contenu serait sans effet : le cache
// le porte déjà, et l'état de la file correspond à celui de l'entrée.
//
// La seconde condition n'est pas de la superstition : une note peut se trouver
// sale sans rien avoir en file — la déduplication d'une frappe arrivée pendant
// une passe de synchronisation y suffit. Repasser par le chemin normal la remet
// en file, là où un raccourci l'y laisserait. Ce n'est qu'un demi-remède :
// l'éditeur n'écrit pas ce qu'il n'a pas modifié, donc il n'appelle rien du tout
// sur la note bloquée. requeueOrphanWritesLocked porte l'autre moitié.
func (s *Store) unchangedLocked(notePath string, content []byte) bool {
	entry, ok := s.entries[notePath]
	if !ok || entry.Size != int64(len(content)) {
		return false
	}
	if entry.Dirty && !s.hasQueuedWriteLocked(notePath) {
		return false
	}

	cached, err := os.ReadFile(s.blobPath(entry.Cache))
	if err != nil {
		// Blob illisible : on réécrit plutôt que de conclure à l'identité.
		return false
	}
	return bytes.Equal(cached, content)
}

func (s *Store) hasQueuedWriteLocked(notePath string) bool {
	for _, op := range s.queue {
		if op.Kind == OpWrite && op.Path == notePath {
			return true
		}
	}
	return false
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
		Path:       notePath,
		Cache:      cacheName(notePath),
		ETag:       etag,
		Dirty:      false,
		BaseHash:   contentHash(content),
		Size:       int64(len(content)),
		LocalMod:   time.Now().UTC(),
		LastAccess: time.Now().UTC(),
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
	// En mode local, une note n'est jamais « en attente d'envoi » : il n'y a
	// pas d'envoi. La marquer sale ferait compter des opérations qui
	// n'existent pas et ferait chercher à requeueOrphanWritesLocked une file
	// à réparer.
	entry.Dirty = !s.localOnly
	entry.Size = int64(len(content))
	entry.LocalMod = time.Now().UTC()
	entry.LastAccess = entry.LocalMod

	if enqueue {
		s.enqueueLocked(Operation{Kind: OpWrite, Path: notePath})
	}
	return s.save()
}

func (s *Store) writeBlob(notePath string, content []byte) error {
	if err := s.ensureSpaceLocked(notePath, int64(len(content))); err != nil {
		return err
	}
	name := cacheName(notePath)
	tmp := s.blobPath(name) + ".tmp"
	defer os.Remove(tmp)
	if err := os.WriteFile(tmp, content, 0o600); err != nil {
		return fmt.Errorf("store: [%s] écriture du cache de %s: %w", CodeStorageIO, notePath, err)
	}
	if err := os.Rename(tmp, s.blobPath(name)); err != nil {
		return fmt.Errorf("store: [%s] remplacement du cache de %s: %w", CodeStorageIO, notePath, err)
	}
	return nil
}

// Delete retire une note ou un dossier du cache et inscrit la suppression en
// file. Sur un dossier, la descendance part avec lui, comme côté serveur.
func (s *Store) Delete(itemPath string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	expectedETag := s.expectedETagLocked(itemPath)
	if s.folders[itemPath] {
		return fmt.Errorf("store: [STRUCTURAL_OFFLINE_FOLDER] suppression différée du dossier %s refusée", itemPath)
	}
	s.dropLocked(itemPath)
	s.enqueueLocked(Operation{Kind: OpDelete, Path: itemPath, ExpectedETag: expectedETag})
	return s.save()
}

// expectedETagLocked retrouve la version observée avant qu'une opération locale
// ne retire ou ne déplace son entrée. Une valeur vide est volontairement
// conservée : une ancienne file ne doit jamais autoriser une mutation distante
// destructive sans version de référence.
func (s *Store) expectedETagLocked(itemPath string) string {
	if entry, ok := s.entries[itemPath]; ok {
		return entry.ETag
	}
	if known, ok := s.known[itemPath]; ok {
		return known.ETag
	}
	return ""
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
	s.forgetKnownLocked(itemPath)
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

	expectedETag := s.expectedETagLocked(from)
	if enqueue && s.folders[from] {
		return fmt.Errorf("store: [STRUCTURAL_OFFLINE_FOLDER] déplacement différé du dossier %s refusé", from)
	}
	if s.folders[from] {
		s.forgetFolderLocked(from)
		s.rememberFolderLocked(to)
	}

	// La descendance suit. Le cas ne se présente que pour un dossier renommé
	// côté serveur — le renommage différé d'un dossier est refusé plus haut —
	// et une note laissée sous l'ancien chemin décrirait alors un fichier qui
	// n'existe plus là-bas : illisible hors connexion, et prétendant une
	// version que le prochain envoi opposerait à un chemin disparu.
	deplacees := make([]string, 0, 1)
	for chemin := range s.entries {
		if _, ok := sousChemin(chemin, from); ok {
			deplacees = append(deplacees, chemin)
		}
	}
	sort.Strings(deplacees)

	for _, chemin := range deplacees {
		suffixe, _ := sousChemin(chemin, from)
		cible := to + suffixe
		entry := s.entries[chemin]
		content, err := os.ReadFile(s.blobPath(entry.Cache))
		if err == nil {
			if err := s.writeBlob(cible, content); err != nil {
				return err
			}
			_ = os.Remove(s.blobPath(entry.Cache))
		}
		entry.Path = cible
		entry.Cache = cacheName(cible)
		delete(s.entries, chemin)
		s.entries[cible] = entry
	}

	// Les écritures en attente suivent aussi, et c'est le cœur du correctif.
	// Laissée sous l'ancien chemin, une écriture est perdue : la passe suivante
	// n'y trouve plus de contenu à envoyer et la retire de la file, laissant la
	// note marquée « en attente d'envoi » pour toujours et sa modification à
	// quai. C'est arrivé en conditions réelles.
	ecritures := s.dequeueWritesUnderLocked(from)

	s.renameKnownLocked(from, to)

	if enqueue {
		s.enqueueLocked(Operation{Kind: OpMove, Path: from, Target: to, ExpectedETag: expectedETag})
	}
	// Réinscrites après le déplacement, jamais avant : tant que le serveur n'a
	// pas vu le nouveau chemin, il n'y a rien à y écrire.
	for _, chemin := range ecritures {
		suffixe, _ := sousChemin(chemin, from)
		s.enqueueLocked(Operation{Kind: OpWrite, Path: to + suffixe})
	}
	return s.save()
}

// dequeueWritesUnderLocked retire de la file les écritures visant un chemin ou
// sa descendance, et renvoie ces chemins dans l'ordre où ils y figuraient.
func (s *Store) dequeueWritesUnderLocked(base string) []string {
	var retirees []string
	restantes := s.queue[:0]
	for _, op := range s.queue {
		if op.Kind == OpWrite {
			if _, ok := sousChemin(op.Path, base); ok {
				retirees = append(retirees, op.Path)
				continue
			}
		}
		restantes = append(restantes, op)
	}
	s.queue = restantes
	return retirees
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
