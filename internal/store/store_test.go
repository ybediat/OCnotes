package store

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"opennote/internal/opencloud"
)

// fakeRemote simule le serveur : il applique la même règle d'If-Match, et sait
// être injoignable pour éprouver le comportement hors connexion.
type fakeRemote struct {
	files   map[string]string // chemin -> contenu
	etags   map[string]string
	folders map[string]bool
	seq     int

	offline bool
	calls   []string
}

func newFakeRemote() *fakeRemote {
	return &fakeRemote{
		files:   map[string]string{},
		etags:   map[string]string{},
		folders: map[string]bool{},
	}
}

var errOffline = errors.New("fake: réseau indisponible")

func (r *fakeRemote) nextETag() string {
	r.seq++
	return fmt.Sprintf("%q", fmt.Sprintf("e%d", r.seq))
}

func (r *fakeRemote) Read(_ context.Context, p string) ([]byte, string, error) {
	if r.offline {
		return nil, "", errOffline
	}
	r.calls = append(r.calls, "read "+p)
	content, ok := r.files[p]
	if !ok {
		return nil, "", fmt.Errorf("fake: %s: %w", p, opencloud.ErrNotFound)
	}
	return []byte(content), r.etags[p], nil
}

func (r *fakeRemote) Save(_ context.Context, p string, content []byte, ifMatch string) (string, error) {
	if r.offline {
		return "", errOffline
	}
	r.calls = append(r.calls, "save "+p)

	if ifMatch != "" && r.etags[p] != ifMatch {
		return "", fmt.Errorf("fake: %s: %w", p, opencloud.ErrConflict)
	}
	etag := r.nextETag()
	r.files[p] = string(content)
	r.etags[p] = etag
	return etag, nil
}

func (r *fakeRemote) Exists(_ context.Context, p string) (bool, error) {
	if r.offline {
		return false, errOffline
	}
	r.calls = append(r.calls, "exists "+p)
	_, ok := r.files[p]
	return ok, nil
}

// SaveNew refuse d'écraser : c'est la protection des notes créées hors
// connexion, dont on ignore si le nom est déjà pris côté serveur.
func (r *fakeRemote) SaveNew(ctx context.Context, p string, content []byte) (string, error) {
	if r.offline {
		return "", errOffline
	}
	if _, exists := r.files[p]; exists {
		r.calls = append(r.calls, "saveNew-refuse "+p)
		return "", fmt.Errorf("fake: %s: %w", p, opencloud.ErrConflict)
	}
	return r.Save(ctx, p, content, "")
}

func (r *fakeRemote) Delete(_ context.Context, p string) error {
	if r.offline {
		return errOffline
	}
	r.calls = append(r.calls, "delete "+p)
	if _, ok := r.files[p]; !ok {
		return fmt.Errorf("fake: %s: %w", p, opencloud.ErrNotFound)
	}
	delete(r.files, p)
	delete(r.etags, p)
	return nil
}

func (r *fakeRemote) MoveTo(_ context.Context, from, to string) error {
	if r.offline {
		return errOffline
	}
	r.calls = append(r.calls, "move "+from+" -> "+to)
	content, ok := r.files[from]
	if !ok {
		return fmt.Errorf("fake: %s: %w", from, opencloud.ErrNotFound)
	}
	r.files[to], r.etags[to] = content, r.etags[from]
	delete(r.files, from)
	delete(r.etags, from)
	return nil
}

func (r *fakeRemote) EnsureFolder(_ context.Context, dir string) error {
	if r.offline {
		return errOffline
	}
	r.calls = append(r.calls, "mkdir "+dir)
	r.folders[dir] = true
	return nil
}

func newStore(t *testing.T) *Store {
	t.Helper()
	s, err := Open(t.TempDir())
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	return s
}

func testTime() time.Time {
	return time.Date(2026, 8, 28, 14, 32, 5, 0, time.UTC)
}

func writeCorruptIndex(dir string) error {
	return os.WriteFile(filepath.Join(dir, "index.json"), []byte("{ceci n'est pas du JSON"), 0o600)
}

