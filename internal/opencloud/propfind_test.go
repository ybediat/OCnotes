package opencloud

import (
	"os"
	"path/filepath"
	"testing"
)

// spaceBase est la racine WebDAV telle qu'elle apparaît dans les fixtures.
// Le '$' entre l'identifiant de stockage et celui de l'espace est volontaire :
// c'est la forme réelle produite par OpenCloud.
const spaceBase = "/dav/spaces/11111111-1111-4111-8111-111111111111$22222222-2222-4222-8222-222222222222"

func readFixture(t *testing.T, name string) []byte {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatalf("lecture de la fixture %s: %v", name, err)
	}
	return data
}

// Les fixtures proviennent d'un serveur OpenCloud 7.0.0 réel (capturées par
// scripts/spike-webdav.ps1), avec les identifiants anonymisés.
func TestParseMultistatusTree(t *testing.T) {
	resources, err := parseMultistatus(readFixture(t, "propfind-tree.xml"), spaceBase)
	if err != nil {
		t.Fatalf("parseMultistatus: %v", err)
	}

	want := []Resource{
		{Path: "Notes", Name: "Notes", IsDir: true, Permissions: "RDNVCKZP"},
		{Path: "Notes/Sous-dossier", Name: "Sous-dossier", IsDir: true, Permissions: "RDNVCKZP"},
		{Path: "Notes/note-1.md", Name: "note-1.md", Size: 27, ContentType: "text/markdown"},
		{Path: "Notes/Réunion du 15 - notes à relire.md", Name: "Réunion du 15 - notes à relire.md", Size: 35, ContentType: "text/markdown"},
	}

	if len(resources) != len(want) {
		t.Fatalf("%d ressources, %d attendues", len(resources), len(want))
	}

	for i, w := range want {
		got := resources[i]
		if got.Path != w.Path {
			t.Errorf("[%d] Path = %q, attendu %q", i, got.Path, w.Path)
		}
		if got.Name != w.Name {
			t.Errorf("[%d] Name = %q, attendu %q", i, got.Name, w.Name)
		}
		if got.IsDir != w.IsDir {
			t.Errorf("[%d] %s: IsDir = %v, attendu %v", i, got.Path, got.IsDir, w.IsDir)
		}
		if got.Size != w.Size {
			t.Errorf("[%d] %s: Size = %d, attendu %d", i, got.Path, got.Size, w.Size)
		}
		if got.ContentType != w.ContentType {
			t.Errorf("[%d] %s: ContentType = %q, attendu %q", i, got.Path, got.ContentType, w.ContentType)
		}
		if w.Permissions != "" && got.Permissions != w.Permissions {
			t.Errorf("[%d] %s: Permissions = %q, attendu %q", i, got.Path, got.Permissions, w.Permissions)
		}
		if got.ETag == "" {
			t.Errorf("[%d] %s: ETag vide", i, got.Path)
		}
		if got.ModTime.IsZero() {
			t.Errorf("[%d] %s: ModTime nulle", i, got.Path)
		}
	}
}

// Un dossier porte un second bloc <d:propstat> en 404 contenant
// getcontentlength et getcontenttype vides. Le parser doit l'ignorer, sans
// quoi un dossier passerait pour un fichier de taille nulle.
func TestParseMultistatusIgnorePropstat404(t *testing.T) {
	resources, err := parseMultistatus(readFixture(t, "propfind-tree.xml"), spaceBase)
	if err != nil {
		t.Fatalf("parseMultistatus: %v", err)
	}

	for _, r := range resources {
		if !r.IsDir {
			continue
		}
		if r.ContentType != "" {
			t.Errorf("%s est un dossier mais porte le ContentType %q", r.Path, r.ContentType)
		}
		if r.FileID == "" {
			t.Errorf("%s: FileID vide, le bloc propstat 200 n'a pas été lu", r.Path)
		}
	}
}

// La racine de l'espace doit produire un Path vide plutôt qu'une erreur.
func TestParseMultistatusRoot(t *testing.T) {
	resources, err := parseMultistatus(readFixture(t, "propfind-root.xml"), spaceBase)
	if err != nil {
		t.Fatalf("parseMultistatus: %v", err)
	}
	if len(resources) != 1 {
		t.Fatalf("%d ressources, 1 attendue", len(resources))
	}
	if got := resources[0]; got.Path != "" || !got.IsDir {
		t.Errorf("racine = %+v, attendu Path vide et IsDir vrai", got)
	}
}

func TestRelativePath(t *testing.T) {
	tests := []struct {
		name string
		href string
		want string
	}{
		{"racine", spaceBase + "/", ""},
		{"racine sans slash", spaceBase, ""},
		{"fichier", spaceBase + "/note.md", "note.md"},
		{"dossier", spaceBase + "/Notes/", "Notes"},
		{"imbrique", spaceBase + "/Notes/Sous-dossier/a.md", "Notes/Sous-dossier/a.md"},
		{"accents percent-encodes", spaceBase + "/R%C3%A9union%20du%2015.md", "Réunion du 15.md"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := relativePath(tc.href, spaceBase)
			if err != nil {
				t.Fatalf("relativePath(%q): %v", tc.href, err)
			}
			if got != tc.want {
				t.Errorf("relativePath(%q) = %q, attendu %q", tc.href, got, tc.want)
			}
		})
	}
}

func TestRelativePathHorsEspace(t *testing.T) {
	if _, err := relativePath("/dav/spaces/un-autre-espace/note.md", spaceBase); err == nil {
		t.Error("un href hors de l'espace devrait produire une erreur")
	}
}

func TestPropstatOK(t *testing.T) {
	tests := map[string]bool{
		"HTTP/1.1 200 OK":           true,
		"HTTP/1.1 404 Not Found":    false,
		"HTTP/1.1 403 Forbidden":    false,
		"":                          false,
		"HTTP/1.1 207 Multi-Status": false,
	}
	for status, want := range tests {
		if got := propstatOK(status); got != want {
			t.Errorf("propstatOK(%q) = %v, attendu %v", status, got, want)
		}
	}
}
