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

// CodeTargetExists étiquette un renommage ou un déplacement vers un chemin que
// le cache sait déjà occupé. Le serveur refuse le même geste (Overwrite: F) ;
// sans ce refus, le cache écraserait la note visée — et en mode local, c'est
// la seule copie.
const CodeTargetExists = "TARGET_EXISTS"

// CodeQuotaProtected étiquette un quota retenu mais que les seuls contenus
// protégés — brouillons, copies de conflit, opérations en attente — dépassent
// déjà. Rien n'est en panne et rien n'est perdu : ce n'est pas une STORAGE_IO,
// qui envoyait l'utilisateur vérifier un disque qui avait toute la place
// voulue. Seul SetQuota le renvoie ; une écriture n'est jamais refusée pour
// cela.
const CodeQuotaProtected = "QUOTA_PROTECTED"

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

	// gen change à chaque modification de l'entrée. Il n'est pas persisté :
	// il ne sert qu'à comparer deux états observés par le même processus, de
	// part et d'autre d'un appel réseau. Voir Observation.
	gen uint64
}

// Observation fige l'état d'une entrée avant un appel réseau.
//
// Entre la lecture d'une note et le retour du serveur, l'utilisateur a pu
// taper, renommer ou se déconnecter. Tout ce qui écrit dans le cache une
// réponse du serveur doit donc vérifier que l'entrée est restée celle qu'il a
// observée : sinon il remplacerait un texte qu'il n'a jamais vu, et le
// marquerait propre — la frappe disparaîtrait sans laisser de trace.
type Observation struct {
	present bool
	gen     uint64
	epoch   uint64
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

	// gen alimente Entry.gen ; epoch change à chaque purge, pour qu'aucune
	// réponse arrivée après une déconnexion ne réécrive le cache vidé.
	gen   uint64
	epoch uint64
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
	content, entry, _, ok := s.getObserved(notePath)
	return content, entry, ok
}

// getObserved lit une note et l'observation qui l'accompagne, d'un seul
// verrou : lues séparément, une frappe pourrait se glisser entre les deux.
func (s *Store) getObserved(notePath string) ([]byte, Entry, Observation, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	obs := s.observeLocked(notePath)
	entry, ok := s.entries[notePath]
	if !ok {
		return nil, Entry{}, obs, false
	}

	content, err := os.ReadFile(s.blobPath(entry.Cache))
	if err != nil {
		// L'index connaît la note mais le fichier a disparu : on traite le
		// cache comme absent plutôt que de propager une erreur d'E/S.
		return nil, Entry{}, obs, false
	}
	// La date d'accès reste en mémoire : l'éviction la voit tout de suite, et
	// la prochaine écriture de l'index l'emporte. La persister ici réécrivait
	// l'index entier à chaque lecture — trois fois par ouverture en ligne, avec
	// l'Accept du rafraîchissement. Un arrêt brutal avant toute écriture perd
	// cette date ; au pire une note lue est évincée un peu tôt et se
	// retélécharge. En mode local, rien n'est évincé.
	entry.LastAccess = time.Now().UTC()
	return content, *entry, obs, true
}

// Observe fige l'état actuel d'une entrée, présente ou non.
func (s *Store) Observe(notePath string) Observation {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.observeLocked(notePath)
}

func (s *Store) observeLocked(notePath string) Observation {
	obs := Observation{epoch: s.epoch}
	if entry, ok := s.entries[notePath]; ok {
		obs.present = true
		obs.gen = entry.gen
	}
	return obs
}

// unchangedSinceLocked dit si l'entrée est toujours celle qui a été observée.
func (s *Store) unchangedSinceLocked(notePath string, obs Observation) bool {
	if s.epoch != obs.epoch {
		return false
	}
	entry, ok := s.entries[notePath]
	if ok != obs.present {
		return false
	}
	return !ok || entry.gen == obs.gen
}

