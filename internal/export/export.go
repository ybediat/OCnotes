// Package export écrit les notes locales dans une archive zip : l'arborescence
// et les noms que l'utilisateur voit dans l'application, lisibles par
// n'importe quel outil.
//
// Le paquet ignore le cache : il consomme une Source, déclarée ici, côté
// consommateur. C'est ce qui le rend testable avec une arborescence en mémoire.
package export

import (
	"archive/zip"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/ybediat/OpenNote/internal/notes"
)

// CodeStorageIO couvre une panne du support : destination refusée, disque
// plein, archive illisible à la relecture. Même code, même forme que ceux de
// config et de store, pour que ErrorCode d'Android le reconnaisse.
const CodeStorageIO = "STORAGE_IO"

// RenamesFile est le fichier posé à la racine de l'archive quand au moins un
// nom a dû changer. Une ligne par renommage, « original<TAB>exporté », sans
// prose : il n'y a donc aucune langue à traduire.
const RenamesFile = "_renommages.txt"

// FileInfo décrit une note à exporter.
type FileInfo struct {
	Path    string // chemin logique, relatif au dossier de notes, séparateur « / »
	Size    int64
	ModTime time.Time
}

// Source est ce que l'export lit. Folders liste les dossiers, vides compris.
type Source interface {
	Folders() []string
	Files() []FileInfo
	Read(path string) ([]byte, error)
}

// Result rend compte de l'export. Un export où Skipped n'est pas nul est
// incomplet : l'interface ne doit pas le présenter comme un succès.
type Result struct {
	Notes        int   `json:"notes"`        // notes écrites
	Folders      int   `json:"folders"`      // dossiers écrits, vides compris
	Renamed      int   `json:"renamed"`      // noms changés, listés dans RenamesFile
	Skipped      int   `json:"skipped"`      // notes illisibles ou disparues pendant l'export
	ArchiveBytes int64 `json:"archiveBytes"` // taille de l'archive sur le disque
}

// entry est un élément de l'arbre à écrire.
type entry struct {
	logical  string // chemin logique, nettoyé
	exported string // chemin dans l'archive ; un dossier se termine par « / »
	dir      bool
	info     FileInfo
	rename   string // ligne de RenamesFile si le nom a changé, sinon vide
}

type layout struct {
	entries     []entry
	renamesName string // nom de RenamesFile après résolution des collisions
}

// Zip écrit toutes les notes de src dans l'archive dest.
//
// L'écriture passe par dest.tmp, synchronisé puis renommé ; en cas d'échec le
// temporaire est supprimé. Rien n'est annoncé réussi avant d'avoir rouvert
// l'archive et relu chaque entrée jusqu'au bout — la lecture vérifie le CRC.
// Une note illisible ne fait pas échouer l'export : elle est comptée.
func Zip(src Source, dest string) (Result, error) {
	l := plan(src)

	tmp := dest + ".tmp"
	result, written, err := write(src, l, tmp)
	if err != nil {
		_ = os.Remove(tmp)
		return Result{}, err
	}
	if err := verify(tmp, written); err != nil {
		_ = os.Remove(tmp)
		return Result{}, err
	}
	if err := os.Rename(tmp, dest); err != nil {
		_ = os.Remove(tmp)
		return Result{}, storageErr("remplacement", err)
	}
	if dir, err := os.Open(filepath.Dir(dest)); err == nil {
		_ = dir.Sync() // meilleur effort, comme config.Save
		_ = dir.Close()
	}
	if st, err := os.Stat(dest); err == nil {
		result.ArchiveBytes = st.Size()
	}
	return result, nil
}

