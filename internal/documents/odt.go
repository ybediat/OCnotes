package documents

import (
	"bytes"
	"encoding/xml"
	"io"
	"strings"

	"github.com/ybediat/OpenNote/internal/markdown"
)

// Espaces de noms d'ODF, relevés sur un fichier produit par LibreOffice.
const (
	nsOffice = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
	nsTexte  = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
	nsStyle  = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
	nsTable  = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
	nsFo     = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
	nsXlink  = "http://www.w3.org/1999/xlink"
)

// Odt analyse un .odt et renvoie ses blocs d'affichage.
//
// Deux parties sont lues : content.xml, obligatoire, et styles.xml, qui porte
// les styles nommés dont héritent les styles automatiques du contenu.
//
// # Pourquoi deux passes sur le même fichier
//
// Là où OOXML pose le gras dans les propriétés du run lui-même, l'ODF pose un
// text:style-name qui renvoie à une table, plus haut dans le même fichier. Les
// styles sont donc collectés d'abord, le contenu ensuite. Un seul parcours
// marcherait — office:automatic-styles précède office:body dans le schéma —
// mais reposerait sur l'ordre du fichier, ce qu'un producteur n'a pas promis.
func Odt(data []byte) ([]markdown.Block, error) {
	z, err := ouvrir(data)
	if err != nil {
		return nil, err
	}

	contenu, err := partieObligatoire(z, "content.xml")
	if err != nil {
		return nil, err
	}
	styles, err := partie(z, "styles.xml")
	if err != nil {
		return nil, err
	}

	a := &odt{
		parents: map[string]string{},
		textes:  map[string][]markdown.Style{},
		listes:  map[string]map[int]bool{},
	}
	a.collecterStyles(styles)
	a.collecterStyles(contenu)

	if err := a.corps(contenu); err != nil {
		return nil, err
	}
	return a.out, nil
}

type odt struct {
	parents map[string]string           // style -> style dont il hérite
	textes  map[string][]markdown.Style // style de texte -> mises en forme
	listes  map[string]map[int]bool     // style de liste -> niveau -> numérotée

	out []markdown.Block
}

// contexteOdt descend avec le parcours : une liste imbriquée hérite du style
// de sa parente, et connaît sa propre profondeur.
type contexteOdt struct {
	profondeur int
	styleListe string
	numero     int
}

// --- Première passe : les styles --------------------------------------------

// collecterStyles remplit les tables d'héritage, de mise en forme et de listes.
//
// Appelée sur styles.xml puis sur content.xml : les deux parties déclarent des
// styles, et un style automatique du contenu hérite couramment d'un style nommé
// de l'autre partie. C'est ce chaînage qui fait qu'un paragraphe se révèle être
// un titre.
//
// Une partie illisible n'est pas fatale : on rend ce qu'on a pu en tirer.
func (a *odt) collecterStyles(data []byte) {
	if data == nil {
		return
	}

	d := xml.NewDecoder(bytes.NewReader(data))
	var courant, liste string
	for {
		tok, err := d.Token()
		if err != nil {
			return
		}
		switch t := tok.(type) {
		case xml.StartElement:
			switch {
			case estS(t.Name, "style"):
				courant = attr(t, nsStyle, "name")
				if parent := attr(t, nsStyle, "parent-style-name"); parent != "" {
					a.parents[courant] = parent
				}

			case estS(t.Name, "text-properties") && courant != "":
				if formes := misesEnForme(t); len(formes) > 0 {
					a.textes[courant] = formes
				}

			case estT(t.Name, "list-style"):
				liste = attr(t, nsStyle, "name")
				if liste != "" && a.listes[liste] == nil {
					a.listes[liste] = map[int]bool{}
				}

			case liste != "" && t.Name.Space == nsTexte && strings.HasPrefix(t.Name.Local, "list-level-style-"):
				// text:level est 1-based, contrairement au w:ilvl d'OOXML.
				a.listes[liste][entier(attr(t, nsTexte, "level"))] = t.Name.Local == "list-level-style-number"
			}

		case xml.EndElement:
			switch {
			case estS(t.Name, "style"):
				courant = ""
			case estT(t.Name, "list-style"):
				liste = ""
			}
		}
	}
}

