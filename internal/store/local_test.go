package store

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// newLocalStore ouvre un cache déjà basculé en mode local.
func newLocalStore(t *testing.T) *Store {
	t.Helper()
	s := newStore(t)
	if err := s.SetLocalOnly(true); err != nil {
		t.Fatalf("SetLocalOnly: %v", err)
	}
	return s
}

func TestModeLocalNEnfileRien(t *testing.T) {
	s := newLocalStore(t)

	if err := s.Put("carnet.md", []byte("# Carnet")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if err := s.RenameLocal("carnet.md", "journal.md"); err != nil {
		t.Fatalf("RenameLocal: %v", err)
	}
	if err := s.Forget("journal.md"); err != nil {
		t.Fatalf("Forget: %v", err)
	}

	if pending := s.Pending(); len(pending) != 0 {
		t.Fatalf("file non vide en mode local : %v", pending)
	}
}

func TestModeLocalNeMarquePasLesNotesEnAttente(t *testing.T) {
	s := newLocalStore(t)

	if err := s.Put("carnet.md", []byte("# Carnet")); err != nil {
		t.Fatalf("Put: %v", err)
	}

	entry, ok := s.CachedEntry("carnet.md")
	if !ok {
		t.Fatal("entrée absente après Put")
	}
	if entry.Dirty {
		t.Error("une note locale ne doit pas être « en attente d'envoi » : il n'y a pas d'envoi")
	}
}

// Le cœur du mode local : une note locale n'a pas de copie ailleurs, donc
// l'évincer n'est pas récupérer de la place, c'est détruire du travail.
func TestModeLocalNEvinceRienEtNEchouePas(t *testing.T) {
	s := newLocalStore(t)

	for _, nom := range []string{"a.md", "b.md", "c.md"} {
		if err := s.Put(nom, []byte(strings.Repeat("x", 4096))); err != nil {
			t.Fatalf("Put(%s): %v", nom, err)
		}
	}

	// Un quota très inférieur à l'occupation réelle : en mode serveur, il
	// évincerait tout ce qui est propre.
	if err := s.SetQuota(1024); err != nil {
		t.Fatalf("SetQuota sous l'occupation ne doit pas échouer en mode local : %v", err)
	}

	for _, nom := range []string{"a.md", "b.md", "c.md"} {
		if _, _, ok := s.Get(nom); !ok {
			t.Errorf("%s a été évincée alors qu'elle est la seule copie", nom)
		}
	}

	// Et l'écriture suivante passe : sans la garde, pruneForSizeLocked
	// refuserait faute de pouvoir descendre sous le quota.
	if err := s.Put("d.md", []byte(strings.Repeat("y", 4096))); err != nil {
		t.Fatalf("écriture refusée par un quota qui n'évince rien : %v", err)
	}
	if err := s.Prune(); err != nil {
		t.Fatalf("Prune: %v", err)
	}
	if _, _, ok := s.Get("a.md"); !ok {
		t.Error("Prune a supprimé une note locale")
	}
}

// Sans cette règle, la bibliothèque serait vide à l'écran : listingDepuisIndex
// lit Index(), qui ne remontait que les entrées sales.
func TestModeLocalIndexeToutesLesNotes(t *testing.T) {
	s := newLocalStore(t)

	if err := s.Put("dossier/note.md", []byte("contenu")); err != nil {
		t.Fatalf("Put: %v", err)
	}

	if !s.HasIndex() {
		t.Error("HasIndex doit être vrai en mode local : l'inventaire est le disque")
	}
	if !indexContains(s.Index(), "dossier/note.md") {
		t.Error("une note locale jamais synchronisée doit figurer dans l'inventaire")
	}
}

func TestModeLocalSurvitAuRedemarrage(t *testing.T) {
	dir := t.TempDir()

	s, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := s.SetLocalOnly(true); err != nil {
		t.Fatalf("SetLocalOnly: %v", err)
	}
	if err := s.Put("carnet.md", []byte("# Carnet")); err != nil {
		t.Fatalf("Put: %v", err)
	}

	rouvert, err := Open(dir)
	if err != nil {
		t.Fatalf("réouverture: %v", err)
	}
	if !rouvert.LocalOnly() {
		t.Fatal("le mode local n'a pas survécu au redémarrage")
	}
	if !indexContains(rouvert.Index(), "carnet.md") {
		t.Error("la note locale a disparu de l'inventaire au redémarrage")
	}
	if pending := rouvert.Pending(); len(pending) != 0 {
		t.Errorf("l'ouverture a réinscrit des opérations : %v", pending)
	}
}

// Un index écrit avant l'existence du champ s'ouvre en mode serveur : la
// valeur nulle du champ est le comportement d'avant, c'est ce qui a permis de
// ne pas changer indexVersion.
func TestIndexSansChampLocalOnlyOuvreEnModeServeur(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "notes"), 0o700); err != nil {
		t.Fatalf("MkdirAll: %v", err)
	}
	index := `{"version":3,"entries":{},"queue":null}`
	if err := os.WriteFile(filepath.Join(dir, "index.json"), []byte(index), 0o600); err != nil {
		t.Fatalf("écriture de l'index: %v", err)
	}

	s, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if s.LocalOnly() {
		t.Error("un index sans le champ localOnly doit s'ouvrir en mode serveur")
	}
}

