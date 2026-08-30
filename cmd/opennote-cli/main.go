// Commande opennote-cli est un harnais de test desktop pour le cœur métier.
//
// Elle exécute le vrai client Go contre un vrai serveur OpenCloud, là où les
// tests unitaires ne valident que des hypothèses face à un serveur simulé.
// Elle sert aussi à capturer des cas réels quand un comportement surprend.
//
// L'App Token se transmet par la variable d'environnement OPENNOTE_APP_TOKEN,
// jamais par un argument : un argument atterrirait dans l'historique du shell
// et dans la liste des processus.
package main

import (
	"bytes"
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"
	"text/tabwriter"
	"time"

	"github.com/ybediat/OpenNote/internal/opencloud"
)

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "erreur:", err)
		os.Exit(1)
	}
}

const usageText = `opennote-cli — harnais de test du client OpenCloud

Usage :
  opennote-cli [options] <commande> [arguments]

Commandes :
  drives                 liste les espaces accessibles
  ls [chemin]            liste un dossier (défaut : racine de l'espace)
  tree [chemin]          liste récursivement
  cat <chemin>           affiche une note
  put <chemin> [fichier] écrit une note (depuis un fichier, ou l'entrée standard)
  mkdir <chemin>         crée un dossier et ses parents
  mv <source> <cible>    renomme ou déplace
  rm <chemin>            supprime

Les options doivent précéder la commande.

Authentification :
  L'App Token se lit dans la variable d'environnement OPENNOTE_APP_TOKEN.
  OPENNOTE_SERVER et OPENNOTE_USER fournissent les valeurs par défaut de
  -server et -user.

Exemple :
  $env:OPENNOTE_APP_TOKEN = "..."
  opennote-cli -server https://cloud.exemple.fr -user moi ls Notes

Options :
`

func run(args []string) error {
	fs := flag.NewFlagSet("opennote-cli", flag.ContinueOnError)
	fs.SetOutput(os.Stderr)
	fs.Usage = func() {
		fmt.Fprint(os.Stderr, usageText)
		fs.PrintDefaults()
	}

	server := fs.String("server", os.Getenv("OPENNOTE_SERVER"), "URL du serveur OpenCloud")
	user := fs.String("user", os.Getenv("OPENNOTE_USER"), "nom d'utilisateur, ou UUID du compte si l'IdP est en autoprovisioning")
	driveName := fs.String("drive", "", "nom ou identifiant de l'espace (défaut : espace personnel)")
	timeout := fs.Duration("timeout", 30*time.Second, "délai maximal d'une requête")

	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return nil
		}
		return err
	}

	rest := fs.Args()
	if len(rest) == 0 {
		fs.Usage()
		return errors.New("commande manquante")
	}

	token := os.Getenv("OPENNOTE_APP_TOKEN")
	switch {
	case *server == "":
		return errors.New("serveur non indiqué : utiliser -server ou OPENNOTE_SERVER")
	case *user == "":
		return errors.New("utilisateur non indiqué : utiliser -user ou OPENNOTE_USER")
	case token == "":
		return errors.New("App Token absent : définir OPENNOTE_APP_TOKEN")
	}

	client, err := opencloud.New(*server, opencloud.AppTokenAuth{Username: *user, Token: token})
	if err != nil {
		return err
	}

	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()

	command, cmdArgs := rest[0], rest[1:]
	if command == "drives" {
		return cmdDrives(ctx, client)
	}

	space, err := openSpace(ctx, client, *driveName)
	if err != nil {
		return err
	}

	switch command {
	case "ls":
		return cmdList(ctx, space, firstOr(cmdArgs, ""))
	case "tree":
		return cmdTree(ctx, space, firstOr(cmdArgs, ""), "")
	case "cat":
		return cmdCat(ctx, space, cmdArgs)
	case "put":
		return cmdPut(ctx, space, cmdArgs)
	case "mkdir":
		return cmdMkdir(ctx, space, cmdArgs)
	case "mv":
		return cmdMove(ctx, space, cmdArgs)
	case "rm":
		return cmdRemove(ctx, space, cmdArgs)
	default:
		fs.Usage()
		return fmt.Errorf("commande inconnue: %s", command)
	}
}

