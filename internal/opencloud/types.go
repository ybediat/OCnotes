// Package opencloud fournit un client pour les API HTTP d'un serveur
// OpenCloud : découverte des espaces via LibreGraph et accès aux fichiers via
// WebDAV.
//
// Le paquet ne dépend que de la bibliothèque standard et ignore tout de
// l'interface graphique : il se compile et se teste sur desktop.
package opencloud

import "time"

// Types d'espaces renvoyés par LibreGraph dans le champ driveType.
const (
	DrivePersonal   = "personal"
	DriveProject    = "project"
	DriveVirtual    = "virtual"
	DriveMountpoint = "mountpoint"
)

// Drive est un espace de stockage OpenCloud.
type Drive struct {
	// ID a la forme {storageID}${spaceID}. Le '$' fait partie de
	// l'identifiant : ne jamais l'encoder.
	ID string

	Name string
	Type string

	// WebDavURL est l'URL absolue de la racine de l'espace, telle que
	// renvoyée par le serveur dans root.webDavUrl.
	WebDavURL string
}

// IsStorage indique si l'espace peut héberger des notes.
//
// L'espace virtuel « Shares » est un agrégat de partages, pas un stockage : on
// ne peut pas y créer de dossier, et son ETag est une valeur factice.
func (d Drive) IsStorage() bool {
	return d.Type != DriveVirtual && d.WebDavURL != ""
}

// Resource décrit un fichier ou un dossier renvoyé par un PROPFIND.
type Resource struct {
	// Path est relatif à la racine de l'espace, sans slash initial.
	// La racine elle-même a un Path vide.
	Path string

	// Name est le dernier segment de Path, décodé (accents compris).
	Name string

	IsDir bool
	Size  int64

	ContentType string

	// ETag est conservé verbatim, guillemets inclus, tel que le serveur l'a
	// émis. C'est un jeton opaque : il est renvoyé tel quel dans If-Match.
	ETag string

	ModTime time.Time

	// FileID a la forme {storageID}${spaceID}!{opaqueID}.
	FileID string

	// Permissions est la chaîne de droits OpenCloud, par exemple RDNVCKZP
	// pour un dossier modifiable ou RDNVWZP pour un fichier.
	Permissions string
}
