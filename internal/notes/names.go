// Package notes porte le modèle de notes : arborescence, nommage et
// opérations de haut niveau, au-dessus du transport WebDAV.
package notes

import (
	"fmt"
	"path"
	"strings"
	"unicode"
)

// Extension des notes gérées par l'application.
const Extension = ".md"

// markdownExtensions liste ce que l'application reconnaît comme une note.
// L'écriture se fait toujours en .md ; les autres sont acceptées en lecture
// parce qu'elles peuvent avoir été créées ailleurs.
var markdownExtensions = []string{".md", ".markdown", ".mdown", ".mkd"}

// plainExtensions liste ce que l'application ouvre sans l'interpréter.
//
// OpenCloud crée ses fichiers texte en .txt : ce sont eux qu'on trouve dans un
// dossier alimenté depuis l'interface web. Ils sont donc lisibles et
// modifiables, mais jamais rendus comme du Markdown — un « # » y est un dièse,
// pas un titre.
var plainExtensions = []string{".txt"}

// documentExtensions liste ce que l'application sait lire et ne saura jamais
// écrire.
//
// Un dossier de notes alimenté depuis l'interface web finit par en contenir.
// Les afficher dans la liste et les ouvrir en aperçu est un service ; prétendre
// les modifier serait un mensonge, et un .docx réécrit par nos soins serait un
// .docx cassé.
var documentExtensions = []string{".docx", ".odt"}

// forbiddenInName rassemble les caractères qu'un nom de note ne peut pas
// contenir.
//
// Le serveur, lui, les accepte : le test d'intégration a montré qu'OpenCloud
// stocke sans broncher « ? », « * » ou « % » dans un nom de fichier. La
// contrainte vient d'ailleurs — du cache local, qui doit écrire de vrais
// fichiers sur un système où « < > : " | ? * \ » sont interdits sous Windows
// et « / » partout.
//
// D'où une asymétrie assumée : l'application refuse de *créer* ces noms, mais
// sait *lire* et afficher ceux qui existent déjà, créés depuis l'interface web
// ou un autre client.
const forbiddenInName = `<>:"|?*\/`

// reservedDeviceNames sont des noms de périphériques hérités de MS-DOS.
// Windows refuse encore aujourd'hui de créer un fichier qui les porte, quelle
// que soit l'extension.
var reservedDeviceNames = map[string]bool{
	"CON": true, "PRN": true, "AUX": true, "NUL": true,
	"COM1": true, "COM2": true, "COM3": true, "COM4": true, "COM5": true,
	"COM6": true, "COM7": true, "COM8": true, "COM9": true,
	"LPT1": true, "LPT2": true, "LPT3": true, "LPT4": true, "LPT5": true,
	"LPT6": true, "LPT7": true, "LPT8": true, "LPT9": true,
}

// Codes d'erreur de nommage.
//
// Ils voyagent entre crochets dans le message, exactement comme les codes de
// transport d'opencloud, parce que gomobile ne fait traverser qu'une chaîne.
// C'est ce qui permet à Android de reformuler la règle dans la langue de
// l'appareil au lieu d'afficher le français du cœur.
//
// La phrase française reste dans le message derrière le code : elle sert la
// CLI desktop, les journaux, les tests, et le repli d'Android quand un code
// n'a pas encore de traduction.
const (
	CodeNameEmpty          = "NAME_EMPTY"
	CodeNameReserved       = "NAME_RESERVED"
	CodeNameTooLong        = "NAME_TOO_LONG"
	CodeNameForbiddenChars = "NAME_FORBIDDEN_CHARS"
	CodeNameControlChar    = "NAME_CONTROL_CHAR"
	CodeNameSpaceEdge      = "NAME_SPACE_EDGE"
	CodeNameTrailingDot    = "NAME_TRAILING_DOT"
	CodeNameLeadingDot     = "NAME_LEADING_DOT"
	CodeNameReservedDevice = "NAME_RESERVED_DEVICE"
)

// CodeReadOnly signale une écriture demandée sur un format que l'application
// ne sait que lire.
//
// Ce n'est pas une règle de nommage mais une règle de format, d'où sa place à
// part. Elle ne devrait jamais atteindre l'utilisateur : l'interface n'ouvre
// pas de champ de saisie sur un document. Si elle y arrive, c'est un défaut de
// l'interface — et le refus a évité d'écraser un fichier sur le serveur.
const CodeReadOnly = "READONLY"

// maxNameBytes borne la longueur d'un nom. La plupart des systèmes de fichiers
// s'arrêtent à 255 octets ; on garde une marge pour les suffixes ajoutés lors
// d'un conflit de synchronisation.
const maxNameBytes = 200

