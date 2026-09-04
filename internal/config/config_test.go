package config

import (
	"os"
	"strings"
	"testing"
)

func sample() Config {
	return Config{
		ServerURL:      "https://cloud.exemple.fr",
		Username:       "moi",
		DriveID:        "1111-aaaa$2222-bbbb",
		DriveName:      "Admin",
		DriveWebDavURL: "https://cloud.exemple.fr/dav/spaces/1111-aaaa$2222-bbbb",
		Root:           "Notes",
	}
}

// HasWorkspace conditionne le démarrage hors connexion : sans l'URL WebDAV, il
// faudrait interroger le serveur pour la retrouver, et l'application ne
// pourrait pas s'ouvrir dans le métro.
func TestHasWorkspaceExigeLURLWebDav(t *testing.T) {
	sans := sample()
	sans.DriveWebDavURL = ""

	if sans.HasWorkspace() {
		t.Error("HasWorkspace devrait être faux sans URL WebDAV")
	}
	if !sans.IsConnected() {
		t.Error("IsConnected devrait rester vrai : le compte est bien connu")
	}
	if !sample().HasWorkspace() {
		t.Error("HasWorkspace devrait être vrai avec une configuration complète")
	}
}

func TestSaveEtLoad(t *testing.T) {
	dir := t.TempDir()

	if err := Save(dir, sample()); err != nil {
		t.Fatalf("Save: %v", err)
	}

	got, err := Load(dir)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if got.ServerURL != "https://cloud.exemple.fr" || got.Username != "moi" {
		t.Errorf("configuration relue = %+v", got)
	}
	// Le '$' de l'identifiant d'espace doit survivre au passage par JSON.
	if got.DriveID != "1111-aaaa$2222-bbbb" {
		t.Errorf("DriveID = %q", got.DriveID)
	}
	if !got.HasWorkspace() {
		t.Error("HasWorkspace devrait être vrai")
	}
}

// Le fichier de configuration ne doit jamais contenir de secret : le token
// vit dans le Keystore Android, pas ici.
func TestAucunSecretSurLeDisque(t *testing.T) {
	dir := t.TempDir()
	if err := Save(dir, sample()); err != nil {
		t.Fatalf("Save: %v", err)
	}

	data, err := os.ReadFile(Path(dir))
	if err != nil {
		t.Fatalf("lecture: %v", err)
	}

	for _, interdit := range []string{"token", "password", "secret", "authorization"} {
		if strings.Contains(strings.ToLower(string(data)), interdit) {
			t.Errorf("le fichier de configuration contient %q :\n%s", interdit, data)
		}
	}
}

func TestLoadSansFichier(t *testing.T) {
	got, err := Load(t.TempDir())
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if got.IsConnected() {
		t.Error("une configuration absente ne devrait pas être considérée comme connectée")
	}
}

// Une configuration abîmée doit ramener à l'écran de connexion, pas empêcher
// l'application de démarrer.
func TestLoadFichierCorrompu(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(Path(dir), []byte("{pas du JSON"), 0o600); err != nil {
		t.Fatalf("écriture: %v", err)
	}

	got, err := Load(dir)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if got.IsConnected() {
		t.Error("une configuration corrompue ne devrait pas être considérée comme connectée")
	}
}

func TestLoadVersionInconnue(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(Path(dir), []byte(`{"version":99,"serverUrl":"https://x","username":"y"}`), 0o600); err != nil {
		t.Fatalf("écriture: %v", err)
	}

	got, err := Load(dir)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if got.IsConnected() {
		t.Error("une version inconnue devrait être ignorée")
	}
}

