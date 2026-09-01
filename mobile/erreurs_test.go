package mobile

import (
	"testing"

	"github.com/ybediat/OpenNote/internal/config"
	"github.com/ybediat/OpenNote/internal/notes"
	"github.com/ybediat/OpenNote/internal/opencloud"
	"github.com/ybediat/OpenNote/internal/store"
)

// TestErrorCodePrioriteTransport est le test qui protège le repli local.
//
// Une erreur du cache enveloppe couramment une erreur réseau. Si le repli
// générique passait avant la liste de transport, « store: [STORAGE_IO] … :
// opencloud: [NOTFOUND] … » se lirait STORAGE_IO, et Android cesserait de
// reconnaître un 404 — donc d'effacer l'entrée disparue du serveur.
func TestErrorCodePrioriteTransport(t *testing.T) {
	cas := []struct {
		message string
		attendu string
	}{
		{
			"store: [STORAGE_IO] écriture du cache de a.md: opencloud: [NOTFOUND] PROPFIND: HTTP 404",
			opencloud.CodeNotFound,
		},
		{
			"store: [STORAGE_IO] sauvegarde de a.md: opencloud: [CONFLICT] PUT: HTTP 412",
			opencloud.CodeConflict,
		},
		{
			"store: [STORAGE_IO] écriture de l'index: no space left on device",
			store.CodeStorageIO,
		},
		{
			// Message tel que ValidateName le produit, liste de caractères
			// comprise : elle contient des crochets d'aucune sorte, mais
			// bien des guillemets et une barre verticale.
			"notes: [NAME_FORBIDDEN_CHARS] le nom ne peut pas contenir un de ces caractères : " +
				notes.ForbiddenNameChars(),
			notes.CodeNameForbiddenChars,
		},
		{"mobile: aucune session ouverte, appeler Connect", ""},
		{"", ""},
	}

	for _, c := range cas {
		if got := ErrorCode(c.message); got != c.attendu {
			t.Errorf("ErrorCode(%q) = %q, attendu %q", c.message, got, c.attendu)
		}
	}
}

// TestCodeLocalFormeStricte : le repli ne doit pas confondre une étiquette
// avec un fragment de chemin ou de contenu entre crochets. Un faux positif
// ferait afficher une phrase sans rapport avec l'erreur.
func TestCodeLocalFormeStricte(t *testing.T) {
	cas := map[string]string{
		"notes: [] chemin vide":                  "",
		"markdown: [x](url) mal formé":           "",
		"store: [minuscules] pas une étiquette":  "",
		"store: [NAME_2] deux segments":          "NAME_2",
		"config: [SERVER_URL_INVALID] hôte vide": config.CodeServerURLInvalid,
	}
	for message, attendu := range cas {
		if got := codeLocal(message); got != attendu {
			t.Errorf("codeLocal(%q) = %q, attendu %q", message, got, attendu)
		}
	}
}

// TestCodesLocauxAlignes : store et config redéclarent STORAGE_IO chacun de
// leur côté, faute d'un paquet commun. Ce test est le seul lien entre les
// deux — sans lui, Android traduirait un seul des deux cas.
func TestCodesLocauxAlignes(t *testing.T) {
	if store.CodeStorageIO != config.CodeStorageIO {
		t.Errorf("store.CodeStorageIO = %q, config.CodeStorageIO = %q : les deux doivent coïncider",
			store.CodeStorageIO, config.CodeStorageIO)
	}
}

// TestBornesNommageExposees vérifie que la façade relaie bien les bornes que
// l'interface utilise pour rédiger la règle de nommage.
func TestBornesNommageExposees(t *testing.T) {
	if MaxNameBytes() != notes.MaxNameBytes() {
		t.Errorf("MaxNameBytes() = %d, attendu %d", MaxNameBytes(), notes.MaxNameBytes())
	}
	if ForbiddenNameChars() != notes.ForbiddenNameChars() {
		t.Errorf("ForbiddenNameChars() = %q, attendu %q", ForbiddenNameChars(), notes.ForbiddenNameChars())
	}
}