// openSpace choisit l'espace de travail : celui demandé par nom ou par
// identifiant, sinon l'espace personnel.
func openSpace(ctx context.Context, c *opencloud.Client, wanted string) (*opencloud.Space, error) {
	drives, err := c.ListDrives(ctx)
	if err != nil {
		return nil, err
	}

	if wanted != "" {
		for _, d := range drives {
			if d.Name == wanted || d.ID == wanted {
				return c.Space(d)
			}
		}
		return nil, fmt.Errorf("espace %q introuvable (voir « opennote-cli drives »)", wanted)
	}

	drive, ok := opencloud.PersonalDrive(drives)
	if !ok {
		return nil, errors.New("aucun espace de stockage exploitable")
	}
	return c.Space(drive)
}

func cmdDrives(ctx context.Context, c *opencloud.Client) error {
	drives, err := c.ListDrives(ctx)
	if err != nil {
		return err
	}

	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "TYPE\tNOM\tSTOCKAGE\tIDENTIFIANT")
	for _, d := range drives {
		usable := "non"
		if d.IsStorage() {
			usable = "oui"
		}
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\n", d.Type, d.Name, usable, d.ID)
	}
	return w.Flush()
}

func cmdList(ctx context.Context, s *opencloud.Space, dir string) error {
	resources, err := s.List(ctx, dir)
	if err != nil {
		return err
	}
	if len(resources) == 0 {
		fmt.Fprintln(os.Stderr, "(dossier vide)")
		return nil
	}

	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "TYPE\tTAILLE\tMODIFIÉ\tNOM")
	for _, r := range resources {
		kind, size := "note", fmt.Sprintf("%d", r.Size)
		name := r.Name
		if r.IsDir {
			kind, size, name = "dossier", "-", r.Name+"/"
		}
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\n", kind, size, r.ModTime.Local().Format("2006-01-02 15:04"), name)
	}
	return w.Flush()
}

func cmdTree(ctx context.Context, s *opencloud.Space, dir, indent string) error {
	resources, err := s.List(ctx, dir)
	if err != nil {
		return err
	}
	for _, r := range resources {
		if r.IsDir {
			fmt.Printf("%s%s/\n", indent, r.Name)
			if err := cmdTree(ctx, s, r.Path, indent+"  "); err != nil {
				return err
			}
			continue
		}
		fmt.Printf("%s%s  (%d o)\n", indent, r.Name, r.Size)
	}
	return nil
}

func cmdCat(ctx context.Context, s *opencloud.Space, args []string) error {
	if len(args) != 1 {
		return errors.New("usage: cat <chemin>")
	}
	content, etag, err := s.Read(ctx, args[0])
	if err != nil {
		return err
	}
	fmt.Fprintf(os.Stderr, "ETag: %s\n", etag)
	_, err = os.Stdout.Write(content)
	return err
}

func cmdPut(ctx context.Context, s *opencloud.Space, args []string) error {
	if len(args) < 1 || len(args) > 2 {
		return errors.New("usage: put <chemin> [fichier]")
	}

	var (
		content []byte
		err     error
	)
	if len(args) == 2 {
		content, err = os.ReadFile(args[1])
	} else {
		content, err = io.ReadAll(os.Stdin)
	}
	if err != nil {
		return err
	}

	// PowerShell préfixe d'un BOM ce qu'il envoie dans un pipe vers un
	// exécutable natif. Un BOM dans un fichier Markdown est un caractère
	// invisible parasite, qui se retrouverait tel quel dans l'éditeur mobile.
	if trimmed, ok := bytes.CutPrefix(content, []byte{0xEF, 0xBB, 0xBF}); ok {
		content = trimmed
		fmt.Fprintln(os.Stderr, "note: BOM UTF-8 retiré en tête du contenu")
	}

	etag, err := s.Write(ctx, args[0], content, "")
	if err != nil {
		return err
	}
	fmt.Fprintf(os.Stderr, "%d octets écrits, ETag %s\n", len(content), etag)
	return nil
}

func cmdMkdir(ctx context.Context, s *opencloud.Space, args []string) error {
	if len(args) != 1 {
		return errors.New("usage: mkdir <chemin>")
	}
	return s.MkdirAll(ctx, args[0])
}

func cmdMove(ctx context.Context, s *opencloud.Space, args []string) error {
	if len(args) != 2 {
		return errors.New("usage: mv <source> <cible>")
	}
	return s.Move(ctx, args[0], args[1])
}

func cmdRemove(ctx context.Context, s *opencloud.Space, args []string) error {
	if len(args) != 1 {
		return errors.New("usage: rm <chemin>")
	}
	return s.Remove(ctx, args[0])
}

func firstOr(args []string, fallback string) string {
	if len(args) > 0 {
		return strings.TrimSpace(args[0])
	}
	return fallback
}
