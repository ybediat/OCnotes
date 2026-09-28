package mobile

import (
	"strings"
	"testing"
)

// Un fichier Latin-1 ou UTF-16 à BOM s'ouvre en saisie, décodé côté Go : ses
// octets bruts n'atteignent jamais Kotlin, où gomobile remplacerait ses accents
// par des « � » que le premier enregistrement écrirait sur le serveur.
func TestReadNoteDecodeLesEncodagesReconnus(t *testing.T) {
	app, server, _ := prepare(t)

	fichiers := map[string]struct {
		brut    []byte
		attendu string
		enc     string
	}{
		// « résumé de l'été » en Latin-1.
		"latin1.txt": {[]byte("r\xe9sum\xe9 de l'\xe9t\xe9\n"), "résumé de l'été\n", "windows-1252"},
		// Les guillemets et l'euro propres à Windows-1252.
		"cp1252.txt": {[]byte("\x93prix\x94 : 5 \x80\n"), "“prix” : 5 €\n", "windows-1252"},
		// « # Été » en UTF-16LE avec BOM, comme l'écrit le Bloc-notes.
		"windows.md": {[]byte("\xff\xfe#\x00 \x00\xc9\x00t\x00\xe9\x00"), "# Été", "UTF-16LE"},
		// La BOM UTF-8 ne se montre pas dans l'éditeur : elle est rendue à
		// l'écriture, comme celle de l'UTF-16.
		"bom.md":    {[]byte("\xef\xbb\xbf# Été\n"), "# Été\n", "UTF-8"},
		"propre.md": {[]byte("# Été 😀\n"), "# Été 😀\n", "UTF-8"},
	}
	octets := map[string][]byte{}
	for nom, f := range fichiers {
		octets[nom] = f.brut
	}
	deposer(server, octets)

	for nom, f := range fichiers {
		contenu, err := app.ReadNote(nom)
		if err != nil || contenu != f.attendu {
			t.Errorf("ReadNote(%s) = %q, %v ; veut %q", nom, contenu, err, f.attendu)
		}
		if enc := app.NoteEncoding(nom); enc != f.enc {
			t.Errorf("NoteEncoding(%s) = %q, veut %q", nom, enc, f.enc)
		}
	}
}

// Deux cas restent en aperçu seul : l'UTF-16 sans BOM, reconnu sur une
// supposition, et un contenu que l'aller-retour ne rend pas à l'identique. Les
// réécrire, même sans modification, pourrait les abîmer.
func TestReadNoteRefuseCeQuIlNeSaitPasReecrire(t *testing.T) {
	app, server, _ := prepare(t)

	fichiers := map[string]struct {
		brut    []byte
		attendu string // ce que l'aperçu doit montrer
	}{
		// « note ok » en UTF-16LE sans BOM. Tout en ASCII, c'est de l'UTF-8
		// valide pour utf8.Valid — qui l'ouvrirait en saisie, nuls compris.
		"sans-bom.txt": {[]byte("n\x00o\x00t\x00e\x00 \x00o\x00k\x00"), "note ok"},
		// « Było już późno » en ISO-8859-2 : lu en Windows-1252, « By³o ju¿
		// pó¼no ». Y écrire mêlerait deux encodages.
		"polonais.txt": {[]byte("By\xb3o ju\xbf p\xf3\xbcno"), "By³o"},
		// UTF-16 tronqué : un octet orphelin en fin de fichier.
		"tronque.txt": {[]byte("\xff\xfeo\x00k\x00!"), "ok"},
	}
	octets := map[string][]byte{}
	for nom, f := range fichiers {
		octets[nom] = f.brut
	}
	deposer(server, octets)

	for nom, f := range fichiers {
		contenu, err := app.ReadNote(nom)
		if code := ErrorCode(errString(err)); code != CodeNotUTF8 {
			t.Errorf("ReadNote(%s) : code %q, attendu %s (contenu %q)", nom, code, CodeNotUTF8, contenu)
		}
		sortie, err := app.RenderFileJSON(nom)
		if err != nil {
			t.Errorf("RenderFileJSON(%s) : %v", nom, err)
		} else if !strings.Contains(sortie, f.attendu) {
			t.Errorf("RenderFileJSON(%s) ne montre pas %q : %s", nom, f.attendu, sortie)
		}
	}
}

