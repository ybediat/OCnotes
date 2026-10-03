package store

import "testing"

// Le propriétaire survit à une réouverture : c'est tout son intérêt, il doit
// tenir quand la configuration, elle, est perdue.
func TestOwnerSurvitALaReouverture(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := s.Put("note.md", []byte("contenu")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if err := s.SetOwner("compte-a"); err != nil {
		t.Fatalf("SetOwner: %v", err)
	}

	relu, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if got := relu.Owner(); got != "compte-a" {
		t.Errorf("Owner après réouverture = %q, attendu %q", got, "compte-a")
	}
}

// Un cache qui ne double plus aucun serveur n'appartient plus à personne :
// purgé, ou devenu le stockage unique d'un profil local.
func TestOwnerOublie(t *testing.T) {
	for nom, geste := range map[string]func(*Store) error{
		"Clear":        func(s *Store) error { return s.Clear() },
		"SetLocalOnly": func(s *Store) error { return s.SetLocalOnly(true) },
		"GoLocal":      func(s *Store) error { _, err := s.GoLocal(); return err },
	} {
		t.Run(nom, func(t *testing.T) {
			s := newStore(t)
			if err := s.SetOwner("compte-a"); err != nil {
				t.Fatalf("SetOwner: %v", err)
			}
			if err := geste(s); err != nil {
				t.Fatalf("%s: %v", nom, err)
			}
			if got := s.Owner(); got != "" {
				t.Errorf("Owner après %s = %q, attendu vide", nom, got)
			}
		})
	}
}
