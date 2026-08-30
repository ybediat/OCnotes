package mobile

import (
	"errors"
	"time"

	"github.com/ybediat/OpenNote/internal/notes"
	"github.com/ybediat/OpenNote/internal/opencloud"
	"github.com/ybediat/OpenNote/internal/store"
)

// folderRef décrit un dossier pour le sélecteur de destination.
//
// Le dossier de notes lui-même a un chemin vide : c'est la racine, et
// l'interface lui donne le nom qu'elle affiche déjà en titre. Le lui faire
// traverser la frontière obligerait à choisir un nom en Go pour un libellé qui
// est déjà connu de Kotlin.
type folderRef struct {
	Path string `json:"path"`
	Name string `json:"name"`
}

// ListAllJSON renvoie l'inventaire complet du dossier de notes, à plat.
//
// Toutes les notes, quel que soit leur dossier, sans aucune entrée de type
// dossier : c'est une liste de notes, pas un arbre. Le dossier de chaque note
// se lit dans son `path`, dont il est le préfixe — inutile de le dupliquer
// dans un champ, et le contrat de la façade reste inchangé.
//
// # Ce que la fraîcheur veut dire ici
//
// L'inventaire vient du serveur quand il répond, du cache sinon. Dans les deux
// cas il est **fusionné avec ce que le cache sait de plus récent** : une note
// écrite il y a deux secondes n'est pas encore dans l'index du serveur, et la
// faire disparaître de la liste serait le pire symptôme possible.
//
// `fromCache` signale que le réseau a manqué, comme pour ListFolderJSON.
func (a *App) ListAllJSON() (string, error) {
	a.mu.Lock()
	lib := a.lib
	a.mu.Unlock()

	if lib == nil {
		return "", errNoWorkspace()
	}

	ctx, cancel := a.ctx()
	defer cancel()

	index, err := lib.ListAll(ctx)
	if err != nil {
		a.noteNetworkResult(err)
		// Le cache prend le relais dès qu'un inventaire a été constitué une
		// fois. Sans inventaire, mieux vaut remonter l'erreur que d'annoncer
		// une bibliothèque vide.
		if a.cache.HasIndex() {
			return toJSON(a.listingDepuisIndex(true))
		}
		return "", err
	}
	a.noteNetworkResult(nil)

	connues := make([]store.Known, 0, len(index.Notes))
	for _, n := range index.Notes {
		connues = append(connues, store.Known{Path: n.Path, ETag: n.ETag, Size: n.Size, ModTime: n.ModTime})
	}
	dossiers := make([]string, 0, len(index.Folders))
	for _, f := range index.Folders {
		dossiers = append(dossiers, f.Path)
	}
	if err := a.cache.SetIndex(connues, dossiers); err != nil {
		return "", err
	}

	return toJSON(a.listingDepuisIndex(false))
}

// listingDepuisIndex met en forme l'inventaire du cache.
//
// La lecture passe toujours par le cache, même quand le serveur vient de
// répondre : c'est lui qui applique la fusion avec les écritures locales, et
// avoir deux chemins de mise en forme ferait diverger la liste selon l'état du
// réseau.
func (a *App) listingDepuisIndex(fromCache bool) folderListing {
	out := folderListing{Path: "", FromCache: fromCache, Entries: []folderEntry{}}

	for _, k := range a.cache.Index() {
		nom := lastSegment(k.Path)
		entry, cached := a.cache.CachedEntry(k.Path)
		out.Entries = append(out.Entries, folderEntry{
			Path:    k.Path,
			Name:    nom,
			Display: notes.DisplayName(nom),
			Size:    k.Size,
			ModTime: k.ModTime.UTC().Format(time.RFC3339),
			Pending: cached && entry.Dirty,
		})
	}
	return out
}

// FoldersJSON renvoie tous les dossiers connus, pour choisir une destination.
//
// La racine du dossier de notes en fait partie, avec un chemin vide : créer
// une note à la racine est le cas le plus courant, et l'absenter du sélecteur
// obligerait l'interface à l'y rajouter elle-même.
//
// La liste vient du cache, jamais du réseau : un sélecteur doit s'ouvrir tout
// de suite. Elle est alimentée par ListAllJSON et par la navigation.
func (a *App) FoldersJSON() (string, error) {
	a.mu.Lock()
	lib := a.lib
	a.mu.Unlock()

	if lib == nil {
		return "", errNoWorkspace()
	}

	out := []folderRef{{Path: "", Name: ""}}
	for _, d := range a.cache.Folders() {
		out = append(out, folderRef{Path: d, Name: lastSegment(d)})
	}
	return toJSON(out)
}

// RefreshIndex reconstruit l'inventaire sans rien renvoyer.
//
// Sert au travailleur de synchronisation : il vient de pousser des écritures,
// donc l'inventaire du serveur a changé, mais personne ne regarde l'écran.
// Renvoyer le listing entier pour le jeter serait du travail perdu.
func (a *App) RefreshIndex() error {
	_, err := a.ListAllJSON()
	if err != nil && errors.Is(err, opencloud.ErrOffline) {
		// Hors connexion, il n'y a rien à reconstruire et rien à signaler :
		// l'inventaire précédent reste valable.
		return nil
	}
	return err
}

// lastSegment renvoie le dernier segment d'un chemin.
func lastSegment(p string) string {
	for i := len(p) - 1; i >= 0; i-- {
		if p[i] == '/' {
			return p[i+1:]
		}
	}
	return p
}
