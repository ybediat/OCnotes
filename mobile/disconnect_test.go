package mobile

import (
	"testing"
	"time"
)

// Une déconnexion pendant une passe annule celle-ci et l'attend : la passe ne
// doit ni retenir la déconnexion jusqu'à son délai de cinq minutes, ni
// réécrire après la purge des notes du compte qu'on quitte.
func TestDeconnexionInterrompLaPasseEnCours(t *testing.T) {
	app, server, _ := prepare(t)

	if _, err := app.CreateNoteJSON("", "secret", "# Secret\n"); err != nil {
		t.Fatalf("CreateNoteJSON: %v", err)
	}
	if err := app.WriteNote("secret.md", "# Secret\n\nmodifiée\n"); err != nil {
		t.Fatalf("WriteNote: %v", err)
	}

	retenu := make(chan struct{}, 1)
	server.mu.Lock()
	server.putRetenu = retenu
	server.mu.Unlock()

	passe := make(chan error, 1)
	go func() {
		_, err := app.SyncJSON()
		passe <- err
	}()

	select {
	case <-retenu:
	case <-time.After(10 * time.Second):
		t.Fatal("la passe n'a jamais atteint le serveur")
	}

	deconnecte := make(chan error, 1)
	go func() { deconnecte <- app.Disconnect() }()
	select {
	case err := <-deconnecte:
		if err != nil {
			t.Fatalf("Disconnect: %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("la déconnexion attend la fin de la passe au lieu de l'interrompre")
	}

	select {
	case err := <-passe:
		if err != nil {
			t.Fatalf("SyncJSON: %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("la passe n'a pas rendu la main")
	}

	if n := len(app.cache.Entries()); n != 0 {
		t.Fatalf("%d note(s) du compte quitté restent sur l'appareil", n)
	}
	if n := app.PendingCount(); n != 0 {
		t.Fatalf("%d opération(s) du compte quitté restent en file", n)
	}
}
