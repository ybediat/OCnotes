package store

import (
	"sort"
	"strings"
	"time"
)

// Known est ce que le cache sait d'une note **sans forcément en avoir le
// contenu**.
//
// C'est la différence avec Entry, et elle est structurante : Entry porte un
// Cache, le nom du blob sur le disque, donc une note dont on a le texte. Known
// ne porte que de quoi l'afficher dans une liste. Un inventaire complet tient
// ainsi en quelques dizaines d'octets par note, là où mettre en cache le
// contenu de tout l'espace ne passerait pas à l'échelle.
//
// Deux cartes plutôt qu'un champ « contenu absent » dans Entry : chaque
// lecteur d'Entry existant suppose qu'un blob l'accompagne, et leur apprendre
// à s'en méfier un par un aurait été la vraie source de bugs.
type Known struct {
	Path    string    `json:"path"`
	ETag    string    `json:"etag,omitempty"`
	Size    int64     `json:"size"`
	ModTime time.Time `json:"modTime"`
}

// SetIndex remplace l'inventaire distant par celui qu'on vient de recevoir.
//
// # Ce que « remplacer » ne doit pas emporter
//
// L'inventaire du serveur est en retard sur le téléphone — d'environ 1,3 s
// quand il vient du service de recherche, indéfiniment quand on est hors
// connexion. L'appliquer tel quel ferait **disparaître de la liste la note que
// l'utilisateur vient d'écrire**, ce qui est le pire symptôme possible pour
// une application de notes : elle a l'air d'avoir perdu le travail.
//
// Trois règles corrigent ça, et elles sont ici plutôt que dans l'interface
// parce qu'elles se testent :
//
//  1. une note portant une modification locale non poussée est réinscrite,
//     avec ses valeurs locales ;
//  2. une note dont la suppression est en attente est retirée, même si le
//     serveur la voit encore ;
//  3. un dossier créé hors connexion est conservé, même absent du serveur.
func (s *Store) SetIndex(notes []Known, folders []string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	supprimees := map[string]bool{}
	for _, op := range s.queue {
		if op.Kind == OpDelete {
			supprimees[op.Path] = true
		}
	}

	s.known = make(map[string]*Known, len(notes))
	for _, n := range notes {
		if supprimeeOuDedans(supprimees, n.Path) {
			continue
		}
		copie := n
		s.known[n.Path] = &copie
	}

	// Règle 1 : ce que le cache a de plus frais que le serveur.
	for chemin, e := range s.entries {
		if !e.Dirty || supprimeeOuDedans(supprimees, chemin) {
			continue
		}
		s.known[chemin] = &Known{Path: chemin, Size: e.Size, ModTime: e.LocalMod}
	}

	// Règle 3 : les dossiers en attente de création survivent au remplacement.
	enAttente := map[string]bool{}
	for _, op := range s.queue {
		if op.Kind == OpMkdir {
			enAttente[op.Path] = true
		}
	}
	s.folders = map[string]bool{}
	for _, d := range folders {
		if supprimeeOuDedans(supprimees, d) {
			continue
		}
		s.rememberFolderLocked(d)
	}
	for d := range enAttente {
		s.rememberFolderLocked(d)
	}

	s.indexed = true
	return s.save()
}

// Index renvoie l'inventaire connu, trié par chemin.
//
// Les notes dont le cache détient le contenu y figurent avec leurs valeurs
// locales quand elles sont modifiées : c'est la même règle que SetIndex, mais
// appliquée à la lecture, pour qu'une écriture faite depuis le dernier
// inventaire soit visible sans attendre le prochain.
func (s *Store) Index() []Known {
	s.mu.Lock()
	defer s.mu.Unlock()

	out := make(map[string]Known, len(s.known))
	for chemin, k := range s.known {
		out[chemin] = *k
	}
	for chemin, e := range s.entries {
		if !e.Dirty {
			continue
		}
		out[chemin] = Known{Path: chemin, Size: e.Size, ModTime: e.LocalMod}
	}

	liste := make([]Known, 0, len(out))
	for _, k := range out {
		liste = append(liste, k)
	}
	sort.Slice(liste, func(i, j int) bool { return liste[i].Path < liste[j].Path })
	return liste
}

// HasIndex dit si un inventaire a déjà été constitué.
//
// Un inventaire vide et un inventaire jamais fait ne veulent pas dire la même
// chose : le premier signifie « aucune note », le second « je ne sais pas
// encore ». L'interface doit pouvoir les distinguer pour ne pas annoncer une
// bibliothèque vide au premier démarrage hors connexion.
func (s *Store) HasIndex() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.indexed
}

// forgetKnownLocked retire un chemin et sa descendance de l'inventaire.
func (s *Store) forgetKnownLocked(itemPath string) {
	for chemin := range s.known {
		if chemin == itemPath || strings.HasPrefix(chemin, itemPath+"/") {
			delete(s.known, chemin)
		}
	}
}

// renameKnownLocked suit un déplacement dans l'inventaire.
func (s *Store) renameKnownLocked(from, to string) {
	for chemin, k := range s.known {
		suffixe, ok := sousChemin(chemin, from)
		if !ok {
			continue
		}
		delete(s.known, chemin)
		nouveau := to + suffixe
		copie := *k
		copie.Path = nouveau
		s.known[nouveau] = &copie
	}
}

// sousChemin dit si chemin est racine ou descendant de base, et renvoie ce qui
// suit base.
func sousChemin(chemin, base string) (string, bool) {
	if chemin == base {
		return "", true
	}
	if strings.HasPrefix(chemin, base+"/") {
		return chemin[len(base):], true
	}
	return "", false
}

// supprimeeOuDedans dit si un chemin est visé par une suppression en attente,
// directement ou parce qu'un de ses ancêtres l'est.
func supprimeeOuDedans(supprimees map[string]bool, chemin string) bool {
	for supprime := range supprimees {
		if _, ok := sousChemin(chemin, supprime); ok {
			return true
		}
	}
	return false
}