// plan calcule l'arborescence exportée : tous les noms passés par
// notes.ExportSiblings, dossier par dossier, de sorte que les enfants suivent
// leur dossier renommé.
func plan(src Source) layout {
	type key struct {
		path string
		dir  bool
	}
	infos := map[key]FileInfo{}
	dirs := map[string]bool{}
	addDir := func(p string) {
		for p != "" {
			dirs[p] = true
			p = parentOf(p)
		}
	}
	for _, f := range src.Folders() {
		addDir(notes.CleanPath(f))
	}
	for _, f := range src.Files() {
		p := notes.CleanPath(f.Path)
		if p == "" {
			continue
		}
		f.Path = p
		infos[key{p, false}] = f
		addDir(parentOf(p))
	}

	// Enfants de chaque dossier, la racine étant « ».
	children := map[string][]key{}
	for d := range dirs {
		children[parentOf(d)] = append(children[parentOf(d)], key{d, true})
	}
	for k := range infos {
		children[parentOf(k.path)] = append(children[parentOf(k.path)], key{k.path, false})
	}

	var l layout
	exportedDir := map[string]string{"": ""}
	var walk func(dir string)
	walk = func(dir string) {
		kids := children[dir]
		sort.Slice(kids, func(a, b int) bool {
			if kids[a].path != kids[b].path {
				return kids[a].path < kids[b].path
			}
			return !kids[a].dir && kids[b].dir
		})
		names := make([]string, len(kids), len(kids)+1)
		for i, k := range kids {
			names[i] = path.Base(k.path)
		}
		// Le fichier de renommages concourt pour son nom, en dernier : une
		// note qui le porte déjà le garde.
		if dir == "" {
			names = append(names, RenamesFile)
		}
		resolved := notes.ExportSiblings(names)

		for i, k := range kids {
			out := resolved[i]
			if exportedDir[dir] != "" {
				out = exportedDir[dir] + "/" + out
			}
			e := entry{logical: k.path, exported: out, dir: k.dir, info: infos[k]}
			if resolved[i] != names[i] {
				e.rename = renameLine(k.path, out)
			}
			if k.dir {
				exportedDir[k.path] = out
				e.exported = out + "/"
				l.entries = append(l.entries, e)
				walk(k.path)
			} else {
				l.entries = append(l.entries, e)
			}
		}
		if dir == "" {
			l.renamesName = resolved[len(resolved)-1]
		}
	}
	walk("")

	// Un parent précède toujours ses enfants : « a/ » est préfixe de « a/x ».
	sort.Slice(l.entries, func(a, b int) bool { return l.entries[a].exported < l.entries[b].exported })
	return l
}

func parentOf(p string) string {
	i := strings.LastIndex(p, "/")
	if i < 0 {
		return ""
	}
	return p[:i]
}

// renameLine écrit « original<TAB>exporté ». Un nom qui porte lui-même une
// tabulation ou un saut de ligne — le serveur les accepte — serait ambigu : il
// est alors écrit entre guillemets, à la façon de strconv.Quote.
func renameLine(original, exported string) string {
	return quoteIfAmbiguous(original) + "\t" + quoteIfAmbiguous(exported)
}

func quoteIfAmbiguous(s string) string {
	if strings.IndexFunc(s, unicode.IsControl) >= 0 {
		return strconv.Quote(s)
	}
	return s
}

// written est ce que verify doit retrouver dans l'archive.
type written map[string]int64 // nom d'entrée → taille décompressée

