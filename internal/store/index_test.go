package store

import (
	"testing"
	"time"
)

var jadis = time.Date(2026, 8, 20, 9, 0, 0, 0, time.UTC)

// cheminsIndex liste l'inventaire, dans l'ordre où le cache le rend.
func cheminsIndex(s *Store) []string {
	out := make([]string, 0)
	for _, k := range s.Index() {
		out = append(out, k.Path)
	}
	return out
}

func contient(liste []string, cible string) bool {
	for _, v := range liste {
		if v == cible {
			return true
		}
	}
	return false
}

func TestIndexVideAvantToutInventaire(t *testing.T) {
	s := newStore(t)
	if s.HasIndex() {
		t.Error("HasIndex vrai avant tout inventaire")
	}
	if got := cheminsIndex(s); len(got) != 0 {
		t.Errorf("inventaire = %v, attendu vide", got)
	}
}

func TestSetIndexPuisIndex(t *testing.T) {
	s := newStore(t)

	err := s.SetIndex([]Known{
		{Path: "a.md", Size: 10, ModTime: jadis},
		{Path: "dossier/b.md", Size: 20, ModTime: jadis},
	}, []string{"dossier"})
	if err != nil {
		t.Fatalf("SetIndex: %v", err)
	}

	if !s.HasIndex() {
		t.Error("HasIndex faux après un inventaire")
	}
	attendu := []string{"a.md", "dossier/b.md"}
	if got := cheminsIndex(s); len(got) != 2 || got[0] != attendu[0] || got[1] != attendu[1] {
		t.Errorf("inventaire = %v, attendu %v", got, attendu)
	}
	if got := s.Folders(); len(got) != 1 || got[0] != "dossier" {
		t.Errorf("dossiers = %v, attendu [dossier]", got)
	}
}

// LA règle. L'inventaire du serveur retarde de ~1,3 s sur une écriture : s'il
// écrasait l'état local, la note qu'on vient de créer disparaîtrait de la
// liste, et l'application aurait l'air d'avoir perdu le travail.
func TestSetIndexNEffacePasUneNoteEcriteLocalement(t *testing.T) {
	s := newStore(t)

	if err := s.Put("toute-neuve.md", []byte("# Toute neuve\n")); err != nil {
		t.Fatalf("Put: %v", err)
	}

	// L'inventaire arrive du serveur sans elle : elle vient d'être écrite.
	if err := s.SetIndex([]Known{{Path: "ancienne.md", Size: 5, ModTime: jadis}}, nil); err != nil {
		t.Fatalf("SetIndex: %v", err)
	}

	if got := cheminsIndex(s); !contient(got, "toute-neuve.md") {
		t.Fatalf("la note écrite localement a disparu de l'inventaire : %v", got)
	}
}

// Symétrique : une suppression pas encore poussée ne doit pas ressusciter.
func TestSetIndexNeRessuscitePasUneNoteSupprimee(t *testing.T) {
	s := newStore(t)

	if err := s.SetIndex([]Known{{Path: "a.md", Size: 1, ModTime: jadis}}, nil); err != nil {
		t.Fatalf("SetIndex: %v", err)
	}
	if err := s.Delete("a.md"); err != nil {
		t.Fatalf("Delete: %v", err)
	}

	// Le serveur la voit encore : la suppression n'est pas encore partie.
	if err := s.SetIndex([]Known{{Path: "a.md", Size: 1, ModTime: jadis}}, nil); err != nil {
		t.Fatalf("SetIndex: %v", err)
	}

	if got := cheminsIndex(s); contient(got, "a.md") {
		t.Fatalf("note supprimée localement revenue dans l'inventaire : %v", got)
	}
}

// Un dossier vide créé hors connexion n'existe nulle part ailleurs que dans le
// cache : un inventaire distant ne doit pas l'emporter.
func TestSetIndexConserveUnDossierCreeHorsConnexion(t *testing.T) {
	s := newStore(t)

	if err := s.EnsureFolder("brouillons"); err != nil {
		t.Fatalf("EnsureFolder: %v", err)
	}
	if err := s.SetIndex(nil, []string{"Projets"}); err != nil {
		t.Fatalf("SetIndex: %v", err)
	}

	dossiers := s.Folders()
	if !contient(dossiers, "brouillons") {
		t.Errorf("dossier créé hors connexion perdu : %v", dossiers)
	}
	if !contient(dossiers, "Projets") {
		t.Errorf("dossier du serveur absent : %v", dossiers)
	}
}

