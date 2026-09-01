package markdown

import "testing"

func TestTitle(t *testing.T) {
	tests := []struct {
		name string
		text string
		want string
	}{
		{"titre de niveau 1", "# Mon titre\n\nDu contenu.", "Mon titre"},
		{"titre plus bas dans la note", "Une intro.\n\n# Le vrai titre\n", "Le vrai titre"},
		{"pas de titre, première ligne", "Juste du texte\net une suite.", "Juste du texte"},
		{"préfixe de liste retiré", "- premier élément\n- second", "premier élément"},
		{"case à cocher retirée", "- [ ] acheter du pain", "acheter du pain"},
		{"citation retirée", "> une citation", "une citation"},
		{"lignes vides ignorées", "\n\n\n# Après du vide", "Après du vide"},
		{"espaces ignorés", "   \n  # Titre indenté  ", "Titre indenté"},
		{"note vide", "", ""},
		{"note sans contenu", "\n \n\t\n", ""},
		{"titre de niveau 2 ne compte pas comme titre", "## Sous-titre\n\n# Titre", "Titre"},
		{"accents et emoji", "# Réunion du 15 😀", "Réunion du 15 😀"},

		// Un « # » à l'intérieur d'un bloc de code est un commentaire de shell,
		// pas un titre de note.
		{
			"dièse dans un bloc de code",
			"```sh\n# ceci est un commentaire\n```\n\n# Le vrai titre",
			"Le vrai titre",
		},
		{
			"bloc de code avant toute ligne de texte",
			"```\n# pas un titre\ncode\n```\nDu texte",
			"Du texte",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := Title(tc.text); got != tc.want {
				t.Errorf("Title() = %q, attendu %q", got, tc.want)
			}
		})
	}
}