func write(src Source, l layout, tmp string) (Result, written, error) {
	var res Result
	want := written{}
	var renames []string

	if err := os.MkdirAll(filepath.Dir(tmp), 0o700); err != nil {
		return res, nil, storageErr("création du dossier", err)
	}
	file, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return res, nil, storageErr("écriture", err)
	}
	// Le fichier est fermé exactement une fois, par closeFile.
	closed := false
	closeFile := func() error {
		if closed {
			return nil
		}
		closed = true
		return file.Close()
	}
	defer func() { _ = closeFile() }()

	zw := zip.NewWriter(file)
	put := func(h *zip.FileHeader, data []byte) error {
		w, err := zw.CreateHeader(h)
		if err != nil {
			return err
		}
		_, err = w.Write(data)
		return err
	}

	for _, e := range l.entries {
		if e.dir {
			h := &zip.FileHeader{Name: e.exported, Method: zip.Store}
			h.SetMode(os.ModeDir | 0o755)
			if _, err := zw.CreateHeader(h); err != nil {
				return res, nil, storageErr("écriture", err)
			}
			want[e.exported] = 0
			res.Folders++
			if e.rename != "" {
				renames = append(renames, e.rename)
			}
			continue
		}

		// Une note en mémoire à la fois, jamais l'archive entière.
		data, err := src.Read(e.logical)
		if err != nil {
			res.Skipped++
			continue
		}
		h := &zip.FileHeader{Name: e.exported, Method: methodFor(e.exported), Modified: e.info.ModTime}
		h.SetMode(0o644)
		if err := put(h, data); err != nil {
			return res, nil, storageErr("écriture", err)
		}
		want[e.exported] = int64(len(data))
		res.Notes++
		if e.rename != "" {
			renames = append(renames, e.rename)
		}
	}

	// Seuls les renommages de ce qui est réellement dans l'archive y figurent :
	// une note ignorée n'a rien à y faire.
	if len(renames) > 0 {
		sort.Strings(renames)
		body := []byte(strings.Join(renames, "\n") + "\n")
		h := &zip.FileHeader{Name: l.renamesName, Method: zip.Deflate, Modified: time.Now()}
		h.SetMode(0o644)
		if err := put(h, body); err != nil {
			return res, nil, storageErr("écriture", err)
		}
		want[l.renamesName] = int64(len(body))
	}
	res.Renamed = len(renames)

	if err := zw.Close(); err != nil {
		return res, nil, storageErr("écriture", err)
	}
	if err := file.Sync(); err != nil {
		return res, nil, storageErr("écriture", err)
	}
	if err := closeFile(); err != nil {
		return res, nil, storageErr("écriture", err)
	}
	return res, want, nil
}

// methodFor stocke sans recompresser ce qui l'est déjà.
func methodFor(name string) uint16 {
	switch strings.ToLower(path.Ext(name)) {
	case ".docx", ".odt":
		return zip.Store
	}
	return zip.Deflate
}

// verify rouvre l'archive et relit chaque entrée jusqu'au bout. Le lecteur de
// archive/zip contrôle le CRC en fin d'entrée : une entrée abîmée échoue ici
// plutôt que chez l'utilisateur.
func verify(name string, want written) error {
	zr, err := zip.OpenReader(name)
	if err != nil {
		return storageErr("relecture", err)
	}
	defer zr.Close()

	if len(zr.File) != len(want) {
		return storageErr("relecture", fmt.Errorf("%d entrées lues, %d attendues", len(zr.File), len(want)))
	}
	for _, f := range zr.File {
		size, ok := want[f.Name]
		if !ok {
			return storageErr("relecture", fmt.Errorf("entrée inattendue"))
		}
		rc, err := f.Open()
		if err != nil {
			return storageErr("relecture", err)
		}
		n, err := io.Copy(io.Discard, rc)
		_ = rc.Close()
		if err != nil {
			return storageErr("relecture", err)
		}
		if n != size {
			return storageErr("relecture", fmt.Errorf("taille lue %d, attendue %d", n, size))
		}
	}
	return nil
}

// storageErr n'embarque jamais le chemin de l'archive : le message peut finir
// dans un journal, et le chemin n'y a rien à faire. L'erreur système, elle,
// reste (permission, disque plein).
func storageErr(action string, err error) error {
	var pe *fs.PathError
	if errors.As(err, &pe) {
		err = pe.Err
	}
	var le *os.LinkError
	if errors.As(err, &le) {
		err = le.Err
	}
	return fmt.Errorf("export: [%s] %s : %w", CodeStorageIO, action, err)
}
