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
// et l'aperçu passe par RenderFileJSON, qui lit les octets côté Go.
func TestReadNoteRefuseUnContenuQuiNEstPasUTF8(t *testing.T) {
	app, server, _ := prepare(t)

	latin1 := []byte("r\xe9sum\xe9 de l'\xe9t\xe9\n") // « résumé de l'été » en Latin-1
	utf16 := []byte("\xff\xfeo\x00k\x00")             // « ok » en UTF-16LE avec BOM
	server.mu.Lock()
	server.files["Notes/latin1.txt"] = latin1
	server.etags["Notes/latin1.txt"] = server.nextETag()
	server.files["Notes/windows.md"] = utf16
	server.etags["Notes/windows.md"] = server.nextETag()
	server.files["Notes/propre.md"] = []byte("# Été 😀\n")
	server.etags["Notes/propre.md"] = server.nextETag()
	server.mu.Unlock()

	for _, nom := range []string{"latin1.txt", "windows.md"} {
		contenu, err := app.ReadNote(nom)
		if code := ErrorCode(errString(err)); code != CodeNotUTF8 {
			t.Errorf("ReadNote(%s) : code %q, attendu %s (contenu %q)", nom, code, CodeNotUTF8, contenu)
		}

		// Le même fichier reste lisible en aperçu.
		sortie, err := app.RenderFileJSON(nom)
		if err != nil {
			t.Errorf("RenderFileJSON(%s) : %v", nom, err)
		} else if !strings.Contains(sortie, `"kind"`) {
			t.Errorf("RenderFileJSON(%s) n'a produit aucun bloc : %s", nom, sortie)
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
