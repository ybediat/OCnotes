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

// De vrais textes étrangers, dans leur encodage d'origine, lus de travers en
// Windows-1252 : aucun ne doit passer pour un texte de l'Ouest. Les octets
// viennent des codecs de Python, pas d'une recopie à la main.
func TestPlausible1252RefuseLesAutresEncodages(t *testing.T) {
	cas := map[string][]byte{
		// « Zażółć gęślą jaźń. Było już późno. »
		"polonais ISO-8859-2": []byte("Za\xbf\xf3\xb3\xe6 g\xea\xb6l\xb1 ja\xbc\xf1. By\xb3o ju\xbf p\xf3\xbcno."),
		// « Příliš žluťoučký kůň » : le « ť » est un octet que Windows-1252 ne définit pas.
		"tchèque Windows-1250": []byte("P\xf8\xedli\x9a \x9elu\x9dou\xe8k\xfd k\xf9\xf2"),
		// « Ťapka » : le « Ť » en tête de mot n'est entouré d'aucune lettre, seul
		// son octet indéfini en Windows-1252 le trahit.
		"tchèque, Ť initial": []byte("\x8dapka"),
		// « Привет, мир »
		"russe Windows-1251": []byte("\xcf\xf0\xe8\xe2\xe5\xf2, \xec\xe8\xf0"),
		// « Καλημέρα »
		"grec Windows-1253": []byte("\xca\xe1\xeb\xe7\xec\xdd\xf1\xe1"),
		// « 日本語のテキスト »
		"japonais Shift-JIS": []byte("\x93\xfa\x96{\x8c\xea\x82\xcc\x83e\x83L\x83X\x83g"),
		// « Résumé de l'été » écrit sous DOS
		"français CP850": []byte("R\x82sum\x82 de l'\x82t\x82"),
		// Une image renommée en .txt
		"binaire": []byte("\x89PNG\r\n\x1a\n"),
	}
	for nom, brut := range cas {
		texte, enc := Decode(brut)
		if enc.Name != Windows1252 {
			t.Fatalf("%s : lu en %s, le test suppose Windows-1252", nom, enc.Name)
		}
		if Plausible1252(texte) {
			t.Errorf("%s : %q passe pour un texte de l'Ouest", nom, texte)
		}
	}
}

// Les langues de l'Ouest passent, y compris ce qui frôle les règles : une
// apostrophe typographique entre deux lettres, deux accents d'affilée, un
// symbole collé à un chiffre, une note de trois lettres dont deux accentuées.
func TestPlausible1252AccepteLesLanguesDeLOuest(t *testing.T) {
	for _, texte := range []string{
		"Résumé de l’été, aujourd’hui : 5 € — « œuvre » naïve. Ça !\r\n",
		"Grüße aus Köln, Straße 12.",
		"¿Qué tal? ¡Mañana, señor!",
		"Atenção, não há ações.",
		"Col·lecció d’art català.",
		"Température : 20 °C, 3 m², ½ litre, § 4, © 2026.",
		"Blåbærsyltetøj på Ærø.",
		"été",
		"“Prix” : 5 € — une œuvre…",
		"\tcolonne\tsuivante\f",
	} {
		if !Plausible1252(texte) {
			t.Errorf("%q refusé", texte)
		}
	}
}

func TestEtiquetteDEncodageAllerRetour(t *testing.T) {
	for _, enc := range []Encoding{
		{Name: UTF8}, {Name: UTF8, BOM: true},
		{Name: UTF16LE}, {Name: UTF16LE, BOM: true},
		{Name: UTF16BE}, {Name: UTF16BE, BOM: true},
		{Name: Windows1252},
	} {
		if got, ok := ParseEncoding(enc.String()); !ok || got != enc {
			t.Errorf("ParseEncoding(%q) = %v, %v", enc.String(), got, ok)
		}
	}
	for _, label := range []string{"", "latin1", "ISO-8859-2", "windows-1252+bom"} {
		if got, ok := ParseEncoding(label); ok {
			t.Errorf("ParseEncoding(%q) accepte : %v", label, got)
		}
	}
}

// DecodeAs lit un contenu dans l'encodage qu'on lui impose, même contre la
// détection, mais seulement si l'aller-retour reste exact.
func TestDecodeAs(t *testing.T) {
	win := Encoding{Name: Windows1252}
	cas := []struct {
		nom   string
		brut  []byte
		enc   Encoding
		texte string
		ok    bool
	}{
		// Ce que Plausible1252 refuse, l'utilisateur peut l'imposer.
		{"trois accents d'affilée", []byte("\xc9 \xe9\x9c\x9c \xf8\n"), win, "É éœœ ø\n", true},
		{"UTF-16 sans BOM imposé", []byte("o\x00k\x00"), Encoding{Name: UTF16LE}, "ok", true},
		{"UTF-16 à BOM", []byte("\xff\xfeo\x00k\x00"), Encoding{Name: UTF16LE, BOM: true}, "ok", true},
		{"BOM annoncée mais absente", []byte("o\x00k\x00"), Encoding{Name: UTF16LE, BOM: true}, "", false},
		{"BOM de l'autre boutisme", []byte("\xfe\xff\x00o"), Encoding{Name: UTF16LE, BOM: true}, "", false},
		{"UTF-16 tronqué", []byte("o\x00k"), Encoding{Name: UTF16LE}, "", false},
		{"UTF-8 à BOM", []byte("\xef\xbb\xbf\xc3\xa9"), Encoding{Name: UTF8, BOM: true}, "é", true},
		{"UTF-8 invalide", []byte("\xe9t\xe9"), Encoding{Name: UTF8}, "", false},
		{"encodage inconnu", []byte("abc"), Encoding{Name: "ISO-8859-2"}, "", false},
	}
	for _, c := range cas {
		texte, ok := DecodeAs(c.brut, c.enc)
		if ok != c.ok || texte != c.texte {
			t.Errorf("%s : DecodeAs = %q, %v ; veut %q, %v", c.nom, texte, ok, c.texte, c.ok)
		}
	}
}
