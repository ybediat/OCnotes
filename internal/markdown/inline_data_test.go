package markdown

import (
	"fmt"
	"strings"
	"testing"
)

// image construit une image en ligne d'une taille donnée.
func image(alt string, octets int) string {
	return fmt.Sprintf("![%s](data:image/jpeg;base64,%s)", alt, strings.Repeat("A", octets))
}

// La propriété qui compte : ce qui sort revient à l'identique.
//
// Une restitution ratée ne planterait pas, elle écrirait le texte allégé sur le
// serveur — l'image serait perdue dans la vraie note, en silence. C'est le pire
// scénario du dispositif, et ce test est ce qui l'écarte.
func TestInlineDataAllerRetour(t *testing.T) {
	cas := map[string]string{
		"aucune image":     "# Titre\n\nDu texte sans rien de spécial.\n",
		"une image seule":  image("photo", 500),
		"image et texte":   "avant\n\n" + image("photo", 500) + "\n\naprès\n",
		"deux images":      image("a", 100) + "\n\n" + image("b", 200),
		"image sans alt":   image("", 300),
		"image avec titre": "![p](data:image/png;base64,AAAA \"une photo\")",
		// Un lien ordinaire ne doit pas être touché.
		"lien normal": "voir [le site](https://exemple.fr) et " + image("p", 50),
	}

	for nom, source := range cas {
		texte, donnees := ExtractInlineData(source)
		if got := RestoreInlineData(texte, donnees); got != source {
			t.Errorf("%s : l'aller-retour ne rend pas l'original\n  attendu : %.80q\n  obtenu  : %.80q",
				nom, source, got)
		}
	}
}

// Onze images ou plus : le jeton 1 est un préfixe du jeton 12.
//
// Restitués dans l'ordre croissant, le jeton 1 mangerait le début du jeton 12.
// Le test porte sur treize images pour que le cas se présente vraiment.
func TestInlineDataAllerRetourAuDelaDeDixImages(t *testing.T) {
	var source strings.Builder
	for i := 0; i < 13; i++ {
		fmt.Fprintf(&source, "ligne %d %s\n\n", i, image(fmt.Sprintf("img%d", i), 20+i))
	}

	texte, donnees := ExtractInlineData(source.String())
	if len(donnees) != 13 {
		t.Fatalf("%d données extraites, 13 attendues", len(donnees))
	}
	if got := RestoreInlineData(texte, donnees); got != source.String() {
		t.Error("l'aller-retour échoue au-delà de dix images : les jetons se recouvrent")
	}
}

// L'extraction doit rendre le texte affichable, c'est tout son objet.
func TestExtractInlineDataRendLeTexteEditable(t *testing.T) {
	source := "# Note\n\n" + image("photo", 60000) + "\n\nla suite du texte\n"

	if Editable(source) {
		t.Fatal("le texte d'origine devrait être jugé inaffichable")
	}

	texte, donnees := ExtractInlineData(source)
	if len(donnees) != 1 {
		t.Fatalf("%d données extraites, 1 attendue", len(donnees))
	}
	if !Editable(texte) {
		t.Errorf("le texte allégé reste inaffichable, plus long mot = %d", LongestWord(texte))
	}
	if strings.Contains(texte, "base64") {
		t.Error("la donnée est restée dans le texte allégé")
	}
	if !strings.Contains(texte, PlaceholderScheme+"0") {
		t.Errorf("le jeton est absent du texte allégé : %.120q", texte)
	}
}

// Le jeton reste du Markdown valide : l'aperçu doit continuer à le lire.
func TestTexteAllegeResteDuMarkdownValide(t *testing.T) {
	texte, _ := ExtractInlineData("![une photo](data:image/jpeg;base64,AAAABBBB)")

	blocks := Render(texte)
	if len(blocks) != 1 {
		t.Fatalf("%d blocs, 1 attendu: %+v", len(blocks), blocks)
	}
	if b := blocks[0]; b.Kind != KindImage || b.Text != "une photo" {
		t.Errorf("bloc = %+v, attendu une image « une photo »", b)
	}
}

