// Package charset reconnaît l'encodage d'un fichier texte et le convertit
// depuis et vers l'UTF-8.
//
// Le cœur ne manipule que de l'UTF-8, et gomobile n'en transmet pas d'autre :
// un fichier Latin-1 ou UTF-16 venu de Windows doit donc être décodé avant de
// devenir une chaîne, et réencodé à l'identique avant de redevenir un fichier.
//
// La propriété qui compte est l'aller-retour sans perte : Decode puis Encode
// rendent exactement les octets d'origine. Windows-1252 la garantit pour
// n'importe quel contenu — ses cinq octets non attribués passent tels quels —,
// si bien qu'une détection erronée fausse l'affichage sans jamais abîmer ce
// que l'utilisateur n'a pas touché. RoundTrips la vérifie sur un contenu
// donné.
//
// Aucune dépendance : la bibliothèque standard porte l'UTF-16, et
// Windows-1252 tient en une table de 32 entrées.
package charset

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"unicode/utf16"
	"unicode/utf8"
)

// Name désigne un encodage reconnu.
type Name string

const (
	UTF8        Name = "UTF-8"
	UTF16LE     Name = "UTF-16LE"
	UTF16BE     Name = "UTF-16BE"
	Windows1252 Name = "windows-1252"
)

// Encoding est l'encodage d'un fichier : son nom, et la présence d'une marque
// d'ordre des octets (BOM) en tête, à rendre telle quelle à l'écriture.
type Encoding struct {
	Name Name
	BOM  bool
}

// CodeUnrepresentable signale un caractère que l'encodage du fichier ne sait
// pas écrire — un emoji dans un fichier Windows-1252, par exemple.
const CodeUnrepresentable = "ENCODING_UNREPRESENTABLE"

var (
	bomUTF8    = []byte{0xEF, 0xBB, 0xBF}
	bomUTF16LE = []byte{0xFF, 0xFE}
	bomUTF16BE = []byte{0xFE, 0xFF}
)

// Detect reconnaît l'encodage d'un contenu.
//
// L'ordre des questions compte :
//
//  1. une BOM tranche sans discussion ;
//  2. des octets nuls désignent de l'UTF-16 sans BOM — un texte en UTF-8 ou
//     sur 8 bits n'en contient pas, alors que « ok » en UTF-16LE s'écrit
//     « o\x00k\x00 », qui est pourtant de l'UTF-8 valide ;
//  3. un UTF-8 valide est de l'UTF-8 ;
//  4. le reste est lu en Windows-1252, sur-ensemble du Latin-1 et encodage
//     des fichiers texte de Windows en Europe de l'Ouest. C'est une
//     supposition, sans risque pour les données (voir le paquet).
func Detect(b []byte) Encoding {
	switch {
	case bytes.HasPrefix(b, bomUTF8) && utf8.Valid(b):
		// Une BOM UTF-8 suivie d'octets invalides ne tranche rien : le fichier
		// a été assemblé de morceaux, et retombe sur Windows-1252 plus bas.
		return Encoding{Name: UTF8, BOM: true}
	case bytes.HasPrefix(b, bomUTF16LE):
		return Encoding{Name: UTF16LE, BOM: true}
	case bytes.HasPrefix(b, bomUTF16BE):
		return Encoding{Name: UTF16BE, BOM: true}
	}
	if name, ok := utf16SansBOM(b); ok {
		return Encoding{Name: name}
	}
	if utf8.Valid(b) {
		return Encoding{Name: UTF8}
	}
	return Encoding{Name: Windows1252}
}

// utf16SansBOM reconnaît un texte UTF-16 à ses octets nuls.
//
// Un texte en alphabet latin a un octet de poids fort nul pour presque chaque
// caractère : placé en position impaire en petit-boutiste, paire en
// gros-boutiste. On exige que les nuls soient tous du même côté et qu'ils
// concernent au moins un caractère sur quatre : un nul isolé dans un fichier
// UTF-8 ne suffit pas à le requalifier. Un texte UTF-16 sans BOM ni caractère
// latin — du chinois, par exemple — échappe à cette règle et sera lu en
// Windows-1252 : affichage faux, octets intacts.
func utf16SansBOM(b []byte) (Name, bool) {
	if len(b) < 2 || len(b)%2 != 0 {
		return "", false
	}
	var pairs, impairs int
	for i, c := range b {
		if c != 0 {
			continue
		}
		if i%2 == 0 {
			pairs++
		} else {
			impairs++
		}
	}
	unites := len(b) / 2
	switch {
	case pairs == 0 && impairs*4 >= unites:
		return UTF16LE, true
	case impairs == 0 && pairs*4 >= unites:
		return UTF16BE, true
	}
	return "", false
}

