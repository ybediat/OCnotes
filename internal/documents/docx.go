package documents

import (
	"bytes"
	"encoding/xml"
	"io"
	"strconv"
	"strings"

	"github.com/ybediat/OpenNote/internal/markdown"
)

// Espaces de noms d'OOXML.
//
// Les comparer explicitement plutôt que de se fier au préfixe : « w: » est une
// convention du producteur, pas une garantie du format, et encoding/xml résout
// déjà les préfixes en URI. Un document qui déclarerait « x: » pour le même
// espace serait lu de la même façon.
const (
	nsWord = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
	nsRel  = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
)

// Docx analyse un .docx et renvoie ses blocs d'affichage.
//
// Quatre parties de l'archive sont lues, et une seule est obligatoire :
//
//   - word/document.xml         le contenu ;
//   - word/styles.xml           le nom canonique des styles, sans quoi les
//     titres d'un producteur localisé sont invisibles ;
//   - word/numbering.xml        le genre des listes, puce ou numéro ;
//   - word/_rels/document.xml.rels  la cible des liens hypertexte, absente du
//     document lui-même.
func Docx(data []byte) ([]markdown.Block, error) {
	z, err := ouvrir(data)
	if err != nil {
		return nil, err
	}

	corps, err := partieObligatoire(z, "word/document.xml")
	if err != nil {
		return nil, err
	}
	styles, err := partie(z, "word/styles.xml")
	if err != nil {
		return nil, err
	}
	numerotation, err := partie(z, "word/numbering.xml")
	if err != nil {
		return nil, err
	}
	relations, err := partie(z, "word/_rels/document.xml.rels")
	if err != nil {
		return nil, err
	}

	a := &docx{
		titres:    titresDocx(styles),
		formats:   numerotationsDocx(numerotation),
		liens:     liensDocx(relations),
		compteurs: map[string][]int{},
	}
	if err := a.corps(corps); err != nil {
		return nil, err
	}
	return a.out, nil
}

// docx porte les tables résolues avant la passe de contenu, et les blocs
// produits.
type docx struct {
	titres  map[string]int            // styleId -> niveau de titre
	formats map[string]map[int]string // numId -> ilvl -> w:numFmt
	liens   map[string]string         // r:id -> URL

	// compteurs tient la numérotation que nous fabriquons nous-mêmes, un
	// tableau de compteurs par liste, indexé par niveau.
	compteurs  map[string][]int
	dernierNum string

	out []markdown.Block
}

// --- Tables résolues avant la passe de contenu ------------------------------

// titresDocx associe chaque identifiant de style au niveau de titre qu'il
// désigne.
//
// C'est la passe qui sauve les documents produits par un logiciel localisé.
// LibreOffice en français écrit « Titre1 » comme identifiant de style, pas
// « Heading1 » : un analyseur qui ne reconnaît que la forme anglaise rend tous
// les titres en paragraphes, sans le moindre message. Le nom canonique, lui,
// est dans styles.xml à côté de l'identifiant :
//
//	<w:style w:styleId="Titre1"><w:name w:val="Heading 1"/>…
//
// w:outlineLvl serait le candidat évident, et c'est un piège : il est 0-based,
// et absent du style de niveau 1. Constaté, pas déduit.
//
// Un styles.xml illisible n'est pas fatal : on rend ce qu'on a pu en tirer, et
// le document reste lisible avec ses titres en paragraphes.
func titresDocx(data []byte) map[string]int {
	titres := map[string]int{}
	if data == nil {
		return titres
	}

	d := xml.NewDecoder(bytes.NewReader(data))
	var courant string
	for {
		tok, err := d.Token()
		if err != nil {
			return titres
		}
		switch t := tok.(type) {
		case xml.StartElement:
			switch {
			case estW(t.Name, "style"):
				courant = attr(t, nsWord, "styleId")
			case estW(t.Name, "name") && courant != "":
				if n := niveauDepuisNom(attr(t, nsWord, "val")); n > 0 {
					titres[courant] = n
				}
			}
		case xml.EndElement:
			if estW(t.Name, "style") {
				courant = ""
			}
		}
	}
}