// MaxNameBytes est la borne exposée à l'interface.
//
// Sans elle, Android devrait recopier « 200 » dans sa propre formulation de la
// règle, et les deux valeurs divergeraient au premier ajustement.
func MaxNameBytes() int { return maxNameBytes }

// ForbiddenNameChars est la liste exposée à l'interface, pour la même raison.
func ForbiddenNameChars() string { return forbiddenInName }

// ValidateName vérifie qu'un nom peut être créé sans risque, sur le serveur
// comme dans le cache local.
//
// Le nom attendu est un segment unique : ni chemin, ni séparateur.
func ValidateName(name string) error {
	if strings.TrimSpace(name) == "" {
		return fmt.Errorf("notes: [%s] le nom est vide", CodeNameEmpty)
	}
	if name == "." || name == ".." {
		return fmt.Errorf("notes: [%s] %q est un nom réservé", CodeNameReserved, name)
	}
	if len(name) > maxNameBytes {
		return fmt.Errorf("notes: [%s] le nom dépasse %d octets", CodeNameTooLong, maxNameBytes)
	}
	if strings.ContainsAny(name, forbiddenInName) {
		return fmt.Errorf("notes: [%s] le nom ne peut pas contenir un de ces caractères : %s", CodeNameForbiddenChars, forbiddenInName)
	}
	for _, r := range name {
		if unicode.IsControl(r) {
			return fmt.Errorf("notes: [%s] le nom contient un caractère de contrôle", CodeNameControlChar)
		}
	}
	if name != strings.TrimSpace(name) {
		return fmt.Errorf("notes: [%s] le nom ne peut pas commencer ni finir par une espace", CodeNameSpaceEdge)
	}
	if strings.HasSuffix(name, ".") {
		return fmt.Errorf("notes: [%s] le nom ne peut pas finir par un point", CodeNameTrailingDot)
	}
	if strings.HasPrefix(name, ".") {
		return fmt.Errorf("notes: [%s] le nom ne peut pas commencer par un point", CodeNameLeadingDot)
	}

	base := name
	if i := strings.Index(base, "."); i > 0 {
		base = base[:i]
	}
	if reservedDeviceNames[strings.ToUpper(base)] {
		return fmt.Errorf("notes: [%s] %q est un nom réservé par Windows", CodeNameReservedDevice, base)
	}

	return nil
}

// SanitizeName transforme un texte quelconque en nom de fichier valide.
//
// Sert à proposer un nom à partir du titre saisi par l'utilisateur, sans le
// forcer à connaître les contraintes du système de fichiers.
func SanitizeName(text string) string {
	var b strings.Builder
	for _, r := range text {
		switch {
		case unicode.IsControl(r), strings.ContainsRune(forbiddenInName, r):
			b.WriteRune('-')
		default:
			b.WriteRune(r)
		}
	}

	name := strings.TrimSpace(b.String())
	name = strings.Trim(name, ".")
	name = strings.TrimSpace(name)

	// Les tirets accumulés par les substitutions sont réduits.
	for strings.Contains(name, "--") {
		name = strings.ReplaceAll(name, "--", "-")
	}
	name = strings.Trim(name, "-")
	name = strings.TrimSpace(name)

	for len(name) > maxNameBytes {
		_, size := lastRune(name)
		name = name[:len(name)-size]
		name = strings.TrimSpace(name)
	}

	if name == "" {
		return "Sans titre"
	}
	if base := strings.SplitN(name, ".", 2)[0]; reservedDeviceNames[strings.ToUpper(base)] {
		name = "_" + name
	}
	return name
}

func lastRune(s string) (rune, int) {
	runes := []rune(s)
	if len(runes) == 0 {
		return 0, 0
	}
	last := runes[len(runes)-1]
	return last, len(string(last))
}

// WithExtension garantit qu'un nom porte l'extension d'écriture.
//
// L'application ne crée que du Markdown : un nom sans extension de note
// reçoit .md. Pour un renommage, qui doit préserver le format existant,
// voir WithExtensionOf.
//
// La condition est IsEditable et **non IsNote**, et c'est tout sauf un détail :
// depuis que IsNote inclut les documents, une note créée sous le nom
// « rapport.docx » repartirait avec cette extension — l'application écrirait du
// Markdown dans un fichier que tout le monde, elle comprise, relira comme une
// archive OOXML.
func WithExtension(name string) string {
	if IsEditable(name) {
		return name
	}
	return name + Extension
}

