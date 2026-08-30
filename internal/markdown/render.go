package markdown

import (
	"strings"
	"unicode/utf16"

	"github.com/yuin/goldmark"
	"github.com/yuin/goldmark/ast"
	"github.com/yuin/goldmark/extension"
	east "github.com/yuin/goldmark/extension/ast"
	"github.com/yuin/goldmark/text"
)

// Kind énumère les blocs qu'un aperçu sait afficher.
//
// Le modèle est délibérément *plat* : une note se rend en parcourant une liste,
// sans arbre à descendre côté Kotlin. L'imbrication survit dans Depth et Quote,
// qui suffisent à décaler un élément et à lui poser une barre de citation.
type Kind string

const (
	KindParagraph Kind = "paragraph"
	KindHeading   Kind = "heading"
	KindBullet    Kind = "bullet"
	KindOrdered   Kind = "ordered"
	KindTask      Kind = "task"
	KindCode      Kind = "code"
	KindRule      Kind = "rule"
	KindImage     Kind = "image"
	KindTableRow  Kind = "tablerow"

	// KindPlain porte un fichier qui n'est pas du Markdown, affiché tel quel.
	KindPlain Kind = "plain"
)

// Style énumère les mises en forme en ligne.
type Style string

const (
	StyleBold      Style = "bold"
	StyleItalic    Style = "italic"
	StyleStrike    Style = "strike"
	StyleCode      Style = "code"
	StyleLink      Style = "link"
	StyleUnderline Style = "underline"
)

// Span est une portion mise en forme du texte d'un bloc.
//
// Start et End sont en **unités de code UTF-16**, comme partout ailleurs à la
// frontière : c'est l'unité d'AnnotatedString et de TextRange dans Compose,
// donc Kotlin pose les bornes telles quelles. Les offsets d'origine, eux, sont
// des octets — la conversion se fait ici, une fois, plutôt que de l'autre côté
// à chaque bloc.
type Span struct {
	Start int
	End   int
	Style Style
	Href  string // uniquement pour StyleLink
}

// Block est une unité d'affichage : un paragraphe, un titre, une puce…
type Block struct {
	Kind  Kind
	Text  string
	Spans []Span

	Level   int      // titre : 1 à 6
	Depth   int      // imbrication de liste, 0 au premier niveau
	Quote   int      // imbrication de citation, 0 hors citation
	Number  int      // liste numérotée : le numéro à afficher
	Checked bool     // tâche cochée
	Lang    string   // bloc de code : le langage annoncé, s'il y en a un
	Cells   []string // ligne de tableau
	Header  bool     // ligne de tableau : c'est l'en-tête
}

// parser est construit une fois : goldmark est sûr en usage concurrent, et
// reconstruire l'analyseur à chaque aperçu ne servirait à rien.
//
// extension.GFM apporte les tableaux, le barré et les cases à cocher — que la
// barre d'outils de l'éditeur écrit elle-même (« ~~ », « - [ ] »). Sans elle,
// l'aperçu n'afficherait pas ce que ses propres boutons produisent.
var parser = goldmark.New(goldmark.WithExtensions(extension.GFM))

// Render analyse du Markdown et en renvoie les blocs d'affichage.
//
// Le HTML brut est ignoré plutôt que transmis : l'aperçu n'a pas de moteur
// pour l'interpréter, et une note vient d'un serveur partagé.
func Render(source string) []Block {
	if strings.TrimSpace(source) == "" {
		return nil
	}
	src := []byte(source)
	r := &renderer{src: src}
	r.walk(parser.Parser().Parse(text.NewReader(src)), blockCtx{})
	return r.out
}

// RenderPlain renvoie un fichier non-Markdown tel quel, en un seul bloc.
//
// Aucune interprétation : dans un .txt, « # » est un dièse et « - » un tiret.
// C'est le contrat annoncé à l'utilisateur, et le seul qui ne mente pas sur un
// fichier créé par un autre outil.
func RenderPlain(source string) []Block {
	body := strings.TrimRight(source, "\n")
	if strings.TrimSpace(body) == "" {
		return nil
	}
	return []Block{protegerLaMiseEnPage(Block{Kind: KindPlain, Text: body})}
}

// --- Parcours des blocs -----------------------------------------------------

type blockCtx struct {
	depth int
	quote int
}

func (c blockCtx) deeper() blockCtx { c.depth++; return c }
func (c blockCtx) quoted() blockCtx { c.quote++; return c }

type renderer struct {
	src []byte
	out []Block
}

func (r *renderer) walk(n ast.Node, ctx blockCtx) {
	for c := n.FirstChild(); c != nil; c = c.NextSibling() {
		r.node(c, ctx)
	}
}