// numerotationsDocx associe chaque instance de liste au format de ses niveaux.
//
// Le plan de ce chantier disait « ne résolvez pas numbering.xml ». C'est
// intenable : document.xml ne dit nulle part si une liste est à puces ou
// numérotée — le style de paragraphe est le même dans les deux cas — et seul
// w:numFmt le sait.
//
// Ce qui est résolu ici est le minimum utile :
//
//	w:num[@w:numId] -> w:abstractNumId -> w:abstractNum/w:lvl[@w:ilvl]/w:numFmt
//
// Ce qui ne l'est pas, volontairement : w:lvlOverride, les redéfinitions de
// niveau, la numérotation continue d'une liste à l'autre. Un visualiseur
// compte ses éléments lui-même, et le résultat à l'écran est le même.
func numerotationsDocx(data []byte) map[string]map[int]string {
	formats := map[string]map[int]string{}
	if data == nil {
		return formats
	}

	abstraits := map[string]map[int]string{} // abstractNumId -> ilvl -> format
	instances := map[string]string{}         // numId -> abstractNumId

	d := xml.NewDecoder(bytes.NewReader(data))
	var abstrait, instance string
	var ilvl int
	for {
		tok, err := d.Token()
		if err != nil {
			break
		}
		switch t := tok.(type) {
		case xml.StartElement:
			switch {
			case estW(t.Name, "abstractNum"):
				abstrait = attr(t, nsWord, "abstractNumId")
				abstraits[abstrait] = map[int]string{}
			case estW(t.Name, "lvl") && abstrait != "":
				ilvl = entier(attr(t, nsWord, "ilvl"))
			case estW(t.Name, "numFmt") && abstrait != "":
				abstraits[abstrait][ilvl] = attr(t, nsWord, "val")
			case estW(t.Name, "num"):
				instance = attr(t, nsWord, "numId")
			// Attention : « abstractNumId » est ici un *élément*, enfant de
			// w:num, là où c'est un attribut sur w:abstractNum. Même nom, deux
			// rôles.
			case estW(t.Name, "abstractNumId") && instance != "":
				instances[instance] = attr(t, nsWord, "val")
			}
		case xml.EndElement:
			switch {
			case estW(t.Name, "abstractNum"):
				abstrait = ""
			case estW(t.Name, "num"):
				instance = ""
			}
		}
	}

	for numID, ref := range instances {
		if niveaux, ok := abstraits[ref]; ok {
			formats[numID] = niveaux
		}
	}
	return formats
}

// liensDocx associe chaque identifiant de relation à sa cible.
//
// Les liens hypertexte d'un .docx ne portent pas leur URL : le document ne
// contient qu'un r:id, et la cible vit dans une partie séparée.
func liensDocx(data []byte) map[string]string {
	liens := map[string]string{}
	if data == nil {
		return liens
	}

	d := xml.NewDecoder(bytes.NewReader(data))
	for {
		tok, err := d.Token()
		if err != nil {
			return liens
		}
		t, ok := tok.(xml.StartElement)
		if !ok || t.Name.Local != "Relationship" {
			continue
		}
		// Le fichier de relations porte aussi les images, les polices et les
		// en-têtes : sans ce filtre, un r:id d'image passerait pour une URL.
		if strings.HasSuffix(attr(t, "", "Type"), "/hyperlink") {
			liens[attr(t, "", "Id")] = attr(t, "", "Target")
		}
	}
}

// --- Passe de contenu -------------------------------------------------------