func TestPutEtGet(t *testing.T) {
	s := newStore(t)

	if err := s.Put("a.md", []byte("# Bonjour")); err != nil {
		t.Fatalf("Put: %v", err)
	}

	content, entry, ok := s.Get("a.md")
	if !ok {
		t.Fatal("la note n'est pas dans le cache")
	}
	if string(content) != "# Bonjour" {
		t.Errorf("contenu = %q", content)
	}
	if !entry.Dirty {
		t.Error("la note devrait être marquée comme modifiée localement")
	}
	if entry.ETag != "" {
		t.Errorf("ETag = %q, attendu vide pour une note jamais poussée", entry.ETag)
	}
}

// Le nom du fichier de cache ne doit pas reprendre celui du serveur : une note
// parfaitement valide côté OpenCloud peut porter des caractères interdits par
// le système de fichiers local.
func TestCacheAccepteLesNomsInterditsLocalement(t *testing.T) {
	s := newStore(t)

	noms := []string{
		"point d'interrogation ?.md",
		"deux:points.md",
		"asterisque *.md",
		`chevrons <>.md`,
		"barre|verticale.md",
		"Projets/sous-dossier/note.md",
	}

	for _, nom := range noms {
		if err := s.Put(nom, []byte("contenu de "+nom)); err != nil {
			t.Fatalf("Put(%q): %v", nom, err)
		}
		content, _, ok := s.Get(nom)
		if !ok {
			t.Errorf("Get(%q) : absente du cache", nom)
			continue
		}
		if string(content) != "contenu de "+nom {
			t.Errorf("Get(%q) = %q", nom, content)
		}
	}
}

func TestPushAlignLeCache(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	ctx := context.Background()

	if err := s.Put("a.md", []byte("v1")); err != nil {
		t.Fatalf("Put: %v", err)
	}

	report, err := s.Push(ctx, remote)
	if err != nil {
		t.Fatalf("Push: %v", err)
	}
	if report.Pushed != 1 {
		t.Errorf("Pushed = %d, attendu 1", report.Pushed)
	}
	if remote.files["a.md"] != "v1" {
		t.Errorf("contenu distant = %q", remote.files["a.md"])
	}

	_, entry, _ := s.Get("a.md")
	if entry.Dirty {
		t.Error("la note devrait être propre après un envoi réussi")
	}
	if entry.ETag == "" {
		t.Error("l'ETag du serveur n'a pas été enregistré")
	}
	if len(s.Pending()) != 0 {
		t.Errorf("file restante = %+v", s.Pending())
	}
}

// Une écriture faite hors connexion doit rester en attente, pas disparaître.
func TestPushHorsConnexionConserveLaFile(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	remote.offline = true

	if err := s.Put("a.md", []byte("écrit dans le métro")); err != nil {
		t.Fatalf("Put: %v", err)
	}

	report, err := s.Push(context.Background(), remote)
	if err == nil {
		t.Fatal("Push aurait dû échouer hors connexion")
	}
	if report.Remaining != 1 {
		t.Errorf("Remaining = %d, attendu 1", report.Remaining)
	}

	remote.offline = false
	if _, err := s.Push(context.Background(), remote); err != nil {
		t.Fatalf("Push après retour du réseau: %v", err)
	}
	if remote.files["a.md"] != "écrit dans le métro" {
		t.Errorf("la note n'a pas été poussée au retour du réseau: %q", remote.files["a.md"])
	}
}

// Enregistrer à chaque frappe ne doit pas empiler des centaines d'écritures :
// seule la dernière version compte.
func TestEcrituresRepeteesAbsorbees(t *testing.T) {
	s := newStore(t)

	for i := 0; i < 50; i++ {
		if err := s.Put("a.md", []byte(fmt.Sprintf("version %d", i))); err != nil {
			t.Fatalf("Put %d: %v", i, err)
		}
	}

	pending := s.Pending()
	if len(pending) != 1 {
		t.Fatalf("%d opérations en attente, 1 attendue", len(pending))
	}

	remote := newFakeRemote()
	if _, err := s.Push(context.Background(), remote); err != nil {
		t.Fatalf("Push: %v", err)
	}
	if remote.files["a.md"] != "version 49" {
		t.Errorf("contenu poussé = %q, attendu la dernière version", remote.files["a.md"])
	}
}

