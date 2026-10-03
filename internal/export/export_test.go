package export

import (
	"archive/zip"
	"bytes"
	"crypto/rand"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"
)

// memSource est une Source en mémoire. fail liste les chemins dont Read échoue.
type memSource struct {
	folders []string
	files   map[string][]byte
	fail    map[string]bool
}

func (m *memSource) Folders() []string { return m.folders }

func (m *memSource) Files() []FileInfo {
	var out []FileInfo
	for p, b := range m.files {
		out = append(out, FileInfo{Path: p, Size: int64(len(b)), ModTime: time.Date(2026, 9, 1, 12, 0, 0, 0, time.UTC)})
	}
	return out
}

func (m *memSource) Read(p string) ([]byte, error) {
	if m.fail[p] {
		return nil, errors.New("illisible")
	}
	b, ok := m.files[p]
	if !ok {
		return nil, os.ErrNotExist
	}
	return b, nil
}

// lire ouvre l'archive et renvoie nom → contenu.
func lire(t *testing.T, name string) map[string][]byte {
	t.Helper()
	zr, err := zip.OpenReader(name)
	if err != nil {
		t.Fatal(err)
	}
	defer zr.Close()
	out := map[string][]byte{}
	for _, f := range zr.File {
		rc, err := f.Open()
		if err != nil {
			t.Fatal(err)
		}
		b, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			t.Fatalf("%s : %v", f.Name, err)
		}
		out[f.Name] = b
	}
	return out
}

func noms(m map[string][]byte) []string {
	var s []string
	for k := range m {
		s = append(s, k)
	}
	sort.Strings(s)
	return s
}

func TestZipAllerRetour(t *testing.T) {
	docx := make([]byte, 5000)
	_, _ = rand.Read(docx)
	image := []byte("avant ![x](data:image/png;base64," + strings.Repeat("QUJD", 5000) + ") après")
	gros := bytes.Repeat([]byte("ligne de 300 Ko, accentuée é\n"), 11000)

	src := &memSource{
		folders: []string{"Vide", "Projets/2026/Q3", "Réunions 😀"},
		files: map[string][]byte{
			"racine.md":               []byte("# Racine\n"),
			"Projets/2026/Q3/plan.md": []byte("plan"),
			"Réunions 😀/été.md":       []byte("é😀"),
			"Documents/rapport.docx":  docx,
			"Documents/image.md":      image,
			"Documents/gros.md":       gros,
			"Documents/latin1.txt":    {0xe9, 0xe8, 0xe0}, // ISO-8859-1, pas de l'UTF-8
		},
	}
	dest := filepath.Join(t.TempDir(), "sortie.zip")
	res, err := Zip(src, dest)
	if err != nil {
		t.Fatal(err)
	}
	got := lire(t, dest)

	for p, want := range src.files {
		if !bytes.Equal(got[p], want) {
			t.Errorf("%s : contenu différent", p)
		}
	}
	for _, d := range []string{"Vide/", "Projets/", "Projets/2026/", "Projets/2026/Q3/", "Réunions 😀/", "Documents/"} {
		if _, ok := got[d]; !ok {
			t.Errorf("dossier %q absent de %v", d, noms(got))
		}
	}
	if _, ok := got[RenamesFile]; ok {
		t.Error("aucun nom changé, pas de fichier de renommages")
	}
	if res.Notes != len(src.files) || res.Folders != 6 || res.Renamed != 0 || res.Skipped != 0 || res.ArchiveBytes == 0 {
		t.Errorf("résultat inattendu : %+v", res)
	}
	if len(gros) < 300_000 {
		t.Fatalf("la note de test doit faire 300 Ko, elle en fait %d", len(gros))
	}
}

func TestZipDrapeauUTF8DateEtMethode(t *testing.T) {
	src := &memSource{files: map[string][]byte{
		"été.md": []byte("x"),
		"a.docx": []byte("PK"),
		"b.md":   []byte("y"),
	}}
	dest := filepath.Join(t.TempDir(), "o.zip")
	if _, err := Zip(src, dest); err != nil {
		t.Fatal(err)
	}
	zr, err := zip.OpenReader(dest)
	if err != nil {
		t.Fatal(err)
	}
	defer zr.Close()
	for _, f := range zr.File {
		switch f.Name {
		case "été.md":
			if f.Flags&0x800 == 0 {
				t.Error("drapeau UTF-8 absent : le nom serait lu en CP437")
			}
		case "a.docx":
			if f.Method != zip.Store {
				t.Error(".docx doit être stocké, pas recompressé")
			}
		case "b.md":
			if f.Method != zip.Deflate {
				t.Error("le texte doit être compressé")
			}
		}
		if f.Modified.UTC().Year() != 2026 {
			t.Errorf("%s : date de la note perdue (%v)", f.Name, f.Modified)
		}
	}
}