// WithExtensionOf garantit qu'un nom porte une extension de note, en
// reprenant celle de ref quand le nom n'en porte pas.
//
// C'est ce qui fait qu'un renommage préserve le format : « journal.txt »
// renommé en « carnet » donne « carnet.txt », et non « carnet.md » — un
// changement de format silencieux, que l'utilisateur n'a pas demandé.
//
// Un document est traité à part, et plus strictement : son extension d'origine
// est la seule qui vaille. Renommer « rapport.docx » en « bilan » donne
// « bilan.docx » ; le renommer en « bilan.odt » donnerait « bilan.odt.docx »,
// laid mais honnête — le fichier reste un .docx, et l'application ne sait pas
// convertir. Entre deux formats modifiables, en revanche, saisir l'autre
// extension la change délibérément : c'est le contrat annoncé.
func WithExtensionOf(ref, name string) string {
	if IsDocument(ref) {
		if strings.EqualFold(path.Ext(name), path.Ext(ref)) {
			return name
		}
		return name + path.Ext(ref)
	}
	if IsEditable(name) {
		return name
	}
	if IsEditable(ref) {
		return name + path.Ext(ref)
	}
	return name + Extension
}

// IsMarkdown indique si un nom de fichier désigne du Markdown, donc du texte
// à interpréter.
func IsMarkdown(name string) bool {
	return hasExtension(name, markdownExtensions)
}

// IsPlainText indique si un nom de fichier désigne du texte brut, affiché tel
// quel et jamais interprété.
func IsPlainText(name string) bool {
	return hasExtension(name, plainExtensions)
}

// IsDocument indique un fichier bureautique : lisible, jamais modifiable.
func IsDocument(name string) bool {
	return hasExtension(name, documentExtensions)
}

// IsEditable indique un format que l'application sait écrire.
func IsEditable(name string) bool {
	return IsMarkdown(name) || IsPlainText(name)
}

// IsNote indique si l'application sait ouvrir ce fichier, quel que soit son
// format.
//
// Quatre questions distinctes se posent, et les confondre coûte cher. Elles ont
// longtemps été trois, la quatrième étant restée cachée derrière IsNote jusqu'à
// ce que les documents arrivent :
//
//   - IsNote       : « faut-il l'afficher dans la liste ? » — oui au .docx ;
//   - IsMarkdown   : « faut-il l'interpréter ? » — non au .txt ;
//   - IsDocument   : « faut-il l'analyser, et interdire la saisie ? » ;
//   - IsEditable   : « l'application sait-elle écrire ce format ? » — c'est la
//     condition de WithExtension, et elle ne peut pas être IsNote.
func IsNote(name string) bool {
	return IsEditable(name) || IsDocument(name)
}

// EnsureWritable refuse un chemin que l'application ne sait que lire.
//
// C'est le garde-fou du seul chemin qui peut détruire un fichier de
// l'utilisateur en silence : une écriture partie sur un .docx le remplacerait
// par du texte, sur un serveur partagé, sans le moindre message. Le vérifier
// dans le cœur plutôt que de faire confiance à l'interface, c'est le même
// principe que « ne jamais écrire sans restituer ».
func EnsureWritable(itemPath string) error {
	if IsDocument(itemPath) {
		return fmt.Errorf("notes: [%s] un fichier %s s'ouvre en lecture seule", CodeReadOnly, path.Ext(itemPath))
	}
	return nil
}

func hasExtension(name string, extensions []string) bool {
	ext := strings.ToLower(path.Ext(name))
	for _, candidate := range extensions {
		if ext == candidate {
			return true
		}
	}
	return false
}

// DisplayName retire l'extension d'un nom de note pour l'affichage.
//
// Seul le Markdown perd la sienne. Un « notes.txt » garde la sienne parce
// qu'il peut cohabiter avec un « notes.md » dans le même dossier : les
// afficher tous deux sous « notes » donnerait deux lignes identiques
// désignant deux fichiers différents.
//
// Un document la garde pour la même raison, et pour une autre : c'est
// l'extension qui prévient qu'on ne pourra pas le modifier.
func DisplayName(name string) string {
	if !IsMarkdown(name) {
		return name
	}
	return strings.TrimSuffix(name, path.Ext(name))
}

// isHidden reconnaît les fichiers techniques à ne pas présenter comme des
// notes : les fichiers cachés et les artefacts d'autres clients.
func isHidden(name string) bool {
	return strings.HasPrefix(name, ".")
}

// CleanPath normalise un chemin relatif et le contient dans la racine.
//
// Comme dans le client WebDAV, ancrer le chemin sur « / » avant de le nettoyer
// neutralise les « .. » qui remonteraient au-dessus du dossier de notes.
func CleanPath(p string) string {
	cleaned := strings.Trim(path.Clean("/"+p), "/")
	if cleaned == "." {
		return ""
	}
	return cleaned
}