// Clear promet que rien de l'utilisateur précédent ne reste sur l'appareil, et
// ne tenait pas parole : l'inventaire survivait à la purge, avec les noms des
// notes du compte précédent, que la liste plate affichait ensuite. Les
// conflits ouverts aussi.
func TestClearOublieAussiLInventaireEtLesConflits(t *testing.T) {
	s := newStore(t)

	if err := s.SetIndex(
		[]Known{{Path: "Compte précédent.md", Size: 12}},
		[]string{"Dossier privé"},
	); err != nil {
		t.Fatalf("SetIndex: %v", err)
	}
	accepteSansQuota(t, s, "note.md", "contenu")
	if _, err := s.recordConflict(OpWrite, "note.md", "note (conflit).md", `"etag"`); err != nil {
		t.Fatalf("recordConflict: %v", err)
	}

	if err := s.Clear(); err != nil {
		t.Fatalf("Clear: %v", err)
	}

	if index := s.Index(); len(index) != 0 {
		t.Errorf("l'inventaire du compte précédent a survécu : %v", index)
	}
	if s.HasIndex() {
		t.Error("HasIndex reste vrai : l'appareil prétend savoir ce qu'il ne sait plus")
	}
	if conflits := s.Conflicts(); len(conflits) != 0 {
		t.Errorf("des conflits du compte précédent ont survécu : %d", len(conflits))
	}
	if folders := s.Folders(); len(folders) != 0 {
		t.Errorf("des dossiers ont survécu : %v", folders)
	}
}

// Le chemin de cache dérive du chemin de note : renommer une note sous son
// propre nom réécrivait son fichier puis le supprimait comme s'il s'agissait
// de l'ancien. En mode local, c'était la seule copie.
func TestRenommerSurPlaceGardeLeContenu(t *testing.T) {
	gestes := map[string]func(*Store, string, string) error{
		"Rename":         (*Store).Rename,
		"RenameLocal":    (*Store).RenameLocal,
		"RenameOnDevice": (*Store).RenameOnDevice,
	}
	for nom, geste := range gestes {
		t.Run(nom, func(t *testing.T) {
			s := newStore(t)
			if err := s.Put("carnet.md", []byte("# Carnet")); err != nil {
				t.Fatalf("Put: %v", err)
			}
			avant := len(s.Pending())

			if err := geste(s, "carnet.md", "carnet.md"); err != nil {
				t.Fatalf("%s sur place: %v", nom, err)
			}

			content, _, ok := s.Get("carnet.md")
			if !ok || string(content) != "# Carnet" {
				t.Fatalf("contenu après %s sur place = %q, présent = %v", nom, content, ok)
			}
			if apres := len(s.Pending()); apres != avant {
				t.Errorf("file passée de %d à %d opérations pour un geste sans effet", avant, apres)
			}
		})
	}
}