// corps parcourt document.xml et empile les blocs.
//
// Un seul niveau de boucle suffit parce que chaque gestionnaire consomme son
// élément jusqu'à sa fermeture : les w:p d'un tableau sont avalés par tableau()
// et ne repassent jamais ici.
func (a *docx) corps(data []byte) error {
	d := xml.NewDecoder(bytes.NewReader(data))
	for {
		tok, err := d.Token()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return erreurXML(err)
		}
		t, ok := tok.(xml.StartElement)
		if !ok {
			continue
		}
		switch {
		case estW(t.Name, "p"):
			if err := a.paragraphe(d); err != nil {
				return err
			}
		case estW(t.Name, "tbl"):
			if err := a.tableau(d); err != nil {
				return err
			}
		}
	}
}

// paragraphe consomme un w:p et en tire un bloc.
func (a *docx) paragraphe(d *xml.Decoder) error {
	var (
		c     constructeur
		style string
		numID string
		ilvl  int
		liste bool
	)

	for {
		tok, err := d.Token()
		if err != nil {
			return erreurXML(err)
		}
		switch t := tok.(type) {
		case xml.StartElement:
			switch {
			case estW(t.Name, "pStyle"):
				style = attr(t, nsWord, "val")

			case estW(t.Name, "numPr"):
				numID, ilvl, err = a.numPr(d)
				if err != nil {
					return err
				}
				liste = true

			case estW(t.Name, "hyperlink"):
				// La cible est dans le fichier de relations ; un r:id inconnu
				// donne un span sans href, ce que l'interface sait afficher.
				debut := c.n
				if err := a.runs(d, &c, "hyperlink"); err != nil {
					return err
				}
				c.span(debut, markdown.StyleLink, a.liens[attr(t, nsRel, "id")])

			case estW(t.Name, "r"):
				if err := a.run(d, &c); err != nil {
					return err
				}
			}

		case xml.EndElement:
			if estW(t.Name, "p") {
				a.emettre(&c, style, numID, ilvl, liste)
				return nil
			}
		}
	}
}

// emettre transforme le texte accumulé en bloc, ou l'abandonne s'il est vide.
func (a *docx) emettre(c *constructeur, style, numID string, ilvl int, liste bool) {
	texte, spans := c.fini()
	if texte == "" {
		return
	}

	b := markdown.Block{Text: texte, Spans: spans}
	if liste {
		b.Kind = a.genre(numID, ilvl)
		b.Depth = ilvl
		// Le numéro est compté pour tous les éléments, y compris les puces :
		// c'est ce qui garde les compteurs justes quand une liste à puces
		// s'intercale entre deux listes numérotées.
		numero := a.suivant(numID, ilvl)
		if b.Kind == markdown.KindOrdered {
			b.Number = numero
		}
		a.push(b)
		return
	}

	a.dernierNum = ""
	if n := a.niveau(style); n > 0 {
		b.Kind, b.Level = markdown.KindHeading, n
	} else {
		b.Kind = markdown.KindParagraph
	}
	a.push(b)
}

// run consomme un w:r : ses propriétés de mise en forme, puis son texte.
//
// Les propriétés arrivent avant le texte dans un run bien formé, mais rien ne
// l'impose ici : les spans ne sont posés qu'à la fermeture, quand le texte est
// écrit et les bascules connues.
func (a *docx) run(d *xml.Decoder, c *constructeur) error {
	debut := c.n
	var gras, italique, souligne, barre bool

	for {
		tok, err := d.Token()
		if err != nil {
			return erreurXML(err)
		}
		switch t := tok.(type) {
		case xml.StartElement:
			switch {
			case estW(t.Name, "b"):
				gras = actif(t)
			case estW(t.Name, "i"):
				italique = actif(t)
			case estW(t.Name, "u"):
				souligne = actif(t)
			case estW(t.Name, "strike"):
				barre = actif(t)
			case estW(t.Name, "t"):
				texte, err := texteDe(d, t)
				if err != nil {
					return err
				}
				c.write(texte)
			case estW(t.Name, "tab"):
				c.write("\t")
			case estW(t.Name, "br"):
				c.write("\n")
			}

		case xml.EndElement:
			if estW(t.Name, "r") {
				if gras {
					c.span(debut, markdown.StyleBold, "")
				}
				if italique {
					c.span(debut, markdown.StyleItalic, "")
				}
				if souligne {
					c.span(debut, markdown.StyleUnderline, "")
				}
				if barre {
					c.span(debut, markdown.StyleStrike, "")
				}
				return nil
			}
		}
	}
}

