package opencloud

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"testing"
	"time"
)

// Tests d'intégration contre un vrai serveur OpenCloud.
//
// Ils ne s'exécutent que si les trois variables suivantes sont définies :
//
//	OPENNOTE_IT_SERVER   https://cloud.exemple.fr
//	OPENNOTE_IT_USER     login, ou UUID si l'IdP est en autoprovisioning
//	OPENNOTE_IT_TOKEN    App Token
//
// Ces noms sont volontairement distincts de ceux du CLI : lancer « go test »
// ne doit jamais écrire par accident sur le serveur de quelqu'un.
//
// Tout se passe dans un dossier temporaire supprimé en fin de test, y compris
// en cas d'échec.

func integrationSpace(t *testing.T) (*Space, context.Context, string) {
	t.Helper()

	server := os.Getenv("OPENNOTE_IT_SERVER")
	user := os.Getenv("OPENNOTE_IT_USER")
	token := os.Getenv("OPENNOTE_IT_TOKEN")

	if server == "" || user == "" || token == "" {
		t.Skip("intégration ignorée : définir OPENNOTE_IT_SERVER, OPENNOTE_IT_USER et OPENNOTE_IT_TOKEN")
	}
	if testing.Short() {
		t.Skip("intégration ignorée en mode court")
	}

	client, err := New(server, AppTokenAuth{Username: user, Token: token})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	t.Cleanup(cancel)

	drives, err := client.ListDrives(ctx)
	if err != nil {
		t.Fatalf("ListDrives: %v", err)
	}
	drive, ok := PersonalDrive(drives)
	if !ok {
		t.Fatal("aucun espace de stockage exploitable")
	}

	space, err := client.Space(drive)
	if err != nil {
		t.Fatalf("Space: %v", err)
	}

	sandbox := fmt.Sprintf("opennote-it-%d", time.Now().UnixNano())
	if err := space.Mkdir(ctx, sandbox); err != nil {
		t.Fatalf("création du bac à sable: %v", err)
	}
	t.Cleanup(func() {
		// Contexte neuf : celui du test peut déjà être expiré.
		cleanupCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		if err := space.Remove(cleanupCtx, sandbox); err != nil {
			t.Errorf("nettoyage de %s impossible, à supprimer à la main: %v", sandbox, err)
		}
	})

	return space, ctx, sandbox
}

func TestIntegrationCycleDeVie(t *testing.T) {
	space, ctx, sandbox := integrationSpace(t)
	note := sandbox + "/cycle.md"
	content := []byte("# Titre\n\nUn paragraphe avec des accents : éàçù €.\n")

	etag, err := space.Write(ctx, note, content, "")
	if err != nil {
		t.Fatalf("Write: %v", err)
	}
	if etag == "" {
		t.Error("le PUT n'a pas renvoyé d'ETag : la synchronisation ne pourrait pas détecter les conflits")
	}
	t.Logf("ETag après création : %s", etag)

	got, readETag, err := space.Read(ctx, note)
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if !bytes.Equal(got, content) {
		t.Errorf("aller-retour altéré :\nenvoyé = %q\nrelu   = %q", content, got)
	}
	if readETag != etag {
		t.Errorf("ETag du GET = %q, celui du PUT = %q", readETag, etag)
	}

	info, err := space.Stat(ctx, note)
	if err != nil {
		t.Fatalf("Stat: %v", err)
	}
	if info.IsDir {
		t.Error("Stat annonce un dossier pour une note")
	}
	if info.Size != int64(len(content)) {
		t.Errorf("Size = %d, attendu %d", info.Size, len(content))
	}
	if info.ETag != etag {
		t.Errorf("ETag du PROPFIND = %q, celui du PUT = %q", info.ETag, etag)
	}

	renamed := sandbox + "/cycle-renommee.md"
	if err := space.Move(ctx, note, renamed); err != nil {
		t.Fatalf("Move: %v", err)
	}
	if _, _, err := space.Read(ctx, note); !errors.Is(err, ErrNotFound) {
		t.Errorf("après MOVE, l'ancien chemin renvoie %v, attendu ErrNotFound", err)
	}
	if _, _, err := space.Read(ctx, renamed); err != nil {
		t.Errorf("après MOVE, le nouveau chemin est illisible: %v", err)
	}

	if err := space.Remove(ctx, renamed); err != nil {
		t.Fatalf("Remove: %v", err)
	}
	if _, _, err := space.Read(ctx, renamed); !errors.Is(err, ErrNotFound) {
		t.Errorf("après DELETE, la lecture renvoie %v, attendu ErrNotFound", err)
	}
}