func TestValidate(t *testing.T) {
	tests := []struct {
		name  string
		cfg   Config
		valid bool
	}{
		{"complète", sample(), true},
		{"sans espace choisi", Config{ServerURL: "https://x.fr", Username: "moi"}, true},
		{"locale sans serveur ni compte", Config{Mode: ModeLocal}, true},
		{"locale avec un dernier dossier", Config{Mode: ModeLocal, LastPath: "carnets"}, true},
		{"URL manquante", Config{Username: "moi"}, false},
		{"utilisateur manquant", Config{ServerURL: "https://x.fr"}, false},
		{"utilisateur en espaces", Config{ServerURL: "https://x.fr", Username: "   "}, false},
		{"schéma manquant", Config{ServerURL: "cloud.exemple.fr", Username: "moi"}, false},
		{"schéma inattendu", Config{ServerURL: "ftp://x.fr", Username: "moi"}, false},
		{"HTTP interdit", Config{ServerURL: "http://x.fr", Username: "moi"}, false},
		{"hôte manquant", Config{ServerURL: "https://", Username: "moi"}, false},
		{"WebDAV HTTP interdit", Config{ServerURL: "https://x.fr", Username: "moi", DriveWebDavURL: "http://x.fr/dav"}, false},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			err := tc.cfg.Validate()
			if tc.valid && err != nil {
				t.Errorf("Validate() = %v, attendu valide", err)
			}
			if !tc.valid && err == nil {
				t.Error("Validate() a accepté une configuration invalide")
			}
		})
	}
}

// Une configuration locale doit pouvoir être écrite et relue. Sans la sortie
// anticipée de Validate, Save la refuserait faute d'URL, et le mode local ne
// pourrait même pas retenir le dernier dossier consulté.
func TestSaveEtLoadEnModeLocal(t *testing.T) {
	dir := t.TempDir()

	if err := Save(dir, Config{Mode: ModeLocal, LastPath: "carnets"}); err != nil {
		t.Fatalf("Save d'une configuration locale: %v", err)
	}

	got, err := Load(dir)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if !got.IsLocal() {
		t.Error("le mode local n'a pas survécu à l'aller-retour")
	}
	if got.LastPath != "carnets" {
		t.Errorf("LastPath = %q", got.LastPath)
	}
	// Les deux drapeaux qui décident de l'écran de départ : une configuration
	// locale qui se dirait connectée enverrait l'interface chercher un serveur
	// qui n'existe pas.
	if got.IsConnected() {
		t.Error("une configuration locale ne doit pas se présenter comme connectée")
	}
	if got.HasWorkspace() {
		t.Error("une configuration locale n'a pas d'espace de travail")
	}
}

func TestSaveRefuseUneConfigurationInvalide(t *testing.T) {
	dir := t.TempDir()
	if err := Save(dir, Config{Username: "moi"}); err == nil {
		t.Error("Save aurait dû refuser une configuration sans URL")
	}
	if _, err := os.Stat(Path(dir)); !os.IsNotExist(err) {
		t.Error("un fichier a été écrit malgré la configuration invalide")
	}
}

func TestClear(t *testing.T) {
	dir := t.TempDir()
	if err := Save(dir, sample()); err != nil {
		t.Fatalf("Save: %v", err)
	}
	if err := Clear(dir); err != nil {
		t.Fatalf("Clear: %v", err)
	}
	if err := Clear(dir); err != nil {
		t.Errorf("Clear sur une configuration absente: %v", err)
	}

	got, _ := Load(dir)
	if got.IsConnected() {
		t.Error("la configuration existe encore après Clear")
	}
}

// Un serveur se saisit au clavier sur un téléphone : un préfixe manquant ne
// doit pas faire échouer la connexion.
func TestNormalizeServerURL(t *testing.T) {
	tests := map[string]string{
		"cloud.exemple.fr":          "https://cloud.exemple.fr",
		"https://cloud.exemple.fr":  "https://cloud.exemple.fr",
		"https://cloud.exemple.fr/": "https://cloud.exemple.fr",
		"http://192.168.1.10:9200":  "http://192.168.1.10:9200",
		"  cloud.exemple.fr  ":      "https://cloud.exemple.fr",
		"cloud.exemple.fr///":       "https://cloud.exemple.fr",
		"":                          "",
	}

	for in, want := range tests {
		if got := NormalizeServerURL(in); got != want {
			t.Errorf("NormalizeServerURL(%q) = %q, attendu %q", in, got, want)
		}
	}
}