// misesEnForme lit les mises en forme d'un style:text-properties.
//
// Les quatre attributs sont ceux relevés sur la fixture. Comparer à « none »
// plutôt qu'à la présence : l'ODF éteint un souligné hérité en le déclarant
// explicitement absent.
func misesEnForme(e xml.StartElement) []markdown.Style {
	var formes []markdown.Style
	if v := attr(e, nsFo, "font-weight"); v != "" && v != "normal" {
		formes = append(formes, markdown.StyleBold)
	}
	if v := attr(e, nsFo, "font-style"); v == "italic" || v == "oblique" {
		formes = append(formes, markdown.StyleItalic)
	}
	if v := attr(e, nsStyle, "text-underline-style"); v != "" && v != "none" {
		formes = append(formes, markdown.StyleUnderline)
	}
	if v := attr(e, nsStyle, "text-line-through-style"); v != "" && v != "none" {
		formes = append(formes, markdown.StyleStrike)
	}
	return formes
}

// niveauDeStyle remonte la chaîne d'héritage jusqu'à trouver un style de titre.
//
// C'est ce qui rattrape le cas le plus coûteux du format : le titre de niveau 1
// d'un document converti n'est pas un text:h mais un text:p, dont seul le style
// parent — « Heading_20_1 » — dit ce qu'il est. Un analyseur qui ne lirait que
// text:h perdrait le titre principal de tout document, en silence.
//
// La remontée est bornée : un fichier peut décrire un cycle d'héritage, et une
// boucle infinie dans un visualiseur est pire qu'un titre manquant.
func (a *odt) niveauDeStyle(nom string) int {
	for i := 0; nom != "" && i < 8; i++ {
		if n := niveauDepuisNom(nom); n > 0 {
			return n
		}
		nom = a.parents[nom]
	}
	return 0
}

// genreListe dit si une liste s'affiche en puces ou en numéros.
func (a *odt) genreListe(style string, profondeur int) markdown.Kind {
	if a.listes[style][profondeur+1] {
		return markdown.KindOrdered
	}
	return markdown.KindBullet
}

// --- Seconde passe : le contenu ---------------------------------------------

// corps trouve office:text et lui délègue le parcours.
//
// Entrer par le corps plutôt que par la racine évite d'avoir à ignorer
// office:automatic-styles, qui contient lui aussi des text:p — dans un modèle
// de note de bas de page, par exemple.
func (a *odt) corps(data []byte) error {
	d := xml.NewDecoder(bytes.NewReader(data))
	for {
		tok, err := d.Token()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return erreurXML(err)
		}
		if t, ok := tok.(xml.StartElement); ok && estO(t.Name, "text") {
			return a.blocs(d, t.Name, contexteOdt{})
		}
	}
}

// blocs parcourt les éléments de niveau bloc jusqu'à la fermeture de arret.
func (a *odt) blocs(d *xml.Decoder, arret xml.Name, ctx contexteOdt) error {
	for {
		tok, err := d.Token()
		if err != nil {
			return erreurXML(err)
		}
		switch t := tok.(type) {
		case xml.StartElement:
			switch {
			case estT(t.Name, "h"):
				if err := a.titre(d, t); err != nil {
					return err
				}
			case estT(t.Name, "p"):
				if err := a.paragraphe(d, t, ctx); err != nil {
					return err
				}
			case estT(t.Name, "list"):
				if err := a.liste(d, t, ctx); err != nil {
					return err
				}
			case estTab(t.Name, "table"):
				if err := a.tableau(d, t); err != nil {
					return err
				}
			case estT(t.Name, "sequence-decls"), estO(t.Name, "forms"):
				// Déclarations techniques : rien à afficher, et leurs
				// attributs ressemblent assez au contenu pour tromper.
				if err := d.Skip(); err != nil {
					return erreurXML(err)
				}
			}
		case xml.EndElement:
			if t.Name == arret {
				return nil
			}
		}
	}
}

// titre rend un text:h.
func (a *odt) titre(d *xml.Decoder, ouverture xml.StartElement) error {
	niveau := entier(attr(ouverture, nsTexte, "outline-level"))
	if niveau <= 0 {
		niveau = a.niveauDeStyle(attr(ouverture, nsTexte, "style-name"))
	}
	if niveau <= 0 {
		niveau = 1
	}
	if niveau > 6 {
		niveau = 6
	}

	var c constructeur
	if err := a.contenu(d, &c, ouverture.Name); err != nil {
		return err
	}
	if texte, spans := c.fini(); texte != "" {
		a.push(markdown.Block{Kind: markdown.KindHeading, Level: niveau, Text: texte, Spans: spans})
	}
	return nil
}