// Le mécanisme sur lequel repose toute la brique 3, vérifié de bout en bout
// à travers le client Go et non plus en curl.
func TestIntegrationConflitETag(t *testing.T) {
	space, ctx, sandbox := integrationSpace(t)
	note := sandbox + "/conflit.md"

	premier, err := space.Write(ctx, note, []byte("version 1"), "")
	if err != nil {
		t.Fatalf("création: %v", err)
	}

	second, err := space.Write(ctx, note, []byte("version 2"), premier)
	if err != nil {
		t.Fatalf("écriture avec un ETag à jour: %v", err)
	}
	if second == premier {
		t.Error("l'ETag n'a pas changé après modification : les conflits seraient indétectables")
	}

	_, err = space.Write(ctx, note, []byte("version 3"), premier)
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("écriture avec un ETag périmé : erreur = %v, attendu ErrConflict", err)
	}

	// La note ne doit pas avoir été écrasée par l'écriture refusée.
	got, _, err := space.Read(ctx, note)
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if string(got) != "version 2" {
		t.Errorf("contenu = %q, attendu \"version 2\" : le refus n'a pas protégé la note", got)
	}
}

// OpenCloud ignore « If-None-Match: * » : un PUT portant cet en-tête écrase
// quand même une ressource existante.
//
// Ce test verrouille ce constat. Il n'échoue pas — il documente une limite du
// serveur, mesurée le 2026-08-28 sur OpenCloud 7.0.0, après qu'un écrasement a
// été observé sur un vrai téléphone.
//
// C'est la raison pour laquelle la synchronisation vérifie explicitement
// l'existence d'une note avant de pousser une création faite hors connexion
// (voir store.pushWrite). Si ce test se met un jour à signaler que le serveur
// honore l'en-tête, cette vérification pourra être allégée — mais pas avant.
func TestIntegrationWriteNewEtIfNoneMatch(t *testing.T) {
	space, ctx, sandbox := integrationSpace(t)
	note := sandbox + "/deja-la.md"

	if _, err := space.Write(ctx, note, []byte("version d'origine"), ""); err != nil {
		t.Fatalf("création initiale: %v", err)
	}

	_, err := space.WriteNew(ctx, note, []byte("version qui ne devrait pas passer"))

	got, _, readErr := space.Read(ctx, note)
	if readErr != nil {
		t.Fatalf("Read: %v", readErr)
	}

	switch {
	case err == nil && string(got) != "version d'origine":
		t.Logf("CONSTAT : le serveur ignore « If-None-Match: * » et a écrasé la note.\n"+
			"La protection des créations hors connexion repose donc entièrement sur la\n"+
			"vérification d'existence faite dans store.pushWrite. Contenu après PUT : %q", got)

	case errors.Is(err, ErrConflict):
		t.Logf("CONSTAT : le serveur honore « If-None-Match: * » (refus en 412).\n" +
			"La vérification d'existence de store.pushWrite devient une seconde barrière,\n" +
			"et pourrait être allégée si ce comportement est garanti.")

	default:
		t.Errorf("comportement inattendu : erreur = %v, contenu = %q", err, got)
	}
}

