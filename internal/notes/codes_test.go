package notes

import (
	"strings"
	"testing"
)

// TestValidateNameCodes vérifie que chaque refus porte son étiquette.
//
// C'est l'étiquette, et elle seule, qui permet à Android de reformuler la
// règle dans la langue de l'appareil : le test ne regarde donc jamais la
// phrase française qui la suit, sous peine de figer la formulation.
func TestValidateNameCodes(t *testing.T) {
	cas := []struct {
		nom  string
		code string
	}{
		{"", CodeNameEmpty},
		{"   ", CodeNameEmpty},
		{".", CodeNameReserved},
		{"..", CodeNameReserved},
		{strings.Repeat("a", maxNameBytes+1), CodeNameTooLong},
		{"note?.md", CodeNameForbiddenChars},
		{"note\x01", CodeNameControlChar},
		{" note", CodeNameSpaceEdge},
		{"note ", CodeNameSpaceEdge},
		{"note.", CodeNameTrailingDot},
		{".note", CodeNameLeadingDot},
		{"CON.md", CodeNameReservedDevice},
	}

	for _, c := range cas {
		err := ValidateName(c.nom)
		if err == nil {
			t.Errorf("ValidateName(%q) accepté, refus attendu (%s)", c.nom, c.code)
			continue
		}
		if !strings.Contains(err.Error(), "["+c.code+"]") {
			t.Errorf("ValidateName(%q) = %q, étiquette [%s] attendue", c.nom, err, c.code)
		}
	}
}

// TestBornesExposees vérifie que les accesseurs rendent bien la valeur
// interne. Ils existent pour qu'Android ne recopie pas ces bornes dans sa
// propre formulation ; les laisser diverger viderait l'exercice de son sens.
func TestBornesExposees(t *testing.T) {
	if MaxNameBytes() != maxNameBytes {
		t.Errorf("MaxNameBytes() = %d, attendu %d", MaxNameBytes(), maxNameBytes)
	}
	if ForbiddenNameChars() != forbiddenInName {
		t.Errorf("ForbiddenNameChars() = %q, attendu %q", ForbiddenNameChars(), forbiddenInName)
	}
}
