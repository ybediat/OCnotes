package opencloud

import (
	"encoding/xml"
	"fmt"
	"net/http"
	"net/url"
	"path"
	"strconv"
	"strings"
	"time"
)

// propfindBody demande les seules propriétés dont l'application a besoin.
// Un PROPFIND sans corps renverrait toutes les propriétés, ce qui est plus
// lourd à transférer et à parser.
const propfindBody = `<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
  <d:prop>
    <d:getlastmodified/>
    <d:getcontentlength/>
    <d:getcontenttype/>
    <d:getetag/>
    <d:resourcetype/>
    <oc:fileid/>
    <oc:permissions/>
  </d:prop>
</d:propfind>`

type multistatus struct {
	XMLName   xml.Name      `xml:"DAV: multistatus"`
	Responses []davResponse `xml:"DAV: response"`
}

type davResponse struct {
	Href      string        `xml:"DAV: href"`
	Propstats []davPropstat `xml:"DAV: propstat"`
}

type davPropstat struct {
	Status string  `xml:"DAV: status"`
	Prop   davProp `xml:"DAV: prop"`
}

type davProp struct {
	LastModified  string           `xml:"DAV: getlastmodified"`
	ContentLength string           `xml:"DAV: getcontentlength"`
	ContentType   string           `xml:"DAV: getcontenttype"`
	ETag          string           `xml:"DAV: getetag"`
	ResourceType  *davResourceType `xml:"DAV: resourcetype"`
	FileID        string           `xml:"http://owncloud.org/ns fileid"`
	Permissions   string           `xml:"http://owncloud.org/ns permissions"`
}

type davResourceType struct {
	Collection *struct{} `xml:"DAV: collection"`
}

// parseMultistatus convertit une réponse 207 en ressources.
//
// basePath est le chemin de la racine de l'espace, sous forme décodée, par
// exemple /dav/spaces/{storageID}${spaceID}.
func parseMultistatus(data []byte, basePath string) ([]Resource, error) {
	var ms multistatus
	if err := xml.Unmarshal(data, &ms); err != nil {
		return nil, fmt.Errorf("opencloud: réponse PROPFIND illisible: %w", err)
	}

	out := make([]Resource, 0, len(ms.Responses))
	for _, r := range ms.Responses {
		res, ok, err := resourceFromResponse(r, basePath)
		if err != nil {
			return nil, err
		}
		if ok {
			out = append(out, res)
		}
	}
	return out, nil
}

// resourceFromResponse extrait une ressource d'un bloc <d:response>.
//
// OpenCloud renvoie parfois deux blocs <d:propstat> pour une même entrée : un
// en 200 avec les propriétés trouvées, et un en 404 avec celles qui n'existent
// pas sur ce type de ressource. Sur un dossier, getcontentlength et
// getcontenttype se retrouvent dans le bloc 404, vides.
//
// Il faut donc impérativement filtrer sur le statut du propstat. Un parser qui
// fusionnerait les deux blocs verrait un dossier comme un fichier de taille
// nulle et de type MIME vide.
func resourceFromResponse(r davResponse, basePath string) (Resource, bool, error) {
	var p davProp
	found := false
	for _, ps := range r.Propstats {
		if propstatOK(ps.Status) {
			p = ps.Prop
			found = true
			break
		}
	}
	if !found {
		return Resource{}, false, nil
	}

	rel, err := relativePath(r.Href, basePath)
	if err != nil {
		return Resource{}, false, err
	}

	res := Resource{
		Path:        rel,
		Name:        lastSegment(rel),
		IsDir:       p.ResourceType != nil && p.ResourceType.Collection != nil,
		ContentType: strings.TrimSpace(p.ContentType),
		ETag:        strings.TrimSpace(p.ETag),
		FileID:      strings.TrimSpace(p.FileID),
		Permissions: strings.TrimSpace(p.Permissions),
	}

	if s := strings.TrimSpace(p.ContentLength); s != "" {
		if n, err := strconv.ParseInt(s, 10, 64); err == nil {
			res.Size = n
		}
	}
	if s := strings.TrimSpace(p.LastModified); s != "" {
		if t, err := time.Parse(http.TimeFormat, s); err == nil {
			res.ModTime = t
		}
	}

	return res, true, nil
}

// propstatOK reconnaît un statut « HTTP/1.1 200 OK ».
func propstatOK(status string) bool {
	fields := strings.Fields(status)
	return len(fields) >= 2 && fields[1] == "200"
}

// relativePath transforme un href absolu en chemin relatif à l'espace.
//
// Le href est percent-encodé par le serveur (« R%C3%A9union%20du%2015.md »),
// mais le '$' des identifiants d'espace ne l'est pas. PathUnescape restitue la
// forme décodée sans toucher au '$'.
func relativePath(href, basePath string) (string, error) {
	decoded, err := url.PathUnescape(href)
	if err != nil {
		return "", fmt.Errorf("opencloud: href illisible %q: %w", href, err)
	}

	decoded = strings.TrimSuffix(decoded, "/")
	base := strings.TrimSuffix(basePath, "/")

	if decoded == base {
		return "", nil
	}
	if !strings.HasPrefix(decoded, base+"/") {
		return "", fmt.Errorf("opencloud: href %q hors de l'espace %q", decoded, base)
	}
	return strings.TrimPrefix(decoded, base+"/"), nil
}

func lastSegment(p string) string {
	if p == "" {
		return ""
	}
	return path.Base(p)
}
