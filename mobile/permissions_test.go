package mobile

import (
	"encoding/json"
	"testing"
)

func TestPermissionsOpenCloudAtteignentListeEtEcriture(t *testing.T) {
	app, server, _ := prepare(t)

	server.mu.Lock()
	server.files["Notes/lecture.md"] = []byte("# Lecture seule\n")
	server.etags["Notes/lecture.md"] = server.nextETag()
	server.permissions["Notes/lecture.md"] = "G"
	server.mu.Unlock()

	raw, err := app.ListFolderJSON("")
	if err != nil {
		t.Fatalf("ListFolderJSON: %v", err)
	}
	var listing folderListing
	if err := json.Unmarshal([]byte(raw), &listing); err != nil {
		t.Fatalf("décodage listing: %v", err)
	}
	if len(listing.Entries) != 1 {
		t.Fatalf("entrées = %+v", listing.Entries)
	}
	entry := listing.Entries[0]
	if entry.CanWrite || entry.CanDelete || entry.CanRename || entry.CanMove {
		t.Fatalf("droits de lecture seule non propagés: %+v", entry)
	}

	if err := app.WriteNote("lecture.md", "modification"); ErrorCode(errString(err)) != CodePermissionDenied {
		t.Fatalf("WriteNote: %v, code attendu %s", err, CodePermissionDenied)
	}
	if _, err := app.Rename("lecture.md", "renommee"); ErrorCode(errString(err)) != CodePermissionDenied {
		t.Fatalf("Rename: %v, code attendu %s", err, CodePermissionDenied)
	}
	if _, err := app.Move("lecture.md", "cible"); ErrorCode(errString(err)) != CodePermissionDenied {
		t.Fatalf("Move: %v, code attendu %s", err, CodePermissionDenied)
	}
	if err := app.Delete("lecture.md"); ErrorCode(errString(err)) != CodePermissionDenied {
		t.Fatalf("Delete: %v, code attendu %s", err, CodePermissionDenied)
	}
	if got := app.PendingCount(); got != 0 {
		t.Fatalf("écriture interdite mise en attente: %d", got)
	}
}

func TestPermissionsDuDossierInterdisentLesCreations(t *testing.T) {
	app, server, _ := prepare(t)

	server.mu.Lock()
	server.permissions["Notes"] = "G"
	server.files["Notes/mobile.md"] = []byte("# Mobile\n")
	server.etags["Notes/mobile.md"] = server.nextETag()
	server.mu.Unlock()

	raw, err := app.ListFolderJSON("")
	if err != nil {
		t.Fatalf("ListFolderJSON: %v", err)
	}
	var listing folderListing
	if err := json.Unmarshal([]byte(raw), &listing); err != nil {
		t.Fatalf("décodage listing: %v", err)
	}
	if listing.CanCreateFile || listing.CanCreateFolder {
		t.Fatalf("créations annoncées sur un dossier en lecture seule: %+v", listing)
	}
	if _, err := app.CreateNoteJSON("", "interdite", "contenu"); ErrorCode(errString(err)) != CodePermissionDenied {
		t.Fatalf("CreateNoteJSON: %v, code attendu %s", err, CodePermissionDenied)
	}
	if _, err := app.CreateFolderJSON("", "interdit"); ErrorCode(errString(err)) != CodePermissionDenied {
		t.Fatalf("CreateFolderJSON: %v, code attendu %s", err, CodePermissionDenied)
	}
	if _, err := app.Move("mobile.md", ""); ErrorCode(errString(err)) != CodePermissionDenied {
		t.Fatalf("Move vers dossier verrouillé: %v, code attendu %s", err, CodePermissionDenied)
	}
	if _, err := app.CopyJSON("mobile.md", ""); ErrorCode(errString(err)) != CodePermissionDenied {
		t.Fatalf("CopyJSON vers dossier verrouillé: %v, code attendu %s", err, CodePermissionDenied)
	}
}
