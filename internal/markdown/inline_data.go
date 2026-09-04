package markdown

import (
	"strconv"
	"strings"
	"unicode"
	"unicode/utf16"
)

// PlaceholderScheme préfixe les jetons qui remplacent une donnée en ligne le
// temps d'une session d'édition.
//
// C'est volontairement une URL bien formée : « ![photo](ocnotes-image:0) »
// reste du Markdown valide, donc l'aperçu continue de le lire et d'afficher son
// repère d'image. Un caractère sentinelle exotique aurait cassé l'analyse.
const PlaceholderScheme = "ocnotes-image:"

// maxEditableWord borne la plus longue suite de caractères sans espace qu'un
// éditeur de texte accepte d'afficher.
//
// Ce n'est pas une limite de taille de note : 285 ko de prose se mettent en
// page sans peine, parce qu'ils offrent des milliers de points de coupure. Un
// mot unique de 60 000 caractères, lui, n'en offre aucun, et le moteur de
// retour à la ligne d'Android s'y épuise en mémoire native jusqu'à faire tuer
// l'application par le système — constaté sur appareil, sans la moindre
// exception Java pour l'expliquer.
//
// 2000 est choisi dans un écart énorme : le plus long mot d'une langue
// naturelle tient en 200 caractères, une image en base64 en fait des dizaines
// de milliers. Aucun texte réel ne tombe entre les deux.
const maxEditableWord = 2000

// MaxEditableWord est la borne exposée à l'interface, pour qu'elle n'ait pas à
// recopier la valeur dans sa propre formulation du message.
func MaxEditableWord() int { return maxEditableWord }

// ExtractInlineData sort les données en ligne du texte et les remplace par des
// jetons courts.
//
// L'éditeur web d'OpenCloud insère les images en « data:image/jpeg;base64,… ».
// Laisser ce pavé rejoindre le champ de saisie coûte l'application entière ;
// l'en sortir le temps de l'édition rend la note modifiable comme une autre.
//
// Renvoie le texte allégé et les données retirées, dans l'ordre de leurs
// jetons. RestoreInlineData refait le chemin inverse à l'identique — c'est la
// seule propriété qui compte ici, et un test aller-retour la vérifie.
func ExtractInlineData(source string) (string, []string) {
	if !strings.Contains(source, "](data:") {
		return source, nil
	}

	// Un contenu qui porte déjà nos jetons ne pourrait pas être restitué sans
	// ambiguïté : on renonce à extraire plutôt que de risquer d'injecter une
	// image là où l'utilisateur avait écrit du texte. Le garde-fou du mot trop
	// long prendra le relais si le fichier est réellement inaffichable.
	if strings.Contains(source, PlaceholderScheme) {
		return source, nil
	}

	var out strings.Builder
	var data []string

	rest := source
	for {
		ouverture := strings.Index(rest, "](data:")
		if ouverture < 0 {
			break
		}
		debut := ouverture + len("](")

		// Une donnée en base64 ne contient pas de parenthèse fermante : le
		// premier « ) » rencontré ferme donc bien le lien. Un titre éventuel
		// (« …base64,AAA "photo" ») part avec la donnée et revient avec elle,
		// ce qui préserve l'aller-retour sans avoir à l'analyser.
		fermeture := strings.IndexByte(rest[debut:], ')')
		if fermeture < 0 {
			break
		}
		fin := debut + fermeture

		out.WriteString(rest[:debut])
		out.WriteString(PlaceholderScheme)
		out.WriteString(strconv.Itoa(len(data)))
		data = append(data, rest[debut:fin])

		rest = rest[fin:]
	}
	out.WriteString(rest)

	return out.String(), data
}

// RestoreInlineData remet les données à la place de leurs jetons.
//
// Un jeton que l'utilisateur a effacé ne revient pas : supprimer le repère
// d'une image, c'est supprimer l'image, et c'est le seul geste dont il dispose
// pour le faire depuis un téléphone.
func RestoreInlineData(text string, data []string) string {
	if len(data) == 0 {
		return text
	}

	// À l'envers, et ce n'est pas un détail : « ocnotes-image:1 » est un
	// préfixe de « ocnotes-image:12 ». Traité dans l'ordre croissant, le
	// jeton 1 mangerait le début du jeton 12 et laisserait un « 2 » orphelin
	// collé à une image entière.
	out := text
	for i := len(data) - 1; i >= 0; i-- {
		out = strings.ReplaceAll(out, PlaceholderScheme+strconv.Itoa(i), data[i])
	}
	return out
}

// LongestWord renvoie la plus longue suite de caractères sans espace, en
// unités de code UTF-16.
//
// C'est le prédicat qui décide si un texte est affichable dans un champ de
// saisie — pas sa taille totale. Voir maxEditableWord.
func LongestWord(text string) int {
	longest := 0
	for _, mot := range strings.Fields(text) {
		if n := len(utf16.Encode([]rune(mot))); n > longest {
			longest = n
		}
	}
	return longest
}

// Editable indique qu'un texte peut être confié à un champ de saisie sans
// mettre l'appareil en danger.
func Editable(text string) bool {
	return LongestWord(text) <= maxEditableWord
}

// apercuMotTronque borne ce qui reste visible d'un mot démesuré.
//
// 40 caractères suffisent à reconnaître ce dont il s'agit — « data:image/jpeg;
// base64,/9j/4AAQ… » se lit tout seul — sans en garder assez pour peser.
const apercuMotTronque = 40

// ShortenLongWords remplace toute suite de caractères sans espace démesurée
// par son début suivi d'une ellipse.
//
// L'aperçu en a besoin autant que le champ de saisie : un Text et un TextField
// partagent le même moteur de mise en page, et le second mot de 60 000
// caractères tuerait l'application dans les deux. Le repli « ouvrir en lecture
// seule » ne protégeait donc rien tant que cette fonction n'existait pas.
//
// C'est une transformation **d'affichage** : elle ne touche jamais le contenu
// confié à l'éditeur, et rien de tronqué n'est jamais réécrit sur le serveur.
func ShortenLongWords(text string) (string, bool) {
	if LongestWord(text) <= maxEditableWord {
		return text, false
	}

	var out, mot strings.Builder
	vider := func() {
		s := mot.String()
		if len(utf16.Encode([]rune(s))) > maxEditableWord {
			s = tronquer(s)
		}
		out.WriteString(s)
		mot.Reset()
	}

	for _, r := range text {
		if unicode.IsSpace(r) {
			vider()
			out.WriteRune(r)
			continue
		}
		mot.WriteRune(r)
	}
	vider()

	return out.String(), true
}

func tronquer(mot string) string {
	runes := []rune(mot)
	if len(runes) > apercuMotTronque {
		runes = runes[:apercuMotTronque]
	}
	return string(runes) + "…"
}
