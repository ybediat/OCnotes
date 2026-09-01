package notes

import (
	"context"
	"errors"
	"sort"
	"strings"

	"github.com/ybediat/OpenNote/internal/opencloud"
)

// Searcher est la partie facultative d'un Backend qui sait énumérer tout
// l'espace en une requête.
//
// Elle est séparée de Backend à dessein : tous les serveurs ne l'offrent pas —
// le service de recherche s'éteint au déploiement — et une implémentation en
// mémoire n'a aucune raison de la fournir. ListAll teste donc la capacité
// plutôt que de l'exiger.
type Searcher interface {
	SearchAll(ctx context.Context, limit int) ([]opencloud.Resource, error)
}

// Index est l'inventaire complet du dossier de notes : toutes les notes, où
// qu'elles soient, et tous les dossiers.
//
// Il ne porte aucun contenu. C'est ce qui permet de le tenir en mémoire et de
// le persister : quelques dizaines d'octets par note, pas des mégaoctets.
type Index struct {
	Notes   []Note
	Folders []Folder

	// FromSearch dit par quel chemin l'inventaire a été obtenu. Utile au
	// diagnostic : les deux chemins n'ont ni le même coût ni la même
	// fraîcheur.
	FromSearch bool
}

// ListAll dresse l'inventaire de tout le dossier de notes.
//
// Deux chemins, dans cet ordre :
//
//  1. Le service de recherche du serveur, quand le backend l'expose : une
//     requête pour tout l'arbre, mesurée à ~90 ms là où le parcours en
//     demandait 260.
//  2. Un parcours récursif en PROPFIND Depth 1, une requête par dossier.
//
// Le repli n'est pas une précaution de principe : le service de recherche
// peut être désactivé au déploiement, et PROPFIND Depth: infinity — qui
// aurait évité les deux — est refusé par le serveur.
//
// **L'inventaire issu de la recherche retarde d'environ 1,3 seconde sur une
// écriture.** ListAll ne corrige pas ce décalage : c'est au cache local, qui
// sait ce qui vient d'être écrit, de primer sur ce que l'index raconte.
func (l *Library) ListAll(ctx context.Context) (Index, error) {
	if s, ok := l.backend.(Searcher); ok {
		index, err := l.listAllViaSearch(ctx, s)
		if err == nil {
			return index, nil
		}
		// Hors connexion, le parcours échouerait pareillement, en plus lent.
		if errors.Is(err, opencloud.ErrOffline) {
			return Index{}, err
		}
	}

	index, err := l.listAllViaWalk(ctx)
	if err != nil {
		return Index{}, err
	}
	return index, nil
}

// listAllViaSearch convertit le résultat du service de recherche.
//
// Le serveur renvoie tout l'espace quel que soit le chemin interrogé : la
// restriction au dossier de notes se fait donc ici, et nulle part ailleurs.
func (l *Library) listAllViaSearch(ctx context.Context, s Searcher) (Index, error) {
	resources, err := s.SearchAll(ctx, 0)
	if err != nil {
		return Index{}, err
	}

	index := Index{FromSearch: true}
	for _, r := range resources {
		relative, ok := l.within(r.Path)
		if !ok {
			continue
		}
		l.collect(&index, r, relative)
	}

	index.sort()
	return index, nil
}

// listAllViaWalk parcourt l'arborescence dossier par dossier.
//
// Le parcours est borné par le contexte, pas par une profondeur arbitraire :
// une limite en dur tronquerait l'inventaire en silence, ce qui est pire
// qu'une erreur franche.
func (l *Library) listAllViaWalk(ctx context.Context) (Index, error) {
	index := Index{}
	aVisiter := []string{""}
	vus := map[string]bool{"": true}

	for len(aVisiter) > 0 {
		if err := ctx.Err(); err != nil {
			return Index{}, err
		}

		dir := aVisiter[0]
		aVisiter = aVisiter[1:]

		listing, err := l.List(ctx, dir)
		if err != nil {
			// La racine est la seule dont l'échec est fatal : sans elle il n'y
			// a pas d'inventaire du tout. Un sous-dossier devenu illisible —
			// supprimé pendant le parcours, partage révoqué — ne doit pas
			// faire perdre le reste.
			if dir == "" {
				return Index{}, err
			}
			continue
		}

		index.Notes = append(index.Notes, listing.Notes...)
		for _, f := range listing.Folders {
			if vus[f.Path] {
				continue
			}
			vus[f.Path] = true
			index.Folders = append(index.Folders, f)
			aVisiter = append(aVisiter, f.Path)
		}
	}

	index.sort()
	return index, nil
}

// within ramène un chemin d'espace dans le référentiel du dossier de notes,
// et dit s'il en fait partie.
//
// La racine elle-même n'en fait pas partie : elle n'est pas une entrée de son
// propre inventaire.
func (l *Library) within(spacePath string) (string, bool) {
	if l.root == "" {
		return spacePath, spacePath != ""
	}
	if spacePath == l.root {
		return "", false
	}
	prefix := l.root + "/"
	if !strings.HasPrefix(spacePath, prefix) {
		return "", false
	}
	return strings.TrimPrefix(spacePath, prefix), true
}

// collect range une ressource dans l'inventaire, en appliquant les mêmes
// règles que List : les fichiers cachés et ce qui n'est pas une note sont
// écartés.
func (l *Library) collect(index *Index, r opencloud.Resource, relative string) {
	if hiddenAnywhere(relative) {
		return
	}
	if r.IsDir {
		index.Folders = append(index.Folders, Folder{Path: relative, Name: r.Name})
		return
	}
	if !IsNote(r.Name) {
		return
	}
	index.Notes = append(index.Notes, Note{
		Path:        relative,
		Name:        r.Name,
		DisplayName: DisplayName(r.Name),
		Size:        r.Size,
		ModTime:     r.ModTime,
		ETag:        r.ETag,
		FileID:      r.FileID,
	})
}

// hiddenAnywhere étend la règle de List à un chemin complet.
//
// Le parcours récursif n'entre jamais dans un dossier caché, donc n'en voit
// pas le contenu. La recherche, elle, renvoie l'arbre à plat : sans cette
// vérification sur chaque segment, une note rangée sous « .corbeille »
// apparaîtrait dans un inventaire et pas dans l'autre.
func hiddenAnywhere(p string) bool {
	for _, segment := range strings.Split(p, "/") {
		if isHidden(segment) {
			return true
		}
	}
	return false
}

func (i *Index) sort() {
	sort.Slice(i.Folders, func(a, b int) bool {
		return lessName(i.Folders[a].Path, i.Folders[b].Path)
	})
	sort.Slice(i.Notes, func(a, b int) bool {
		return lessName(i.Notes[a].Path, i.Notes[b].Path)
	})
}
