package markdown

import "strings"

// Title extrait un titre lisible d'une note.
//
// Le premier titre de niveau 1 fait foi ; à défaut, la première ligne non vide
// débarrassée de son préfixe de style. Le contenu des blocs de code est
// ignoré : un commentaire « # TODO » dans un extrait de shell ne doit pas
// devenir le titre de la note.
//
// Renvoie une chaîne vide si la note ne contient rien d'exploitable ; c'est à
// l'appelant de retomber sur le nom du fichier.
func Title(text string) string {
	var fallback string
	inFence := false

	for _, raw := range strings.Split(text, "\n") {
		line := strings.TrimRight(raw, " \t\r")
		trimmed := strings.TrimSpace(line)

		if strings.HasPrefix(trimmed, fence) {
			inFence = !inFence
			continue
		}
		if inFence || trimmed == "" {
			continue
		}

		_, prefix, rest := splitLine(line)
		rest = strings.TrimSpace(rest)
		if rest == "" {
			continue
		}
		if prefix == "# " {
			return rest
		}
		if fallback == "" {
			fallback = rest
		}
	}
	return fallback
}
