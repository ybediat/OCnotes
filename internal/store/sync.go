package store

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path"
	"sort"
	"strings"
	"time"

	"github.com/ybediat/OpenNote/internal/opencloud"
)

// OpKind désigne le type d'une opération en attente.
type OpKind string

const (
	OpWrite  OpKind = "write"
	OpDelete OpKind = "delete"
	OpMove   OpKind = "move"
	OpMkdir  OpKind = "mkdir"
)

// Operation est une modification locale qui reste à propager au serveur.
//
// La file est persistée avec l'index : une écriture faite dans le métro
// survit à la fermeture de l'application.
type Operation struct {
	Kind         OpKind `json:"kind"`
	Path         string `json:"path"`
	Target       string `json:"target,omitempty"`
	ExpectedETag string `json:"expectedETag,omitempty"`
}

// Remote est la partie de la bibliothèque de notes dont la synchronisation a
// besoin. *notes.Library l'implémente.
type Remote interface {
	Read(ctx context.Context, notePath string) ([]byte, string, error)
	Exists(ctx context.Context, itemPath string) (bool, error)
	Stat(ctx context.Context, itemPath string) (string, error)
	Save(ctx context.Context, notePath string, content []byte, ifMatch string) (string, error)
	SaveNew(ctx context.Context, notePath string, content []byte) (string, error)
	Delete(ctx context.Context, itemPath string) error
	MoveTo(ctx context.Context, from, to string) error
	EnsureFolder(ctx context.Context, dir string) error
}

// Report résume une passe de synchronisation.
type Report struct {
	Pushed    int
	Deleted   int
	Moved     int
	Conflicts []Conflict

	// Remaining est le nombre d'opérations toujours en attente : une panne de
	// transport arrête la passe pour préserver l'ordre, et une opération que le
	// serveur refuse y reste, mise de côté, pendant que les autres passent.
	Remaining int
}

// HasChanges indique si la passe a modifié quelque chose.
func (r Report) HasChanges() bool {
	return r.Pushed+r.Deleted+r.Moved+len(r.Conflicts) > 0
}

// Pending renvoie les opérations en attente de propagation.
func (s *Store) Pending() []Operation {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]Operation(nil), s.queue...)
}

// enqueueLocked ajoute une opération, en absorbant les doublons.
//
// Sans cette absorption, une note enregistrée à chaque frappe empilerait des
// centaines d'écritures identiques : seule la dernière compte, puisque le
// contenu poussé est toujours lu dans le cache au moment de l'envoi.
// L'appelant doit détenir le verrou.
func (s *Store) enqueueLocked(op Operation) {
	switch op.Kind {
	case OpWrite:
		for _, existing := range s.queue {
			if existing.Kind == OpWrite && existing.Path == op.Path {
				return
			}
		}

	case OpDelete:
		// Une suppression rend caduque toute écriture en attente sur le même
		// chemin, mais pas un déplacement, qui a pu créer ce chemin.
		filtered := s.queue[:0]
		for _, existing := range s.queue {
			if existing.Kind == OpWrite && existing.Path == op.Path {
				continue
			}
			filtered = append(filtered, existing)
		}
		s.queue = filtered

	case OpMkdir:
		for _, existing := range s.queue {
			if existing.Kind == OpMkdir && existing.Path == op.Path {
				return
			}
		}
	}

	s.queue = append(s.queue, op)
}

// requeueOrphanWritesLocked réinscrit les écritures que la file a perdues.
//
// L'invariant que cette fonction rétablit est simple : une entrée sale porte
// toujours une écriture en file, puisque c'est cette écriture qui la rendra
// propre. Deux chemins l'ont enfreint, avec le même symptôme — une note reste
// « en attente d'envoi » pour toujours, et la modification qu'elle porte ne
// part jamais :
//
//   - un renommage déplaçait l'entrée sans déplacer l'écriture, qui restait
//     inscrite sous l'ancien chemin ; la passe suivante n'y trouvait plus de
//     contenu et retirait l'opération sans rien envoyer ;
//   - une frappe pendant une passe est absorbée comme doublon de l'écriture en
//     cours de traitement, puis cette écriture est retirée de la file une fois
//     poussée : la version tapée entre-temps n'est plus réclamée par personne.
//
// Le premier est corrigé à sa source, dans rename. Le second est inhérent à la
// déduplication, et se rattrape ici. Surtout, la réparation est le seul moyen
// de récupérer un appareil dont l'index porte déjà l'état fautif : rien
// d'autre ne remet une note en file, l'éditeur n'écrivant pas ce qu'il n'a pas
// modifié.
//
// Elle ne s'exécute qu'au démarrage et à l'ouverture d'une passe, jamais dans
// la boucle de drainage : une note qui resterait sale ferait tourner la passe
// sans fin.
func (s *Store) requeueOrphanWritesLocked() bool {
	orphelines := make([]string, 0)
	for chemin, entry := range s.entries {
		if !entry.Dirty || s.hasQueuedWriteLocked(chemin) {
			continue
		}
		// Sans blob, il n'y a rien à envoyer : l'écriture serait retirée sans
		// effet à la passe suivante.
		if _, err := os.Stat(s.blobPath(entry.Cache)); err != nil {
			continue
		}
		orphelines = append(orphelines, chemin)
	}
	// L'ordre de parcours d'une map varie d'une exécution à l'autre ; la file,
	// elle, est rejouée dans l'ordre et persistée.
	sort.Strings(orphelines)
	for _, chemin := range orphelines {
		s.enqueueLocked(Operation{Kind: OpWrite, Path: chemin})
	}
	return len(orphelines) > 0
}

