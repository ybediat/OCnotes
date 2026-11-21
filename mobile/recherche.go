package mobile

import (
	"sort"
	"strings"
	"unicode/utf8"

	"github.com/ybediat/OpenNote/internal/markdown"
	"github.com/ybediat/OpenNote/internal/notes"
	"github.com/ybediat/OpenNote/internal/search"
)

// maxResultatsRecherche borne la liste rendue : au-delà, l'utilisateur affine
// sa requête plutôt que de faire défiler. Ce sont les plus pertinentes qui
// sont gardées (voir SearchJSON), donc toute la portée est fouillée.
const maxResultatsRecherche = 50

// largeurExtrait borne l'extrait d'un résultat, en caractères : une ligne
// d'écran. Le texte complet d'une note ne traverse jamais la frontière pour
// une recherche.
const largeurExtrait = 120

// searchResult est une note dont le texte contient la requête.
//
// Start et End bornent le terme dans Excerpt, en unités UTF-16.
type searchResult struct {
	Path    string `json:"path"`
	Name    string `json:"name"`
	Display string `json:"display"`
	Count   int    `json:"count"`
	Excerpt string `json:"excerpt"`
	Start   int    `json:"start"`
	End     int    `json:"end"`
}

// searchResponse est la forme sérialisée de SearchJSON.
type searchResponse struct {
	// Query revient telle quelle : Kotlin écarte une réponse qui ne
	// correspond plus au texte affiché dans le champ.
	Query   string         `json:"query"`
	Results []searchResult `json:"results"`
	// Skipped compte les notes de la portée qui n'ont pas pu être fouillées :
	// connues mais absentes du cache, ou d'un encodage incertain.
	Skipped int `json:"skipped"`
	// Truncated signale l'arrêt à maxResultatsRecherche notes trouvées.
	Truncated bool `json:"truncated"`
	// Cancelled signale une passe abandonnée parce qu'une autre l'a suivie.
	Cancelled bool `json:"cancelled"`
}

// SearchMinLength est le nombre de caractères à partir duquel SearchJSON
// fouille le texte. Exposé pour que l'interface n'ait pas à le recopier.
func SearchMinLength() int { return search.MinQueryLength }

// SearchJSON cherche query dans le texte des notes de dir et de ses
// sous-dossiers (dir vide : toute la bibliothèque), et renvoie searchResponse.
//
// Ni réseau ni écriture : seules les notes déjà dans le cache sont lues, par
// Peek, qui ne touche ni l'index ni les dates d'accès. Une note absente du
// cache est comptée dans `skipped`, jamais téléchargée. Les documents et les
// fichiers en lecture seule ne sont pas fouillés.
//
// Une recherche lancée pendant celle-ci la rend périmée : elle revient alors
// vide avec `cancelled`, ce qui n'est pas une erreur. Le terme cherché et les
// extraits ne sont jamais journalisés.
func (a *App) SearchJSON(query, dir string) (string, error) {
	gen := a.rechercheGen.Add(1)
	perimee := func() bool { return a.rechercheGen.Load() != gen }
	annulee := func() (string, error) {
		return toJSON(searchResponse{Query: query, Results: []searchResult{}, Cancelled: true})
	}

	if lib, local := a.session(); lib == nil && !local {
		return "", errNoWorkspace()
	}

	out := searchResponse{Query: query, Results: []searchResult{}}
	if utf8.RuneCountInString(strings.TrimSpace(query)) < search.MinQueryLength {
		return toJSON(out)
	}

	prefixe := strings.Trim(dir, "/")
	if prefixe != "" {
		prefixe += "/"
	}

	for _, k := range a.cache.Index() {
		if !strings.HasPrefix(k.Path, prefixe) {
			continue
		}
		nom := lastSegment(k.Path)
		// Seuls les formats que l'application écrit : un .docx demanderait
		// d'être analysé à chaque recherche, un .yaml n'est pas une note.
		if !notes.IsEditable(nom) {
			continue
		}
		if perimee() {
			return annulee()
		}

		count, m, lue := a.fouiller(k.Path, query)
		if a.apresNoteFouillee != nil {
			a.apresNoteFouillee()
		}
		if !lue {
			out.Skipped++
			continue
		}
		if count == 0 {
			continue
		}
		out.Results = append(out.Results, searchResult{
			Path:    k.Path,
			Name:    nom,
			Display: notes.DisplayName(nom),
			Count:   count,
			Excerpt: m.Excerpt,
			Start:   m.Start,
			End:     m.End,
		})
	}

	// Une passe finie après qu'une autre a commencé n'a plus de lecteur.
	if perimee() {
		return annulee()
	}

	// Les plus pertinentes d'abord : la note qui contient le terme le plus
	// souvent. À égalité, l'ordre des chemins, pour un affichage stable d'une
	// frappe à l'autre. Avant, on gardait les 50 premières par ordre
	// alphabétique, et la note cherchée pouvait manquer.
	sort.SliceStable(out.Results, func(i, j int) bool {
		if out.Results[i].Count != out.Results[j].Count {
			return out.Results[i].Count > out.Results[j].Count
		}
		return out.Results[i].Path < out.Results[j].Path
	})
	// Tronqué seulement s'il y avait vraiment plus : exactement 50 notes
	// trouvées ne sont pas une liste coupée.
	if len(out.Results) > maxResultatsRecherche {
		out.Results = out.Results[:maxResultatsRecherche]
		out.Truncated = true
	}
	return toJSON(out)
}

// CancelSearch abandonne la recherche dans le texte en cours, s'il y en a
// une : elle revient vide avec `cancelled`. L'interface l'appelle quand le
// champ est effacé, à chaque frappe, au changement de dossier et en quittant
// l'écran : sans cela, la passe finissait de lire toutes les notes pour rien.
func (a *App) CancelSearch() { a.rechercheGen.Add(1) }

// fouiller cherche query dans une note du cache. lue est faux si la note n'a
// pas pu être lue : absente du cache, ou encodage que ReadNote refuserait.
func (a *App) fouiller(notePath, query string) (count int, m search.Match, lue bool) {
	content, ok := a.cache.Peek(notePath)
	if !ok {
		return 0, search.Match{}, false
	}
	// Le même décodage que ReadNote : ce qui s'ouvrirait à l'écran est ce
	// qu'on fouille. decodeNote ne retient rien, à la différence de ReadNote.
	text, _, err := a.decodeNote(notePath, content)
	if err != nil {
		return 0, search.Match{}, false
	}
	text = notes.NormalizeLineEndings(text)

	// Jamais dans le base64 : une image en ligne correspondrait à presque
	// toute requête, et son extrait serait le mot sans espace qui tue
	// l'application. Les données sortent, et leurs jetons aussi.
	text, data := markdown.ExtractInlineData(text)
	text = markdown.RestoreInlineData(text, make([]string, len(data)))
	// Pas de ShortenLongWords sur le texte entier : mesuré sur téléphone, il
	// doublait le temps de la recherche, et n'apporte rien ici. Un mot
	// démesuré qui ne serait pas une image ne traverse pas la frontière :
	// search.Find borne l'extrait à largeurExtrait caractères.

	count, m = search.Find(text, query, largeurExtrait)
	return count, m, true
}
