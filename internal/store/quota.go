package store

import (
	"fmt"
	"os"
	"sort"
	"time"
)

// SetQuota applique un quota en octets et purge immédiatement les contenus
// récupérables. Un quota nul signifie illimité.
//
// Si les seuls contenus restants sont protégés, le quota est tout de même
// retenu afin que l'état affiché soit honnête, puis une STORAGE_IO explique que
// le travail local doit d'abord être synchronisé ou que le disque doit être
// libéré.
func (s *Store) SetQuota(quota int64) error {
	if quota < 0 {
		return fmt.Errorf("store: [%s] quota négatif", CodeStorageIO)
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	s.quota = quota
	return s.pruneLocked("")
}

// Quota renvoie le quota courant, en octets. Zéro désigne « illimité ».
func (s *Store) Quota() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.quota
}

// Usage mesure les blobs réellement présents. L'index n'est pas une source
// fiable de taille : une interruption peut laisser une entrée sans son blob.
func (s *Store) Usage() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	usage, _ := s.usageLocked()
	return usage
}

// Prune évince les contenus récupérables jusqu'au quota courant. Les brouillons,
// conflits et données liées à une opération en attente ne sont jamais candidats.
func (s *Store) Prune() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.pruneLocked("")
}

// ensureSpaceLocked prépare une écriture de blob. keep est le chemin en cours
// d'écriture : même propre, son ancien blob ne doit pas être évincé entre le
// calcul et son remplacement atomique.
func (s *Store) ensureSpaceLocked(keep string, newSize int64) error {
	if s.quota == UnlimitedQuota {
		return nil
	}
	return s.pruneForSizeLocked(keep, newSize)
}

func (s *Store) pruneLocked(keep string) error {
	if s.quota == UnlimitedQuota {
		return nil
	}
	return s.pruneForSizeLocked(keep, -1)
}

// pruneForSizeLocked évince selon LRU. newSize >= 0 décrit la taille finale du
// blob keep ; -1 demande seulement de rentrer sous le quota actuel.
func (s *Store) pruneForSizeLocked(keep string, newSize int64) error {
	usage, sizes := s.usageLocked()
	projected := usage
	if newSize >= 0 {
		projected -= sizes[keep]
		projected += newSize
	}

	candidates := make([]*Entry, 0)
	for path, entry := range s.entries {
		if path == keep || sizes[path] == 0 || s.protectedLocked(path, entry) {
			continue
		}
		candidates = append(candidates, entry)
	}
	sort.Slice(candidates, func(i, j int) bool {
		left, right := candidates[i], candidates[j]
		if left.LastAccess.Equal(right.LastAccess) {
			return left.Path < right.Path
		}
		return left.LastAccess.Before(right.LastAccess)
	})

	changed := false
	for _, entry := range candidates {
		if projected <= s.quota {
			break
		}
		size := sizes[entry.Path]
		if err := s.evictLocked(entry); err != nil {
			return err
		}
		projected -= size
		changed = true
	}
	if changed {
		if err := s.save(); err != nil {
			return err
		}
	}
	if projected > s.quota {
		return fmt.Errorf("store: [%s] quota de cache atteint (%d octets, %d occupés par des données protégées)", CodeStorageIO, s.quota, projected)
	}
	return nil
}

// usageLocked interroge les fichiers, pas Entry.Size. Les blobs propres qui ont
// disparu deviennent de simples métadonnées Known : ils restent visibles et
// seront téléchargés à la prochaine ouverture en ligne.
func (s *Store) usageLocked() (int64, map[string]int64) {
	var usage int64
	sizes := make(map[string]int64, len(s.entries))
	for path, entry := range s.entries {
		info, err := os.Stat(s.blobPath(entry.Cache))
		if err != nil || info.IsDir() {
			continue
		}
		sizes[path] = info.Size()
		usage += info.Size()
	}
	return usage, sizes
}

func (s *Store) protectedLocked(notePath string, entry *Entry) bool {
	if entry.Dirty || entry.Conflict {
		return true
	}
	for _, op := range s.queue {
		if op.Path == notePath || op.Target == notePath {
			return true
		}
	}
	return false
}

// evictLocked remplace une entrée de contenu par son inventaire. Le renommage
// intermédiaire rend le redémarrage sûr dans les deux ordres : avant save, un
// blob manquant est détecté ; après save, un éventuel orphelin est inoffensif.
func (s *Store) evictLocked(entry *Entry) error {
	blob := s.blobPath(entry.Cache)
	staged := blob + ".evicting"
	if err := os.Rename(blob, staged); err != nil {
		return fmt.Errorf("store: [%s] éviction du cache de %s: %w", CodeStorageIO, entry.Path, err)
	}

	if _, exists := s.known[entry.Path]; !exists {
		s.known[entry.Path] = &Known{
			Path: entry.Path, ETag: entry.ETag, Size: entry.Size, ModTime: entry.LocalMod,
		}
	}
	delete(s.entries, entry.Path)
	if err := os.Remove(staged); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("store: [%s] suppression du cache évincé de %s: %w", CodeStorageIO, entry.Path, err)
	}
	return nil
}

// MarkConflict protège une copie créée par la résolution automatique. Il est
// volontairement séparé d'Accept : recevoir la version serveur n'en fait pas
// une copie de conflit.
func (s *Store) MarkConflict(notePath string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	entry, ok := s.entries[notePath]
	if !ok {
		return fmt.Errorf("store: [%s] copie de conflit absente: %s", CodeStorageIO, notePath)
	}
	entry.Conflict = true
	entry.LastAccess = time.Now().UTC()
	return s.save()
}

// UnmarkConflict lève la protection posée par MarkConflict : la copie redevient
// une note ordinaire, de nouveau évincible par le quota. Appelé quand
// l'utilisateur garde les deux versions — la copie n'est plus un conflit en
// attente. Une entrée déjà disparue n'est pas une erreur : il n'y a plus rien
// à protéger.
func (s *Store) UnmarkConflict(notePath string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	entry, ok := s.entries[notePath]
	if !ok || !entry.Conflict {
		return nil
	}
	entry.Conflict = false
	entry.LastAccess = time.Now().UTC()
	return s.save()
}