func TestZipAssainitEtListeLesRenommages(t *testing.T) {
	src := &memSource{
		folders: []string{"Dossier?"},
		files: map[string][]byte{
			"Dossier?/a:b.md": []byte("1"),
			"Note.md":         []byte("2"),
			"note.md":         []byte("3"),
			"valide--ok.md":   []byte("4"),
			"CON.md":          []byte("5"),
		},
	}
	dest := filepath.Join(t.TempDir(), "o.zip")
	res, err := Zip(src, dest)
	if err != nil {
		t.Fatal(err)
	}
	got := lire(t, dest)

	// Les enfants suivent leur dossier renommé.
	if string(got["Dossier_/a_b.md"]) != "1" {
		t.Errorf("arbre inattendu : %v", noms(got))
	}
	if string(got["valide--ok.md"]) != "4" {
		t.Error("un nom valide a été retouché")
	}
	if string(got["Note.md"]) != "2" || string(got["note (2).md"]) != "3" {
		t.Errorf("collision de casse mal résolue : %v", noms(got))
	}
	if string(got["_CON.md"]) != "5" {
		t.Errorf("nom réservé : %v", noms(got))
	}

	lignes := strings.Split(strings.TrimSuffix(string(got[RenamesFile]), "\n"), "\n")
	want := []string{
		"CON.md\t_CON.md",
		"Dossier?\tDossier_",
		"Dossier?/a:b.md\tDossier_/a_b.md",
		"note.md\tnote (2).md",
	}
	if strings.Join(lignes, "|") != strings.Join(want, "|") {
		t.Errorf("renommages :\n%q\nattendu :\n%q", lignes, want)
	}
	if res.Renamed != len(want) {
		t.Errorf("Renamed = %d, attendu %d", res.Renamed, len(want))
	}
}

func TestZipFichierDeRenommagesEnCollision(t *testing.T) {
	src := &memSource{files: map[string][]byte{
		RenamesFile: []byte("à moi"),
		"a?.md":     []byte("x"),
	}}
	dest := filepath.Join(t.TempDir(), "o.zip")
	if _, err := Zip(src, dest); err != nil {
		t.Fatal(err)
	}
	got := lire(t, dest)
	if string(got[RenamesFile]) != "à moi" {
		t.Error("la note de l'utilisateur doit garder son nom")
	}
	if _, ok := got["_renommages (2).txt"]; !ok {
		t.Errorf("le fichier de renommages doit s'écarter : %v", noms(got))
	}
}

func TestZipNoteIllisibleComptee(t *testing.T) {
	src := &memSource{
		files: map[string][]byte{"ok.md": []byte("ok"), "mauvaise?.md": []byte("x"), "autre.md": []byte("y")},
		fail:  map[string]bool{"mauvaise?.md": true},
	}
	dest := filepath.Join(t.TempDir(), "o.zip")
	res, err := Zip(src, dest)
	if err != nil {
		t.Fatal(err)
	}
	if res.Skipped != 1 || res.Notes != 2 {
		t.Errorf("résultat : %+v", res)
	}
	got := lire(t, dest)
	if string(got["ok.md"]) != "ok" || string(got["autre.md"]) != "y" {
		t.Error("les autres notes doivent sortir")
	}
	if res.Renamed != 0 || len(got[RenamesFile]) != 0 {
		t.Errorf("une note ignorée ne figure pas dans les renommages : %+v %q", res, got[RenamesFile])
	}
}

func TestZipNoteDisparueComptee(t *testing.T) {
	// Liste figée au départ, puis la note disparaît avant sa lecture.
	src := &memSource{files: map[string][]byte{"a.md": []byte("a")}}
	liste := &listeFigee{memSource: src, files: src.Files()}
	delete(src.files, "a.md")
	res, err := Zip(liste, filepath.Join(t.TempDir(), "o.zip"))
	if err != nil || res.Skipped != 1 || res.Notes != 0 {
		t.Errorf("res=%+v err=%v", res, err)
	}
}

