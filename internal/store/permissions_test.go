package store

import "testing"

func TestPermissionsSuiventLeRenommageEtLaSuppression(t *testing.T) {
	s := newStore(t)
	if err := s.SetIndexWithPermissions(
		[]Known{{Path: "ancien/note.md", Permissions: "G"}},
		[]FolderKnown{
			{Path: "ancien", Permissions: "GDNVCK"},
			{Path: "ancien/sous", Permissions: "G"},
		},
	); err != nil {
		t.Fatalf("SetIndexWithPermissions: %v", err)
	}

	if err := s.RenameLocal("ancien", "nouveau"); err != nil {
		t.Fatalf("RenameLocal: %v", err)
	}
	if got := s.FolderPermissions("nouveau"); got != "GDNVCK" {
		t.Errorf("droits du dossier renommé = %q, attendu GDNVCK", got)
	}
	if got := s.FolderPermissions("nouveau/sous"); got != "G" {
		t.Errorf("droits du sous-dossier renommé = %q, attendu G", got)
	}
	if got := s.Permissions("nouveau/note.md"); got != "G" {
		t.Errorf("droits de la note renommée = %q, attendu G", got)
	}
	if got := s.FolderPermissions("ancien"); got != "" {
		t.Errorf("droits conservés sous l'ancien chemin : %q", got)
	}

	if err := s.Forget("nouveau"); err != nil {
		t.Fatalf("Forget: %v", err)
	}
	if got := s.FolderPermissions("nouveau"); got != "" {
		t.Errorf("droits conservés après suppression : %q", got)
	}
	if got := s.FolderPermissions("nouveau/sous"); got != "" {
		t.Errorf("droits descendants conservés après suppression : %q", got)
	}
}

func TestPermissionsSurviventAuContenuLocalPlusRecent(t *testing.T) {
	s := newStore(t)
	index := []Known{{Path: "note.md", Permissions: "W"}}
	if err := s.SetIndexWithPermissions(index, []FolderKnown{{Path: "", Permissions: "CK"}}); err != nil {
		t.Fatalf("SetIndexWithPermissions initial: %v", err)
	}
	if err := s.Put("note.md", []byte("modification locale")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if err := s.SetIndexWithPermissions(index, []FolderKnown{{Path: "", Permissions: "CK"}}); err != nil {
		t.Fatalf("SetIndexWithPermissions après écriture: %v", err)
	}
	if got := s.Permissions("note.md"); got != "W" {
		t.Errorf("droits après fusion = %q, attendu W", got)
	}
}
