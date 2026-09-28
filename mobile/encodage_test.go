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
		// Les 256 octets, fins de ligne mises à part : Windows-1252 n'en perd
		// aucun, pas même les cinq qu'il ne définit pas.
		"tous.txt":   sansFinDeLigne(),
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

func sansFinDeLigne() []byte {
	var b []byte
	for i := 0; i < 256; i++ {
		if i != '\r' && i != '\n' {
			b = append(b, byte(i))
		}
	}
	return b
}

// Le texte en lecture seule est refusé à chaque porte d'écriture, pas
// seulement à celle que l'interface est censée emprunter.
func TestUnTexteEnLectureSeuleNeSEcritPas(t *testing.T) {
	app, server, _ := prepare(t)

	const nom = "config.yaml"
	server.mu.Lock()
	server.files["Notes/"+nom] = []byte("port: 80\n")
	server.etags["Notes/"+nom] = server.nextETag()
	server.mu.Unlock()

	if err := app.WriteNote(nom, "port: 81\n"); ErrorCode(errString(err)) != "READONLY" {
		t.Errorf("WriteNote : %v, attendu READONLY", err)
	}
	if _, err := app.OpenEditJSON(nom, "port: 80\n"); ErrorCode(errString(err)) != "READONLY" {
		t.Errorf("OpenEditJSON : %v, attendu READONLY", err)
	}
	if _, err := app.CopyJSON(nom, ""); ErrorCode(errString(err)) != CodeUnsupported {
		t.Errorf("CopyJSON : %v, attendu %s", err, CodeUnsupported)
	}
	if n := app.PendingCount(); n != 0 {
		t.Errorf("%d écriture(s) en attente vers le serveur", n)
	}

	server.mu.Lock()
	defer server.mu.Unlock()
	if got := string(server.files["Notes/"+nom]); got != "port: 80\n" {
		t.Errorf("le serveur a été modifié : %q", got)
	}
}

func TestIsReadOnlyEstExposeSansDupliquerLesExtensions(t *testing.T) {
	for _, nom := range []string{"config.yaml", "rapport.docx"} {
		if !IsReadOnly(nom) {
			t.Errorf("IsReadOnly(%q) = false", nom)
		}
	}
	for _, nom := range []string{"note.md", "note.txt"} {
		if IsReadOnly(nom) {
			t.Errorf("IsReadOnly(%q) = true", nom)
		}
	}
	if !IsPlainText("config.yaml") {
		t.Error("un .yaml ne s'affiche pas tel quel")
	}
}