type listeFigee struct {
	*memSource
	files []FileInfo
}

func (l *listeFigee) Files() []FileInfo { return l.files }

func TestZipDestinationRefusee(t *testing.T) {
	dir := t.TempDir()
	// Le « dossier » de destination est un fichier ordinaire.
	bloc := filepath.Join(dir, "bloc")
	if err := os.WriteFile(bloc, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	dest := filepath.Join(bloc, "o.zip")
	_, err := Zip(&memSource{files: map[string][]byte{"a.md": []byte("a")}}, dest)
	if err == nil || !strings.Contains(err.Error(), "["+CodeStorageIO+"]") {
		t.Fatalf("attendu STORAGE_IO, obtenu %v", err)
	}
	if strings.Contains(err.Error(), dir) {
		t.Errorf("le message porte le chemin de destination : %v", err)
	}
	if _, statErr := os.Stat(dest + ".tmp"); statErr == nil {
		t.Error("un .tmp a été laissé")
	}
}

func TestZipNeLaissePasDeTmpEtRemplaceLaDestination(t *testing.T) {
	dir := t.TempDir()
	dest := filepath.Join(dir, "o.zip")
	if err := os.WriteFile(dest, []byte("ancien"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Zip(&memSource{files: map[string][]byte{"a.md": []byte("a")}}, dest); err != nil {
		t.Fatal(err)
	}
	if string(lire(t, dest)["a.md"]) != "a" {
		t.Error("destination non remplacée")
	}
	if _, err := os.Stat(dest + ".tmp"); err == nil {
		t.Error(".tmp laissé")
	}
}

func TestVerifyDetecteUnCRCAbime(t *testing.T) {
	charge := []byte("CHARGE-UNIQUE-POUR-LE-TEST-CRC")
	src := &memSource{files: map[string][]byte{"x.docx": charge}} // .docx : stocké, donc octets en clair
	dest := filepath.Join(t.TempDir(), "o.zip")
	if _, err := Zip(src, dest); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(dest)
	if err != nil {
		t.Fatal(err)
	}
	attendu := written{"x.docx": int64(len(charge))}
	if err := verify(dest, attendu); err != nil {
		t.Fatalf("l'archive saine doit passer : %v", err)
	}

	i := bytes.Index(raw, charge)
	if i < 0 {
		t.Fatal("charge introuvable dans l'archive")
	}
	raw[i] ^= 0xff
	if err := os.WriteFile(dest, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	err = verify(dest, attendu)
	if err == nil || !strings.Contains(err.Error(), "["+CodeStorageIO+"]") {
		t.Fatalf("la relecture doit échouer sur un CRC abîmé, obtenu %v", err)
	}
}

func TestVerifyCompteLesEntrees(t *testing.T) {
	dest := filepath.Join(t.TempDir(), "o.zip")
	if _, err := Zip(&memSource{files: map[string][]byte{"a.md": []byte("a")}}, dest); err != nil {
		t.Fatal(err)
	}
	if err := verify(dest, written{"a.md": 1, "b.md": 1}); err == nil {
		t.Error("une entrée manquante doit être détectée")
	}
}

func TestZipDeterministe(t *testing.T) {
	mk := func() map[string][]byte {
		src := &memSource{files: map[string][]byte{
			"A.md": []byte("1"), "a.md": []byte("2"), "a.md ": []byte("3"), "d/x?.md": []byte("4"), "d/x*.md": []byte("5"),
		}}
		dest := filepath.Join(t.TempDir(), "o.zip")
		if _, err := Zip(src, dest); err != nil {
			t.Fatal(err)
		}
		return lire(t, dest)
	}
	a, b := mk(), mk()
	if strings.Join(noms(a), "|") != strings.Join(noms(b), "|") {
		t.Errorf("noms différents :\n%v\n%v", noms(a), noms(b))
	}
	for k := range a {
		if !bytes.Equal(a[k], b[k]) {
			t.Errorf("%s : contenu différent d'un export à l'autre", k)
		}
	}
}

func TestRenameLineEchappeLesTabulations(t *testing.T) {
	got := renameLine("a\tb.md", "a_b.md")
	if strings.Count(got, "\t") != 1 || !strings.HasPrefix(got, `"a\tb.md"`) {
		t.Errorf("ligne ambiguë : %q", got)
	}
}
