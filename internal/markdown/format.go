package markdown

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf16"
)

// Action désigne une opération de mise en forme de la barre d'outils.
//
// Le type est une chaîne pour traverser sans friction la frontière gomobile,
// où les énumérations Go ne se transmettent pas.
type Action string

const (
	ActionBold          Action = "bold"
	ActionItalic        Action = "italic"
	ActionStrikethrough Action = "strikethrough"
	ActionInlineCode    Action = "code"
	ActionLink          Action = "link"

	ActionH1        Action = "h1"
	ActionH2        Action = "h2"
	ActionH3        Action = "h3"
	ActionBullet    Action = "bullet"
	ActionNumbered  Action = "numbered"
	ActionTask      Action = "task"
	ActionQuote     Action = "quote"
	ActionCodeBlock Action = "codeblock"
)

// Actions énumère les opérations disponibles, dans l'ordre où elles ont du
// sens dans une barre d'outils.
func Actions() []Action {
	return []Action{
		ActionBold, ActionItalic, ActionStrikethrough, ActionInlineCode, ActionLink,
		ActionH1, ActionH2, ActionH3,
		ActionBullet, ActionNumbered, ActionTask,
		ActionQuote, ActionCodeBlock,
	}
}

// Apply exécute une action de mise en forme.
//
// Chaque action est une bascule : la réappliquer à un texte qui la porte déjà
// la retire. C'est le comportement attendu d'un bouton de barre d'outils.
func Apply(d Doc, action Action) (Doc, error) {
	switch action {
	case ActionBold:
		return wrap(d, "**"), nil
	case ActionItalic:
		return wrap(d, "*"), nil
	case ActionStrikethrough:
		return wrap(d, "~~"), nil
	case ActionInlineCode:
		return wrap(d, "`"), nil
	case ActionLink:
		return link(d), nil

	case ActionH1:
		return linePrefix(d, "# "), nil
	case ActionH2:
		return linePrefix(d, "## "), nil
	case ActionH3:
		return linePrefix(d, "### "), nil
	case ActionBullet:
		return linePrefix(d, "- "), nil
	case ActionNumbered:
		return linePrefix(d, numberedPrefix), nil
	case ActionTask:
		return linePrefix(d, "- [ ] "), nil
	case ActionQuote:
		return linePrefix(d, "> "), nil
	case ActionCodeBlock:
		return codeBlock(d), nil

	default:
		return d, fmt.Errorf("markdown: action inconnue %q", action)
	}
}

// numberedPrefix est un marqueur interne : la numérotation dépend du rang de
// la ligne, elle est donc calculée au moment de l'application.
const numberedPrefix = "\x00numbered"

// --- Mise en forme en ligne -------------------------------------------------

// wrap entoure la sélection d'un marqueur, ou le retire s'il est déjà présent.
//
// Le marqueur est cherché des deux côtés : à l'intérieur de la sélection
// (l'utilisateur a sélectionné « **gras** » marqueurs compris) et juste à
// l'extérieur (il a sélectionné « gras » entre les marqueurs). Sans le second
// cas, cliquer deux fois sur Gras produirait « ****gras**** ».
func wrap(d Doc, marker string) Doc {
	units, start, end := d.decode()
	m := utf16.Encode([]rune(marker))
	n := len(m)

	// Marqueurs à l'intérieur de la sélection.
	if end-start >= 2*n &&
		markerAt(units, start, m, +1) &&
		markerAt(units, end-n, m, -1) {
		return build(start, end-2*n,
			units[:start], units[start+n:end-n], units[end:])
	}

	// Marqueurs juste à l'extérieur de la sélection.
	if start >= n && end+n <= len(units) &&
		markerAt(units, start-n, m, -1) &&
		markerAt(units, end, m, +1) {
		return build(start-n, end-n,
			units[:start-n], units[start:end], units[end+n:])
	}

	// Sinon on entoure. Sur un simple curseur, on le place entre les marqueurs
	// pour que l'utilisateur puisse taper directement.
	if start == end {
		return build(start+n, start+n,
			units[:start], m, m, units[end:])
	}
	return build(start+n, end+n,
		units[:start], m, units[start:end], m, units[end:])
}