// OpenCloud ignore aussi If-Match sur les mutations structurelles. Un DELETE
// ou un MOVE avec un ETag périmé aboutit quand même : l'application ne peut
// donc pas déléguer la protection d'une suppression ou d'un déplacement hors
// connexion au serveur.
//
// Ce test documente le comportement mesuré du serveur réel. Le Store devra
// relire et comparer l'ETag avant toute mutation différée, puis conserver la
// ressource distante en cas de divergence.
func TestIntegrationMutationsStructurellesIgnorentIfMatch(t *testing.T) {
	space, ctx, sandbox := integrationSpace(t)

	t.Run("DELETE", func(t *testing.T) {
		note := sandbox + "/suppression.md"
		ancien, err := space.Write(ctx, note, []byte("version 1"), "")
		if err != nil {
			t.Fatalf("création: %v", err)
		}
		if _, err := space.Write(ctx, note, []byte("version 2"), ancien); err != nil {
			t.Fatalf("modification distante: %v", err)
		}

		if _, _, err := space.c.do(ctx, http.MethodDelete, space.resourceURL(note, false), nil, map[string]string{
			"If-Match": ancien,
		}); err != nil {
			t.Fatalf("DELETE avec ETag périmé: %v", err)
		}
		if _, _, err := space.Read(ctx, note); !errors.Is(err, ErrNotFound) {
			t.Errorf("après DELETE avec ETag périmé, lecture = %v, attendu ErrNotFound", err)
		}
	})

	t.Run("MOVE", func(t *testing.T) {
		from := sandbox + "/source.md"
		to := sandbox + "/destination.md"
		ancien, err := space.Write(ctx, from, []byte("version 1"), "")
		if err != nil {
			t.Fatalf("création: %v", err)
		}
		if _, err := space.Write(ctx, from, []byte("version 2"), ancien); err != nil {
			t.Fatalf("modification distante: %v", err)
		}

		if _, _, err := space.c.do(ctx, "MOVE", space.resourceURL(from, false), nil, map[string]string{
			"Destination": space.resourceURL(to, false).String(),
			"Overwrite":   "F",
			"If-Match":    ancien,
		}); err != nil {
			t.Fatalf("MOVE avec ETag périmé: %v", err)
		}
		if _, _, err := space.Read(ctx, from); !errors.Is(err, ErrNotFound) {
			t.Errorf("après MOVE avec ETag périmé, source = %v, attendu ErrNotFound", err)
		}
		if got, _, err := space.Read(ctx, to); err != nil {
			t.Fatalf("destination après MOVE: %v", err)
		} else if string(got) != "version 2" {
			t.Errorf("contenu déplacé = %q, attendu version distante", got)
		}
	})
}

func TestIntegrationDossiers(t *testing.T) {
	space, ctx, sandbox := integrationSpace(t)

	profond := sandbox + "/Projets/2026/Notes"
	if err := space.MkdirAll(ctx, profond); err != nil {
		t.Fatalf("MkdirAll: %v", err)
	}

	// MkdirAll doit être idempotent : c'est ce qui permet à l'application de
	// l'appeler au démarrage sans vérifier au préalable.
	if err := space.MkdirAll(ctx, profond); err != nil {
		t.Errorf("MkdirAll rejoué: %v", err)
	}

	if err := space.Mkdir(ctx, profond); !errors.Is(err, ErrExists) {
		t.Errorf("Mkdir sur un dossier existant : erreur = %v, attendu ErrExists", err)
	}

	if _, err := space.Write(ctx, profond+"/note.md", []byte("# Imbriquée\n"), ""); err != nil {
		t.Fatalf("écriture dans un sous-dossier: %v", err)
	}

	entries, err := space.List(ctx, sandbox+"/Projets/2026")
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("%d entrées, 1 attendue: %+v", len(entries), entries)
	}
	if !entries[0].IsDir {
		t.Error("le sous-dossier n'est pas reconnu comme dossier")
	}
	if entries[0].Name != "Notes" {
		t.Errorf("Name = %q, attendu \"Notes\"", entries[0].Name)
	}
	if entries[0].ContentType != "" {
		t.Errorf("un dossier porte le ContentType %q : le bloc propstat 404 a été lu à tort", entries[0].ContentType)
	}

	vide, err := space.List(ctx, profond+"/../Notes")
	if err != nil {
		t.Fatalf("List avec un chemin à normaliser: %v", err)
	}
	if len(vide) != 1 {
		t.Errorf("%d entrées, 1 attendue après normalisation du chemin", len(vide))
	}
}