// Push draine la file d'attente vers le serveur.
//
// Les opérations sont rejouées dans l'ordre : un déplacement suivi d'une
// écriture n'a pas le même effet dans l'autre sens. Une panne de transport
// interrompt donc la passe et laisse le reste en file — réessayer plus tard
// vaut mieux qu'appliquer les opérations dans le désordre.
//
// Un refus qui ne vise qu'une opération est traité autrement : elle passe en
// fin de file et la passe continue avec les suivantes. Voir setAside pour ce
// que cet ordre coûte, et ce que le respecter coûtait davantage. L'erreur est
// tout de même renvoyée à la fin, avec le rapport de ce qui est passé : une
// opération refusée pour de bon reste visible à chaque passe, et son message
// nomme la note.
//
// Un conflit, lui, n'interrompt rien : il est résolu sur place et la
// synchronisation continue.
func (s *Store) Push(ctx context.Context, remote Remote) (Report, error) {
	var report Report

	// Une passe s'ouvre sur la remise en file de ce qui a été perdu de vue.
	// Une seule fois, hors de la boucle : voir requeueOrphanWritesLocked.
	s.mu.Lock()
	reinscrites := s.requeueOrphanWritesLocked()
	var err error
	if reinscrites {
		err = s.save()
	}
	s.mu.Unlock()
	if err != nil {
		return report, err
	}

	// misesDeCote compte les opérations que le serveur a refusées pendant cette
	// passe et qui sont passées en fin de file. Quand elles occupent toute la
	// file, tout ce qui restait a été tenté : la passe s'arrête.
	misesDeCote := 0
	var premierRefus error

	for {
		s.mu.Lock()
		if len(s.queue) == 0 || misesDeCote >= len(s.queue) {
			s.mu.Unlock()
			break
		}
		op := s.queue[0]
		s.mu.Unlock()

		if err := ctx.Err(); err != nil {
			report.Remaining = len(s.Pending())
			return report, err
		}

		conflict, err := s.apply(ctx, remote, op, &report)
		if err != nil {
			if estPanneDeTransport(err) {
				report.Remaining = len(s.Pending())
				return report, err
			}
			if premierRefus == nil {
				premierRefus = err
			}
			if err := s.setAside(op); err != nil {
				return report, err
			}
			misesDeCote++
			continue
		}
		if conflict != nil {
			report.Conflicts = append(report.Conflicts, *conflict)
		}

		s.mu.Lock()
		// L'opération traitée est retirée. La comparaison protège du cas où
		// la résolution d'un conflit a elle-même modifié la file.
		if len(s.queue) > 0 && s.queue[0] == op {
			s.queue = s.queue[1:]
		}
		err = s.save()
		s.mu.Unlock()
		if err != nil {
			return report, err
		}
	}

	report.Remaining = len(s.Pending())
	return report, premierRefus
}

// estPanneDeTransport distingue les deux natures d'échec, et c'est toute la
// décision : une panne de transport condamne la passe entière — rien d'autre ne
// passera, et l'ordre de la file doit être gardé intact pour la prochaine —
// tandis qu'un refus ne vise que l'opération qui l'a provoqué.
//
// Un 5xx passe pour un refus alors qu'il est passager. Ce n'est pas grave :
// toutes les opérations le rencontreront, chacune sera mise de côté une fois,
// et la passe s'arrêtera d'elle-même après un tour de file.
func estPanneDeTransport(err error) bool {
	return errors.Is(err, opencloud.ErrOffline) ||
		errors.Is(err, context.DeadlineExceeded) ||
		errors.Is(err, context.Canceled)
}