func (r *renderer) node(n ast.Node, ctx blockCtx) {
	switch v := n.(type) {
	case *ast.Heading:
		r.inline(v, Block{Kind: KindHeading, Level: v.Level, Quote: ctx.quote})

	case *ast.Paragraph:
		r.inline(v, Block{Kind: KindParagraph, Depth: ctx.depth, Quote: ctx.quote})
	case *ast.TextBlock:
		r.inline(v, Block{Kind: KindParagraph, Depth: ctx.depth, Quote: ctx.quote})

	case *ast.FencedCodeBlock:
		r.push(Block{
			Kind:  KindCode,
			Text:  codeText(v, r.src),
			Lang:  string(v.Language(r.src)),
			Quote: ctx.quote,
		})
	case *ast.CodeBlock:
		r.push(Block{Kind: KindCode, Text: codeText(v, r.src), Quote: ctx.quote})

	case *ast.ThematicBreak:
		r.push(Block{Kind: KindRule, Quote: ctx.quote})

	case *ast.Blockquote:
		r.walk(v, ctx.quoted())

	case *ast.List:
		r.list(v, ctx)

	case *east.Table:
		r.table(v, ctx)

	case *ast.HTMLBlock:
		// Rien à afficher : voir Render.

	default:
		r.walk(n, ctx)
	}
}

func (r *renderer) list(l *ast.List, ctx blockCtx) {
	number := l.Start
	if number < 1 {
		number = 1
	}
	for item := l.FirstChild(); item != nil; item = item.NextSibling() {
		kind, n := KindBullet, 0
		if l.IsOrdered() {
			kind, n = KindOrdered, number
			number++
		}
		r.item(item, kind, n, ctx)
	}
}

// item rend un élément de liste : son premier paragraphe porte le marqueur,
// tout ce qui suit — paragraphe de suite, sous-liste — descend d'un cran.
func (r *renderer) item(item ast.Node, kind Kind, number int, ctx blockCtx) {
	marque := false
	for c := item.FirstChild(); c != nil; c = c.NextSibling() {
		if !marque {
			switch c.(type) {
			case *ast.TextBlock, *ast.Paragraph:
				tpl := Block{Kind: kind, Number: number, Depth: ctx.depth, Quote: ctx.quote}
				if checked, ok := taskState(c); ok {
					tpl.Kind, tpl.Checked, tpl.Number = KindTask, checked, 0
				}
				r.inline(c, tpl)
				marque = true
				continue
			}
		}
		r.node(c, ctx.deeper())
	}
}

// table aplatit un tableau en une suite de lignes.
//
// La mise en forme *à l'intérieur* d'une cellule est perdue : un tableau sert
// à comparer des valeurs, et un gras dans une cellule ne vaut pas le modèle
// qu'il faudrait pour le porter. À reprendre si l'usage le demande.
func (r *renderer) table(t *east.Table, ctx blockCtx) {
	for row := t.FirstChild(); row != nil; row = row.NextSibling() {
		_, header := row.(*east.TableHeader)

		cells := []string{}
		for cell := row.FirstChild(); cell != nil; cell = cell.NextSibling() {
			b := &inlineBuilder{}
			b.children(cell, r.src)
			cells = append(cells, b.text.String())
		}
		r.push(Block{Kind: KindTableRow, Cells: cells, Header: header, Quote: ctx.quote})
	}
}

// inline construit le texte et les spans d'un bloc.
//
// Une image rencontrée au premier niveau coupe le bloc et devient un bloc à
// elle seule. Ce n'est pas un raffinement d'affichage : l'éditeur web
// d'OpenCloud insère les images en « data:image/jpeg;base64,… », soit
// plusieurs mégaoctets d'URL. Laisser ça rejoindre le texte d'un paragraphe
// donnerait un pavé illisible, et le ferait traverser la frontière gomobile
// pour rien.
func (r *renderer) inline(n ast.Node, tpl Block) {
	b := &inlineBuilder{}
	for c := n.FirstChild(); c != nil; c = c.NextSibling() {
		if img, ok := c.(*ast.Image); ok {
			r.pushInline(b, tpl)
			b = &inlineBuilder{}
			r.push(Block{Kind: KindImage, Text: altText(img, r.src), Depth: tpl.Depth, Quote: tpl.Quote})
			continue
		}
		b.node(c, r.src)
	}
	r.pushInline(b, tpl)
}

func (r *renderer) pushInline(b *inlineBuilder, tpl Block) {
	texte := b.text.String()
	if strings.TrimSpace(texte) == "" && len(b.spans) == 0 {
		return
	}
	tpl.Text = texte
	tpl.Spans = b.spans
	r.push(tpl)
}

func (r *renderer) push(b Block) { r.out = append(r.out, protegerLaMiseEnPage(b)) }

