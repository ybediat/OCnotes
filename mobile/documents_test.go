package mobile

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestWriteNoteRefuseUnDocument est le test le plus important du chantier
// documents.
//
// `WriteNote` écrit dans le cache et enfile l'écriture pour le serveur, sans
// regarder l'extension. Le jour où un enregistrement se déclenche sur un .docx
// ouvert — un effet mal placé, une bascule d'aperçu, un brouillon restauré — le
// document de l'utilisateur est remplacé par du texte, en silence, sur un
// serveur partagé. Aucune autre méthode de la façade ne peut détruire une
// donnée aussi discrètement.
func TestWriteNoteRefuseUnDocument(t *testing.T) {
	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	const chemin = "Projets/rapport.docx"
	err = app.WriteNote(chemin, "du texte qui n'a rien à faire là")
	if err == nil {
		t.Fatal("l'écriture sur un .docx a été acceptée")
	}
	if code := ErrorCode(err.Error()); code != "READONLY" {
		t.Errorf("code %q, attendu READONLY : %v", code, err)
	}

	// Le refus ne suffit pas : il faut que rien ne soit resté dans le cache,
	// sinon la prochaine synchronisation pousserait quand même.
	if _, _, present := app.cache.Get(chemin); present {
		t.Error("le document a été écrit dans le cache malgré le refus")
	}
	if n := app.PendingCount(); n != 0 {
		t.Errorf("%d opération(s) en attente après un refus", n)
	}
}

func TestWriteNoteAccepteUneNote(t *testing.T) {
	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	for _, chemin := range []string{"carnet.md", "journal.txt"} {
		if err := app.WriteNote(chemin, "contenu"); err != nil {
			t.Errorf("WriteNote(%q) refuse à tort : %v", chemin, err)
		}
	}
}

func TestPrepareEditJSONRefuseUnDocument(t *testing.T) {
	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	_, err = app.PrepareEditJSON("rapport.odt", "peu importe")
	if err == nil {
		t.Fatal("la préparation d'un champ de saisie sur un .odt a été acceptée")
	}
	if code := ErrorCode(err.Error()); code != "READONLY" {
		t.Errorf("code %q, attendu READONLY : %v", code, err)
	}
}

// TestRenderNoteJSONRefuseUnDocument garde la porte de service.
//
// Le contenu arrive ici **en chaîne**, depuis Kotlin. Un .docx passé par là
// aurait déjà été mutilé par le décodage UTF-8 de gomobile : refuser le nom est
// la seule réponse honnête, et le message dit où aller.
func TestRenderNoteJSONRefuseUnDocument(t *testing.T) {
	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	_, err = app.RenderNoteJSON("rapport.docx", "PK\x03\x04 du binaire abîmé")
	if err == nil {
		t.Fatal("le rendu d'un .docx depuis une chaîne a été accepté")
	}
	if code := ErrorCode(err.Error()); code != CodeUnsupported {
		t.Errorf("code %q, attendu %s : %v", code, CodeUnsupported, err)
	}
}

func TestRenderNoteJSONRendToujoursLesNotes(t *testing.T) {
	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatalf("NewApp: %v", err)
	}

	sortie, err := app.RenderNoteJSON("note.md", "# Titre")
	if err != nil {
		t.Fatalf("RenderNoteJSON: %v", err)
	}
	if !strings.Contains(sortie, `"kind":"heading"`) {
		t.Errorf("le Markdown n'a pas été interprété : %s", sortie)
	}
}

// RenderFileJSON lit et analyse le document du côté Go : le ZIP ne passe jamais
// par une chaîne Kotlin. Le même chemin est ensuite servi depuis le cache quand
// le réseau disparaît.
func TestRenderFileJSONLitUnDocumentPuisLeRouvreHorsConnexion(t *testing.T) {
	app, server, _ := prepare(t)

	for _, nom := range []string{"exemple.docx", "exemple.odt"} {
		document, err := os.ReadFile(filepath.Join("..", "internal", "documents", "testdata", nom))
		if err != nil {
			t.Fatalf("lecture de la fixture %s: %v", nom, err)
		}

		server.mu.Lock()
		server.files["Notes/"+nom] = document
		server.etags["Notes/"+nom] = server.nextETag()
		server.mu.Unlock()

		sortie, err := app.RenderFileJSON(nom)
		if err != nil {
			t.Fatalf("RenderFileJSON(%s): %v", nom, err)
		}
		if !strings.Contains(sortie, `"kind":"heading"`) {
			t.Errorf("%s n'a pas produit son titre: %s", nom, sortie)
		}
		if !strings.Contains(sortie, `"style":"underline"`) {
			t.Errorf("%s n'a pas produit son souligné: %s", nom, sortie)
		}
	}

	server.setOffline(true)
	for _, nom := range []string{"exemple.docx", "exemple.odt"} {
		if _, err := app.RenderFileJSON(nom); err != nil {
			t.Errorf("RenderFileJSON(%s) hors connexion: %v", nom, err)
		}
	}
}

func TestRenderFileJSONTransmetLesErreursDuDocument(t *testing.T) {
	app, server, _ := prepare(t)

	server.mu.Lock()
	server.files["Notes/casse.odt"] = []byte("ceci n'est pas une archive")
	server.etags["Notes/casse.odt"] = server.nextETag()
	server.mu.Unlock()

	_, err := app.RenderFileJSON("casse.odt")
	if err == nil {
		t.Fatal("un document invalide a été rendu")
	}
	if code := ErrorCode(err.Error()); code != "DOC_INVALID" {
		t.Errorf("code %q, attendu DOC_INVALID: %v", code, err)
	}
}

func TestIsDocumentEstExposeSansDupliquerLesExtensions(t *testing.T) {
	for _, nom := range []string{"rapport.docx", "rapport.ODT"} {
		if !IsDocument(nom) {
			t.Errorf("IsDocument(%q) = false", nom)
		}
	}
	if IsDocument("rapport.md") {
		t.Error("IsDocument reconnaît à tort un Markdown")
	}
}
