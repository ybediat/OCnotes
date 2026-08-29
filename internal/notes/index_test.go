package notes

import (
	"context"
	"errors"
	"strings"
	"testing"

	"opennote/internal/opencloud"
)

// chercheur enveloppe le backend en mémoire pour lui ajouter la capacité de
// recherche, en imitant les trois comportements constatés sur le vrai serveur :
// la racine de l'espace figure dans les résultats, le chemin interrogé est
// ignoré, et le service peut refuser de répondre.
type chercheur struct {
	*fakeBackend
	echec  error
	appels int
}

func (c *chercheur) SearchAll(ctx context.Context, limit int) ([]opencloud.Resource, error) {
	c.appels++
	if c.echec != nil {
		return nil, c.echec
	}

	out := []opencloud.Resource{{Path: "", IsDir: true}}
	for p := range c.fakeBackend.dirs {
		if p == "" {
			continue
		}
		out = append(out, opencloud.Resource{Path: p, Name: base(p), IsDir: true})
	}
	for p, f := range c.fakeBackend.files {
		out = append(out, opencloud.Resource{
			Path: p, Name: base(p), Size: int64(len(f.content)), ModTime: f.mod, ETag: f.etag,
		})
	}
	return out, nil
}

func base(p string) string {
	if i := strings.LastIndex(p, "/"); i >= 0 {
		return p[i+1:]
	}
	return p
}

func chemins(notes []Note) []string {
	out := make([]string, len(notes))
	for i, n := range notes {
		out[i] = n.Path
	}
	return out
}

func cheminsDossiers(dossiers []Folder) []string {
	out := make([]string, len(dossiers))
	for i, f := range dossiers {
		out[i] = f.Path
	}
	return out
}

func egales(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

const (
	// Arborescence commune aux deux chemins d'inventaire.
	racine = "Notes"
)

func semis() []string {
	return []string{
		"Notes/a.md",
		"Notes/b.txt",
		"Notes/Projets/c.md",
		"Notes/Projets/Archives/d.md",
		"Notes/Projets/vide/",
		"Notes/image.png",        // pas une note
		"Notes/.cache/e.md",      // dossier caché
		"Ailleurs/hors-sujet.md", // hors du dossier de notes
	}
}

// Le parcours récursif est le chemin de repli : il doit tout voir.
func TestListAllParParcours(t *testing.T) {
	lib, _ := newLibrary(t, racine, semis()...)

	index, err := lib.ListAll(context.Background())
	if err != nil {
		t.Fatalf("ListAll: %v", err)
	}
	if index.FromSearch {
		t.Error("FromSearch vrai alors que le backend ne sait pas chercher")
	}

	attendu := []string{"a.md", "b.txt", "Projets/Archives/d.md", "Projets/c.md"}
	if got := chemins(index.Notes); !egales(got, attendu) {
		t.Errorf("notes = %v, attendu %v", got, attendu)
	}

	attenduDossiers := []string{"Projets", "Projets/Archives", "Projets/vide"}
	if got := cheminsDossiers(index.Folders); !egales(got, attenduDossiers) {
		t.Errorf("dossiers = %v, attendu %v", got, attenduDossiers)
	}
}

// Le service de recherche renvoie tout l'espace : c'est ListAll qui restreint.
func TestListAllParRechercheRestreintAuDossierDeNotes(t *testing.T) {
	backend := newFakeBackend()
	backend.seed(semis()...)
	c := &chercheur{fakeBackend: backend}

	lib, err := NewLibrary(c, racine)
	if err != nil {
		t.Fatalf("NewLibrary: %v", err)
	}

	index, err := lib.ListAll(context.Background())
	if err != nil {
		t.Fatalf("ListAll: %v", err)
	}
	if !index.FromSearch {
		t.Error("FromSearch faux alors que la recherche a répondu")
	}
	if c.appels != 1 {
		t.Errorf("%d appels à la recherche, attendu 1", c.appels)
	}

	for _, n := range index.Notes {
		if strings.HasPrefix(n.Path, "..") || strings.Contains(n.Path, "hors-sujet") {
			t.Errorf("note hors du dossier de notes dans l'inventaire: %s", n.Path)
		}
	}
	attendu := []string{"a.md", "b.txt", "Projets/Archives/d.md", "Projets/c.md"}
	if got := chemins(index.Notes); !egales(got, attendu) {
		t.Errorf("notes = %v, attendu %v", got, attendu)
	}
}

// Les deux chemins doivent voir exactement la même chose. C'est la propriété
// qui autorise à basculer de l'un à l'autre sans que l'utilisateur le sente.
func TestListAllRechercheEtParcoursConcordent(t *testing.T) {
	backendA := newFakeBackend()
	backendA.seed(semis()...)
	libParcours, err := NewLibrary(backendA, racine)
	if err != nil {
		t.Fatalf("NewLibrary: %v", err)
	}

	backendB := newFakeBackend()
	backendB.seed(semis()...)
	libRecherche, err := NewLibrary(&chercheur{fakeBackend: backendB}, racine)
	if err != nil {
		t.Fatalf("NewLibrary: %v", err)
	}

	parcours, err := libParcours.ListAll(context.Background())
	if err != nil {
		t.Fatalf("parcours: %v", err)
	}
	recherche, err := libRecherche.ListAll(context.Background())
	if err != nil {
		t.Fatalf("recherche: %v", err)
	}

	if got, want := chemins(recherche.Notes), chemins(parcours.Notes); !egales(got, want) {
		t.Errorf("notes divergentes :\n  recherche %v\n  parcours  %v", got, want)
	}
	if got, want := cheminsDossiers(recherche.Folders), cheminsDossiers(parcours.Folders); !egales(got, want) {
		t.Errorf("dossiers divergents :\n  recherche %v\n  parcours  %v", got, want)
	}
}

// Le service de recherche peut être éteint au déploiement : son refus doit
// rester invisible pour l'appelant.
func TestListAllReplieSurLeParcoursSiLaRechercheRefuse(t *testing.T) {
	backend := newFakeBackend()
	backend.seed(semis()...)
	c := &chercheur{fakeBackend: backend, echec: opencloud.ErrSearchUnavailable}

	lib, err := NewLibrary(c, racine)
	if err != nil {
		t.Fatalf("NewLibrary: %v", err)
	}

	index, err := lib.ListAll(context.Background())
	if err != nil {
		t.Fatalf("ListAll: %v", err)
	}
	if index.FromSearch {
		t.Error("FromSearch vrai alors que la recherche a échoué")
	}
	attendu := []string{"a.md", "b.txt", "Projets/Archives/d.md", "Projets/c.md"}
	if got := chemins(index.Notes); !egales(got, attendu) {
		t.Errorf("notes = %v, attendu %v", got, attendu)
	}
}

// Hors connexion, le parcours échouerait pareillement, en plus lent : l'erreur
// remonte telle quelle pour que l'appelant serve son cache.
func TestListAllHorsConnexionNeRepliePas(t *testing.T) {
	backend := newFakeBackend()
	backend.seed(semis()...)
	c := &chercheur{fakeBackend: backend, echec: opencloud.ErrOffline}

	lib, err := NewLibrary(c, racine)
	if err != nil {
		t.Fatalf("NewLibrary: %v", err)
	}

	if _, err := lib.ListAll(context.Background()); !errors.Is(err, opencloud.ErrOffline) {
		t.Fatalf("erreur = %v, attendu ErrOffline", err)
	}
}