// Une note modifiée après le dernier inventaire doit apparaître avec sa date
// locale, sans attendre l'inventaire suivant.
func TestIndexPrefereLEtatLocalQuandIlEstPlusFrais(t *testing.T) {
	s := newStore(t)

	if err := s.SetIndex([]Known{{Path: "a.md", Size: 3, ModTime: jadis}}, nil); err != nil {
		t.Fatalf("SetIndex: %v", err)
	}
	if err := s.Put("a.md", []byte("beaucoup plus long qu'avant")); err != nil {
		t.Fatalf("Put: %v", err)
	}

	for _, k := range s.Index() {
		if k.Path != "a.md" {
			continue
		}
		if k.Size == 3 {
			t.Error("taille du serveur conservée alors que le cache est plus frais")
		}
		if !k.ModTime.After(jadis) {
			t.Errorf("date = %v, attendue postérieure à %v", k.ModTime, jadis)
		}
		return
	}
	t.Fatal("a.md absente de l'inventaire")
}

// L'inventaire est la seule chose qui rende la liste plate utilisable au
// démarrage hors connexion : il doit survivre à la fermeture.
func TestIndexSurvitALaReouverture(t *testing.T) {
	dir := t.TempDir()

	s, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := s.SetIndex([]Known{{Path: "a.md", Size: 7, ModTime: jadis}}, []string{"d"}); err != nil {
		t.Fatalf("SetIndex: %v", err)
	}

	relu, err := Open(dir)
	if err != nil {
		t.Fatalf("réouverture: %v", err)
	}
	if !relu.HasIndex() {
		t.Error("HasIndex faux après réouverture")
	}
	if got := cheminsIndex(relu); len(got) != 1 || got[0] != "a.md" {
		t.Errorf("inventaire relu = %v, attendu [a.md]", got)
	}
	if got := relu.Folders(); len(got) != 1 || got[0] != "d" {
		t.Errorf("dossiers relus = %v, attendu [d]", got)
	}
}

func TestIndexSuitRenommageEtSuppression(t *testing.T) {
	s := newStore(t)

	err := s.SetIndex([]Known{
		{Path: "dossier/a.md", Size: 1, ModTime: jadis},
		{Path: "dossier/b.md", Size: 1, ModTime: jadis},
	}, []string{"dossier"})
	if err != nil {
		t.Fatalf("SetIndex: %v", err)
	}

	if err := s.RenameLocal("dossier/a.md", "dossier/renommee.md"); err != nil {
		t.Fatalf("RenameLocal: %v", err)
	}
	got := cheminsIndex(s)
	if contient(got, "dossier/a.md") || !contient(got, "dossier/renommee.md") {
		t.Errorf("renommage non suivi dans l'inventaire : %v", got)
	}

	if err := s.Forget("dossier/b.md"); err != nil {
		t.Fatalf("Forget: %v", err)
	}
	if got := cheminsIndex(s); contient(got, "dossier/b.md") {
		t.Errorf("suppression non suivie dans l'inventaire : %v", got)
	}
}

// Un dossier renommé emporte son contenu, dans l'inventaire comme ailleurs.
func TestIndexSuitLeRenommageDUnDossier(t *testing.T) {
	s := newStore(t)

	err := s.SetIndex([]Known{
		{Path: "vieux/a.md", Size: 1, ModTime: jadis},
		{Path: "vieux/sous/b.md", Size: 1, ModTime: jadis},
	}, []string{"vieux", "vieux/sous"})
	if err != nil {
		t.Fatalf("SetIndex: %v", err)
	}

	if err := s.RenameLocal("vieux", "neuf"); err != nil {
		t.Fatalf("RenameLocal: %v", err)
	}

	got := cheminsIndex(s)
	if !contient(got, "neuf/a.md") || !contient(got, "neuf/sous/b.md") {
		t.Errorf("descendance non suivie : %v", got)
	}
	if contient(got, "vieux/a.md") {
		t.Errorf("ancien chemin conservé : %v", got)
	}
}