// touchLocked signale qu'une entrée vient de changer.
func (s *Store) touchLocked(entry *Entry) {
	s.gen++
	entry.gen = s.gen
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

// Accept enregistre une version reçue du serveur : le cache est alors aligné,
// donc propre, et rien n'est mis en file.
//
// Sans condition : réservé aux chemins que personne d'autre ne peut toucher
// pendant l'appel réseau — une copie de conflit qu'on vient de nommer, une
// note qu'on vient de créer. Partout ailleurs, AcceptIfUnchanged.
func (s *Store) Accept(notePath string, content []byte, etag string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if err := s.acceptLocked(notePath, content, etag); err != nil {
		return err
	}
	return s.save()
}

// AcceptIfUnchanged enregistre une version reçue du serveur seulement si
// l'entrée est restée celle qui a été observée avant l'appel réseau.
//
// Le refus n'est pas une erreur : la note a bougé localement, elle reste telle
// quelle et la synchronisation suivante la confrontera au serveur. Accepter
// quand même remplaçait la frappe par la version distante et la marquait
// propre — elle n'existait plus nulle part.
func (s *Store) AcceptIfUnchanged(notePath string, obs Observation, content []byte, etag string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.unchangedSinceLocked(notePath, obs) {
		return false, nil
	}
	if err := s.acceptLocked(notePath, content, etag); err != nil {
		return false, err
	}
	return true, s.save()
}

func (s *Store) acceptLocked(notePath string, content []byte, etag string) error {
	if err := s.writeBlob(notePath, content); err != nil {
		return err
	}
	entry := &Entry{
		Path:       notePath,
		Cache:      cacheName(notePath),
		ETag:       etag,
		Dirty:      false,
		BaseHash:   contentHash(content),
		Size:       int64(len(content)),
		LocalMod:   time.Now().UTC(),
		LastAccess: time.Now().UTC(),
	}
	s.touchLocked(entry)
	s.entries[notePath] = entry
	return nil
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
	s.touchLocked(entry)

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
// À utiliser quand le renommage n'a pas encore atteint le serveur. Une cible
// que le cache sait occupée est refusée : le serveur la refuserait aussi, et
// d'ici là la note visée aurait été écrasée dans le cache.
func (s *Store) Rename(from, to string) error {
	return s.rename(from, to, true, true)
}

// RenameLocal déplace une note dans le cache sans rien inscrire en file.
// À utiliser quand le serveur a déjà appliqué le renommage : le rejouer
// échouerait, la source n'existant plus là-bas. Le serveur fait foi, donc une
// entrée qui occuperait encore la cible est périmée et se laisse écraser.
func (s *Store) RenameLocal(from, to string) error {
	return s.rename(from, to, false, false)
}

// RenameOnDevice déplace une note ou un dossier quand l'appareil est le seul
// stockage : rien en file, et une cible occupée refusée — il n'y a pas de
// serveur pour dire qu'elle est périmée, c'est une vraie note.
func (s *Store) RenameOnDevice(from, to string) error {
	return s.rename(from, to, false, true)
}

func (s *Store) rename(from, to string, enqueue, refuseTaken bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if err := s.renameLocked(from, to, enqueue, refuseTaken); err != nil {
		return err
	}
	return s.save()
}

// renameLocked porte le renommage sans écrire l'index. L'appelant doit
// détenir le verrou, et sauvegarder.
func (s *Store) renameLocked(from, to string, enqueue, refuseTaken bool) error {
	// Rien à faire, et surtout rien à tenter : le chemin de cache dérive du
	// chemin de note, donc la boucle plus bas réécrirait le fichier puis le
	// supprimerait comme s'il s'agissait de l'ancien. Renommer une note sous
	// son propre nom effaçait ainsi son contenu.
	if from == to {
		return nil
	}

	expectedETag := s.expectedETagLocked(from)
	if enqueue && s.folders[from] {
		return fmt.Errorf("store: [STRUCTURAL_OFFLINE_FOLDER] déplacement différé du dossier %s refusé", from)
	}
	if refuseTaken && s.takenLocked(to) {
		return fmt.Errorf("store: [%s] %s existe déjà", CodeTargetExists, to)
	}

	// Les sous-dossiers suivent leur parent, vides compris : ne réinscrire que
	// la cible les faisait disparaître du sélecteur de destination, et pour
	// de bon en mode local, où aucun listing serveur ne les rapporte.
	dossiers := make([]string, 0)
	for d := range s.folders {
		if suffixe, ok := sousChemin(d, from); ok {
			dossiers = append(dossiers, to+suffixe)
		}
	}
	if len(dossiers) > 0 {
		s.forgetFolderLocked(from)
		for _, d := range dossiers {
			s.rememberFolderLocked(d)
		}
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

	// Le blob est déplacé, pas recopié. Un déplacement ne change pas
	// l'occupation, et la copie passait par writeBlob, donc par le quota, qui
	// comptait la source en plus de la copie : dans un cache plein, elle
	// évinçait une note sans rapport, voire une sœur que cette boucle n'avait
	// pas encore traitée — dont l'entrée disparue était ensuite déréférencée.
	for _, chemin := range deplacees {
		suffixe, _ := sousChemin(chemin, from)
		cible := to + suffixe
		entry := s.entries[chemin]
		nom := cacheName(cible)
		// Un blob déjà absent n'empêche pas l'entrée de suivre : Get le
		// traitera comme un contenu manquant, comme avant.
		if err := os.Rename(s.blobPath(entry.Cache), s.blobPath(nom)); err != nil && !os.IsNotExist(err) {
			return fmt.Errorf("store: [%s] déplacement du cache de %s: %w", CodeStorageIO, chemin, err)
		}
		entry.Path = cible
		entry.Cache = nom
		s.touchLocked(entry)
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
	return nil
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

// takenLocked dit si le cache connaît déjà quelque chose à ce chemin : une
// note détenue ou seulement inventoriée, un dossier retenu, ou un dossier
// implicite — le parent d'une note, que rien n'a inscrit comme dossier.
func (s *Store) takenLocked(chemin string) bool {
	for p := range s.entries {
		if _, ok := sousChemin(p, chemin); ok {
			return true
		}
	}
	for p := range s.known {
		if _, ok := sousChemin(p, chemin); ok {
			return true
		}
	}
	for d := range s.folders {
		if _, ok := sousChemin(d, chemin); ok {
			return true
		}
	}
	return false
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

// ForgetIfUnchanged est à Forget ce qu'AcceptIfUnchanged est à Accept : une
// note modifiée pendant l'appel réseau n'est pas oubliée, sa frappe avec.
func (s *Store) ForgetIfUnchanged(itemPath string, obs Observation) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.unchangedSinceLocked(itemPath, obs) {
		return false, nil
	}
	s.dropLocked(itemPath)
	return true, s.save()
}
