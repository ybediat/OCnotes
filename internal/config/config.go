// Package config persiste les réglages non sensibles de l'application.
//
// Aucun secret n'est stocké ici. L'App Token vit côté Android, dans des
// EncryptedSharedPreferences adossées au Keystore matériel, et il est
// retransmis au cœur Go à chaque démarrage. Le code Go ne conserve donc jamais
// d'identifiant sur le disque : ce fichier de configuration peut être lu sans
// rien compromettre.
package config

import (
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

// FileName est le nom du fichier de configuration dans le dossier de données.
const FileName = "config.json"

// version distingue les évolutions du format. Une configuration d'une version
// inconnue est ignorée : mieux vaut refaire la connexion que repartir sur des
// réglages mal interprétés.
const version = 1

// Config rassemble ce qu'il faut retrouver au démarrage pour reconstituer la
// session — tout sauf le secret.
type Config struct {
	Version int `json:"version"`

	// ServerURL est la racine du serveur, sans chemin ni slash final.
	ServerURL string `json:"serverUrl"`

	// Username est le login, ou l'UUID du compte si le fournisseur
	// d'identité est en mode autoprovisioning.
	Username string `json:"username"`

	// DriveID a la forme {storageID}${spaceID}.
	DriveID   string `json:"driveId,omitempty"`
	DriveName string `json:"driveName,omitempty"`

	// DriveWebDavURL est l'URL WebDAV de l'espace, telle que le serveur l'a
	// annoncée.
	//
	// Elle est persistée pour que la session puisse être remontée sans le
	// moindre appel réseau : sans elle, il faudrait interroger
	// /graph/v1.0/me/drives au démarrage, et l'application serait inutilisable
	// hors connexion — ce qui viderait le modèle local-first de son sens.
	DriveWebDavURL string `json:"driveWebDavUrl,omitempty"`

	// Root est le dossier de notes dans l'espace. Vide signifie la racine de
	// l'espace, ce qui permet de brancher l'application sur une arborescence
	// existante.
	Root string `json:"root"`

	// LastPath est le dernier dossier consulté, pour rouvrir l'application au
	// même endroit.
	LastPath string `json:"lastPath,omitempty"`
}

// Path renvoie l'emplacement du fichier de configuration.
func Path(dataDir string) string {
	return filepath.Join(dataDir, FileName)
}

// Load lit la configuration.
//
// Une configuration absente, illisible ou d'une version inconnue renvoie une
// configuration vide sans erreur : l'application affiche alors l'écran de
// connexion, ce qui est le bon comportement dans les trois cas.
func Load(dataDir string) (Config, error) {
	data, err := os.ReadFile(Path(dataDir))
	if err != nil {
		if os.IsNotExist(err) {
			return Config{}, nil
		}
		return Config{}, fmt.Errorf("config: lecture de %s: %w", Path(dataDir), err)
	}

	var c Config
	if err := json.Unmarshal(data, &c); err != nil || c.Version != version {
		return Config{}, nil
	}
	return c, nil
}

// Save écrit la configuration.
//
// Comme pour l'index du cache, l'écriture passe par un fichier temporaire
// renommé : une interruption ne doit pas laisser une configuration tronquée
// qui obligerait l'utilisateur à tout ressaisir.
func Save(dataDir string, c Config) error {
	c.Version = version
	if err := c.Validate(); err != nil {
		return err
	}

	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return fmt.Errorf("config: création de %s: %w", dataDir, err)
	}

	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return fmt.Errorf("config: sérialisation: %w", err)
	}

	tmp := Path(dataDir) + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return fmt.Errorf("config: écriture: %w", err)
	}
	if err := os.Rename(tmp, Path(dataDir)); err != nil {
		return fmt.Errorf("config: remplacement: %w", err)
	}
	return nil
}

// Clear efface la configuration. Sert à la déconnexion.
func Clear(dataDir string) error {
	err := os.Remove(Path(dataDir))
	if err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("config: suppression: %w", err)
	}
	return nil
}

// Validate vérifie qu'une configuration est cohérente avant d'être écrite.
func (c Config) Validate() error {
	if c.ServerURL == "" {
		return fmt.Errorf("config: URL de serveur manquante")
	}
	u, err := url.Parse(c.ServerURL)
	if err != nil {
		return fmt.Errorf("config: URL de serveur invalide %q: %w", c.ServerURL, err)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return fmt.Errorf("config: URL de serveur invalide %q: schéma http ou https attendu", c.ServerURL)
	}
	if u.Host == "" {
		return fmt.Errorf("config: URL de serveur invalide %q: hôte manquant", c.ServerURL)
	}
	if strings.TrimSpace(c.Username) == "" {
		return fmt.Errorf("config: nom d'utilisateur manquant")
	}
	return nil
}

// IsConnected indique qu'un serveur et un compte sont connus.
func (c Config) IsConnected() bool {
	return c.Validate() == nil
}

// HasWorkspace indique qu'un espace a été choisi et que la bibliothèque de
// notes peut être reconstituée sans repasser par l'écran de sélection, ni même
// contacter le serveur.
func (c Config) HasWorkspace() bool {
	return c.IsConnected() && c.DriveID != "" && c.DriveWebDavURL != ""
}

// NormalizeServerURL nettoie une URL saisie par l'utilisateur.
//
// Un serveur se saisit au clavier sur un téléphone : accepter « cloud.exemple.fr »
// et en faire « https://cloud.exemple.fr » évite un échec de connexion pour un
// préfixe manquant.
func NormalizeServerURL(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return ""
	}
	if !strings.Contains(raw, "://") {
		raw = "https://" + raw
	}
	return strings.TrimRight(raw, "/")
}