// Decode reconnaît l'encodage d'un contenu et le convertit en UTF-8, BOM
// retirée.
//
// Ne peut pas échouer : une séquence invalide — une moitié de paire de
// substitution en UTF-16, un octet orphelin en fin de fichier — devient
// U+FFFD. Le texte obtenu est alors bon pour l'affichage mais pas pour une
// réécriture, ce que RoundTrips permet de savoir.
func Decode(b []byte) (string, Encoding) {
	enc := Detect(b)
	switch enc.Name {
	case UTF16LE, UTF16BE:
		if enc.BOM {
			b = b[2:]
		}
		return decodeUTF16(b, enc.Name), enc
	case Windows1252:
		return decode1252(b), enc
	default:
		if enc.BOM {
			b = b[len(bomUTF8):]
		}
		return string(b), enc
	}
}

// Encode convertit un texte UTF-8 dans l'encodage donné, BOM comprise.
//
// Refuse, plutôt que de le remplacer, un caractère que l'encodage ne sait pas
// écrire : un « ? » glissé à la place d'un caractère saisi serait une perte
// sans message.
func Encode(s string, enc Encoding) ([]byte, error) {
	switch enc.Name {
	case UTF16LE, UTF16BE:
		return encodeUTF16(s, enc), nil
	case Windows1252:
		return encode1252(s)
	default:
		if enc.BOM {
			return append(append([]byte{}, bomUTF8...), s...), nil
		}
		return []byte(s), nil
	}
}

// RoundTrips indique que Decode puis Encode rendent exactement ces octets.
//
// C'est la condition à remplir avant de laisser réécrire un fichier : un
// contenu qui ne la satisfait pas perdrait des octets au premier
// enregistrement, même sans une seule modification.
func RoundTrips(b []byte) bool {
	s, enc := Decode(b)
	again, err := Encode(s, enc)
	return err == nil && bytes.Equal(again, b)
}

func decodeUTF16(b []byte, name Name) string {
	unites := make([]uint16, 0, len(b)/2)
	for i := 0; i+1 < len(b); i += 2 {
		if name == UTF16LE {
			unites = append(unites, binary.LittleEndian.Uint16(b[i:]))
		} else {
			unites = append(unites, binary.BigEndian.Uint16(b[i:]))
		}
	}
	s := string(utf16.Decode(unites))
	if len(b)%2 != 0 {
		// Un octet orphelin : fichier tronqué. Le signaler plutôt que de le
		// taire rend l'aller-retour inexact, donc la réécriture impossible.
		s += string(utf8.RuneError)
	}
	return s
}

func encodeUTF16(s string, enc Encoding) []byte {
	unites := utf16.Encode([]rune(s))
	out := make([]byte, 0, 2+2*len(unites))
	if enc.BOM {
		if enc.Name == UTF16LE {
			out = append(out, bomUTF16LE...)
		} else {
			out = append(out, bomUTF16BE...)
		}
	}
	for _, u := range unites {
		if enc.Name == UTF16LE {
			out = binary.LittleEndian.AppendUint16(out, u)
		} else {
			out = binary.BigEndian.AppendUint16(out, u)
		}
	}
	return out
}

// table1252 donne le caractère des octets 0x80 à 0x9F, là où Windows-1252
// s'écarte du Latin-1. Les cinq octets non attribués (0x81, 0x8D, 0x8F, 0x90,
// 0x9D) valent le caractère de contrôle C1 de même numéro, comme dans la
// norme WHATWG : c'est ce qui rend l'aller-retour exact pour tout contenu.
var table1252 = [32]rune{
	0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
	0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
	0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
	0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178,
}

// inverse1252 est la table réciproque, construite une fois.
var inverse1252 = func() map[rune]byte {
	m := make(map[rune]byte, len(table1252))
	for i, r := range table1252 {
		m[r] = byte(0x80 + i)
	}
	return m
}()

func decode1252(b []byte) string {
	out := make([]rune, len(b))
	for i, c := range b {
		if c >= 0x80 && c <= 0x9F {
			out[i] = table1252[c-0x80]
		} else {
			// Hors de 0x80–0x9F, Windows-1252 et Latin-1 coïncident avec les
			// 256 premiers points de code Unicode.
			out[i] = rune(c)
		}
	}
	return string(out)
}

func encode1252(s string) ([]byte, error) {
	out := make([]byte, 0, len(s))
	for _, r := range s {
		switch {
		case r < 0x80 || (r >= 0xA0 && r <= 0xFF):
			out = append(out, byte(r))
		default:
			c, ok := inverse1252[r]
			if !ok {
				return nil, fmt.Errorf("charset: [%s] le caractère %q (U+%04X) n'existe pas en %s", CodeUnrepresentable, r, r, Windows1252)
			}
			out = append(out, c)
		}
	}
	return out, nil
}
