package notes

import "strings"

// LineEnding est la convention de fin de ligne d'un fichier texte.
//
// L'éditeur ne connaît que « \n » : la touche Entrée d'Android n'insère rien
// d'autre, et les mises en forme de internal/markdown découpent les lignes sur
// ce seul caractère. Un fichier écrit sous Windows, en « \r\n », sortait donc
// de sa première modification avec des fins de ligne mélangées — les lignes
// tapées en « \n », les autres en « \r\n », et un bloc de code dont les
// délimiteurs n'étaient pas de la même convention que son contenu.
//
// D'où la règle : on lit la convention du fichier, on la retire pour l'éditeur
// (NormalizeLineEndings), et on la remet à l'écriture (ApplyLineEnding).
type LineEnding string

const (
	LF   LineEnding = "\n"
	CRLF LineEnding = "\r\n"
	CR   LineEnding = "\r"
)

// DetectLineEnding renvoie la convention dominante d'un texte.
//
// Un fichier homogène — le cas de loin le plus courant — garde exactement la
// sienne, ce qui rend l'aller-retour exact. Un fichier déjà mélangé ressort
// uniforme, dans la convention de la majorité de ses lignes. À égalité, et
// pour un texte sans aucun saut de ligne, c'est « \n », la convention des
// notes que l'application crée.
func DetectLineEnding(text string) LineEnding {
	var crlf, lf, cr int
	for i := 0; i < len(text); i++ {
		switch text[i] {
		case '\r':
			if i+1 < len(text) && text[i+1] == '\n' {
				crlf++
				i++
			} else {
				cr++
			}
		case '\n':
			lf++
		}
	}
	switch {
	case crlf > lf && crlf >= cr:
		return CRLF
	case cr > lf && cr > crlf:
		return CR
	default:
		return LF
	}
}

// NormalizeLineEndings ramène toutes les fins de ligne à « \n ».
//
// « \r\n » est remplacé avant « \r » seul, sans quoi chaque fin de ligne
// Windows deviendrait deux sauts de ligne.
func NormalizeLineEndings(text string) string {
	if !strings.Contains(text, "\r") {
		return text
	}
	return strings.ReplaceAll(strings.ReplaceAll(text, "\r\n", "\n"), "\r", "\n")
}

// ApplyLineEnding écrit un texte dans la convention demandée.
//
// Le texte est d'abord normalisé : un « \r\n » collé depuis le presse-papiers
// dans un fichier en « \n » ne doit pas y réintroduire un mélange.
func ApplyLineEnding(text string, ending LineEnding) string {
	text = NormalizeLineEndings(text)
	if ending == LF || ending == "" {
		return text
	}
	return strings.ReplaceAll(text, "\n", string(ending))
}