// Ouvrir une note, la lire et la refermer ne doit rien salir.
//
// L'éditeur enregistre à la sortie de l'écran, sans savoir si le texte a
// bougé. Si le cache le croyait sur parole, toute note consultée serait
// renvoyée au serveur — et, le temps qu'elle soit sale, ReadNote refuserait de
// la rafraîchir. Son ETag vieillirait, et la première modification faite
// ailleurs deviendrait un conflit avec sa copie.
func TestReenregistrerLeMemeTexteNeSalitPasLaNote(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()

	if err := s.Accept("a.md", []byte("texte inchangé"), `"e1"`); err != nil {
		t.Fatalf("Accept: %v", err)
	}

	if err := s.Put("a.md", []byte("texte inchangé")); err != nil {
		t.Fatalf("Put: %v", err)
	}

	if pending := s.Pending(); len(pending) != 0 {
		t.Errorf("file = %+v, aucune opération attendue", pending)
	}
	if _, entry, _ := s.Get("a.md"); entry.Dirty {
		t.Error("la note est marquée sale alors que son contenu n'a pas changé")
	}

	report, err := s.Push(context.Background(), remote)
	if err != nil {
		t.Fatalf("Push: %v", err)
	}
	if report.Pushed != 0 {
		t.Errorf("Pushed = %d, attendu 0", report.Pushed)
	}
	if len(remote.calls) != 0 {
		t.Errorf("le serveur a été appelé pour rien: %v", remote.calls)
	}
}

// Le filtre ne doit pas avaler une écriture qui, elle, avait quelque chose à
// dire : réenregistrer un brouillon à l'identique le laisse en attente.
func TestReenregistrerUnBrouillonLeLaisseEnAttente(t *testing.T) {
	s := newStore(t)

	if err := s.Put("a.md", []byte("brouillon")); err != nil {
		t.Fatalf("Put initial: %v", err)
	}
	if err := s.Put("a.md", []byte("brouillon")); err != nil {
		t.Fatalf("Put identique: %v", err)
	}

	if pending := s.Pending(); len(pending) != 1 {
		t.Fatalf("file = %+v, une écriture attendue", pending)
	}
	if _, entry, _ := s.Get("a.md"); !entry.Dirty {
		t.Error("le brouillon n'est plus signalé comme modifié")
	}
}

