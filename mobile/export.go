package mobile

import (
	"fmt"
	"os"

	"github.com/ybediat/OpenNote/internal/export"
	"github.com/ybediat/OpenNote/internal/store"
)

// CodeExportRequiresLocal signale un export demandé à une application qui a un
// serveur : ses notes sont sur le serveur, on les récupère de là.
//
// L'interface masque le bouton hors du mode local ; ce refus est le garde-fou
// du cœur, pour un appel qui l'aurait contourné.
const CodeExportRequiresLocal = "EXPORT_REQUIRES_LOCAL"

// exportResult est la forme sérialisée du compte rendu de ExportZip.
type exportResult struct {
	Notes        int   `json:"notes"`
	Folders      int   `json:"folders"`
	Renamed      int   `json:"renamed"`
	Skipped      int   `json:"skipped"`
	ArchiveBytes int64 `json:"archiveBytes"`
}

// ExportZip écrit toutes les notes locales dans une archive zip à destPath et
// renvoie le compte rendu en JSON (exportResult).
//
// Mode local seulement : sinon [EXPORT_REQUIRES_LOCAL], sans rien écrire. Le
// contenu ne traverse pas la frontière — Go écrit le fichier, Kotlin le copie
// vers l'emplacement choisi par l'utilisateur. Rien dans le cache n'est
// modifié, ni notes, ni index, ni dates d'accès.
//
// Une note illisible ne fait pas échouer l'export : elle est comptée dans
// `skipped`, et l'interface ne doit pas présenter ce résultat comme un succès.
func (a *App) ExportZip(destPath string) (string, error) {
	if _, local := a.session(); !local {
		return "", fmt.Errorf("mobile: [%s] l'export ne sert qu'en mode local", CodeExportRequiresLocal)
	}
	res, err := export.Zip(cacheSource{a.cache}, destPath)
	if err != nil {
		return "", err
	}
	return toJSON(exportResult(res))
}

// cacheSource adapte le cache à export.Source. La liste des notes est figée à
// chaque appel de Files ; la lecture se fait ensuite note par note, sans
// verrouiller le cache pendant tout l'export.
type cacheSource struct{ cache *store.Store }

func (c cacheSource) Folders() []string { return c.cache.Folders() }

func (c cacheSource) Files() []export.FileInfo {
	entries := c.cache.Entries()
	out := make([]export.FileInfo, len(entries))
	for i, e := range entries {
		out[i] = export.FileInfo{Path: e.Path, Size: e.Size, ModTime: e.LocalMod}
	}
	return out
}

func (c cacheSource) Read(path string) ([]byte, error) {
	content, ok := c.cache.Peek(path)
	if !ok {
		return nil, os.ErrNotExist
	}
	return content, nil
}