// paragraphe rend un text:p hors liste — qui peut se révéler être un titre.
func (a *odt) paragraphe(d *xml.Decoder, ouverture xml.StartElement, ctx contexteOdt) error {
	var c constructeur
	if err := a.contenu(d, &c, ouverture.Name); err != nil {
		return err
	}
	texte, spans := c.fini()
	if texte == "" {
		return nil
	}

	b := markdown.Block{Kind: markdown.KindParagraph, Text: texte, Spans: spans, Depth: ctx.profondeur}
	if n := a.niveauDeStyle(attr(ouverture, nsTexte, "style-name")); n > 0 {
		b.Kind, b.Level, b.Depth = markdown.KindHeading, n, 0
	}
	a.push(b)
	return nil
}

// liste rend un text:list.
//
// L'imbrication est structurelle en ODF — un text:list dans un text:list-item —
// ce qui rend la profondeur et la numérotation bien plus sûres qu'en OOXML : il
// n'y a rien à déduire, le compteur naît et meurt avec la liste.
func (a *odt) liste(d *xml.Decoder, ouverture xml.StartElement, ctx contexteOdt) error {
	style := attr(ouverture, nsTexte, "style-name")
	if style == "" {
		// Une sous-liste ne redéclare pas son style : elle hérite de sa mère.
		style = ctx.styleListe
	}

	numero := 0
	for {
		tok, err := d.Token()
		if err != nil {
			return erreurXML(err)
		}
		switch t := tok.(type) {
		case xml.StartElement:
			switch {
			case estT(t.Name, "list-item"):
				numero++
				suite := contexteOdt{profondeur: ctx.profondeur, styleListe: style, numero: numero}
				if err := a.item(d, t.Name, suite); err != nil {
					return err
				}
			case estT(t.Name, "list-header"):
				// Un en-tête de liste n'est pas numéroté : il introduit.
				suite := contexteOdt{profondeur: ctx.profondeur, styleListe: style}
				if err := a.item(d, t.Name, suite); err != nil {
					return err
				}
			}
		case xml.EndElement:
			if t.Name == ouverture.Name {
				return nil
			}
		}
	}
}

// item rend un text:list-item.
//
// Son premier paragraphe porte le marqueur ; ce qui suit — paragraphe de suite,
// sous-liste — descend d'un cran, exactement comme dans le rendu Markdown.
func (a *odt) item(d *xml.Decoder, arret xml.Name, ctx contexteOdt) error {
	premier := true
	for {
		tok, err := d.Token()
		if err != nil {
			return erreurXML(err)
		}
		switch t := tok.(type) {
		case xml.StartElement:
			switch {
			case estT(t.Name, "p"):
				var c constructeur
				if err := a.contenu(d, &c, t.Name); err != nil {
					return err
				}
				texte, spans := c.fini()
				if texte == "" {
					continue
				}
				b := markdown.Block{Text: texte, Spans: spans, Depth: ctx.profondeur}
				if premier {
					b.Kind = a.genreListe(ctx.styleListe, ctx.profondeur)
					if b.Kind == markdown.KindOrdered {
						b.Number = ctx.numero
					}
					premier = false
				} else {
					b.Kind = markdown.KindParagraph
					b.Depth = ctx.profondeur + 1
				}
				a.push(b)

			case estT(t.Name, "list"):
				suite := contexteOdt{profondeur: ctx.profondeur + 1, styleListe: ctx.styleListe}
				if err := a.liste(d, t, suite); err != nil {
					return err
				}

			case estT(t.Name, "h"):
				if err := a.titre(d, t); err != nil {
					return err
				}
			}
		case xml.EndElement:
			if t.Name == arret {
				return nil
			}
		}
	}
}

// tableau aplatit un table:table en une suite de lignes.
//
// L'en-tête se lit : table:table-header-rows est un élément à part entière, et
// les lignes qu'il contient sont les lignes d'en-tête. Rien à deviner.
func (a *odt) tableau(d *xml.Decoder, ouverture xml.StartElement) error {
	entete := false
	for {
		tok, err := d.Token()
		if err != nil {
			return erreurXML(err)
		}
		switch t := tok.(type) {
		case xml.StartElement:
			switch {
			case estTab(t.Name, "table-header-rows"):
				entete = true
			case estTab(t.Name, "table-row"):
				if err := a.ligne(d, t, entete); err != nil {
					return err
				}
			}
		case xml.EndElement:
			switch {
			case t.Name == ouverture.Name:
				return nil
			case estTab(t.Name, "table-header-rows"):
				entete = false
			}
		}
	}
}

