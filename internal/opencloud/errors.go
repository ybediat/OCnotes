package opencloud

import (
	"errors"
	"fmt"
	"net/http"
)

// Erreurs sentinelles, à tester avec errors.Is.
//
// ErrConflict est la plus importante : c'est elle qui signale qu'une écriture
// a été refusée parce que la version distante avait changé. Toute la
// stratégie de synchronisation repose dessus.
var (
	ErrNotFound     = errors.New("opencloud: ressource introuvable")
	ErrConflict     = errors.New("opencloud: la version distante a changé")
	ErrExists       = errors.New("opencloud: la ressource existe déjà")
	ErrUnauthorized = errors.New("opencloud: authentification refusée")
)

// HTTPError porte le détail d'une réponse HTTP en échec.
type HTTPError struct {
	Method string
	URL    string
	Status int
	Body   string
}

func (e *HTTPError) Error() string {
	if e.Body != "" {
		return fmt.Sprintf("opencloud: %s %s: HTTP %d: %s", e.Method, e.URL, e.Status, e.Body)
	}
	return fmt.Sprintf("opencloud: %s %s: HTTP %d", e.Method, e.URL, e.Status)
}

// Unwrap rattache les codes HTTP sans ambiguïté à une erreur sentinelle.
//
// 405 n'y figure pas volontairement : sa signification dépend de la méthode
// (sur MKCOL il indique que la ressource existe déjà, ailleurs que le verbe
// est refusé). Mkdir fait cette traduction lui-même.
func (e *HTTPError) Unwrap() error {
	switch e.Status {
	case http.StatusNotFound:
		return ErrNotFound
	case http.StatusPreconditionFailed:
		return ErrConflict
	case http.StatusUnauthorized, http.StatusForbidden:
		return ErrUnauthorized
	}
	return nil
}