// runs consomme les w:r d'un élément conteneur, jusqu'à sa fermeture.
func (a *docx) runs(d *xml.Decoder, c *constructeur, arret string) error {
	for {
		tok, err := d.Token()
		if err != nil {
			return erreurXML(err)
		}
		switch t := tok.(type) {
		case xml.StartElement:
			if estW(t.Name, "r") {
				if err := a.run(d, c); err != nil {
					return err
				}
			}
		case xml.EndElement:
			if estW(t.Name, arret) {
				return nil
			}
		}
	}
}

// numPr lit la liste dont un paragraphe fait partie, et sa profondeur.
func (a *docx) numPr(d *xml.Decoder) (string, int, error) {
	var numID string
	var ilvl int
	for {
		tok, err := d.Token()
		if err != nil {
			return "", 0, erreurXML(err)
		}
		switch t := tok.(type) {
		case xml.StartElement:
			switch {
			case estW(t.Name, "ilvl"):
				ilvl = entier(attr(t, nsWord, "val"))
			case estW(t.Name, "numId"):
				numID = attr(t, nsWord, "val")
			}
		case xml.EndElement:
			if estW(t.Name, "numPr") {
				return numID, ilvl, nil
			}
		}
	}
}

// tableau aplatit un w:tbl en une suite de lignes, comme le fait déjà le rendu
// Markdown.
func (a *docx) tableau(d *xml.Decoder) error {
	for {
		tok, err := d.Token()
		if err != nil {
			return erreurXML(err)
		}
		switch t := tok.(type) {
		case xml.StartElement:
			if estW(t.Name, "tr") {
				if err := a.ligne(d); err != nil {
					return err
				}
			}
		case xml.EndElement:
			if estW(t.Name, "tbl") {
				return nil
			}
		}
	}
}

// ligne rend un w:tr.
//
// L'en-tête se lit, il ne se devine pas : w:tblHeader est posé par les
// producteurs, et l'heuristique « la première ligne est l'en-tête » mentirait
// sur le premier tableau qui n'en a pas.
func (a *docx) ligne(d *xml.Decoder) error {
	entete := false
	cellules := []string{}

	for {
		tok, err := d.Token()
		if err != nil {
			return erreurXML(err)
		}
		switch t := tok.(type) {
		case xml.StartElement:
			switch {
			case estW(t.Name, "tblHeader"):
				entete = actif(t)
			case estW(t.Name, "tc"):
				texte, err := a.cellule(d)
				if err != nil {
					return err
				}
				cellules = append(cellules, texte)
			}
		case xml.EndElement:
			if estW(t.Name, "tr") {
				a.push(markdown.Block{Kind: markdown.KindTableRow, Cells: cellules, Header: entete})
				return nil
			}
		}
	}
}

// cellule rend le texte d'un w:tc, ses paragraphes séparés par une espace.
//
// La mise en forme y est perdue, comme dans le rendu Markdown : un tableau sert
// à comparer des valeurs, et le modèle de bloc ne porte pas de spans par
// cellule. Un tableau imbriqué voit son contenu remonter dans la cellule
// parente — dégradation assumée, un visualiseur n'a pas à en faire un sujet.
func (a *docx) cellule(d *xml.Decoder) (string, error) {
	var b strings.Builder
	for {
		tok, err := d.Token()
		if err != nil {
			return "", erreurXML(err)
		}
		switch t := tok.(type) {
		case xml.StartElement:
			if estW(t.Name, "t") {
				texte, err := texteDe(d, t)
				if err != nil {
					return "", err
				}
				b.WriteString(texte)
			}
		case xml.EndElement:
			switch {
			case estW(t.Name, "p"):
				b.WriteString(" ")
			case estW(t.Name, "tc"):
				return strings.TrimSpace(b.String()), nil
			}
		}
	}
}

