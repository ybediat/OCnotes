package opencloud

import "testing"

func TestCapabilitiesOf(t *testing.T) {
	full := CapabilitiesOf("GDNVWCK")
	if !full.CanWrite || !full.CanDelete || !full.CanRename || !full.CanMove ||
		!full.CanCreateFile || !full.CanCreateFolder {
		t.Fatalf("capacités complètes mal décodées: %+v", full)
	}
	readOnly := CapabilitiesOf("G")
	if readOnly.CanWrite || readOnly.CanDelete || readOnly.CanRename || readOnly.CanMove ||
		readOnly.CanCreateFile || readOnly.CanCreateFolder {
		t.Fatalf("lecture seule trop permissive: %+v", readOnly)
	}
	legacy := CapabilitiesOf("")
	if !legacy.CanWrite || !legacy.CanDelete || !legacy.CanRename || !legacy.CanMove ||
		!legacy.CanCreateFile || !legacy.CanCreateFolder {
		t.Fatalf("permissions absentes incompatibles avec les anciens caches: %+v", legacy)
	}
}