// Ce que l'éditeur écrit repart dans l'encodage du fichier, BOM comprise, et
// arrive tel quel sur le serveur.
func TestWriteNoteReecritDansLEncodageDuFichier(t *testing.T) {
	app, server, _ := prepare(t)

	cas := []struct {
		nom, ajout string
		brut, veut []byte
	}{
		{"latin1.txt", "où ça ? 5 €\n",
			[]byte("r\xe9sum\xe9\n"),
			[]byte("r\xe9sum\xe9\no\xf9 \xe7a ? 5 \x80\n")},
		{"windows.md", "\nœ",
			[]byte("\xff\xfe#\x00 \x00\xc9\x00t\x00\xe9\x00"),
			[]byte("\xff\xfe#\x00 \x00\xc9\x00t\x00\xe9\x00\n\x00\x53\x01")},
		{"be.txt", "😀",
			[]byte("\xfe\xff\x00a"),
			[]byte("\xfe\xff\x00a\xd8\x3d\xde\x00")},
		{"bom.md", "é",
			[]byte("\xef\xbb\xbfa"),
			[]byte("\xef\xbb\xbfa\xc3\xa9")},
		// Le Bloc-notes en Windows-1252 et en « \r\n » : les deux se rendent.
		{"bloc-notes.txt", "d\xe9j\xe0\n",
			[]byte("un\r\n"),
			[]byte("un\r\nd\xe9j\xe0\r\n")},
	}
	octets := map[string][]byte{}
	for _, c := range cas {
		octets[c.nom] = c.brut
	}
	deposer(server, octets)

	for _, c := range cas {
		texte, err := app.ReadNote(c.nom)
		if err != nil {
			t.Fatalf("ReadNote(%s) : %v", c.nom, err)
		}
		if err := app.WriteEditedNote("", c.nom, texte+c.ajout); err != nil {
			t.Fatalf("WriteEditedNote(%s) : %v", c.nom, err)
		}
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatal(err)
	}

	server.mu.Lock()
	defer server.mu.Unlock()
	for _, c := range cas {
		if recu := server.files["Notes/"+c.nom]; string(recu) != string(c.veut) {
			t.Errorf("%s : le serveur a reçu %q, veut %q", c.nom, recu, c.veut)
		}
	}
}

// Ouvrir puis réécrire sans rien changer rend exactement les mêmes octets, quel
// que soit l'encodage : c'est la promesse de ne rien abîmer.
func TestReecrireSansModifierRendLesMemesOctets(t *testing.T) {
	app, server, _ := prepare(t)

	octets := map[string][]byte{
		// Tous les caractères imprimables de Windows-1252, séparés d'espaces
		// pour rester un texte plausible, en fins de ligne Windows.
		"tous.txt":   imprimables1252(),
		"utf16le.md": []byte("\xff\xfe\xc9\x00\r\x00\n\x00=\xd8\x00\xde"),
		"utf16be.md": []byte("\xfe\xff\x00\xc9\x00\r\x00\n\xd8=\xde\x00"),
		"bom.md":     []byte("\xef\xbb\xbf# \xc3\x89t\xc3\xa9\r\n"),
	}
	deposer(server, octets)

	for nom, brut := range octets {
		texte, err := app.ReadNote(nom)
		if err != nil {
			t.Fatalf("ReadNote(%s) : %v", nom, err)
		}
		if err := app.WriteNote(nom, texte); err != nil {
			t.Fatalf("WriteNote(%s) : %v", nom, err)
		}
		if contenu, _, _ := app.cache.Get(nom); string(contenu) != string(brut) {
			t.Errorf("%s : %q devenu %q", nom, brut, contenu)
		}
	}
}

// Un caractère que l'encodage ne sait pas écrire fait refuser l'écriture
// entière, sans rien toucher : ni « ? » à sa place, ni conversion silencieuse
// du fichier en UTF-8. Unrepresentable le désigne, pour que l'interface le
// nomme.
func TestUnCaractereNonRepresentableEstRefuse(t *testing.T) {
	app, server, _ := prepare(t)

	brut := []byte("caf\xe9\n")
	deposer(server, map[string][]byte{"latin1.txt": brut})

	texte, err := app.ReadNote("latin1.txt")
	if err != nil {
		t.Fatal(err)
	}
	modifie := texte + "bravo 😀 et Ω\n"

	err = app.WriteNote("latin1.txt", modifie)
	if code := ErrorCode(errString(err)); code != "ENCODING_UNREPRESENTABLE" {
		t.Errorf("WriteNote : code %q (%v), attendu ENCODING_UNREPRESENTABLE", code, err)
	}
	if contenu, _, _ := app.cache.Get("latin1.txt"); string(contenu) != string(brut) {
		t.Errorf("le cache a changé malgré le refus : %q", contenu)
	}
	if n := app.PendingCount(); n != 0 {
		t.Errorf("%d écriture(s) en attente après un refus", n)
	}

	if c := app.Unrepresentable("latin1.txt", modifie); c != "😀" {
		t.Errorf("Unrepresentable = %q, veut le premier caractère refusé, 😀", c)
	}
	if c := app.Unrepresentable("latin1.txt", texte+"œ €\n"); c != "" {
		t.Errorf("Unrepresentable refuse %q, que Windows-1252 sait écrire", c)
	}
}

