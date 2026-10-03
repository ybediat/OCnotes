package notes

import (
	"fmt"
	"path"
	"sort"
	"strings"
	"unicode"
	"unicode/utf8"
)

// ExportSegment assainit UN segment de chemin pour l'écrire dans une archive
// qui doit s'extraire sur n'importe quel système.
//
// C'est le miroir de ValidateName, pas de SanitizeName : SanitizeName fabrique
// un nom à partir d'un titre et retoucherait des noms parfaitement valides
// (« a--b.md » deviendrait « a-b.md », ce qui ferait collisionner des notes
// distinctes). Ici, tout nom que ValidateName accepte ressort inchangé ; seul
// l'illégal est touché.
//
// Un point initial est conservé : légal sur le disque, ValidateName ne le
// refuse que pour la *création* d'une note.
func ExportSegment(name string) string {
	var b strings.Builder
	for _, r := range name {
		switch {
		case unicode.IsControl(r), strings.ContainsRune(forbiddenInName, r):
			b.WriteRune('_')
		default:
			b.WriteRune(r)
		}
	}

	// Windows retire à l'extraction les espaces et les points finaux.
	out := strings.TrimRight(b.String(), " .")
	if strings.TrimSpace(out) == "" || strings.Trim(out, ".") == "" {
		return "_"
	}

	// Nom de périphérique réservé : avec ou sans extension, et Windows ignore
	// les espaces qui précèdent le point.
	base := strings.SplitN(out, ".", 2)[0]
	if reservedDeviceNames[strings.ToUpper(strings.TrimRight(base, " "))] {
		out = "_" + out
	}

	return truncateKeepingExt(out, maxNameBytes)
}

// ExportSiblings applique ExportSegment à des frères d'un même dossier et lève
// les collisions : unicité insensible à la casse, suffixe « (2) », « (3) » posé
// avant l'extension. Le résultat suit l'ordre de l'entrée.
//
// Un fichier et un dossier de même nom sont des frères : l'appelant les passe
// ensemble.
//
// Les noms que l'assainissement laisse intacts sont servis les premiers, dans
// l'ordre trié : un nom valide ne change jamais au profit d'un nom assaini, et
// deux exports du même cache donnent les mêmes noms.
func ExportSiblings(names []string) []string {
	type item struct {
		index   int
		clean   string
		changed bool
	}
	items := make([]item, len(names))
	for i, n := range names {
		c := ExportSegment(n)
		items[i] = item{index: i, clean: c, changed: c != n}
	}
	sort.SliceStable(items, func(a, b int) bool {
		if items[a].changed != items[b].changed {
			return !items[a].changed
		}
		return names[items[a].index] < names[items[b].index]
	})

	taken := make(map[string]bool, len(names))
	out := make([]string, len(names))
	for _, it := range items {
		name := it.clean
		for n := 2; taken[strings.ToLower(name)]; n++ {
			name = suffixed(it.clean, n)
		}
		taken[strings.ToLower(name)] = true
		out[it.index] = name
	}
	return out
}

// suffixed insère « (n) » avant l'extension, en rognant le radical si le nom
// dépasse la borne.
func suffixed(name string, n int) string {
	stem, ext := splitExt(name)
	suffix := fmt.Sprintf(" (%d)", n)
	// On rogne le radical *avant* d'ajouter le suffixe : rogner après le
	// ferait disparaître, et le nom resterait en collision indéfiniment.
	room := maxNameBytes - len(suffix) - len(ext)
	if room < 1 {
		return truncateKeepingExt(stem+suffix, maxNameBytes)
	}
	for len(stem) > room {
		_, size := utf8.DecodeLastRuneInString(stem)
		stem = stem[:len(stem)-size]
	}
	return stem + suffix + ext
}

// splitExt sépare l'extension, sans prendre « .gitignore » pour une extension.
func splitExt(name string) (stem, ext string) {
	ext = path.Ext(name)
	if ext == name {
		return name, ""
	}
	return strings.TrimSuffix(name, ext), ext
}

// truncateKeepingExt borne un nom à max octets en coupant le radical, jamais
// le suffixe, et jamais au milieu d'un caractère.
func truncateKeepingExt(name string, max int) string {
	if len(name) <= max {
		return name
	}
	stem, ext := splitExt(name)
	room := max - len(ext)
	if room < 1 {
		// Extension démesurée : on coupe le nom entier, faute de mieux.
		stem, ext, room = name, "", max
	}
	for len(stem) > room {
		_, size := utf8.DecodeLastRuneInString(stem)
		stem = stem[:len(stem)-size]
	}
	// Un radical rogné peut finir par un espace ou un point : Windows les
	// retirerait à l'extraction.
	stem = strings.TrimRight(stem, " .")
	if stem == "" {
		stem = "_"
	}
	return stem + ext
}
