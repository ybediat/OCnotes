package mobile

import (
	"strings"
	"testing"
)

// Un fichier Latin-1 ne doit jamais atteindre Kotlin en chaîne.
//
// gomobile remplacerait ses accents par des « � » ; l'éditeur afficherait ces
// remplacements, et le premier caractère tapé les écrirait sur le serveur à la
// place des accents — tout le fichier, sans un message. ReadNote refuse donc,
// et l'aperçu passe par RenderFileJSON, qui décode les octets côté Go : le
// fichier s'y lit avec ses vrais accents.
func TestReadNoteRefuseUnContenuQuiNEstPasUTF8(t *testing.T) {
	app, server, _ := prepare(t)

	fichiers := map[string]struct {
		brut    []byte
		attendu string // ce que l'aperçu doit montrer
	}{
		// « résumé de l'été » en Latin-1.
		"latin1.txt": {[]byte("r\xe9sum\xe9 de l'\xe9t\xe9\n"), "résumé de l'été"},
		// Les guillemets et l'euro propres à Windows-1252.
		"cp1252.txt": {[]byte("\x93prix\x94 : 5 \x80\n"), "“prix” : 5 €"},
		// « # Été » en UTF-16LE avec BOM, comme l'écrit le Bloc-notes.
		"windows.md": {[]byte("\xff\xfe#\x00 \x00\xc9\x00t\x00\xe9\x00"), "Été"},
		// « note ok » en UTF-16LE sans BOM. Tout en ASCII, c'est de l'UTF-8
		// valide pour utf8.Valid — qui l'ouvrirait en saisie, nuls compris.
		"sans-bom.txt": {[]byte("n\x00o\x00t\x00e\x00 \x00o\x00k\x00"), "note ok"},
	}
	server.mu.Lock()
	for nom, f := range fichiers {
		server.files["Notes/"+nom] = f.brut
		server.etags["Notes/"+nom] = server.nextETag()
	}
	server.files["Notes/propre.md"] = []byte("# Été 😀\n")
	server.etags["Notes/propre.md"] = server.nextETag()
	server.mu.Unlock()

	for nom, f := range fichiers {
		contenu, err := app.ReadNote(nom)
		if code := ErrorCode(errString(err)); code != CodeNotUTF8 {
			t.Errorf("ReadNote(%s) : code %q, attendu %s (contenu %q)", nom, code, CodeNotUTF8, contenu)
		}

		// Le même fichier reste lisible en aperçu, accents compris.
		sortie, err := app.RenderFileJSON(nom)
		if err != nil {
			t.Errorf("RenderFileJSON(%s) : %v", nom, err)
		} else if !strings.Contains(sortie, f.attendu) {
			t.Errorf("RenderFileJSON(%s) ne montre pas %q : %s", nom, f.attendu, sortie)
		}
	}

	if contenu, err := app.ReadNote("propre.md"); err != nil || contenu != "# Été 😀\n" {
		t.Errorf("une note UTF-8 valide est refusée ou altérée : %q, %v", contenu, err)
	}
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