// Les noms de notes sont saisis par l'utilisateur : ils contiendront des
// accents, des espaces et de la ponctuation. Plusieurs de ces caractères ont
// une signification dans une URL. Ce test établit lesquels survivent réellement
// à l'aller-retour — c'est la base des règles de validation de la brique 2.
func TestIntegrationNomsDeFichiers(t *testing.T) {
	space, ctx, sandbox := integrationSpace(t)

	noms := []string{
		"simple.md",
		"avec espaces.md",
		"Réunion du 15 à relire.md",
		"parenthèses (2026).md",
		"esperluette & compagnie.md",
		"plus + signe.md",
		"dièse #1.md",
		"point d'interrogation ?.md",
		"pourcent 100%.md",
		"apostrophe d'été.md",
		"virgule, point-virgule;.md",
		"egal=arobase@.md",
		"emoji 😀 et symbole ✅.md",
		"tilde~et^accent.md",
	}

	var rejetes, corrompus []string

	for _, nom := range noms {
		t.Run(nom, func(t *testing.T) {
			chemin := sandbox + "/" + nom
			contenu := []byte("# " + nom + "\n")

			if _, err := space.Write(ctx, chemin, contenu, ""); err != nil {
				// Un refus du serveur n'est pas un bug du client : c'est une
				// contrainte à intégrer aux règles de nommage.
				rejetes = append(rejetes, nom)
				t.Skipf("refusé par le serveur: %v", err)
			}

			relu, _, err := space.Read(ctx, chemin)
			if err != nil {
				corrompus = append(corrompus, nom)
				t.Fatalf("écrit mais illisible au même chemin: %v", err)
			}
			if !bytes.Equal(relu, contenu) {
				corrompus = append(corrompus, nom)
				t.Errorf("contenu altéré: %q", relu)
			}
		})
	}

	// Le nom doit revenir identique dans un listing, sinon l'application ne
	// saurait pas reconstruire le chemin de la note.
	entries, err := space.List(ctx, sandbox)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	vus := make(map[string]bool, len(entries))
	for _, e := range entries {
		vus[e.Name] = true
	}

	for _, nom := range noms {
		if contains(rejetes, nom) {
			continue
		}
		if !vus[nom] {
			t.Errorf("%q est absent du listing : le nom n'a pas fait l'aller-retour à l'identique", nom)
		}
	}

	t.Logf("noms acceptés : %d/%d", len(noms)-len(rejetes), len(noms))
	if len(rejetes) > 0 {
		t.Logf("refusés par le serveur : %q", rejetes)
	}
	if len(corrompus) > 0 {
		t.Logf("altérés à l'aller-retour : %q", corrompus)
	}
}

func contains(list []string, s string) bool {
	for _, v := range list {
		if v == s {
			return true
		}
	}
	return false
}

func TestIntegrationErreurs(t *testing.T) {
	space, ctx, sandbox := integrationSpace(t)

	if _, _, err := space.Read(ctx, sandbox+"/jamais-creee.md"); !errors.Is(err, ErrNotFound) {
		t.Errorf("lecture d'une note absente : erreur = %v, attendu ErrNotFound", err)
	}
	if _, err := space.Stat(ctx, sandbox+"/jamais-creee.md"); !errors.Is(err, ErrNotFound) {
		t.Errorf("Stat d'une note absente : erreur = %v, attendu ErrNotFound", err)
	}
	if err := space.Remove(ctx, sandbox+"/jamais-creee.md"); !errors.Is(err, ErrNotFound) {
		t.Errorf("suppression d'une note absente : erreur = %v, attendu ErrNotFound", err)
	}

	// Un mauvais token doit produire ErrUnauthorized, et non une erreur opaque.
	mauvais, err := New(os.Getenv("OPENNOTE_IT_SERVER"), AppTokenAuth{
		Username: os.Getenv("OPENNOTE_IT_USER"),
		Token:    "token-invalide-pour-le-test",
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if _, err := mauvais.ListDrives(ctx); !errors.Is(err, ErrUnauthorized) {
		t.Errorf("token invalide : erreur = %v, attendu ErrUnauthorized", err)
	}
}
