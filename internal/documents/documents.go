// Package documents lit les formats bureautiques en lecture seule.
//
// # Pourquoi ce paquet existe
//
// Un dossier de notes alimenté depuis l'interface web d'OpenCloud finit par
// contenir des .docx et des .odt. L'application ne sait pas les écrire et n'a
// aucune intention d'apprendre : elle ne crée que du Markdown. Mais ne pas
// savoir les *lire* oblige l'utilisateur à sortir de l'application pour un
// fichier qui est pourtant dans son dossier.
//
// # Ce qu'il produit, et pourquoi
//
// Des markdown.Block, exactement ceux que l'aperçu dessine déjà. Rien d'un
// modèle à lui : Compose sait rendre un titre, une puce, une ligne de tableau,
// et n'a pas à connaître l'existence d'OOXML. Le prix est une conversion —
// c'est le bon prix.
//
// # Ce qu'il n'utilise pas
//
// Rien hors de la bibliothèque standard. Les deux formats sont des archives
// ZIP contenant du XML : archive/zip et encoding/xml suffisent. Chercher un
// module ici, c'est avoir pris un mauvais virage.
//
// Le relevé de ce que les fichiers contiennent réellement — noms de style
// localisés, listes qui ne disent pas leur genre, titre de niveau 1 qui n'est
// pas un titre — vient de fichiers produits par LibreOffice, pas seulement de
// la spécification.
package documents

import (
	"archive/zip"
	"bytes"
	"fmt"
	"io"
	"strconv"
	"strings"
	"unicode/utf16"

	"github.com/ybediat/OpenNote/internal/markdown"
)

// Codes d'erreur, sur le principe du reste du cœur : entre crochets, devant la
// phrase française, pour qu'Android puisse reformuler dans sa langue sans
// chercher de texte dans un message.
const (
	// CodeInvalid : le fichier n'est pas l'archive attendue, ou son XML est
	// illisible.
	CodeInvalid = "DOC_INVALID"
	// CodeTooLarge : une partie de l'archive dépasse la borne une fois
	// décompressée.
	CodeTooLarge = "DOC_TOO_LARGE"
	// CodeFileTooLarge : le fichier lui-même est trop gros pour être chargé.
	CodeFileTooLarge = "FILE_TOO_LARGE"
)

// Bornes de sécurité.
//
// Le fichier vient d'un serveur partagé : une archive de quelques kilo-octets
// peut se décompresser en gigaoctets, et un téléphone n'a pas de quoi encaisser
// l'erreur. Les deux valeurs sont arbitraires et généreuses ; ce qui compte est
// qu'elles existent, qu'elles soient nommées, et qu'elles arrêtent la lecture
// avant l'allocation plutôt qu'après.
const (
	maxFileBytes = 20 << 20 // le document lui-même
	maxPartBytes = 8 << 20  // une partie XML, une fois décompressée
)

// ouvrir vérifie la taille du fichier puis l'ouvre comme archive.
func ouvrir(data []byte) (*zip.Reader, error) {
	if len(data) > maxFileBytes {
		return nil, fmt.Errorf("documents: [%s] le document fait %d octets, la limite est %d", CodeFileTooLarge, len(data), maxFileBytes)
	}
	z, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return nil, fmt.Errorf("documents: [%s] archive illisible: %w", CodeInvalid, err)
	}
	return z, nil
}

// partie lit une entrée de l'archive, bornée.
//
// Une entrée absente rend (nil, nil) : styles.xml, numbering.xml et le fichier
// de relations sont facultatifs, et un document qui n'en a pas reste lisible —
// avec moins d'information. C'est à l'appelant de dire ce qui est obligatoire.
//
// La borne est posée par un io.LimitedReader et non par UncompressedSize64 :
// cette taille est une métadonnée de l'archive, donc écrite par celui qui
// fabrique le fichier. Elle indique, elle ne protège pas.
func partie(z *zip.Reader, nom string) ([]byte, error) {
	var entree *zip.File
	for _, f := range z.File {
		if f.Name == nom {
			entree = f
			break
		}
	}
	if entree == nil {
		return nil, nil
	}

	rc, err := entree.Open()
	if err != nil {
		return nil, fmt.Errorf("documents: [%s] %s: %w", CodeInvalid, nom, err)
	}
	defer rc.Close()

	// Un octet de plus que la borne : c'est ce qui permet de distinguer « pile
	// à la limite » de « tronqué par la limite ».
	data, err := io.ReadAll(io.LimitReader(rc, maxPartBytes+1))
	if err != nil {
		return nil, fmt.Errorf("documents: [%s] %s illisible: %w", CodeInvalid, nom, err)
	}
	if len(data) > maxPartBytes {
		return nil, fmt.Errorf("documents: [%s] %s dépasse %d octets décompressés", CodeTooLarge, nom, maxPartBytes)
	}
	return data, nil
}

// partieObligatoire lit une entrée dont l'absence condamne le document.
func partieObligatoire(z *zip.Reader, nom string) ([]byte, error) {
	data, err := partie(z, nom)
	if err != nil {
		return nil, err
	}
	if data == nil {
		return nil, fmt.Errorf("documents: [%s] %s absent de l'archive", CodeInvalid, nom)
	}
	return data, nil
}

// erreurXML habille une erreur de l'analyseur XML.
func erreurXML(err error) error {
	return fmt.Errorf("documents: [%s] XML illisible: %w", CodeInvalid, err)
}

