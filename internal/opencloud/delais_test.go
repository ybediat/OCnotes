package opencloud

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// Un serveur qui accepte la connexion puis se tait — poignée TLS jamais
// entamée — doit être abandonné bien avant le délai global de 30 s. C'est le
// cas d'un réseau qui se rétablit après la veille : sans délai court, une
// passe de synchronisation restait bloquée une demi-minute.
func TestServeurMuetAbandonneVite(t *testing.T) {
	ecoute, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ecoute.Close()

	// Les connexions sont acceptées et gardées ouvertes, sans un octet. Elles
	// sont fermées à la fin du test, une fois l'écoute arrêtée.
	go func() {
		var ouvertes []net.Conn
		defer func() {
			for _, c := range ouvertes {
				c.Close()
			}
		}()
		for {
			conn, err := ecoute.Accept()
			if err != nil {
				return
			}
			ouvertes = append(ouvertes, conn)
		}
	}()

	// Les délais réels (10 s) rendraient le test long : on les réduit le
	// temps du test, sans toucher au délai global.
	ancienTLS, ancienEntetes := delaiPoigneeTLS, delaiEntetes
	delaiPoigneeTLS, delaiEntetes = 300*time.Millisecond, 300*time.Millisecond
	defer func() { delaiPoigneeTLS, delaiEntetes = ancienTLS, ancienEntetes }()

	client, err := New("https://"+ecoute.Addr().String(), AppTokenAuth{Username: "u", Token: "t"})
	if err != nil {
		t.Fatal(err)
	}

	debut := time.Now()
	_, err = client.ListDrives(context.Background())
	duree := time.Since(debut)

	if err == nil {
		t.Fatal("un serveur muet a été pris pour une réponse")
	}
	if !errors.Is(err, ErrOffline) {
		t.Fatalf("erreur attendue : ErrOffline, reçue : %v", err)
	}
	if duree > 5*time.Second {
		t.Fatalf("serveur muet abandonné en %v : le délai court n'est pas appliqué", duree)
	}
}

// Un serveur qui termine la poignée TLS puis ne répond jamais — requête
// reçue, aucun en-tête renvoyé — doit lui aussi être abandonné avant le délai
// global. C'est le délai d'attente des en-têtes qui doit trancher.
func TestServeurSansReponseAbandonneVite(t *testing.T) {
	libere := make(chan struct{})
	serveur := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-libere
	}))
	defer serveur.Close()
	defer close(libere)

	ancien := delaiEntetes
	delaiEntetes = 300 * time.Millisecond
	defer func() { delaiEntetes = ancien }()

	client, err := New(serveur.URL, AppTokenAuth{Username: "u", Token: "t"})
	if err != nil {
		t.Fatal(err)
	}
	// Le certificat du serveur de test n'est signé par personne : on le fait
	// accepter par le transport du client, sans remplacer ce transport.
	transport, ok := client.hc.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("transport inattendu : %T", client.hc.Transport)
	}
	transport.TLSClientConfig = serveur.Client().Transport.(*http.Transport).TLSClientConfig

	debut := time.Now()
	_, err = client.ListDrives(context.Background())
	duree := time.Since(debut)

	if !errors.Is(err, ErrOffline) {
		t.Fatalf("erreur attendue : ErrOffline, reçue : %v", err)
	}
	if duree > 5*time.Second {
		t.Fatalf("serveur sans réponse abandonné en %v : le délai des en-têtes n'est pas appliqué", duree)
	}
}

// Une recherche serveur ou un envoi peuvent tarder sans que le serveur soit en
// panne : le délai court des en-têtes ne doit pas les couper.
func TestRequeteLenteNonCoupeeParLeDelaiDesEntetes(t *testing.T) {
	serveur := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Plus long que le délai des en-têtes réduit ci-dessous.
		time.Sleep(600 * time.Millisecond)
		w.WriteHeader(http.StatusCreated)
	}))
	defer serveur.Close()

	ancien := delaiEntetes
	delaiEntetes = 200 * time.Millisecond
	defer func() { delaiEntetes = ancien }()

	client, err := New(serveur.URL, AppTokenAuth{Username: "u", Token: "t"})
	if err != nil {
		t.Fatal(err)
	}
	config := serveur.Client().Transport.(*http.Transport).TLSClientConfig
	client.hc.Transport.(*http.Transport).TLSClientConfig = config
	client.hcLent.Transport.(*http.Transport).TLSClientConfig = config

	u := client.resolve("/note.md")
	if _, _, err := client.do(context.Background(), http.MethodPut, u, []byte("x"), nil); err != nil {
		t.Fatalf("PUT lent coupé : %v", err)
	}
	if _, _, err := client.do(context.Background(), "REPORT", u, []byte("x"), nil); err != nil {
		t.Fatalf("REPORT lent coupé : %v", err)
	}
	// Une lecture, elle, reste soumise au délai court.
	if _, _, err := client.do(context.Background(), http.MethodGet, u, nil, nil); !errors.Is(err, ErrOffline) {
		t.Fatalf("GET lent : erreur attendue ErrOffline, reçue %v", err)
	}
}