func TestSuppressionAnnuleLEcritureEnAttente(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	ctx := context.Background()

	remote.files["a.md"] = "sur le serveur"
	remote.etags["a.md"] = `"e0"`

	if err := s.Put("a.md", []byte("brouillon")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if err := s.Delete("a.md"); err != nil {
		t.Fatalf("Delete: %v", err)
	}

	pending := s.Pending()
	if len(pending) != 1 || pending[0].Kind != OpDelete {
		t.Fatalf("file = %+v, attendue une seule suppression", pending)
	}

	if _, err := s.Push(ctx, remote); err != nil {
		t.Fatalf("Push: %v", err)
	}
	for _, call := range remote.calls {
		if strings.HasPrefix(call, "save ") {
			t.Errorf("une écriture a été envoyée alors que la note était supprimée: %v", remote.calls)
		}
	}
	if _, ok := remote.files["a.md"]; ok {
		t.Error("la note existe encore sur le serveur")
	}
}

// Le cœur de la brique : la version locale ne doit jamais être perdue.
func TestConflitConserveLesDeuxVersions(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	ctx := context.Background()

	// État initial partagé.
	if err := s.Put("a.md", []byte("v1")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if _, err := s.Push(ctx, remote); err != nil {
		t.Fatalf("Push: %v", err)
	}

	// Un autre appareil modifie la note.
	if _, err := remote.Save(ctx, "a.md", []byte("version d'un autre appareil"), ""); err != nil {
		t.Fatalf("Save distant: %v", err)
	}

	// Puis on modifie localement, sur la base de l'ancienne version.
	if err := s.Put("a.md", []byte("ma version locale")); err != nil {
		t.Fatalf("Put: %v", err)
	}

	report, err := s.Push(ctx, remote)
	if err != nil {
		t.Fatalf("Push: %v", err)
	}
	if len(report.Conflicts) != 1 {
		t.Fatalf("%d conflits signalés, 1 attendu", len(report.Conflicts))
	}

	conflict := report.Conflicts[0]
	if conflict.Path != "a.md" {
		t.Errorf("Conflict.Path = %q", conflict.Path)
	}
	if !strings.Contains(conflict.CopyPath, "conflit") {
		t.Errorf("CopyPath = %q, attendu un nom signalant le conflit", conflict.CopyPath)
	}

	// La note porte la version du serveur…
	if remote.files["a.md"] != "version d'un autre appareil" {
		t.Errorf("la note distante = %q, la version serveur aurait dû être conservée", remote.files["a.md"])
	}
	// …et la version locale survit à côté, sur le serveur comme en cache.
	if remote.files[conflict.CopyPath] != "ma version locale" {
		t.Errorf("copie distante = %q, attendu la version locale", remote.files[conflict.CopyPath])
	}
	if content, _, ok := s.Get(conflict.CopyPath); !ok || string(content) != "ma version locale" {
		t.Errorf("la copie n'est pas dans le cache: %q", content)
	}
	if content, _, _ := s.Get("a.md"); string(content) != "version d'un autre appareil" {
		t.Errorf("le cache de la note = %q, attendu la version serveur", content)
	}
}

// Un ETag périmé alors que les contenus sont identiques n'est pas un vrai
// conflit : inutile de polluer le dossier d'une copie.
func TestConflitSansDivergenceNeCreePasDeCopie(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	ctx := context.Background()

	remote.files["a.md"] = "même texte"
	remote.etags["a.md"] = `"serveur"`

	// Le cache part d'une version antérieure : sans cela, l'écriture serait
	// filtrée comme sans effet et n'atteindrait jamais la résolution.
	if err := s.Accept("a.md", []byte("version antérieure"), `"perime"`); err != nil {
		t.Fatalf("Accept: %v", err)
	}
	// Les deux côtés ont fait la même modification, chacun de leur côté.
	if err := s.Put("a.md", []byte("même texte")); err != nil {
		t.Fatalf("Put: %v", err)
	}

	report, err := s.Push(ctx, remote)
	if err != nil {
		t.Fatalf("Push: %v", err)
	}
	if len(report.Conflicts) != 0 {
		t.Errorf("%d conflits signalés, aucun attendu : les contenus étaient identiques", len(report.Conflicts))
	}

	for p := range remote.files {
		if strings.Contains(p, "conflit") {
			t.Errorf("une copie de conflit inutile a été créée: %s", p)
		}
	}
}

// oublierLaBase simule une entrée écrite par une version de l'index antérieure
// au champ BaseHash. Cet index-là n'est pas invalidé au chargement — il porte
// la file d'attente — donc le code doit savoir travailler sans la base.
func oublierLaBase(t *testing.T, s *Store, notePath string) {
	t.Helper()

	s.mu.Lock()
	defer s.mu.Unlock()

	entry, ok := s.entries[notePath]
	if !ok {
		t.Fatalf("entrée absente du cache: %s", notePath)
	}
	entry.BaseHash = ""
	if err := s.save(); err != nil {
		t.Fatalf("save: %v", err)
	}
}

// Une modification défaite avant la synchronisation ne doit rien envoyer.
//
// C'est le cas qui rendait les copies de conflit envahissantes : l'écriture en
// file ne porte plus rien de nouveau, mais elle part quand même avec un ETag
// périmé, le serveur la refuse, et une copie naît d'une note que personne
// n'avait modifiée de ce côté-ci.
func TestEcritureRevenueALaBaseNEnvoieRien(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()

	if err := s.Accept("a.md", []byte("version initiale"), `"e1"`); err != nil {
		t.Fatalf("Accept: %v", err)
	}
	if err := s.Put("a.md", []byte("brouillon intermédiaire")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if err := s.Put("a.md", []byte("version initiale")); err != nil {
		t.Fatalf("Put de retour: %v", err)
	}
	if pending := s.Pending(); len(pending) != 1 {
		t.Fatalf("file = %+v, une écriture attendue avant l'envoi", pending)
	}

	// Pendant ce temps, quelqu'un a modifié la note depuis le navigateur.
	remote.files["a.md"] = "version du web"
	remote.etags["a.md"] = `"e2"`

	report, err := s.Push(context.Background(), remote)
	if err != nil {
		t.Fatalf("Push: %v", err)
	}
	if len(report.Conflicts) != 0 {
		t.Errorf("%d conflits signalés, aucun attendu : le local n'avait rien à opposer", len(report.Conflicts))
	}
	if report.Pushed != 0 {
		t.Errorf("Pushed = %d, attendu 0", report.Pushed)
	}
	for _, call := range remote.calls {
		if strings.HasPrefix(call, "save ") {
			t.Errorf("le serveur a été écrit pour rien: %v", remote.calls)
			break
		}
	}
	if remote.files["a.md"] != "version du web" {
		t.Errorf("la version du web a été écrasée: %q", remote.files["a.md"])
	}
	if len(s.Pending()) != 0 {
		t.Errorf("file restante = %+v", s.Pending())
	}
	if _, entry, _ := s.Get("a.md"); entry.Dirty {
		t.Error("la note reste sale alors qu'il n'y avait rien à envoyer")
	}
}

// Même situation, mais par le chemin des notes sans ETag — celui d'une copie de
// conflit, qu'un Accept enregistre sans version distante. Le refus vient alors
// du contrôle d'existence, pas d'un If-Match, et la base doit trancher pareil.
func TestConflitSansModificationLocaleNeCreePasDeCopie(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()

	if err := s.Accept("copie.md", []byte("version initiale"), ""); err != nil {
		t.Fatalf("Accept: %v", err)
	}
	remote.files["copie.md"] = "version du web"
	remote.etags["copie.md"] = `"e2"`

	if err := s.Put("copie.md", []byte("brouillon intermédiaire")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if err := s.Put("copie.md", []byte("version initiale")); err != nil {
		t.Fatalf("Put de retour: %v", err)
	}

	report, err := s.Push(context.Background(), remote)
	if err != nil {
		t.Fatalf("Push: %v", err)
	}
	if len(report.Conflicts) != 0 {
		t.Errorf("%d conflits signalés, aucun attendu", len(report.Conflicts))
	}
	for p := range remote.files {
		if strings.Contains(p, "conflit") {
			t.Errorf("une copie de conflit inutile a été créée: %s", p)
		}
	}
	if content, _, _ := s.Get("copie.md"); string(content) != "version du web" {
		t.Errorf("le cache = %q, attendu la version du serveur", content)
	}
}

// Sans base connue, on ne sait pas si le local avait quelque chose à dire : le
// doute profite à l'utilisateur, la copie est conservée.
func TestConflitAvecBaseInconnueConserveLaCopie(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()

	if err := s.Accept("a.md", []byte("version initiale"), `"e1"`); err != nil {
		t.Fatalf("Accept: %v", err)
	}
	if err := s.Put("a.md", []byte("brouillon intermédiaire")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if err := s.Put("a.md", []byte("version initiale")); err != nil {
		t.Fatalf("Put de retour: %v", err)
	}
	oublierLaBase(t, s, "a.md")

	remote.files["a.md"] = "version du web"
	remote.etags["a.md"] = `"e2"`

	report, err := s.Push(context.Background(), remote)
	if err != nil {
		t.Fatalf("Push: %v", err)
	}
	if len(report.Conflicts) != 1 {
		t.Fatalf("%d conflits signalés, 1 attendu sans base connue", len(report.Conflicts))
	}
	if remote.files[report.Conflicts[0].CopyPath] != "version initiale" {
		t.Errorf("copie distante = %q", remote.files[report.Conflicts[0].CopyPath])
	}
}

func TestConflitPathEvitLesCaracteresInterdits(t *testing.T) {
	// Les deux-points d'un horodatage ISO seraient refusés par le système de
	// fichiers local, où la copie doit pourtant être écrite.
	got := conflictPath("Projets/ma note.md", testTime())
	if strings.ContainsAny(got, `<>:"|?*\`) {
		t.Errorf("conflictPath = %q, contient un caractère interdit localement", got)
	}
	if !strings.HasPrefix(got, "Projets/ma note (conflit ") {
		t.Errorf("conflictPath = %q", got)
	}
	if !strings.HasSuffix(got, ".md") {
		t.Errorf("conflictPath = %q, l'extension a été perdue", got)
	}
}

func TestPullNEcrasePasUneModificationLocale(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	ctx := context.Background()

	remote.files["a.md"] = "version serveur"
	remote.etags["a.md"] = `"e1"`

	if err := s.Put("a.md", []byte("mon brouillon")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if err := s.Pull(ctx, remote, "a.md"); err != nil {
		t.Fatalf("Pull: %v", err)
	}

	content, _, _ := s.Get("a.md")
	if string(content) != "mon brouillon" {
		t.Errorf("le cache = %q, la modification locale a été écrasée", content)
	}
}

func TestPullRafraichitUneNotePropre(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	ctx := context.Background()

	if err := s.Accept("a.md", []byte("ancienne"), `"e0"`); err != nil {
		t.Fatalf("Accept: %v", err)
	}
	remote.files["a.md"] = "nouvelle"
	remote.etags["a.md"] = `"e1"`

	if err := s.Pull(ctx, remote, "a.md"); err != nil {
		t.Fatalf("Pull: %v", err)
	}
	content, entry, _ := s.Get("a.md")
	if string(content) != "nouvelle" {
		t.Errorf("contenu = %q", content)
	}
	if entry.ETag != `"e1"` {
		t.Errorf("ETag = %q", entry.ETag)
	}
}

func TestPullOublieUneNoteSupprimeeAilleurs(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()

	if err := s.Accept("a.md", []byte("contenu"), `"e0"`); err != nil {
		t.Fatalf("Accept: %v", err)
	}
	if err := s.Pull(context.Background(), remote, "a.md"); err != nil {
		t.Fatalf("Pull: %v", err)
	}
	if _, _, ok := s.Get("a.md"); ok {
		t.Error("la note supprimée sur le serveur est restée en cache")
	}
}

// La file doit survivre à la fermeture de l'application : une note écrite
// hors connexion puis l'application tuée ne doit pas être perdue.
func TestPersistanceDeLaFile(t *testing.T) {
	dir := t.TempDir()

	first, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := first.Put("a.md", []byte("écrit hors connexion")); err != nil {
		t.Fatalf("Put: %v", err)
	}

	second, err := Open(dir)
	if err != nil {
		t.Fatalf("réouverture: %v", err)
	}

	pending := second.Pending()
	if len(pending) != 1 || pending[0].Path != "a.md" {
		t.Fatalf("file après réouverture = %+v", pending)
	}
	content, entry, ok := second.Get("a.md")
	if !ok || string(content) != "écrit hors connexion" {
		t.Errorf("contenu après réouverture = %q", content)
	}
	if !entry.Dirty {
		t.Error("la note devrait être encore marquée comme modifiée")
	}

	remote := newFakeRemote()
	if _, err := second.Push(context.Background(), remote); err != nil {
		t.Fatalf("Push: %v", err)
	}
	if remote.files["a.md"] != "écrit hors connexion" {
		t.Error("la note n'a pas été poussée après réouverture")
	}
}

func TestIndexIllisibleNEmpechePasLOuverture(t *testing.T) {
	dir := t.TempDir()

	s, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := s.Put("a.md", []byte("x")); err != nil {
		t.Fatalf("Put: %v", err)
	}

	if err := writeCorruptIndex(dir); err != nil {
		t.Fatalf("écriture de l'index corrompu: %v", err)
	}

	// Perdre le cache est bénin ; refuser de démarrer ne l'est pas.
	again, err := Open(dir)
	if err != nil {
		t.Fatalf("Open sur index corrompu: %v", err)
	}
	if len(again.Entries()) != 0 {
		t.Errorf("entrées = %+v, attendu un cache vide", again.Entries())
	}
}

func TestRename(t *testing.T) {
	s, remote := newStore(t), newFakeRemote()
	ctx := context.Background()

	if err := s.Put("ancienne.md", []byte("contenu")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if _, err := s.Push(ctx, remote); err != nil {
		t.Fatalf("Push: %v", err)
	}
	if err := s.Rename("ancienne.md", "nouvelle.md"); err != nil {
		t.Fatalf("Rename: %v", err)
	}

	if content, _, ok := s.Get("nouvelle.md"); !ok || string(content) != "contenu" {
		t.Errorf("cache sous le nouveau nom = %q", content)
	}
	if _, _, ok := s.Get("ancienne.md"); ok {
		t.Error("l'ancien nom est encore en cache")
	}

	if _, err := s.Push(ctx, remote); err != nil {
		t.Fatalf("Push: %v", err)
	}
	if remote.files["nouvelle.md"] != "contenu" {
		t.Errorf("le serveur n'a pas suivi le renommage: %+v", remote.files)
	}
	if _, ok := remote.files["ancienne.md"]; ok {
		t.Error("l'ancien chemin existe encore sur le serveur")
	}
}

func TestClear(t *testing.T) {
	s := newStore(t)

	if err := s.Put("a.md", []byte("x")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if err := s.Clear(); err != nil {
		t.Fatalf("Clear: %v", err)
	}
	if len(s.Entries()) != 0 || len(s.Pending()) != 0 {
		t.Error("le cache n'est pas vide après Clear")
	}
	if _, _, ok := s.Get("a.md"); ok {
		t.Error("la note est encore lisible après Clear")
	}
}
