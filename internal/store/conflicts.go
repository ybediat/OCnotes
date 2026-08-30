package store

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/ybediat/OpenNote/internal/opencloud"
)

type ConflictResolution string

const (
	KeepServer ConflictResolution = "server"
	KeepLocal  ConflictResolution = "local"
	KeepBoth   ConflictResolution = "both"
)

// Conflict est un conflit ouvert qui attend une décision explicite de
// l'utilisateur. Il est distinct du rapport de synchronisation, éphémère.
type Conflict struct {
	ID         string    `json:"id"`
	Operation  OpKind    `json:"operation"`
	Path       string    `json:"path"`
	CopyPath   string    `json:"copyPath,omitempty"`
	ServerETag string    `json:"serverETag"`
	CreatedAt  time.Time `json:"createdAt"`
}

// Conflicts renvoie les conflits encore ouverts, dans leur ordre de création.
func (s *Store) Conflicts() []Conflict {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Conflict, 0, len(s.conflicts))
	for _, conflict := range s.conflicts {
		out = append(out, conflict)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.Before(out[j].CreatedAt) })
	return out
}

func (s *Store) recordConflict(operation OpKind, path, copyPath, serverETag string) (Conflict, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now().UTC()
	id := fmt.Sprintf("%d-%s", now.UnixNano(), cacheName(path)[:8])
	for suffix := 2; s.conflicts[id].ID != ""; suffix++ {
		id = fmt.Sprintf("%d-%s-%d", now.UnixNano(), cacheName(path)[:8], suffix)
	}
	conflict := Conflict{ID: id, Operation: operation, Path: path, CopyPath: copyPath, ServerETag: serverETag, CreatedAt: now}
	s.conflicts[id] = conflict
	return conflict, s.save()
}

// ResolveConflict applique une décision explicite. Garder les deux clôture
// simplement le conflit ; garder le serveur retire la copie après avoir
// vérifié qu'aucun autre appareil ne l'a changée ; garder le local modifie la
// référence uniquement avec l'ETag retenu au moment du conflit.
func (s *Store) ResolveConflict(ctx context.Context, remote Remote, id string, resolution ConflictResolution) (*Conflict, error) {
	s.mu.Lock()
	conflict, ok := s.conflicts[id]
	s.mu.Unlock()
	if !ok {
		return nil, fmt.Errorf("store: conflit introuvable %q", id)
	}

	if resolution == KeepBoth || (resolution == KeepServer && conflict.CopyPath == "") {
		return nil, s.closeConflict(id)
	}
	if resolution == KeepLocal {
		return s.keepLocal(ctx, remote, conflict)
	}
	if resolution != KeepServer {
		return nil, fmt.Errorf("store: résolution inconnue %q", resolution)
	}

	_, entry, cached := s.Get(conflict.CopyPath)
	if !cached || entry.ETag == "" {
		return nil, fmt.Errorf("store: copie locale absente ou sans ETag pour %s", conflict.CopyPath)
	}
	etag, err := remote.Stat(ctx, conflict.CopyPath)
	if err != nil {
		return nil, err
	}
	if etag != entry.ETag {
		return nil, fmt.Errorf("store: copie de conflit modifiée à distance: %w", opencloud.ErrConflict)
	}
	if err := remote.Delete(ctx, conflict.CopyPath); err != nil && !errors.Is(err, opencloud.ErrNotFound) {
		return nil, err
	}
	if err := s.Forget(conflict.CopyPath); err != nil {
		return nil, err
	}
	return nil, s.closeConflict(id)
}

func (s *Store) keepLocal(ctx context.Context, remote Remote, conflict Conflict) (*Conflict, error) {
	local, entry, cached := s.Get(conflict.CopyPath)
	if !cached || entry.ETag == "" {
		return nil, fmt.Errorf("store: copie locale absente ou sans ETag pour %s", conflict.CopyPath)
	}
	copyETag, err := remote.Stat(ctx, conflict.CopyPath)
	if err != nil {
		return nil, err
	}
	if copyETag != entry.ETag {
		return nil, fmt.Errorf("store: copie de conflit modifiée à distance: %w", opencloud.ErrConflict)
	}
	etag, err := remote.Save(ctx, conflict.Path, local, conflict.ServerETag)
	if err == nil {
		if err := s.Accept(conflict.Path, local, etag); err != nil {
			return nil, err
		}
		if _, err := s.ResolveConflict(ctx, remote, conflict.ID, KeepServer); err != nil {
			return nil, err
		}
		return nil, nil
	}
	if !errors.Is(err, opencloud.ErrConflict) {
		return nil, err
	}

	server, serverETag, err := remote.Read(ctx, conflict.Path)
	if err != nil {
		return nil, err
	}
	if string(server) == string(local) {
		// La première écriture a pu aboutir, puis la réponse se perdre. La
		// référence contient déjà le choix local : on ne la réécrit pas.
		if err := s.Accept(conflict.Path, server, serverETag); err != nil {
			return nil, err
		}
		if _, err := s.ResolveConflict(ctx, remote, conflict.ID, KeepServer); err != nil {
			return nil, err
		}
		return nil, nil
	}
	if err := s.Accept(conflict.Path, server, serverETag); err != nil {
		return nil, err
	}
	next, err := s.recordConflict(conflict.Operation, conflict.Path, conflict.CopyPath, serverETag)
	if err != nil {
		return nil, err
	}
	if err := s.closeConflict(conflict.ID); err != nil {
		return nil, err
	}
	return &next, nil
}

func (s *Store) closeConflict(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.conflicts[id]; !ok {
		return fmt.Errorf("store: conflit introuvable %q", id)
	}
	delete(s.conflicts, id)
	return s.save()
}