// protegerLaMiseEnPage tronque les suites de caractères qu'Android ne saurait
// pas couper en lignes.
//
// Tous les blocs passent par ici, y compris les blocs de code et les cellules
// de tableau : un TextField et un Text partagent le même moteur de mise en
// page, et un mot de 60 000 caractères fait tuer l'application dans les deux.
// Le repli « ouvrir en lecture seule » n'aurait donc rien protégé sans cette
// étape.
//
// Les spans du bloc sont abandonnés quand le texte change : leurs bornes ne
// désigneraient plus rien. Perdre un gras sur un bloc qui contenait un pavé
// illisible est un prix qu'on paie volontiers.
func protegerLaMiseEnPage(b Block) Block {
	if court, tronque := ShortenLongWords(b.Text); tronque {
		b.Text = court
		b.Spans = nil
	}
	for i, cellule := range b.Cells {
		if court, tronque := ShortenLongWords(cellule); tronque {
			b.Cells[i] = court
		}
	}
	return b
}

// ProtectLayout applique cette protection à un bloc construit ailleurs.
//
// internal/documents produit ses blocs sans passer par Render, donc sans
// l'entonnoir de push. Sans cette porte, chaque analyseur réécrirait la même
// règle — dont l'abandon des spans, qui n'est pas évident — et la première
// divergence se paierait en appareil tué par un document.
func ProtectLayout(b Block) Block { return protegerLaMiseEnPage(b) }

// --- Parcours en ligne ------------------------------------------------------

// inlineBuilder accumule le texte d'un bloc en tenant à jour sa longueur en
// unités UTF-16.
//
// C'est le point délicat de tout le fichier. goldmark repère ses nœuds par des
// offsets en octets ; les convertir après coup demanderait de retraduire chaque
// borne, avec une occasion de se tromper par borne. En comptant au fil de
// l'écriture, il n'y a jamais d'offset à convertir.
type inlineBuilder struct {
	text     strings.Builder
	n        int
	spans    []Span
	trimNext bool
}

func (b *inlineBuilder) write(s string) {
	if b.trimNext {
		s = strings.TrimLeft(s, " \t")
		b.trimNext = false
	}
	if s == "" {
		return
	}
	b.text.WriteString(s)
	b.n += len(utf16.Encode([]rune(s)))
}

func (b *inlineBuilder) span(start int, style Style, href string) {
	if start >= b.n {
		return
	}
	b.spans = append(b.spans, Span{Start: start, End: b.n, Style: style, Href: href})
}

func (b *inlineBuilder) children(n ast.Node, src []byte) {
	for c := n.FirstChild(); c != nil; c = c.NextSibling() {
		b.node(c, src)
	}
}

func (b *inlineBuilder) node(n ast.Node, src []byte) {
	switch v := n.(type) {
	case *ast.Text:
		b.write(string(v.Segment.Value(src)))
		// Un retour simple devient un vrai retour à la ligne, plutôt qu'une
		// espace comme le veut CommonMark. Dans un carnet de notes, deux
		// lignes tapées l'une sous l'autre sont deux lignes voulues ; les
		// recoller ferait douter l'utilisateur de ce qu'il a écrit.
		if v.SoftLineBreak() || v.HardLineBreak() {
			b.write("\n")
		}

	case *ast.String:
		b.write(string(v.Value))

	case *ast.CodeSpan:
		start := b.n
		b.children(v, src)
		b.span(start, StyleCode, "")

	case *ast.Emphasis:
		start := b.n
		b.children(v, src)
		style := StyleItalic
		if v.Level >= 2 {
			style = StyleBold
		}
		b.span(start, style, "")

	case *east.Strikethrough:
		start := b.n
		b.children(v, src)
		b.span(start, StyleStrike, "")

	case *ast.Link:
		start := b.n
		b.children(v, src)
		b.span(start, StyleLink, string(v.Destination))

	case *ast.AutoLink:
		url := string(v.URL(src))
		start := b.n
		b.write(strings.TrimPrefix(url, "mailto:"))
		b.span(start, StyleLink, url)

	case *east.TaskCheckBox:
		// La case elle-même est portée par le bloc ; il reste l'espace qui la
		// suivait dans la source, à ne pas laisser en tête du texte.
		b.trimNext = true

	case *ast.RawHTML:
		// Rien à afficher.

	default:
		b.children(v, src)
	}
}

// --- Utilitaires ------------------------------------------------------------

// taskState lit la case à cocher qui ouvre un élément de liste.
func taskState(n ast.Node) (checked, ok bool) {
	box, is := n.FirstChild().(*east.TaskCheckBox)
	if !is || box == nil {
		return false, false
	}
	return box.IsChecked, true
}

// altText renvoie le texte alternatif d'une image, jamais sa source.
func altText(img *ast.Image, src []byte) string {
	b := &inlineBuilder{}
	b.children(img, src)
	return b.text.String()
}

func codeText(n ast.Node, src []byte) string {
	var sb strings.Builder
	lines := n.Lines()
	for i := 0; i < lines.Len(); i++ {
		seg := lines.At(i)
		sb.Write(seg.Value(src))
	}
	return strings.TrimRight(sb.String(), "\n")
}