// markerAt vérifie qu'un marqueur commence à la position donnée, et qu'il ne
// fait pas partie d'une suite plus longue du même caractère.
//
// Cette seconde condition évite qu'appliquer l'italique (« * ») à du texte en
// gras (« ** ») ne défasse le gras : dans « **mot** », le « * » adjacent
// appartient à un marqueur de deux caractères, pas à un marqueur d'italique.
// direction indique de quel côté prolonger la vérification.
func markerAt(units []uint16, pos int, marker []uint16, direction int) bool {
	if pos < 0 || pos+len(marker) > len(units) {
		return false
	}
	if !unitsEqual(units[pos:pos+len(marker)], marker) {
		return false
	}

	// Le contrôle de suite n'a de sens que pour un marqueur fait d'un seul
	// caractère répété, ce qui est le cas de tous les nôtres.
	ch := marker[0]
	for _, u := range marker {
		if u != ch {
			return true
		}
	}

	if direction > 0 {
		next := pos + len(marker)
		return next >= len(units) || units[next] != ch
	}
	prev := pos - 1
	return prev < 0 || units[prev] != ch
}

// link insère un lien Markdown.
//
// Avec une sélection, celle-ci devient le libellé et le curseur se place dans
// l'URL, qui est ce qu'il reste à saisir. Sans sélection, le curseur se place
// dans le libellé.
func link(d Doc) Doc {
	units, start, end := d.decode()

	open := utf16.Encode([]rune("["))
	middle := utf16.Encode([]rune("]("))
	closing := utf16.Encode([]rune(")"))

	if start == end {
		pos := start + len(open)
		return build(pos, pos, units[:start], open, middle, closing, units[end:])
	}

	pos := start + len(open) + (end - start) + len(middle)
	return build(pos, pos,
		units[:start], open, units[start:end], middle, closing, units[end:])
}

// --- Mise en forme par ligne ------------------------------------------------

// linePrefixRe reconnaît l'indentation puis un éventuel préfixe de style.
//
// Les styles de ligne — titre, liste, case à cocher, citation — forment une
// seule famille mutuellement exclusive : appliquer « liste » à un titre le
// convertit en liste plutôt que de produire « - # Titre ». C'est le
// comportement attendu d'une barre d'outils simple.
//
// L'ordre des alternatives compte : la case à cocher doit être reconnue avant
// la puce, dont elle est un cas particulier.
//
// Un numéro de liste a au plus neuf chiffres, `.` ou `)` pour délimiteur :
// c'est la règle CommonMark, et ce qui garde `N+1` loin de tout débordement.
var linePrefixRe = regexp.MustCompile(`^([ \t]*)((?:#{1,6} )|(?:[-*+] \[[ xX]\] )|(?:[-*+] )|(?:\d{1,9}[.)] )|(?:> ))?`)

// splitLine sépare une ligne en indentation, préfixe de style et contenu.
func splitLine(line string) (indent, prefix, rest string) {
	m := linePrefixRe.FindStringSubmatch(line)
	indent, prefix = m[1], m[2]
	return indent, prefix, line[len(indent)+len(prefix):]
}

// lineRange délimite une ligne dans un tableau d'unités ; end exclut le saut
// de ligne.
type lineRange struct {
	start, end int
}

func splitLines(units []uint16) []lineRange {
	lines := []lineRange{}
	start := 0
	for i, u := range units {
		if u == '\n' {
			lines = append(lines, lineRange{start, i})
			start = i + 1
		}
	}
	return append(lines, lineRange{start, len(units)})
}

// selectedLines délimite les lignes touchées par la sélection.
//
// Une sélection qui s'arrête exactement au début d'une ligne ne l'embarque
// pas : sélectionner « un\n » dans « un\ndeux » ne doit pas mettre en forme
// la ligne « deux ».
func selectedLines(lines []lineRange, start, end int) (first, last int) {
	first, last = -1, -1
	for i, ln := range lines {
		touched := ln.start <= end && ln.end >= start
		if touched && start != end && ln.start == end && ln.start > start {
			touched = false
		}
		if touched {
			if first == -1 {
				first = i
			}
			last = i
		}
	}
	if first == -1 {
		return 0, 0
	}
	return first, last
}

