package markdown

import (
	"strings"
	"unicode/utf16"
	"unicode/utf8"
)

// maxOctetsMorceau borne la taille d'un morceau de texte courant, en octets.
//
// Compter les lignes ne suffit pas : un texte collé sans retour à la ligne
// tient sur une ou deux lignes de 100 000 caractères, que la règle des
// maxPlainLines lignes laisse entières. Au-delà de cette taille, le morceau est
// coupé aussi sur une espace, ce qui fait finir une ligne un peu plus tôt à
// l'écran à chaque jointure : c'est le prix d'un aperçu qui reste fluide.
// Les blocs de code n'y passent pas : une coupure au milieu d'une ligne de code
// la ferait paraître sur deux lignes.
const maxOctetsMorceau = 4096

// decouperGrosBlocs découpe en morceaux d'environ maxPlainLines lignes tout
// bloc de texte trop long pour un seul Text de Compose.
//
// Un paragraphe Markdown fait de lignes séparées par un simple retour reste
// un seul bloc pour goldmark : sur une note de 295 ko, un bloc de 150 000
// caractères, mesuré et dessiné en entier par l'aperçu — 0,8 s pour y entrer,
// autant par cran de loupe, la LazyColumn ne virtualisant rien à l'intérieur
// d'un élément. Le texte brut était déjà découpé (decouperTexteBrut) ; le
// Markdown passe désormais par la même règle.
//
// Rien n'est perdu ni ajouté : la concaténation des morceaux rend le texte du
// bloc. Les morceaux qui suivent le premier portent Cont, pour que l'aperçu
// les colle au précédent sans l'espace qui sépare deux blocs.
func decouperGrosBlocs(blocs []Block) []Block {
	var out []Block
	for i, b := range blocs {
		if !decoupable(b) {
			if out != nil {
				out = append(out, b)
			}
			continue
		}
		// Le tableau n'est recopié qu'au premier bloc découpé : une note sans
		// gros bloc ressort telle quelle, sans allocation.
		if out == nil {
			out = append(make([]Block, 0, len(blocs)+8), blocs[:i]...)
		}
		out = append(out, decouperBloc(b)...)
	}
	if out == nil {
		return blocs
	}
	return out
}

// decoupable dit si un bloc doit être découpé : un texte continu de plus de
// maxPlainLines lignes, dans un genre dont le texte se lit d'un seul tenant.
// Un titre tient sur une ligne ; une ligne de tableau, une image, un trait
// n'ont pas de texte à couper.
func decoupable(b Block) bool {
	switch b.Kind {
	case KindParagraph, KindBullet, KindOrdered, KindTask, KindCode:
	default:
		return false
	}
	if strings.Count(b.Text, "\n") >= maxPlainLines {
		return true
	}
	return b.Kind != KindCode && len(b.Text) > maxOctetsMorceau
}

// decouperBloc coupe un bloc selon decouperTexteBrut et répartit ses spans.
//
// Les bornes des spans sont en unités UTF-16 : on compte la longueur de chaque
// morceau dans la même unité, et un span à cheval sur une coupure devient un
// span dans chacun des deux morceaux.
func decouperBloc(b Block) []Block {
	morceaux := decouperTexteBrut(b.Text)
	if b.Kind != KindCode {
		morceaux = decouperLongsMorceaux(morceaux)
	}
	out := make([]Block, 0, len(morceaux))
	debut := 0 // position UTF-16 du morceau dans le texte du bloc
	for i, texte := range morceaux {
		fin := debut + len(utf16.Encode([]rune(texte)))

		m := b
		m.Text = texte
		m.Spans = nil
		for _, s := range b.Spans {
			d, f := max(s.Start, debut), min(s.End, fin)
			if d < f {
				m.Spans = append(m.Spans, Span{Start: d - debut, End: f - debut, Style: s.Style, Href: s.Href})
			}
		}
		if i > 0 {
			m.Cont = true
			// Seul le premier morceau commence là où commençait le bloc. La
			// position d'une tâche est celle de sa case, commune à tous ses
			// morceaux : comme après une image (voir inline), chacun la porte
			// pour que la bascule les coche ensemble.
			if b.Kind != KindTask {
				m.Source = nil
			}
		}
		out = append(out, m)
		debut = fin
	}
	return out
}

// decouperLongsMorceaux recoupe les morceaux trop gros (voir maxOctetsMorceau)
// juste après une espace. La concaténation reste exacte : l'espace termine le
// morceau de gauche, rien n'est ajouté ni retiré.
func decouperLongsMorceaux(morceaux []string) []string {
	var out []string
	for _, m := range morceaux {
		for len(m) > maxOctetsMorceau {
			coupe := coupureSurEspace(m)
			out = append(out, m[:coupe])
			m = m[coupe:]
		}
		out = append(out, m)
	}
	return out
}

// coupureSurEspace donne la position de coupe d'un texte plus long que
// maxOctetsMorceau : juste après la dernière espace (ou le dernier retour à
// la ligne) de la première moitié à la borne, sinon à la borne elle-même,
// ramenée au début d'un caractère pour ne jamais couper un caractère en deux.
func coupureSurEspace(m string) int {
	borne := maxOctetsMorceau
	if i := strings.LastIndexAny(m[maxOctetsMorceau/2:borne], " \t\n"); i >= 0 {
		return maxOctetsMorceau/2 + i + 1
	}
	for borne > 0 && !utf8.RuneStart(m[borne]) {
		borne--
	}
	return borne
}
