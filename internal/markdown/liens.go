package markdown

import (
	"net/url"
	"strings"
)

// schemasRefuses désignent une ressource de l'appareil plutôt qu'une adresse.
//
// file: ouvrirait un fichier du téléphone, content: la donnée qu'une autre
// application expose : ni l'un ni l'autre n'a de sens dans une note venue
// d'un serveur, et c'est exactement ce qu'un lien piégé viserait.
var schemasRefuses = map[string]bool{
	"file":    true,
	"content": true,
}

// OpenableLink indique si une destination de lien mérite d'être cliquable.
//
// Une note peut venir d'un espace partagé, et chaque lien est alors choisi par
// quelqu'un d'autre. Sont écartés les schémas de ressource locale, et toute
// destination sans schéma — lien relatif, ancre, « //hôte » — qu'Android ne
// saurait de toute façon pas ouvrir. Une destination illisible est refusée
// plutôt que devinée : Android pourrait la lire autrement que Go.
//
// Ce qui reste n'est pas ouvert d'office : l'aperçu montre la destination
// réelle et demande confirmation. Cette fonction ne tranche que ce qui ne doit
// jamais l'être.
func OpenableLink(href string) bool {
	u, err := url.Parse(strings.TrimSpace(href))
	if err != nil || u.Scheme == "" {
		return false
	}
	return !schemasRefuses[strings.ToLower(u.Scheme)]
}