// linePrefix applique — ou retire — un préfixe de style aux lignes touchées
// par la sélection.
//
// Si toutes les lignes concernées portent déjà exactement ce préfixe, l'action
// le retire. Sinon elle l'applique partout, en remplaçant tout préfixe de
// style existant.
func linePrefix(d Doc, prefix string) Doc {
	units, start, end := d.decode()
	lines := splitLines(units)
	first, last := selectedLines(lines, start, end)

	// Bascule : toutes les lignes non vides portent-elles déjà ce préfixe ?
	var removing bool
	if prefix == numberedPrefix {
		removing = numberedRun(units, lines, first, last)
	} else {
		removing = samePrefixRun(units, lines, first, last, prefix)
	}

	// Une liste numérotée reprend celle qui la précède immédiatement, au même
	// retrait : numéroter la ligne qui suit « 3. c » donne « 4. », pas « 1. ».
	// Seule la ligne d'avant est lue — c'est la ligne de contexte que la
	// fenêtre de l'éditeur fournit déjà (`fenetreMiseEnForme`).
	numberStart, numberDelim := 1, byte('.')
	if prefix == numberedPrefix && !removing && first > 0 {
		prevIndent, prevPrefix, _ := splitLine(lineText(units, lines[first-1]))
		indent, _, _ := splitLine(lineText(units, lines[first]))
		if n, delim, ok := parseNumbered(prevPrefix); ok && prevIndent == indent {
			numberStart, numberDelim = n+1, delim
		}
	}

	// Réécriture ligne à ligne, en mémorisant l'ancienne et la nouvelle
	// longueur d'en-tête. Les préfixes et l'indentation sont en ASCII : leur
	// longueur en octets vaut leur longueur en unités UTF-16.
	rebuilt := make([]string, len(lines))
	oldLead := make([]int, len(lines))
	newLead := make([]int, len(lines))
	numbering := 0

	for i, ln := range lines {
		text := lineText(units, ln)
		if i < first || i > last {
			rebuilt[i] = text
			continue
		}

		indent, existing, rest := splitLine(text)
		wanted := ""
		if !removing {
			if prefix == numberedPrefix {
				wanted = fmt.Sprintf("%d%c ", numberStart+numbering, numberDelim)
				numbering++
			} else {
				wanted = prefix
			}
		}

		rebuilt[i] = indent + wanted + rest
		oldLead[i] = len(indent) + len(existing)
		newLead[i] = len(indent) + len(wanted)
	}

	// Décalage cumulé introduit par les lignes précédentes.
	shifts := make([]int, len(lines))
	acc := 0
	for i := range lines {
		shifts[i] = acc
		acc += newLead[i] - oldLead[i]
	}

	// Report des bornes de sélection, calculé à partir des positions
	// d'origine — jamais de proche en proche, sous peine de les décaler
	// plusieurs fois.
	cursor := start == end
	mapPos := func(pos int) int {
		for i, ln := range lines {
			if pos > ln.end && i < len(lines)-1 {
				continue
			}
			switch {
			case pos <= ln.start && !cursor:
				// Une borne de sélection reste accrochée au début de ligne,
				// de sorte que les lignes entières restent sélectionnées.
				return ln.start + shifts[i]
			case pos <= ln.start+oldLead[i]:
				// Une position dans l'en-tête est ramenée au début du
				// contenu : sinon elle atterrirait dans le balisage.
				return ln.start + shifts[i] + newLead[i]
			default:
				return pos + shifts[i] + newLead[i] - oldLead[i]
			}
		}
		return pos
	}

	text := strings.Join(rebuilt, "\n")
	n := len(utf16.Encode([]rune(text)))
	return Doc{
		Text:  text,
		Start: clamp(mapPos(start), 0, n),
		End:   clamp(mapPos(end), 0, n),
	}
}