// Sans serveur, une cible que le cache connaît est une vraie note : l'écraser
// la détruit. Le serveur refuse le même geste (Overwrite: F).
func TestRenameOnDeviceRefuseUneCibleOccupee(t *testing.T) {
	cas := []struct {
		nom, from, to string
	}{
		{"note sur note", "a.md", "b.md"},
		{"dossier sur dossier retenu", "A", "B"},
		{"dossier sur dossier implicite", "A", "C"},
	}
	for _, c := range cas {
		t.Run(c.nom, func(t *testing.T) {
			s := newLocalStore(t)
			for chemin, contenu := range map[string]string{
				"a.md": "note a", "b.md": "note b",
				"A/n.md": "de A", "B/n.md": "de B", "C/n.md": "de C",
			} {
				if err := s.Put(chemin, []byte(contenu)); err != nil {
					t.Fatalf("Put %s: %v", chemin, err)
				}
			}
			if err := s.RememberFolder("A"); err != nil {
				t.Fatal(err)
			}
			if err := s.RememberFolder("B"); err != nil {
				t.Fatal(err)
			}

			err := s.RenameOnDevice(c.from, c.to)
			if err == nil || !strings.Contains(err.Error(), "["+CodeTargetExists+"]") {
				t.Fatalf("RenameOnDevice(%s, %s) = %v, attendu un refus %s", c.from, c.to, err, CodeTargetExists)
			}

			for chemin, contenu := range map[string]string{
				"a.md": "note a", "b.md": "note b",
				"A/n.md": "de A", "B/n.md": "de B", "C/n.md": "de C",
			} {
				if got, _, ok := s.Get(chemin); !ok || string(got) != contenu {
					t.Errorf("%s = %q (présent : %v) après le refus, attendu %q", chemin, got, ok, contenu)
				}
			}
		})
	}
}

// Le renommage différé refuse lui aussi une cible connue : le serveur la
// refuserait à la synchronisation, et d'ici là la note visée aurait été
// écrasée dans le cache.
func TestRenameDiffereRefuseUneCibleConnue(t *testing.T) {
	s := newStore(t)
	if err := s.Put("a.md", []byte("note a")); err != nil {
		t.Fatal(err)
	}
	if err := s.SetIndex([]Known{{Path: "b.md", ETag: "e1", Size: 6}}, nil); err != nil {
		t.Fatal(err)
	}

	err := s.Rename("a.md", "b.md")
	if err == nil || !strings.Contains(err.Error(), "["+CodeTargetExists+"]") {
		t.Fatalf("Rename vers une note inventoriée = %v, attendu %s", err, CodeTargetExists)
	}
	if got, _, ok := s.Get("a.md"); !ok || string(got) != "note a" {
		t.Errorf("a.md = %q après le refus", got)
	}
}

// Après un renommage confirmé par le serveur, la cible était libre là-bas :
// une entrée qui l'occupe encore dans le cache est périmée et doit céder.
func TestRenameLocalEcraseUneCiblePerimee(t *testing.T) {
	s := newStore(t)
	if err := s.Accept("a.md", []byte("nouvelle"), "e1"); err != nil {
		t.Fatal(err)
	}
	if err := s.Accept("b.md", []byte("périmée"), "e2"); err != nil {
		t.Fatal(err)
	}

	if err := s.RenameLocal("a.md", "b.md"); err != nil {
		t.Fatalf("RenameLocal: %v", err)
	}
	if got, _, ok := s.Get("b.md"); !ok || string(got) != "nouvelle" {
		t.Errorf("b.md = %q, attendu le contenu renommé", got)
	}
}

// Les sous-dossiers suivent leur parent, vides compris. Seule la cible était
// réinscrite : ils disparaissaient du sélecteur de destination, et pour de bon
// en mode local, où aucun listing serveur ne les rapporte.
func TestRenommerUnDossierGardeSesSousDossiers(t *testing.T) {
	s := newLocalStore(t)
	for _, d := range []string{"P/vide", "P/plein/profond"} {
		if err := s.RememberFolder(d); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.Put("P/plein/n.md", []byte("x")); err != nil {
		t.Fatal(err)
	}

	if err := s.RenameOnDevice("P", "Q"); err != nil {
		t.Fatalf("RenameOnDevice: %v", err)
	}

	got := strings.Join(s.Folders(), ",")
	if want := "Q,Q/plein,Q/plein/profond,Q/vide"; got != want {
		t.Errorf("dossiers = %s, attendu %s", got, want)
	}
}