// setAside envoie en fin de file l'opération que le serveur vient de refuser.
//
// Sans elle, cette opération restait en tête et **retenait tout ce qui la
// suivait, indéfiniment** : chaque passe la rejouait, échouait pareil, et pas
// une note derrière ne partait. Une seule note dont le dossier parent avait
// disparu suffisait à arrêter la synchronisation de l'appareil.
//
// L'ordre de la file est un vrai contrat — un déplacement suivi d'une écriture
// n'a pas le même effet dans l'autre sens — mais il n'engage que des opérations
// qui s'appliquent. Celle-ci n'a rien appliqué : la faire passer derrière ne
// change l'effet d'aucune autre, alors que la laisser devant les annule toutes.
func (s *Store) setAside(op Operation) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if len(s.queue) > 0 && s.queue[0] == op {
		s.queue = append(append([]Operation(nil), s.queue[1:]...), op)
	}
	return s.save()
}

// apply exécute une opération. Un conflit est renvoyé plutôt que remonté comme
// erreur : il est attendu et se résout.
func (s *Store) apply(ctx context.Context, remote Remote, op Operation, report *Report) (*Conflict, error) {
	switch op.Kind {
	case OpMkdir:
		if err := remote.EnsureFolder(ctx, op.Path); err != nil {
			return nil, err
		}
		return nil, nil

	case OpDelete:
		changed, err := s.structuralChange(ctx, remote, op)
		if err != nil {
			return nil, err
		}
		if changed {
			return s.resolveDeleteConflict(ctx, remote, op.Path)
		}
		err = remote.Delete(ctx, op.Path)
		if err != nil && !errors.Is(err, opencloud.ErrNotFound) {
			return nil, err
		}
		// Une note déjà absente du serveur est le résultat voulu.
		report.Deleted++
		return nil, nil

	case OpMove:
		changed, err := s.structuralChange(ctx, remote, op)
		if err != nil {
			return nil, err
		}
		if changed {
			return s.resolveMoveConflict(ctx, remote, op)
		}
		err = remote.MoveTo(ctx, op.Path, op.Target)
		if err != nil && !errors.Is(err, opencloud.ErrNotFound) {
			return nil, err
		}
		report.Moved++
		return nil, nil

	case OpWrite:
		return s.pushWrite(ctx, remote, op.Path, report)

	default:
		return nil, fmt.Errorf("store: opération inconnue %q", op.Kind)
	}
}

// structuralChange compare la version observée au geste local avec celle qui
// est présente juste avant la mutation. OpenCloud ignorant If-Match sur DELETE
// et MOVE, cette lecture est la barrière qui évite le cas courant de perte.
func (s *Store) structuralChange(ctx context.Context, remote Remote, op Operation) (bool, error) {
	if op.ExpectedETag == "" {
		return true, nil
	}
	etag, err := remote.Stat(ctx, op.Path)
	if errors.Is(err, opencloud.ErrNotFound) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return etag != op.ExpectedETag, nil
}