// samePrefixRun dit si toutes les lignes non vides de la sélection portent
// déjà exactement [prefix]. Une ligne vide ne décide pas de la bascule ; une
// sélection sans aucune ligne non vide n'est jamais « déjà mise en forme ».
func samePrefixRun(units []uint16, lines []lineRange, first, last int, prefix string) bool {
	meaningful := 0
	for i := first; i <= last; i++ {
		_, existing, rest := splitLine(lineText(units, lines[i]))
		if existing == "" && rest == "" {
			continue
		}
		meaningful++
		if existing != prefix {
			return false
		}
	}
	return meaningful > 0
}

// numberedRun dit si la sélection est déjà une suite numérotée, quel que soit
// son premier numéro : « 4. », « 5. », « 6. » se retire comme « 1. », « 2. »,
// « 3. ». Sans cela, une liste qui en reprend une autre ne pourrait plus être
// défaite par le bouton, qui la renumérotait au lieu de la retirer.
//
// Le rang compte les lignes vides, comme la numérotation à l'application :
// défaire doit reconnaître exactement ce que le bouton a écrit. Le délimiteur
// reste le même d'un bout à l'autre — en changer ouvre une autre liste.
func numberedRun(units []uint16, lines []lineRange, first, last int) bool {
	base, delim, meaningful := 0, byte(0), 0
	for i := first; i <= last; i++ {
		_, existing, rest := splitLine(lineText(units, lines[i]))
		if existing == "" && rest == "" {
			continue
		}
		n, d, ok := parseNumbered(existing)
		if !ok {
			return false
		}
		if meaningful == 0 {
			base, delim = n-(i-first), d
		} else if n != base+(i-first) || d != delim {
			return false
		}
		meaningful++
	}
	return meaningful > 0
}

// parseNumbered lit un préfixe de liste numérotée tel que [splitLine] le
// découpe — « 12. » ou « 12) », espace compris.
func parseNumbered(prefix string) (n int, delim byte, ok bool) {
	if len(prefix) < 3 || prefix[len(prefix)-1] != ' ' {
		return 0, 0, false
	}
	delim = prefix[len(prefix)-2]
	if delim != '.' && delim != ')' {
		return 0, 0, false
	}
	n, err := strconv.Atoi(prefix[:len(prefix)-2])
	if err != nil || n < 0 {
		return 0, 0, false
	}
	return n, delim, true
}

func lineText(units []uint16, ln lineRange) string {
	return decodeUnits(units[ln.start:ln.end])
}

// --- Bloc de code -----------------------------------------------------------

const fence = "```"

// codeBlock entoure les lignes sélectionnées d'une paire de délimiteurs, ou
// les retire si le bloc est déjà délimité.
func codeBlock(d Doc) Doc {
	units, start, end := d.decode()
	lines := splitLines(units)
	first, last := selectedLines(lines, start, end)

	isFence := func(i int) bool {
		if i < 0 || i >= len(lines) {
			return false
		}
		return strings.HasPrefix(strings.TrimSpace(lineText(units, lines[i])), fence)
	}

	var kept []string
	var shift int

	if isFence(first-1) && isFence(last+1) {
		for i, ln := range lines {
			if i == first-1 || i == last+1 {
				continue
			}
			kept = append(kept, lineText(units, ln))
		}
		// La ligne de délimiteur qui précédait disparaît, saut de ligne inclus.
		shift = -(lines[first-1].end - lines[first-1].start + 1)
	} else {
		for i, ln := range lines {
			if i == first {
				kept = append(kept, fence)
			}
			kept = append(kept, lineText(units, ln))
			if i == last {
				kept = append(kept, fence)
			}
		}
		shift = len(fence) + 1
	}

	text := strings.Join(kept, "\n")
	n := len(utf16.Encode([]rune(text)))
	return Doc{
		Text:  text,
		Start: clamp(start+shift, 0, n),
		End:   clamp(end+shift, 0, n),
	}
}