// L'encodage retenu à la lecture survit à un passage par du pur ASCII. Sans
// cette mémoire, effacer les accents d'une note Latin-1 puis en retaper un
// l'aurait fait partir en UTF-8 : le cache, entre-temps en ASCII, se lit comme
// de l'UTF-8.
func TestLEncodageSurvitAUnTexteSansAccent(t *testing.T) {
	app, server, _ := prepare(t)

	deposer(server, map[string][]byte{"latin1.txt": []byte("\xe9t\xe9\n")})
	if _, err := app.ReadNote("latin1.txt"); err != nil {
		t.Fatal(err)
	}
	if err := app.WriteNote("latin1.txt", "ete\n"); err != nil {
		t.Fatal(err)
	}
	if err := app.WriteNote("latin1.txt", "été\n"); err != nil {
		t.Fatal(err)
	}
	if contenu, _, _ := app.cache.Get("latin1.txt"); string(contenu) != "\xe9t\xe9\n" {
		t.Errorf("cache : %q, veut du Windows-1252", contenu)
	}
}

// La mémoire d'encodage appartient au cache, pas au processus. Un fichier
// Windows-1252 devenu temporairement ASCII doit donc rester Windows-1252 après
// la recréation complète de l'application.
func TestLEncodageSurvitAUnRedemarrageAvecUnTexteASCII(t *testing.T) {
	app, server, dataDir := prepare(t)

	deposer(server, map[string][]byte{"latin1.txt": []byte("\xe9t\xe9\n")})
	if _, err := app.ReadNote("latin1.txt"); err != nil {
		t.Fatal(err)
	}
	if err := app.WriteNote("latin1.txt", "ete\n"); err != nil {
		t.Fatal(err)
	}

	relance, err := NewApp(dataDir)
	if err != nil {
		t.Fatalf("NewApp après redémarrage : %v", err)
	}
	if err := relance.Restore(fakeToken); err != nil {
		t.Fatalf("Restore après redémarrage : %v", err)
	}
	if texte, err := relance.ReadNote("latin1.txt"); err != nil || texte != "ete\n" {
		t.Fatalf("ReadNote après redémarrage = %q, %v", texte, err)
	}
	if enc := relance.NoteEncoding("latin1.txt"); enc != "windows-1252" {
		t.Fatalf("NoteEncoding après redémarrage = %q", enc)
	}
	if err := relance.WriteNote("latin1.txt", "été\n"); err != nil {
		t.Fatal(err)
	}
	if contenu, _, _ := relance.cache.Get("latin1.txt"); string(contenu) != "\xe9t\xe9\n" {
		t.Errorf("cache après redémarrage : %q, veut du Windows-1252", contenu)
	}
}

func TestReadNoteRefuseUnUTF8ABOMInvalide(t *testing.T) {
	app, server, _ := prepare(t)
	deposer(server, map[string][]byte{"abime.md": []byte("\xef\xbb\xbfcaf\xc3\xa9\xff")})

	if _, err := app.ReadNote("abime.md"); ErrorCode(errString(err)) != CodeNotUTF8 {
		t.Fatalf("ReadNote : %v, attendu %s", err, CodeNotUTF8)
	}
}

func deposer(server *fakeServer, fichiers map[string][]byte) {
	server.mu.Lock()
	defer server.mu.Unlock()
	for nom, brut := range fichiers {
		server.files["Notes/"+nom] = brut
		server.etags["Notes/"+nom] = server.nextETag()
	}
}

func imprimables1252() []byte {
	var b []byte
	for c := 0x21; c < 0x100; c++ {
		switch c {
		case 0x7F, 0x81, 0x8D, 0x8F, 0x90, 0x9D:
			continue // DEL et les cinq octets que Windows-1252 ne définit pas
		}
		b = append(b, byte(c), ' ')
		if c%16 == 0 {
			b = append(b, "\r\n"...)
		}
	}
	return b
}

