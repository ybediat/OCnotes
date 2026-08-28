package notes

import (
	"context"
	"errors"
	"testing"

	"opennote/internal/opencloud"
)

func newLibrary(t *testing.T, root string, seed ...string) (*Library, *fakeBackend) {
	t.Helper()
	backend := newFakeBackend()
	backend.seed(seed...)

	lib, err := NewLibrary(backend, root)
	if err != nil {
		t.Fatalf("NewLibrary: %v", err)
	}
	return lib, backend
}

func TestBootstrapCreeLaRacine(t *testing.T) {
	lib, backend := newLibrary(t, DefaultRoot)

	if err := lib.Bootstrap(context.Background()); err != nil {
		t.Fatalf("Bootstrap: %v", err)
	}
	if !backend.dirs["Notes"] {
		t.Error("le dossier Notes n'a pas été créé")
	}

	// Idempotent : l'application l'appelle à chaque démarrage.
	if err := lib.Bootstrap(context.Background()); err != nil {
		t.Errorf("Bootstrap rejoué: %v", err)
	}
}

func TestBootstrapSurRacineExistante(t *testing.T) {
	lib, _ := newLibrary(t, "Documents/Mes notes", "Documents/Mes notes/deja.md")

	if err := lib.Bootstrap(context.Background()); err != nil {
		t.Fatalf("Bootstrap: %v", err)
	}
	listing, err := lib.List(context.Background(), "")
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(listing.Notes) != 1 || listing.Notes[0].Name != "deja.md" {
		t.Errorf("listing = %+v, attendu la note existante", listing)
	}
}

// Les chemins exposés par la bibliothèque sont relatifs au dossier de notes,
// pas à l'espace : l'interface ne doit jamais voir la racine.
func TestListCheminsRelatifsALaRacine(t *testing.T) {
	lib, _ := newLibrary(t, "Notes",
		"Notes/a.md",
		"Notes/Projets/b.md",
	)

	listing, err := lib.List(context.Background(), "")
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(listing.Folders) != 1 || listing.Folders[0].Path != "Projets" {
		t.Fatalf("dossiers = %+v, attendu Projets", listing.Folders)
	}
	if len(listing.Notes) != 1 || listing.Notes[0].Path != "a.md" {
		t.Fatalf("notes = %+v, attendu a.md", listing.Notes)
	}

	sub, err := lib.List(context.Background(), "Projets")
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(sub.Notes) != 1 || sub.Notes[0].Path != "Projets/b.md" {
		t.Errorf("notes = %+v, attendu Projets/b.md", sub.Notes)
	}
}

// Un fichier déposé depuis l'interface web ne doit pas apparaître comme une
// note illisible.
func TestListIgnoreLesNonNotes(t *testing.T) {
	lib, _ := newLibrary(t, "Notes",
		"Notes/note.md",
		"Notes/lisible.markdown",
		"Notes/photo.png",
		"Notes/tableau.xlsx",
		"Notes/.cache.md",
	)

	listing, err := lib.List(context.Background(), "")
	if err != nil {
		t.Fatalf("List: %v", err)
	}

	got := map[string]bool{}
	for _, n := range listing.Notes {
		got[n.Name] = true
	}
	if !got["note.md"] || !got["lisible.markdown"] {
		t.Errorf("les notes attendues sont absentes: %+v", listing.Notes)
	}
	if got["photo.png"] || got["tableau.xlsx"] {
		t.Errorf("un fichier qui n'est pas une note a été listé: %+v", listing.Notes)
	}
	if got[".cache.md"] {
		t.Error("un fichier caché a été listé")
	}
}

func TestListTriDossiersPuisNotes(t *testing.T) {
	lib, _ := newLibrary(t, "Notes",
		"Notes/zebre.md",
		"Notes/Alpha.md",
		"Notes/beta.md",
		"Notes/Zoo/x.md",
		"Notes/archives/y.md",
	)

	listing, err := lib.List(context.Background(), "")
	if err != nil {
		t.Fatalf("List: %v", err)
	}

	wantFolders := []string{"archives", "Zoo"}
	for i, want := range wantFolders {
		if listing.Folders[i].Name != want {
			t.Errorf("dossier[%d] = %q, attendu %q", i, listing.Folders[i].Name, want)
		}
	}
	wantNotes := []string{"Alpha.md", "beta.md", "zebre.md"}
	for i, want := range wantNotes {
		if listing.Notes[i].Name != want {
			t.Errorf("note[%d] = %q, attendu %q", i, listing.Notes[i].Name, want)
		}
	}
}