func (a *odt) ligne(d *xml.Decoder, ouverture xml.StartElement, entete bool) error {
	cellules := []string{}
	for {
		tok, err := d.Token()
		if err != nil {
			return erreurXML(err)
		}
		switch t := tok.(type) {
		case xml.StartElement:
			switch {
			case estTab(t.Name, "table-cell"):
				texte, err := a.cellule(d, t.Name)
				if err != nil {
					return err
				}
				cellules = append(cellules, texte)
			case estTab(t.Name, "covered-table-cell"):
				// Cellule avalée par une fusion : elle occupe une colonne sans
				// porter de texte. L'omettre décalerait la ligne.
				cellules = append(cellules, "")
				if err := d.Skip(); err != nil {
					return erreurXML(err)
				}
			}
		case xml.EndElement:
			if t.Name == ouverture.Name {
				a.push(markdown.Block{Kind: markdown.KindTableRow, Cells: cellules, Header: entete})
				return nil
			}
		}
	}
}

// cellule rend le texte d'une cellule, ses paragraphes séparés par une espace.
func (a *odt) cellule(d *xml.Decoder, arret xml.Name) (string, error) {
	var c constructeur
	for {
		tok, err := d.Token()
		if err != nil {
			return "", erreurXML(err)
		}
		switch t := tok.(type) {
		case xml.StartElement:
			if estT(t.Name, "p") || estT(t.Name, "h") {
				if c.n > 0 {
					c.write(" ")
				}
				if err := a.contenu(d, &c, t.Name); err != nil {
					return "", err
				}
			}
		case xml.EndElement:
			if t.Name == arret {
				return strings.TrimSpace(c.texte.String()), nil
			}
		}
	}
}

// contenu accumule le texte et les spans d'un élément, jusqu'à sa fermeture.
//
// La récursion sur text:span et text:a est ce qui permet aux mises en forme de
// s'imbriquer : la borne de début est prise avant de descendre, la fin est là
// où la descente s'est arrêtée.
func (a *odt) contenu(d *xml.Decoder, c *constructeur, arret xml.Name) error {
	for {
		tok, err := d.Token()
		if err != nil {
			return erreurXML(err)
		}
		switch t := tok.(type) {
		case xml.CharData:
			c.write(string(t))

		case xml.StartElement:
			switch {
			case estT(t.Name, "span"):
				debut := c.n
				formes := a.textes[attr(t, nsTexte, "style-name")]
				if err := a.contenu(d, c, t.Name); err != nil {
					return err
				}
				for _, forme := range formes {
					c.span(debut, forme, "")
				}

			case estT(t.Name, "a"):
				debut := c.n
				href := attr(t, nsXlink, "href")
				if err := a.contenu(d, c, t.Name); err != nil {
					return err
				}
				c.span(debut, markdown.StyleLink, href)

			case estT(t.Name, "s"):
				// L'ODF n'écrit pas deux espaces de suite : il compte.
				n := entier(attr(t, nsTexte, "c"))
				if n < 1 {
					n = 1
				}
				c.write(strings.Repeat(" ", n))
				if err := d.Skip(); err != nil {
					return erreurXML(err)
				}

			case estT(t.Name, "tab"):
				c.write("\t")
				if err := d.Skip(); err != nil {
					return erreurXML(err)
				}

			case estT(t.Name, "line-break"):
				c.write("\n")
				if err := d.Skip(); err != nil {
					return erreurXML(err)
				}

			case estT(t.Name, "note"), estO(t.Name, "annotation"):
				// Une note de bas de page ou un commentaire n'est pas dans le
				// fil du texte : le laisser passer collerait son contenu au
				// milieu d'une phrase.
				if err := d.Skip(); err != nil {
					return erreurXML(err)
				}
			}

		case xml.EndElement:
			if t.Name == arret {
				return nil
			}
		}
	}
}

func (a *odt) push(b markdown.Block) {
	a.out = append(a.out, markdown.ProtectLayout(b))
}

func estO(n xml.Name, local string) bool   { return n.Space == nsOffice && n.Local == local }
func estT(n xml.Name, local string) bool   { return n.Space == nsTexte && n.Local == local }
func estS(n xml.Name, local string) bool   { return n.Space == nsStyle && n.Local == local }
func estTab(n xml.Name, local string) bool { return n.Space == nsTable && n.Local == local }
