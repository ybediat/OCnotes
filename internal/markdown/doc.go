// Package markdown fournit les transformations de texte de l'éditeur.
//
// Toutes les fonctions y sont pures : elles prennent un état d'édition et en
// renvoient un nouveau, sans effet de bord. La barre d'outils de l'interface
// se contente de les appeler et de réappliquer la sélection retournée, ce qui
// évite de réimplémenter — et de déboguer — la logique de mise en forme en
// Kotlin.
package markdown

import "unicode/utf16"

// Doc est un état d'édition : un texte et la sélection qui le parcourt.
//
// Start et End sont exprimés en unités de code UTF-16, et non en octets ni en
// runes. C'est l'unité de TextRange dans Jetpack Compose, seul consommateur de
// ces fonctions : la frontière Kotlin n'a ainsi aucune conversion à faire, donc
// aucune occasion de se tromper.
//
// La distinction n'est pas théorique. Pour « é » : 2 octets, 1 rune, 1 unité
// UTF-16. Pour « 😀 » : 4 octets, 1 rune, 2 unités UTF-16. Un curseur calculé
// en octets se décalerait dès la première note accentuée.
type Doc struct {
	Text  string
	Start int
	End   int
}

// NewDoc construit un état d'édition dont le curseur est en fin de texte.
func NewDoc(text string) Doc {
	n := len(utf16.Encode([]rune(text)))
	return Doc{Text: text, Start: n, End: n}
}

// Length renvoie la longueur du texte en unités de code UTF-16.
func (d Doc) Length() int {
	return len(utf16.Encode([]rune(d.Text)))
}

// Selected renvoie le texte actuellement sélectionné.
func (d Doc) Selected() string {
	units, start, end := d.decode()
	return decodeUnits(units[start:end])
}

// decode renvoie le texte en unités UTF-16 et une sélection assainie :
// bornée au texte et ordonnée, car une sélection Compose peut être inversée
// quand l'utilisateur sélectionne de droite à gauche.
func (d Doc) decode() (units []uint16, start, end int) {
	units = utf16.Encode([]rune(d.Text))

	start, end = d.Start, d.End
	if start > end {
		start, end = end, start
	}
	start = clamp(start, 0, len(units))
	end = clamp(end, 0, len(units))

	// Une borne posée entre les deux moitiés d'une paire de substitution (un
	// emoji) est sortie de la paire : sinon le découpage écrirait chaque
	// moitié à part, soit deux « � » enregistrés dans la note. Un curseur
	// recule devant l'emoji ; une sélection s'élargit pour le contenir.
	if milieuDePaire(units, start) {
		start--
	}
	if milieuDePaire(units, end) {
		if end-1 == start {
			// C'était un curseur, déjà reculé avec start.
			end--
		} else {
			end++
		}
	}
	return units, start, end
}

// milieuDePaire dit si pos tombe entre la moitié haute et la moitié basse
// d'une paire de substitution UTF-16.
func milieuDePaire(units []uint16, pos int) bool {
	return pos > 0 && pos < len(units) &&
		units[pos-1] >= 0xD800 && units[pos-1] <= 0xDBFF &&
		units[pos] >= 0xDC00 && units[pos] <= 0xDFFF
}

func decodeUnits(units []uint16) string {
	return string(utf16.Decode(units))
}

// build assemble un nouveau document à partir de tranches d'unités.
func build(start, end int, parts ...[]uint16) Doc {
	total := 0
	for _, p := range parts {
		total += len(p)
	}
	out := make([]uint16, 0, total)
	for _, p := range parts {
		out = append(out, p...)
	}
	return Doc{Text: decodeUnits(out), Start: start, End: end}
}

func clamp(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func unitsEqual(a, b []uint16) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