// unites compte une chaîne en unités de code UTF-16.
//
// C'est l'unité de toute la frontière : celle d'AnnotatedString et de TextRange
// dans Compose. Compter ici, au fil de l'écriture du texte, évite d'avoir à
// retraduire chaque borne après coup — une occasion de se tromper par borne.
func unites(s string) int { return len(utf16.Encode([]rune(s))) }

// niveauDepuisNom lit le niveau d'un titre dans le nom canonique de son style.
//
// « Heading 1 » côté OOXML, « Heading_20_1 » côté ODF — l'ODF encode l'espace
// en « _20_ ». Les deux se ramènent à « heading1 », et tout ce qui ne ressemble
// pas à un titre rend 0.
func niveauDepuisNom(nom string) int {
	compact := strings.ToLower(remplaceur.Replace(nom))
	if !strings.HasPrefix(compact, "heading") {
		return 0
	}
	n, err := strconv.Atoi(strings.TrimPrefix(compact, "heading"))
	if err != nil || n < 1 || n > 6 {
		return 0
	}
	return n
}

// L'ordre compte : « _20_ » doit disparaître avant que « _ » seul ne le morcelle.
var remplaceur = strings.NewReplacer("_20_", "", " ", "", "_", "", "-", "")

// fondColore dit si une couleur de fond désigne un vrai surlignage.
//
// Partagé par les deux formats. Le vide, « auto » (OOXML) et « transparent »
// (ODF) sont l'absence de trame ; « FFFFFF » est un fond blanc que rien ne
// distingue du papier, ignoré lui aussi pour ne pas marquer un passage que
// personne n'a voulu marquer.
func fondColore(couleur string) bool {
	switch strings.ToLower(couleur) {
	case "", "auto", "transparent", "ffffff", "#ffffff":
		return false
	default:
		return true
	}
}

// constructeur accumule le texte d'un bloc en tenant à jour sa longueur en
// unités UTF-16, et les spans qui s'y rapportent.
//
// Même rôle que l'inlineBuilder de internal/markdown, et pour la même raison :
// une borne posée au moment où le texte s'écrit n'a jamais besoin d'être
// convertie.
type constructeur struct {
	texte strings.Builder
	n     int
	spans []markdown.Span

	// sautAvant / sautApres retiennent qu'un saut de page explicite a été
	// rencontré dans le paragraphe en cours, et de quel côté du texte : un
	// <w:br w:type="page"/> placé avant le moindre caractère ouvre la page
	// suivante, posé après il la ferme. Le marqueur ne peut pas être émis
	// depuis ici — un run ne pousse pas de bloc — donc l'appelant les lit à la
	// fin du paragraphe.
	sautAvant bool
	sautApres bool
}

func (c *constructeur) write(s string) {
	if s == "" {
		return
	}
	c.texte.WriteString(s)
	c.n += unites(s)
}

// span borne une portion mise en forme. Une portion vide n'en vaut pas la
// peine : un run sans texte ne doit pas laisser de trace.
func (c *constructeur) span(debut int, style markdown.Style, href string) {
	if debut >= c.n {
		return
	}
	c.spans = append(c.spans, markdown.Span{Start: debut, End: c.n, Style: style, Href: href})
}

// fini rend le texte du bloc, rogné, et ses spans recalés.
//
// Le rognage se fait ici et pas ailleurs : l'import HTML de LibreOffice laisse
// une espace finale sur la plupart des paragraphes, et un aperçu qui les garde
// affiche des lignes qui ne finissent pas où l'œil les attend. Rogner *run par
// run* serait l'erreur inverse — ça recollerait les mots, ce que xml:space
// demande justement d'éviter.
//
// Les spans suivent le décalage, et sont écrêtés à la nouvelle longueur : une
// borne qui ne désigne plus rien vaut moins que pas de span du tout.
func (c *constructeur) fini() (string, []markdown.Span) {
	brut := c.texte.String()

	sansGauche := strings.TrimLeft(brut, " \t\r\n")
	decalage := unites(brut[:len(brut)-len(sansGauche)])
	net := strings.TrimRight(sansGauche, " \t\r\n")
	if net == "" {
		return "", nil
	}
	limite := unites(net)

	var spans []markdown.Span
	for _, s := range c.spans {
		s.Start -= decalage
		s.End -= decalage
		if s.Start < 0 {
			s.Start = 0
		}
		if s.End > limite {
			s.End = limite
		}
		if s.Start < s.End {
			spans = append(spans, s)
		}
	}
	return net, spans
}

// sansSautsSuperflus retire les marqueurs de saut de page qui n'encadrent aucun
// contenu : en tête de document, en fin, ou collés l'un à l'autre. Un saut de
// page n'a de sens qu'entre deux blocs.
func sansSautsSuperflus(blocs []markdown.Block) []markdown.Block {
	out := make([]markdown.Block, 0, len(blocs))
	for _, b := range blocs {
		if b.Kind == markdown.KindPageBreak {
			dernier := len(out) - 1
			if dernier < 0 || out[dernier].Kind == markdown.KindPageBreak {
				continue
			}
		}
		out = append(out, b)
	}
	for len(out) > 0 && out[len(out)-1].Kind == markdown.KindPageBreak {
		out = out[:len(out)-1]
	}
	return out
}
