package notes

import (
	"strings"
	"testing"

	"github.com/ybediat/OpenNote/internal/markdown"
)

// Un fichier de configuration se lit, ne s'écrit pas : chaque prédicat de
// format doit le dire, et pas seulement celui qu'on pense à regarder.
func TestTexteEnLectureSeuleRepondAuxQuatreQuestions(t *testing.T) {
	for _, nom := range []string{"config.yaml", "app.YML", "nginx.conf", "setup.cfg", "php.ini",
		"Cargo.toml", "data.json", "pom.xml", "export.csv", "serveur.log"} {
		if !IsNote(nom) {
			t.Errorf("IsNote(%q) = false : le fichier serait invisible dans le listing", nom)
		}
		if !IsReadOnly(nom) || !IsReadOnlyText(nom) {
			t.Errorf("IsReadOnly(%q) = false : le fichier s'ouvrirait en saisie", nom)
		}
		if IsEditable(nom) {
			t.Errorf("IsEditable(%q) = true : l'application prétendrait l'écrire", nom)
		}
		if !IsPlainText(nom) {
			t.Errorf("IsPlainText(%q) = false : un « # » y deviendrait un titre", nom)
		}
		if IsDocument(nom) {
			t.Errorf("IsDocument(%q) = true : il partirait chez l'analyseur d'archive", nom)
		}
	}

	// Les formats modifiables ne changent pas de camp.
	for _, nom := range []string{"note.md", "note.txt"} {
		if IsReadOnly(nom) || !IsEditable(nom) {
			t.Errorf("%q a perdu son statut modifiable", nom)
		}
	}
	if !IsReadOnly("rapport.docx") {
		t.Error("un document n'est plus en lecture seule")
	}
}

func TestEnsureWritableRefuseUnTexteEnLectureSeule(t *testing.T) {
	err := EnsureWritable("serveur/config.yaml")
	if err == nil {
		t.Fatal("un .yaml est déclaré modifiable")
	}
	if !strings.Contains(err.Error(), "["+CodeReadOnly+"]") {
		t.Errorf("erreur sans le code attendu : %v", err)
	}
}

// L'application ne crée que du Markdown, et un renommage ne change pas de
// format : les deux règles des documents valent pour le texte en lecture seule.
func TestExtensionsDuTexteEnLectureSeule(t *testing.T) {
	if got := WithExtension("config.yaml"); got != "config.yaml.md" {
		t.Errorf("WithExtension(\"config.yaml\") = %q : l'application créerait un .yaml", got)
	}
	cas := []struct{ ref, nom, attendu string }{
		{"config.yaml", "prod", "prod.yaml"},
		{"config.yaml", "prod.YAML", "prod.YAML"},
		{"config.yaml", "prod.yml", "prod.yml.yaml"},
		{"config.yaml", "prod.md", "prod.md.yaml"},
	}
	for _, c := range cas {
		if got := WithExtensionOf(c.ref, c.nom); got != c.attendu {
			t.Errorf("WithExtensionOf(%q, %q) = %q, attendu %q", c.ref, c.nom, got, c.attendu)
		}
	}
}

// La première ligne d'un fichier de configuration est une clé ou un
// commentaire, pas un titre ; et son « # » n'est pas un titre Markdown.
func TestTexteEnLectureSeuleNEstPasInterprete(t *testing.T) {
	contenu := []byte("# commentaire\nport: 80\n")

	note := Note{Name: "config.yaml", DisplayName: DisplayName("config.yaml")}
	if got := TitleOf(note, contenu); got != "config.yaml" {
		t.Errorf("TitleOf = %q, attendu le nom du fichier", got)
	}

	blocs, err := Render("config.yaml", contenu)
	if err != nil {
		t.Fatalf("Render : %v", err)
	}
	for _, b := range blocs {
		if b.Kind != markdown.KindPlain {
			t.Errorf("le .yaml a été interprété : %+v", blocs)
			break
		}
	}
}
