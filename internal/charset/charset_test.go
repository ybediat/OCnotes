package charset

import (
	"bytes"
	"strings"
	"testing"
)

// La propriété qui justifie de lire en Windows-1252 tout ce qui n'est pas de
// l'UTF-8 : n'importe quel octet revient tel quel. Une détection erronée —
// un fichier cyrillique CP1251 lu en Windows-1252 — fausse l'affichage, mais
// une réécriture ne toucherait pas un seul octet que l'utilisateur n'a pas
// modifié.
func TestWindows1252AllerRetourSurLesDeuxCentCinquanteSixOctets(t *testing.T) {
	tous := make([]byte, 256)
	for i := range tous {
		tous[i] = byte(i)
	}
	// Sans nul, pour ne pas ressembler à de l'UTF-16, et invalide en UTF-8.
	contenu := append([]byte("\xe9t\xe9 "), tous[1:]...)

	s, enc := Decode(contenu)
	if enc != (Encoding{Name: Windows1252}) {
		t.Fatalf("détecté %+v, attendu windows-1252", enc)
	}
	retour, err := Encode(s, enc)
	if err != nil {
		t.Fatalf("Encode : %v", err)
	}
	if !bytes.Equal(retour, contenu) {
		t.Errorf("aller-retour inexact : %d octets rendus, %d attendus", len(retour), len(contenu))
	}
	if !RoundTrips(contenu) {
		t.Error("RoundTrips = false")
	}
}

func TestWindows1252DecodeLesCaracteresPropresAWindows(t *testing.T) {
	cas := map[string]string{
		"r\xe9sum\xe9 de l'\xe9t\xe9":   "résumé de l'été",
		"\x80 \x9c\x9d \x93cit\xe9\x94": "€ œ\u009d “cité”",
		"na\xefve \xc7a":                "naïve Ça",
	}
	for brut, attendu := range cas {
		s, enc := Decode([]byte(brut))
		if enc.Name != Windows1252 {
			t.Errorf("%q : détecté %s", brut, enc.Name)
		}
		if s != attendu {
			t.Errorf("Decode(%q) = %q, attendu %q", brut, s, attendu)
		}
	}
}

// Refuser plutôt que remplacer : un « ? » à la place d'un caractère saisi
// serait une perte sans message.
func TestWindows1252RefuseUnCaractereQuIlNeSaitPasEcrire(t *testing.T) {
	for _, s := range []string{"😀", "łódź", "Ω", "\u0080"} {
		_, err := Encode("avant "+s, Encoding{Name: Windows1252})
		if err == nil || !strings.Contains(err.Error(), "["+CodeUnrepresentable+"]") {
			t.Errorf("Encode(%q) : %v, attendu %s", s, err, CodeUnrepresentable)
		}
	}
	if b, err := Encode("€ œ “é”", Encoding{Name: Windows1252}); err != nil || string(b) != "\x80 \x9c \x93\xe9\x94" {
		t.Errorf("Encode des caractères de Windows-1252 : %q, %v", b, err)
	}
}

// Unrepresentable répond comme Encode, sur tout Unicode : un désaccord ferait
// quitter l'éditeur sur un texte qu'Encode refuse, ou l'y retenir à tort.
func TestUnrepresentableDitCommeEncode(t *testing.T) {
	win := Encoding{Name: Windows1252}
	for r := rune(0); r <= 0x2FFFF; r++ {
		if r >= 0xD800 && r <= 0xDFFF {
			continue // pas un caractère : Go ne le met pas dans une chaîne
		}
		_, errEncode := Encode(string(r), win)
		_, refuse := Unrepresentable(string(r), win)
		if refuse != (errEncode != nil) {
			t.Fatalf("U+%04X : Unrepresentable %v, Encode %v", r, refuse, errEncode)
		}
	}

	if r, ok := Unrepresentable("été 😀 puis Ω", win); !ok || r != '😀' {
		t.Errorf("premier caractère refusé : %q, %v, attendu 😀", r, ok)
	}
	for _, enc := range []Encoding{{Name: UTF8}, {Name: UTF16LE, BOM: true}, {Name: UTF16BE}} {
		if r, ok := Unrepresentable("😀 łódź Ω", enc); ok {
			t.Errorf("%s refuse %q", enc.Name, r)
		}
	}
}