// Un contenu portant déjà nos jetons n'est pas touché.
//
// Restituer y injecterait une image là où l'utilisateur avait écrit du texte.
// Renoncer coûte une note en lecture seule ; se tromper coûte une note fausse.
func TestExtractInlineDataRenonceSiLesJetonsExistentDeja(t *testing.T) {
	source := "j'ai écrit " + PlaceholderScheme + "0 à la main\n\n" + image("p", 100)

	texte, donnees := ExtractInlineData(source)
	if texte != source || donnees != nil {
		t.Errorf("le contenu a été modifié alors qu'il portait déjà un jeton\n  obtenu : %.100q", texte)
	}
}

// Un jeton effacé supprime l'image : c'est le seul geste disponible pour ça
// depuis un téléphone, et il doit marcher.
func TestJetonEffaceSupprimeLImage(t *testing.T) {
	texte, donnees := ExtractInlineData("avant\n\n" + image("p", 100) + "\n\naprès")

	// L'utilisateur efface la ligne du repère.
	sansImage := "avant\n\naprès"
	if got := RestoreInlineData(sansImage, donnees); got != sansImage {
		t.Errorf("l'image est revenue alors qu'elle avait été effacée : %.80q", got)
	}
	_ = texte
}

func TestLongestWord(t *testing.T) {
	tests := map[string]int{
		"":                     0,
		"des mots courts":      6,
		"un\nretour\nà\nligne": 6,
		// « é » vaut 1 unité UTF-16, « 😀 » en vaut 2.
		"é😀": 3,
	}
	for texte, attendu := range tests {
		if got := LongestWord(texte); got != attendu {
			t.Errorf("LongestWord(%q) = %d, attendu %d", texte, got, attendu)
		}
	}
}

// La borne se lit à travers la façade plutôt que d'être recopiée ailleurs.
func TestEditableSurLaBorne(t *testing.T) {
	juste := strings.Repeat("A", MaxEditableWord())
	if !Editable(juste) {
		t.Errorf("un mot de %d caractères devrait passer", MaxEditableWord())
	}
	if Editable(juste + "A") {
		t.Errorf("un mot de %d caractères devrait être refusé", MaxEditableWord()+1)
	}
}

// L'aperçu doit être sûr là où la saisie ne l'est pas.
//
// C'est tout l'intérêt du repli en lecture seule : s'il rendait le même pavé
// dans un Text, il tuerait l'application exactement comme le champ de saisie.
func TestApercuTronqueLesMotsDemesures(t *testing.T) {
	pave := strings.Repeat("z", 60000)

	for nom, blocks := range map[string][]Block{
		"markdown":   Render("début\n\n" + pave + "\n\nfin\n"),
		"texte brut": RenderPlain("début\n" + pave + "\nfin\n"),
		"bloc code":  Render("```\n" + pave + "\n```\n"),
		"tableau":    Render("| a | b |\n|---|---|\n| " + pave + " | x |\n"),
		"titre":      Render("# " + pave + "\n"),
	} {
		if len(blocks) == 0 {
			t.Fatalf("%s : aucun bloc rendu", nom)
		}
		for _, b := range blocks {
			if n := LongestWord(b.Text); n > MaxEditableWord() {
				t.Errorf("%s : un bloc porte un mot de %d caractères, la mise en page n'y survivrait pas", nom, n)
			}
			for _, cellule := range b.Cells {
				if n := LongestWord(cellule); n > MaxEditableWord() {
					t.Errorf("%s : une cellule porte un mot de %d caractères", nom, n)
				}
			}
		}
	}
}

// Le texte ordinaire n'est pas touché, et garde ses mises en forme.
func TestApercuNeTronquePasLeTexteOrdinaire(t *testing.T) {
	blocks := Render("du **gras** et un mot un peu long : anticonstitutionnellement\n")
	if len(blocks) != 1 {
		t.Fatalf("%d blocs, 1 attendu", len(blocks))
	}
	b := blocks[0]
	if strings.Contains(b.Text, "…") {
		t.Errorf("le texte a été tronqué à tort : %q", b.Text)
	}
	if len(b.Spans) != 1 {
		t.Errorf("les spans ont été perdus sur un bloc pourtant intact : %+v", b.Spans)
	}
}