func (s *Store) resolveDeleteConflict(ctx context.Context, remote Remote, notePath string) (*Conflict, error) {
	server, etag, err := remote.Read(ctx, notePath)
	if errors.Is(err, opencloud.ErrNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if err := s.Accept(notePath, server, etag); err != nil {
		return nil, err
	}
	conflict, err := s.recordConflict(OpDelete, notePath, "", etag)
	if err != nil {
		return nil, err
	}
	return &conflict, nil
}

func (s *Store) resolveMoveConflict(ctx context.Context, remote Remote, op Operation) (*Conflict, error) {
	server, etag, err := remote.Read(ctx, op.Path)
	if errors.Is(err, opencloud.ErrNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	local, _, ok := s.Get(op.Target)
	if !ok {
		return nil, fmt.Errorf("store: copie locale absente pendant le conflit de déplacement de %s", op.Path)
	}
	copyPath := conflictPath(op.Target, time.Now())
	copyETag, err := remote.Save(ctx, copyPath, local, "")
	if err != nil {
		return nil, err
	}
	if err := s.Accept(op.Path, server, etag); err != nil {
		return nil, err
	}
	if err := s.Accept(copyPath, local, copyETag); err != nil {
		return nil, err
	}
	if err := s.MarkConflict(copyPath); err != nil {
		return nil, err
	}
	if err := s.Forget(op.Target); err != nil {
		return nil, err
	}
	conflict, err := s.recordConflict(OpMove, op.Path, copyPath, etag)
	if err != nil {
		return nil, err
	}
	return &conflict, nil
}

// pushWrite envoie le contenu en cache, en protégeant la version du serveur
// par un If-Match.
func (s *Store) pushWrite(ctx context.Context, remote Remote, notePath string, report *Report) (*Conflict, error) {
	content, entry, ok := s.Get(notePath)
	if !ok {
		// La note a été supprimée entre-temps : il n'y a plus rien à pousser.
		return nil, nil
	}

	// Le serveur détient déjà exactement ce contenu : l'écriture en file n'a
	// rien à propager. Le cas arrive dès qu'une modification est défaite avant
	// la synchronisation — saisir un caractère puis l'effacer suffit.
	//
	// Renvoyer ce contenu ne serait pas neutre : le PUT changerait l'ETag et la
	// date de la note pour tous les autres appareils, et un If-Match périmé
	// ferait naître une copie de conflit sur une version qui n'a rien à
	// arbitrer. On se contente donc de constater l'alignement.
	if entry.ETag != "" && entry.BaseHash != "" && entry.BaseHash == contentHash(content) {
		return nil, s.settle(notePath, entry, entry.ETag, entry.BaseHash)
	}

	// Un ETag vide signale une note que le serveur n'a jamais vue : elle a été
	// créée hors connexion. Rien ne dit qu'un fichier du même nom n'est pas
	// apparu là-bas entre-temps, et l'écraser détruirait le travail de
	// quelqu'un.
	//
	// La vérification est explicite plutôt que déléguée à « If-None-Match: * » :
	// cet en-tête n'est pas honoré de façon fiable par tous les serveurs, et
	// s'y fier a effectivement laissé passer un écrasement en conditions
	// réelles. SaveNew le pose quand même, comme seconde barrière.
	var etag string
	var err error

	if entry.ETag == "" {
		exists, existsErr := remote.Exists(ctx, notePath)
		if existsErr != nil {
			return nil, existsErr
		}
		if exists {
			return s.resolveConflict(ctx, remote, notePath, content, entry.BaseHash, report)
		}
		etag, err = remote.SaveNew(ctx, notePath, content)
	} else {
		etag, err = remote.Save(ctx, notePath, content, entry.ETag)
	}

	if err == nil {
		if err := s.settle(notePath, entry, etag, contentHash(content)); err != nil {
			return nil, err
		}
		report.Pushed++
		return nil, nil
	}

	if !errors.Is(err, opencloud.ErrConflict) {
		return nil, err
	}
	return s.resolveConflict(ctx, remote, notePath, content, entry.BaseHash, report)
}

// settle enregistre qu'une note est alignée sur le serveur : ETag et base
// décrivent désormais la version distante.
//
// sent est l'entrée telle qu'elle était au moment de l'envoi. La note a pu
// être modifiée depuis : elle reste alors sale, et une nouvelle écriture est
// déjà en file. L'ETag et la base, eux, décrivent le serveur et se posent dans
// tous les cas.
func (s *Store) settle(notePath string, sent Entry, etag, hash string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if current, ok := s.entries[notePath]; ok {
		current.ETag = etag
		current.BaseHash = hash
		if current.Size == sent.Size && current.LocalMod.Equal(sent.LocalMod) {
			current.Dirty = false
		}
	}
	return s.save()
}

// resolveConflict traite une écriture refusée par le serveur.
//
// La résolution est volontairement bête et non destructive : la version du
// serveur devient la note de référence, et la version locale est conservée
// dans une copie voisine. Aucune fusion automatique, donc aucune façon de
// perdre du texte que l'utilisateur avait écrit.
//
// Encore faut-il qu'il y ait un conflit. Un refus du serveur dit seulement que
// la version distante a bougé, pas que la locale a quelque chose à opposer :
// il faut confronter trois versions, et baseHash porte la troisième — celle
// sur laquelle les deux côtés étaient d'accord. Sans elle, une note simplement
// périmée produisait une copie.
func (s *Store) resolveConflict(ctx context.Context, remote Remote, notePath string, local []byte, baseHash string, report *Report) (*Conflict, error) {
	serverContent, serverETag, err := remote.Read(ctx, notePath)

	// Le serveur n'a plus la note : elle a été supprimée ailleurs — interface
	// web, autre appareil — pendant que la modification locale attendait son
	// tour. Il n'y a alors aucune version à arbitrer, seulement un texte que
	// l'utilisateur a écrit et qu'on ne jette pas. Il repart comme une
	// création, exactement comme une note écrite hors connexion.
	//
	// La note réapparaît donc côté serveur, et c'est le comportement voulu :
	// entre perdre une suppression et perdre un texte, on perd la suppression,
	// qui se refait d'un geste.
	//
	// Sans ce cas, l'erreur remontait jusqu'à Push, qui laissait l'opération en
	// tête de file : chaque passe rejouait la même lecture, échouait pareil, et
	// toute la file restait derrière. Une note supprimée depuis l'interface web
	// suffisait à arrêter la synchronisation de l'appareil, sans que rien ne
	// désigne la coupable.
	if errors.Is(err, opencloud.ErrNotFound) {
		etag, err := remote.SaveNew(ctx, notePath, local)
		if err != nil {
			return nil, err
		}
		report.Pushed++
		return nil, s.Accept(notePath, local, etag)
	}
	if err != nil {
		return nil, fmt.Errorf("store: lecture de la version serveur de %s: %w", notePath, err)
	}

	// Si les deux versions sont identiques, il n'y a pas de conflit réel :
	// l'ETag local était simplement périmé (écriture faite par cette même
	// application depuis un autre appareil, ou passe précédente interrompue).
	if string(serverContent) == string(local) {
		if err := s.Accept(notePath, serverContent, serverETag); err != nil {
			return nil, err
		}
		return nil, nil
	}

	// La version locale est encore celle sur laquelle les deux côtés étaient
	// d'accord : elle n'a rien à opposer à celle du serveur, qui l'emporte
	// donc en silence. En conserver une copie ne sauverait aucun texte — elle
	// ne contient rien que le serveur n'ait déjà eu — et donnerait à
	// l'utilisateur un doublon à trier pour une modification qu'il n'a pas
	// faite.
	//
	// Une base vide vient d'un index écrit avant que le champ n'existe : on ne
	// sait alors rien, et l'ancien comportement — conserver — s'applique.
	if baseHash != "" && contentHash(local) == baseHash {
		return nil, s.Accept(notePath, serverContent, serverETag)
	}

	copyPath := conflictPath(notePath, time.Now())
	copyETag, err := remote.Save(ctx, copyPath, local, "")
	if err != nil {
		return nil, fmt.Errorf("store: [%s] sauvegarde de la version locale de %s: %w", CodeStorageIO, notePath, err)
	}

	if err := s.Accept(notePath, serverContent, serverETag); err != nil {
		return nil, err
	}
	if err := s.Accept(copyPath, local, copyETag); err != nil {
		return nil, err
	}
	if err := s.MarkConflict(copyPath); err != nil {
		return nil, err
	}

	conflict, err := s.recordConflict(OpWrite, notePath, copyPath, serverETag)
	if err != nil {
		return nil, err
	}
	return &conflict, nil
}

// conflictPath construit le nom de la copie de secours.
//
// L'horodatage n'utilise pas de deux-points : ils sont interdits dans un nom
// de fichier sous Windows, et le cache local doit pouvoir écrire cette copie.
func conflictPath(notePath string, at time.Time) string {
	dir, file := path.Split(notePath)
	ext := path.Ext(file)
	base := strings.TrimSuffix(file, ext)
	stamp := at.Format("2006-01-02T15-04-05")
	return dir + fmt.Sprintf("%s (conflit %s)%s", base, stamp, ext)
}

// Pull rafraîchit une note depuis le serveur.
//
// Une modification locale non poussée n'est jamais écrasée : la note reste
// telle quelle et sera confrontée au serveur lors du prochain Push, où le
// mécanisme de conflit s'appliquera.
func (s *Store) Pull(ctx context.Context, remote Remote, notePath string) error {
	s.mu.Lock()
	entry, known := s.entries[notePath]
	dirty := known && entry.Dirty
	s.mu.Unlock()

	if dirty {
		return nil
	}

	content, etag, err := remote.Read(ctx, notePath)
	if err != nil {
		if errors.Is(err, opencloud.ErrNotFound) {
			return s.Forget(notePath)
		}
		return err
	}
	return s.Accept(notePath, content, etag)
}

// Clear vide le cache et la file. Sert à la déconnexion : rien de
// l'utilisateur précédent ne doit rester sur l'appareil.
func (s *Store) Clear() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if err := os.RemoveAll(s.notesDir()); err != nil {
		return fmt.Errorf("store: [%s] purge du cache: %w", CodeStorageIO, err)
	}
	if err := os.MkdirAll(s.notesDir(), 0o700); err != nil {
		return fmt.Errorf("store: [%s] recréation du cache: %w", CodeStorageIO, err)
	}

	s.entries = map[string]*Entry{}
	s.folders = map[string]bool{}
	s.queue = nil
	return s.save()
}