func TestUTF16AvecBOMDansLesDeuxSens(t *testing.T) {
	cas := []struct {
		nom     string
		brut    []byte
		attendu Encoding
	}{
		{"petit-boutiste", []byte("\xff\xfeé\x00t\x00=\xd8\x00\xde"), Encoding{Name: UTF16LE, BOM: true}},
		{"gros-boutiste", []byte("\xfe\xff\x00é\x00t\xd8=\xde\x00"), Encoding{Name: UTF16BE, BOM: true}},
	}
	for _, c := range cas {
		// « é » s'écrit ici en un octet : c'est le Latin-1 de la chaîne Go
		// source qui est en UTF-8, d'où la reconstruction explicite.
		brut := bytes.ReplaceAll(c.brut, []byte("é"), []byte{0xe9})
		s, enc := Decode(brut)
		if enc != c.attendu {
			t.Errorf("%s : détecté %+v, attendu %+v", c.nom, enc, c.attendu)
		}
		if s != "ét😀" {
			t.Errorf("%s : Decode = %q, attendu %q", c.nom, s, "ét😀")
		}
		if !RoundTrips(brut) {
			t.Errorf("%s : aller-retour inexact", c.nom)
		}
	}
}

// « ok » en UTF-16LE sans BOM est de l'UTF-8 valide : sans l'examen des nuls,
// il serait pris pour de l'UTF-8 et ouvert en saisie, octets nuls compris.
func TestUTF16SansBOMReconnuASesNuls(t *testing.T) {
	cas := map[string]Name{
		"o\x00k\x00 \x00\xe9\x00": UTF16LE,
		"\x00o\x00k\x00 \x00\xe9": UTF16BE,
	}
	for brut, attendu := range cas {
		s, enc := Decode([]byte(brut))
		if enc != (Encoding{Name: attendu}) {
			t.Errorf("%q : détecté %+v, attendu %s", brut, enc, attendu)
		}
		if s != "ok é" {
			t.Errorf("%q : Decode = %q", brut, s)
		}
		if !RoundTrips([]byte(brut)) {
			t.Errorf("%q : aller-retour inexact", brut)
		}
	}

	// Un nul isolé dans un long texte UTF-8 ne suffit pas.
	isole := []byte(strings.Repeat("texte ordinaire ", 8) + "\x00!")
	if enc := Detect(isole); enc.Name != UTF8 {
		t.Errorf("un nul isolé fait détecter %s", enc.Name)
	}
}

func TestUTF8(t *testing.T) {
	s, enc := Decode([]byte("\xef\xbb\xbf# Titre été"))
	if enc != (Encoding{Name: UTF8, BOM: true}) || s != "# Titre été" {
		t.Errorf("UTF-8 avec BOM : %q, %+v — la BOM doit être retirée du texte", s, enc)
	}
	if !RoundTrips([]byte("\xef\xbb\xbf# Titre")) {
		t.Error("la BOM UTF-8 n'est pas rendue à l'écriture")
	}

	s, enc = Decode([]byte("# Titre 😀"))
	if enc != (Encoding{Name: UTF8}) || s != "# Titre 😀" {
		t.Errorf("UTF-8 : %q, %+v", s, enc)
	}

	// Une BOM UTF-8 devant une séquence invalide désigne un UTF-8 abîmé, pas
	// du Windows-1252 éditable sous forme de mojibake.
	invalide := []byte("\xef\xbb\xbfr\xe9sum\xe9")
	if enc := Detect(invalide); enc != (Encoding{Name: UTF8, BOM: true}) {
		t.Errorf("BOM UTF-8 suivie d'octets invalides : détecté %+v", enc)
	}
	if RoundTrips(invalide) {
		t.Error("un UTF-8 à BOM invalide ne doit pas être déclaré réécrivable")
	}
}

// Ce qui ne revient pas à l'identique doit se savoir : c'est la condition qui
// interdira la réécriture.
func TestRoundTripsSignaleUnContenuAbime(t *testing.T) {
	cas := map[string][]byte{
		"substitution orpheline": []byte("\xff\xfea\x00\x00\xd8b\x00"),
		"octet orphelin":         []byte("\xff\xfea\x00b"),
	}
	for nom, brut := range cas {
		if RoundTrips(brut) {
			t.Errorf("%s : RoundTrips = true", nom)
		}
	}
}