func TestCreateAjouteLExtension(t *testing.T) {
	lib, _ := newLibrary(t, "Notes")
	if err := lib.Bootstrap(context.Background()); err != nil {
		t.Fatalf("Bootstrap: %v", err)
	}

	note, err := lib.Create(context.Background(), "", "Ma réunion", []byte("# Ma réunion\n"))
	if err != nil {
		t.Fatalf("Create: %v", err)
	}
	if note.Name != "Ma réunion.md" {
		t.Errorf("Name = %q, attendu \"Ma réunion.md\"", note.Name)
	}
	if note.DisplayName != "Ma réunion" {
		t.Errorf("DisplayName = %q", note.DisplayName)
	}
	if note.Path != "Ma réunion.md" {
		t.Errorf("Path = %q", note.Path)
	}
}

// Deux notes créées d'affilée depuis le même titre ne doivent pas s'écraser.
func TestCreateEviteLesCollisions(t *testing.T) {
	lib, _ := newLibrary(t, "Notes")
	ctx := context.Background()
	if err := lib.Bootstrap(ctx); err != nil {
		t.Fatalf("Bootstrap: %v", err)
	}

	want := []string{"Note.md", "Note (2).md", "Note (3).md"}
	for i, expected := range want {
		note, err := lib.Create(ctx, "", "Note", []byte("contenu"))
		if err != nil {
			t.Fatalf("Create %d: %v", i, err)
		}
		if note.Name != expected {
			t.Errorf("création %d : Name = %q, attendu %q", i+1, note.Name, expected)
		}
	}
}

func TestCreateRefuseUnNomInvalide(t *testing.T) {
	lib, _ := newLibrary(t, "Notes")
	if _, err := lib.Create(context.Background(), "", "note/interdite", nil); err == nil {
		t.Error("un nom contenant un slash aurait dû être refusé")
	}
}

func TestSaveDetecteLeConflit(t *testing.T) {
	lib, _ := newLibrary(t, "Notes", "Notes/a.md")
	ctx := context.Background()

	_, etag, err := lib.Read(ctx, "a.md")
	if err != nil {
		t.Fatalf("Read: %v", err)
	}

	nouveau, err := lib.Save(ctx, "a.md", []byte("v2"), etag)
	if err != nil {
		t.Fatalf("Save avec l'ETag courant: %v", err)
	}
	if nouveau == etag {
		t.Error("l'ETag n'a pas changé après écriture")
	}

	if _, err := lib.Save(ctx, "a.md", []byte("v3"), etag); !errors.Is(err, opencloud.ErrConflict) {
		t.Errorf("Save avec un ETag périmé : erreur = %v, attendu ErrConflict", err)
	}
}

func TestRenamePreserveLExtension(t *testing.T) {
	lib, _ := newLibrary(t, "Notes", "Notes/Projets/ancienne.md")
	ctx := context.Background()

	// L'utilisateur saisit un titre, pas un nom de fichier.
	newPath, err := lib.Rename(ctx, "Projets/ancienne.md", "nouvelle")
	if err != nil {
		t.Fatalf("Rename: %v", err)
	}
	if newPath != "Projets/nouvelle.md" {
		t.Errorf("chemin = %q, attendu \"Projets/nouvelle.md\"", newPath)
	}

	if _, _, err := lib.Read(ctx, newPath); err != nil {
		t.Errorf("la note renommée est illisible: %v", err)
	}
	if _, _, err := lib.Read(ctx, "Projets/ancienne.md"); !errors.Is(err, opencloud.ErrNotFound) {
		t.Error("l'ancien chemin répond encore")
	}
}

