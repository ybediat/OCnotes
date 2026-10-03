package mobile

import (
	"archive/zip"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"testing"
)

func lireZip(t *testing.T, chemin string) map[string]string {
	t.Helper()
	zr, err := zip.OpenReader(chemin)
	if err != nil {
		t.Fatal(err)
	}
	defer zr.Close()
	out := map[string]string{}
	for _, f := range zr.File {
		rc, err := f.Open()
		if err != nil {
			t.Fatal(err)
		}
		b, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			t.Fatal(err)
		}
		out[f.Name] = string(b)
	}
	return out
}

func TestExportZipEcritLArborescenceLocale(t *testing.T) {
	app, _ := prepareLocal(t)
	if _, err := app.CreateFolderJSON("", "Vide"); err != nil {
		t.Fatal(err)
	}
	if _, err := app.CreateFolderJSON("", "Carnets"); err != nil {
		t.Fatal(err)
	}
	if _, err := app.CreateNoteJSON("", "Idée du soir", "# Idée 😀\n"); err != nil {
		t.Fatal(err)
	}
	if _, err := app.CreateNoteJSON("Carnets", "Rangée", "# Rangée\n"); err != nil {
		t.Fatal(err)
	}

	dest := filepath.Join(t.TempDir(), "sortie.zip")
	raw, err := app.ExportZip(dest)
	if err != nil {
		t.Fatalf("ExportZip: %v", err)
	}
	var res exportResult
	decodeJSON(t, raw, &res)
	if res.Notes != 2 || res.Folders != 2 || res.Skipped != 0 || res.Renamed != 0 || res.ArchiveBytes == 0 {
		t.Errorf("résultat inattendu : %+v", res)
	}

	got := lireZip(t, dest)
	if got["Idée du soir.md"] != "# Idée 😀\n" || got["Carnets/Rangée.md"] != "# Rangée\n" {
		t.Errorf("contenu inattendu : %v", got)
	}
	if _, ok := got["Vide/"]; !ok {
		t.Errorf("dossier vide absent : %v", got)
	}
}

// Exporter ne réécrit pas l'index et ne touche pas aux dates d'accès : un
// export de toutes les notes ne doit rien changer à ce que le cache sait d'elles.
func TestExportZipNeReecritPasLIndex(t *testing.T) {
	app, dataDir := prepareLocal(t)
	if _, err := app.CreateNoteJSON("", "A", "aaaa"); err != nil {
		t.Fatal(err)
	}
	if _, err := app.CreateNoteJSON("", "B", "bbbb"); err != nil {
		t.Fatal(err)
	}

	var index []string
	_ = filepath.WalkDir(dataDir, func(p string, d fs.DirEntry, err error) error {
		if err == nil && d.Name() == "index.json" {
			index = append(index, p)
		}
		return nil
	})
	if len(index) != 1 {
		t.Fatalf("index introuvable : %v", index)
	}
	avant, err := os.ReadFile(index[0])
	if err != nil {
		t.Fatal(err)
	}
	accesAvant := map[string]string{}
	for _, e := range app.cache.Entries() {
		accesAvant[e.Path] = e.LastAccess.String()
	}

	if _, err := app.ExportZip(filepath.Join(t.TempDir(), "o.zip")); err != nil {
		t.Fatal(err)
	}

	apres, err := os.ReadFile(index[0])
	if err != nil {
		t.Fatal(err)
	}
	if string(avant) != string(apres) {
		t.Error("ExportZip a réécrit l'index")
	}
	for _, e := range app.cache.Entries() {
		if e.LastAccess.String() != accesAvant[e.Path] {
			t.Errorf("%s : date d'accès modifiée par l'export", e.Path)
		}
	}
}

func TestExportZipRefuseEnModeServeurSansRienEcrire(t *testing.T) {
	app, _, _ := prepare(t)
	dest := filepath.Join(t.TempDir(), "sortie.zip")

	_, err := app.ExportZip(dest)
	if code := ErrorCode(errString(err)); code != CodeExportRequiresLocal {
		t.Fatalf("code %q, attendu %s : %v", code, CodeExportRequiresLocal, err)
	}
	for _, f := range []string{dest, dest + ".tmp"} {
		if _, err := os.Stat(f); !os.IsNotExist(err) {
			t.Errorf("%s écrit malgré le refus", f)
		}
	}
}

func TestExportZipSignaleUneDestinationRefusee(t *testing.T) {
	app, _ := prepareLocal(t)
	if _, err := app.CreateNoteJSON("", "A", "a"); err != nil {
		t.Fatal(err)
	}
	bloc := filepath.Join(t.TempDir(), "bloc")
	if err := os.WriteFile(bloc, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	_, err := app.ExportZip(filepath.Join(bloc, "o.zip"))
	if code := ErrorCode(errString(err)); code != "STORAGE_IO" {
		t.Errorf("code %q, attendu STORAGE_IO : %v", code, err)
	}
}