// Le scénario qui a motivé la mémoire du cache : une note Windows-1252 où l'on
// tape « éœœ » — trois caractères non ASCII d'affilée, que la détection juge
// peu plausibles. Écrite par l'application, envoyée, puis rouverte après un
// redémarrage, elle doit se rouvrir en saisie et en Windows-1252.
func TestUneNoteEcriteParLApplicationSeRouvreDansSonEncodage(t *testing.T) {
	app, server, dataDir := prepare(t)
	deposer(server, map[string][]byte{"latin1.txt": []byte("caf\xe9\n")})

	texte, err := app.ReadNote("latin1.txt")
	if err != nil {
		t.Fatal(err)
	}
	if err := app.WriteNote("latin1.txt", texte+"É éœœ ø\n"); err != nil {
		t.Fatal(err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatal(err)
	}

	relancee, err := NewApp(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	if err := relancee.Restore(fakeToken); err != nil {
		t.Fatal(err)
	}
	texte, err = relancee.ReadNote("latin1.txt")
	if err != nil {
		t.Fatalf("ReadNote après redémarrage : %v", err)
	}
	if texte != "café\nÉ éœœ ø\n" {
		t.Errorf("ReadNote = %q", texte)
	}
	if enc := relancee.NoteEncoding("latin1.txt"); enc != "windows-1252" {
		t.Errorf("NoteEncoding = %q", enc)
	}
}

// Modifiée ailleurs, la même note redevient l'affaire de la détection : la
// mémoire ne vaut que pour le contenu que l'application a écrit.
func TestLaMemoireNeCouvrePasUneModificationFaiteAilleurs(t *testing.T) {
	app, server, _ := prepare(t)
	deposer(server, map[string][]byte{"note.txt": []byte("caf\xe9\n")})

	if _, err := app.ReadNote("note.txt"); err != nil {
		t.Fatal(err)
	}
	if err := app.WriteNote("note.txt", "café !\n"); err != nil {
		t.Fatal(err)
	}
	if _, err := app.SyncJSON(); err != nil {
		t.Fatal(err)
	}
	// Quelqu'un y dépose du polonais en ISO-8859-2.
	deposer(server, map[string][]byte{"note.txt": []byte("By\xb3o ju\xbf p\xf3\xbcno")})

	if _, err := app.ReadNote("note.txt"); ErrorCode(errString(err)) != CodeNotUTF8 {
		t.Errorf("ReadNote : %v, attendu %s", err, CodeNotUTF8)
	}
}

// Le bouton « Ouvrir quand même » : ForcibleEncoding dit dans quel encodage,
// ForceEncoding le retient, et la note s'ouvre alors en saisie. Un fichier
// qu'aucun encodage ne rend à l'identique n'a pas de bouton.
func TestOuvrirQuandMemeDansLEncodageDevine(t *testing.T) {
	app, server, _ := prepare(t)
	deposer(server, map[string][]byte{
		"court.txt":    []byte("\xc9 \xe9\x9c\x9c \xf8\n"),
		"sans-bom.txt": []byte("o\x00k\x00"),
		"tronque.txt":  []byte("\xff\xfeo\x00k\x00!"),
	})

	cas := []struct{ nom, enc, texte string }{
		{"court.txt", "windows-1252", "É éœœ ø\n"},
		{"sans-bom.txt", "UTF-16LE", "ok"},
	}
	for _, c := range cas {
		if _, err := app.ReadNote(c.nom); ErrorCode(errString(err)) != CodeNotUTF8 {
			t.Fatalf("ReadNote(%s) : %v, attendu %s avant le choix", c.nom, err, CodeNotUTF8)
		}
		if enc := app.ForcibleEncoding(c.nom); enc != c.enc {
			t.Errorf("ForcibleEncoding(%s) = %q, veut %q", c.nom, enc, c.enc)
		}
		if err := app.ForceEncoding(c.nom); err != nil {
			t.Fatalf("ForceEncoding(%s) : %v", c.nom, err)
		}
		texte, err := app.ReadNote(c.nom)
		if err != nil || texte != c.texte {
			t.Errorf("ReadNote(%s) après le choix = %q, %v ; veut %q", c.nom, texte, err, c.texte)
		}
	}

	// L'UTF-16 sans BOM choisi se réécrit sans BOM, dans le même boutisme.
	if err := app.WriteNote("sans-bom.txt", "ok!"); err != nil {
		t.Fatal(err)
	}
	if contenu, _, _ := app.cache.Get("sans-bom.txt"); string(contenu) != "o\x00k\x00!\x00" {
		t.Errorf("sans-bom.txt réécrit en %q", contenu)
	}

	if _, err := app.ReadNote("tronque.txt"); err == nil {
		t.Fatal("un UTF-16 tronqué s'ouvre en saisie")
	}
	if enc := app.ForcibleEncoding("tronque.txt"); enc != "" {
		t.Errorf("ForcibleEncoding(tronque.txt) = %q, veut aucun", enc)
	}
	if err := app.ForceEncoding("tronque.txt"); ErrorCode(errString(err)) != CodeNotUTF8 {
		t.Errorf("ForceEncoding(tronque.txt) : %v, attendu %s", err, CodeNotUTF8)
	}
}
