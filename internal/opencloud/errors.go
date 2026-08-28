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

// Étiquettes de catégorie d'erreur.
//
// Elles apparaissent entre crochets dans le message et permettent de
// reconnaître la nature d'une erreur sans analyser du texte en français.
// C'est indispensable à la façade Android : gomobile ne transmet qu'une
// chaîne de caractères, l'erreur typée ne franchit pas la frontière.
const (
	CodeUnauthorized = "AUTH"
	CodeConflict     = "CONFLICT"
	CodeNotFound     = "NOTFOUND"
	CodeHTTP         = "HTTP"
)

// HTTPError porte le détail d'une réponse HTTP en échec.
type HTTPError struct {
	Method string
	URL    string
	Status int
	Body   string
}

// Code renvoie l'étiquette de catégorie correspondant au statut.
func (e *HTTPError) Code() string {
	switch e.Status {
	case http.StatusNotFound:
		return CodeNotFound
	case http.StatusPreconditionFailed:
		return CodeConflict
	case http.StatusUnauthorized, http.StatusForbidden:
		return CodeUnauthorized
	}
	return CodeHTTP
}

func (e *HTTPError) Error() string {
	base := fmt.Sprintf("opencloud: [%s] %s %s: HTTP %d", e.Code(), e.Method, e.URL, e.Status)
	if e.Body != "" {
		return base + ": " + e.Body
	}
	return base
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