func TestRenameDossierSansExtension(t *testing.T) {
	lib, _ := newLibrary(t, "Notes", "Notes/Ancien/a.md")

	newPath, err := lib.Rename(context.Background(), "Ancien", "Nouveau")
	if err != nil {
		t.Fatalf("Rename: %v", err)
	}
	if newPath != "Nouveau" {
		t.Errorf("chemin = %q, attendu \"Nouveau\"", newPath)
	}
}

func TestMove(t *testing.T) {
	lib, _ := newLibrary(t, "Notes", "Notes/a.md", "Notes/Archives/")
	ctx := context.Background()

	newPath, err := lib.Move(ctx, "a.md", "Archives")
	if err != nil {
		t.Fatalf("Move: %v", err)
	}
	if newPath != "Archives/a.md" {
		t.Errorf("chemin = %q", newPath)
	}
}

// Déplacer un dossier dans son propre sous-arbre le détacherait de la
// hiérarchie : l'opération doit être refusée avant d'atteindre le serveur.
func TestMoveRefuseDansLuiMeme(t *testing.T) {
	lib, _ := newLibrary(t, "Notes", "Notes/Projets/2026/a.md")
	ctx := context.Background()

	if _, err := lib.Move(ctx, "Projets", "Projets/2026"); err == nil {
		t.Error("déplacer un dossier dans son propre sous-arbre aurait dû être refusé")
	}
	if _, err := lib.Move(ctx, "Projets", "Projets"); err == nil {
		t.Error("déplacer un dossier dans lui-même aurait dû être refusé")
	}
}

func TestDeleteRefuseLaRacine(t *testing.T) {
	lib, _ := newLibrary(t, "Notes", "Notes/a.md")

	for _, p := range []string{"", ".", "/", "..", "a.md/.."} {
		if err := lib.Delete(context.Background(), p); err == nil {
			t.Errorf("Delete(%q) aurait dû être refusé", p)
		}
	}
}

func TestDeleteDossierRecursif(t *testing.T) {
	lib, backend := newLibrary(t, "Notes", "Notes/Projets/2026/a.md", "Notes/garde.md")
	ctx := context.Background()

	if err := lib.Delete(ctx, "Projets"); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	if _, ok := backend.files["Notes/Projets/2026/a.md"]; ok {
		t.Error("la note du sous-dossier existe encore")
	}
	if _, ok := backend.files["Notes/garde.md"]; !ok {
		t.Error("une note hors du dossier supprimé a disparu")
	}
}

// Un chemin remontant au-dessus de la racine ne doit jamais atteindre le reste
// de l'espace.
func TestCheminsNeSortentPasDeLaRacine(t *testing.T) {
	lib, backend := newLibrary(t, "Notes", "Notes/a.md", "hors-notes.md")
	ctx := context.Background()

	if err := lib.Delete(ctx, "../hors-notes.md"); err == nil {
		if _, ok := backend.files["hors-notes.md"]; !ok {
			t.Fatal("une note hors du dossier de notes a été supprimée")
		}
	}
	if _, ok := backend.files["hors-notes.md"]; !ok {
		t.Error("le fichier hors racine a été atteint")
	}
}

func TestRacineVideDesigneLEspace(t *testing.T) {
	lib, _ := newLibrary(t, "", "a.md", "Dossier/b.md")

	listing, err := lib.List(context.Background(), "")
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(listing.Notes) != 1 || listing.Notes[0].Path != "a.md" {
		t.Errorf("notes = %+v", listing.Notes)
	}
	if len(listing.Folders) != 1 || listing.Folders[0].Path != "Dossier" {
		t.Errorf("dossiers = %+v", listing.Folders)
	}
}

func TestTitleOf(t *testing.T) {
	note := Note{Name: "reunion.md", DisplayName: "reunion"}

	if got := TitleOf(note, []byte("# Réunion du 15\n\ntexte")); got != "Réunion du 15" {
		t.Errorf("TitleOf = %q, attendu le titre du contenu", got)
	}
	if got := TitleOf(note, []byte("")); got != "reunion" {
		t.Errorf("TitleOf = %q, attendu le nom du fichier", got)
	}
}