// push publie un bloc après la protection de mise en page.
//
// Aucun bloc ne sort d'ici sans être passé par là : un document peut porter une
// suite de caractères sans espace démesurée, et elle fait tuer l'application
// par le système — un Text et un TextField partagent le même moteur de mise en
// page, la lecture seule ne protège de rien toute seule.
func (a *docx) push(b markdown.Block) {
	a.out = append(a.out, markdown.ProtectLayout(b))
}

// --- Décisions tirées des tables --------------------------------------------

// niveau rend le niveau de titre d'un style de paragraphe, 0 si ce n'en est pas
// un.
func (a *docx) niveau(styleID string) int {
	if styleID == "" {
		return 0
	}
	if n, ok := a.titres[styleID]; ok {
		return n
	}
	// Un .docx de Word nomme son style « Heading1 » : l'identifiant et le nom
	// canonique coïncident, et styles.xml devient superflu.
	return niveauDepuisNom(styleID)
}

// genre dit si une liste s'affiche en puces ou en numéros.
//
// Sans numbering.xml, tout devient une puce : c'est le repli le moins faux,
// une numérotation inventée serait pire qu'absente.
func (a *docx) genre(numID string, ilvl int) markdown.Kind {
	switch a.formats[numID][ilvl] {
	case "", "bullet", "none":
		return markdown.KindBullet
	default:
		return markdown.KindOrdered
	}
}

// suivant rend le numéro d'un élément de liste.
//
// Les niveaux plus profonds repartent de zéro dès qu'on remonte, et une liste
// séparée de la précédente par autre chose qu'un élément de liste repart à un.
func (a *docx) suivant(numID string, ilvl int) int {
	if numID != a.dernierNum {
		delete(a.compteurs, numID)
		a.dernierNum = numID
	}

	c := a.compteurs[numID]
	for len(c) <= ilvl {
		c = append(c, 0)
	}
	c = c[:ilvl+1]
	c[ilvl]++
	a.compteurs[numID] = c
	return c[ilvl]
}

// --- Lecture XML ------------------------------------------------------------

func estW(n xml.Name, local string) bool {
	return n.Space == nsWord && n.Local == local
}

// attr lit un attribut. Un espace de noms vide accepte n'importe lequel, ce
// dont le fichier de relations a besoin : ses attributs n'en portent pas.
func attr(e xml.StartElement, espace, local string) string {
	for _, a := range e.Attr {
		if a.Name.Local == local && (espace == "" || a.Name.Space == espace) {
			return a.Value
		}
	}
	return ""
}

// actif lit une propriété à bascule.
//
// `<w:b/>` active, `<w:b w:val="0"/>` désactive. Se contenter de la présence de
// la balise mettrait en gras un run qui éteint justement le gras de son style.
func actif(e xml.StartElement) bool {
	switch attr(e, nsWord, "val") {
	case "0", "false", "off", "none":
		return false
	default:
		// « single », « double », « wave » pour un souligné : tout ce qui n'est
		// pas une extinction est une activation.
		return true
	}
}

// texteDe lit le contenu textuel d'un élément.
//
// Le texte n'est jamais rogné ici, et c'est tout ce que demande
// xml:space="preserve" : les espaces significatives d'un run sont dans la
// donnée, les rogner recollerait les mots. Le rognage se fait une fois, sur le
// bloc assemblé, dans constructeur.fini.
func texteDe(d *xml.Decoder, ouverture xml.StartElement) (string, error) {
	var b strings.Builder
	for {
		tok, err := d.Token()
		if err != nil {
			return "", erreurXML(err)
		}
		switch t := tok.(type) {
		case xml.CharData:
			b.Write(t)
		case xml.EndElement:
			if t.Name == ouverture.Name {
				return b.String(), nil
			}
		}
	}
}

func entier(s string) int {
	n, err := strconv.Atoi(s)
	if err != nil {
		return 0
	}
	return n
}
