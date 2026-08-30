package documents

import (
	"archive/zip"
	"bytes"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/ybediat/OpenNote/internal/markdown"
)

// Les fixtures viennent de LibreOffice, pas d'un XML écrit à la main : voir
// scripts/fixtures-documents.ps1, qui les régénère. Les valeurs attendues ici
// sont celles du **HTML source**, jamais celles que l'analyseur produit — un
// test écrit depuis sa propre sortie ne prouve rien.

func TestDocxTitres(t *testing.T) {
	blocs := lireDocx(t, "exemple.docx")

	var niveaux []int
	var textes []string
	for _, b := range blocs {
		if b.Kind == markdown.KindHeading {
			niveaux = append(niveaux, b.Level)
			textes = append(textes, b.Text)
		}
	}

	attendus := []int{1, 2, 3, 4, 5, 6, 2, 2}
	if len(niveaux) != len(attendus) {
		t.Fatalf("%d titres, attendu %d : %q", len(niveaux), len(attendus), textes)
	}
	for i, n := range attendus {
		if niveaux[i] != n {
			t.Errorf("titre %d (%q) de niveau %d, attendu %d", i, textes[i], niveaux[i], n)
		}
	}
	if textes[0] != "Titre de niveau 1" {
		t.Errorf("premier titre = %q", textes[0])
	}
}

// TestDocxTitresSansStyles montre pourquoi styles.xml est lu.
//
// LibreOffice en français nomme ses styles « Titre1 » : privé du nom canonique,
// l'analyseur ne peut plus reconnaître un titre, et tout le document devient
// des paragraphes. C'est exactement le défaut qu'un analyseur écrit sur la
// spécification aurait livré — silencieux, et invisible à la relecture.
func TestDocxTitresSansStyles(t *testing.T) {
	brut := fixture(t, "exemple.docx")

	avec, err := Docx(brut)
	if err != nil {
		t.Fatalf("analyse: %v", err)
	}
	sans, err := Docx(sansEntree(t, brut, "word/styles.xml"))
	if err != nil {
		t.Fatalf("analyse sans styles.xml: %v", err)
	}

	if compte(avec, markdown.KindHeading) != 8 {
		t.Fatalf("avec styles.xml : %d titres, attendu 8", compte(avec, markdown.KindHeading))
	}
	if n := compte(sans, markdown.KindHeading); n != 0 {
		t.Errorf("sans styles.xml : %d titres reconnus — l'identifiant localisé ne devrait rien donner", n)
	}
}

func TestDocxMiseEnForme(t *testing.T) {
	b := blocContenant(t, lireDocx(t, "exemple.docx"), "en gras")

	cas := []struct {
		style   markdown.Style
		attendu string
	}{
		{markdown.StyleBold, "en gras"},
		{markdown.StyleItalic, "en italique"},
		{markdown.StyleUnderline, "souligne"},
		{markdown.StyleStrike, "barre"},
		{markdown.StyleLink, "lien vers OpenCloud"},
	}
	for _, c := range cas {
		if got := portee(t, b, c.style); got != c.attendu {
			t.Errorf("%s couvre %q, attendu %q", c.style, got, c.attendu)
		}
	}

	for _, s := range b.Spans {
		if s.Style == markdown.StyleLink && s.Href != "https://opencloud.eu/" {
			t.Errorf("href du lien = %q", s.Href)
		}
	}
}

func TestDocxListes(t *testing.T) {
	blocs := lireDocx(t, "exemple.docx")

	attendus := []struct {
		texte  string
		kind   markdown.Kind
		depth  int
		number int
	}{
		{"Premiere puce", markdown.KindBullet, 0, 0},
		{"Deuxieme puce", markdown.KindBullet, 0, 0},
		{"Premier point", markdown.KindOrdered, 0, 1},
		{"Sous-point A", markdown.KindOrdered, 1, 1},
		{"Sous-point B", markdown.KindOrdered, 1, 2},
		{"Deuxieme point", markdown.KindOrdered, 0, 2},
	}
	for _, a := range attendus {
		b := blocContenant(t, blocs, a.texte)
		if b.Kind != a.kind {
			t.Errorf("%q est un %s, attendu %s", a.texte, b.Kind, a.kind)
		}
		if b.Depth != a.depth {
			t.Errorf("%q à la profondeur %d, attendu %d", a.texte, b.Depth, a.depth)
		}
		if b.Number != a.number {
			t.Errorf("%q porte le numéro %d, attendu %d", a.texte, b.Number, a.number)
		}
	}
}

func TestDocxTableau(t *testing.T) {
	var lignes []markdown.Block
	for _, b := range lireDocx(t, "exemple.docx") {
		if b.Kind == markdown.KindTableRow {
			lignes = append(lignes, b)
		}
	}

	if len(lignes) != 3 {
		t.Fatalf("%d lignes de tableau, attendu 3", len(lignes))
	}
	if !lignes[0].Header {
		t.Error("la première ligne n'est pas marquée en-tête")
	}
	if lignes[1].Header || lignes[2].Header {
		t.Error("une ligne de corps est marquée en-tête")
	}

	attendus := [][]string{
		{"Brique", "Role"},
		{"Go", "analyse"},
		{"Compose", "dessin"},
	}
	for i, cellules := range attendus {
		if len(lignes[i].Cells) != len(cellules) {
			t.Fatalf("ligne %d : %d cellules, attendu %d : %q", i, len(lignes[i].Cells), len(cellules), lignes[i].Cells)
		}
		for j, c := range cellules {
			if lignes[i].Cells[j] != c {
				t.Errorf("ligne %d cellule %d = %q, attendu %q", i, j, lignes[i].Cells[j], c)
			}
		}
	}
}

// TestDocxMotLong vérifie le garde-fou qui empêche un document de faire tuer
// l'application par le système.
//
// Le paquet ne bénéficie pas de l'entonnoir de internal/markdown : sans l'appel
// explicite à la protection, ce mot de 5 000 caractères sortirait intact.
func TestDocxMotLong(t *testing.T) {
	blocs := lireDocx(t, "mot-long.docx")

	var long markdown.Block
	for _, b := range blocs {
		if utf8.RuneCountInString(b.Text) > utf8.RuneCountInString(long.Text) {
			long = b
		}
	}

	if !strings.HasSuffix(long.Text, "…") {
		t.Fatalf("le mot démesuré n'a pas été tronqué : %d caractères", utf8.RuneCountInString(long.Text))
	}
	if n := utf8.RuneCountInString(long.Text); n > 64 {
		t.Errorf("le bloc tronqué fait encore %d caractères", n)
	}
	if long.Spans != nil {
		t.Error("les spans d'un bloc tronqué doivent être abandonnés : leurs bornes ne désignent plus rien")
	}

	// Le reste du document n'est pas touché.
	blocContenant(t, blocs, "Avant le mot")
	blocContenant(t, blocs, "Apres le mot")
}

func TestDocxArchiveInvalide(t *testing.T) {
	_, err := Docx([]byte("ceci n'est pas une archive"))
	if err == nil {
		t.Fatal("aucune erreur sur un fichier qui n'est pas un ZIP")
	}
	if !strings.Contains(err.Error(), "["+CodeInvalid+"]") {
		t.Errorf("erreur sans le code attendu : %v", err)
	}
}

func TestDocxFichierTropGros(t *testing.T) {
	_, err := Docx(make([]byte, maxFileBytes+1))
	if err == nil {
		t.Fatal("aucune erreur au-delà de la borne")
	}
	if !strings.Contains(err.Error(), "["+CodeFileTooLarge+"]") {
		t.Errorf("erreur sans le code attendu : %v", err)
	}
}

func TestDocxDocumentAbsent(t *testing.T) {
	_, err := Docx(sansEntree(t, fixture(t, "exemple.docx"), "word/document.xml"))
	if err == nil {
		t.Fatal("aucune erreur sans word/document.xml")
	}
	if !strings.Contains(err.Error(), "["+CodeInvalid+"]") {
		t.Errorf("erreur sans le code attendu : %v", err)
	}
}

// TestDocxTexteRogne fige le rognage des bords d'un bloc.
//
// L'import HTML de LibreOffice laisse une espace finale sur la plupart des
// paragraphes. Les garder ferait finir les lignes de l'aperçu là où l'œil ne
// les attend pas — et les rogner *run par run* serait l'erreur inverse : ça
// recollerait les mots, ce que xml:space demande justement d'éviter.
func TestDocxTexteRogne(t *testing.T) {
	for _, b := range lireDocx(t, "exemple.docx") {
		if b.Text != strings.TrimSpace(b.Text) {
			t.Errorf("bloc %s non rogné : %q", b.Kind, b.Text)
		}
		for _, c := range b.Cells {
			if c != strings.TrimSpace(c) {
				t.Errorf("cellule non rognée : %q", c)
			}
		}
	}
}

// --- Utilitaires ------------------------------------------------------------

func fixture(t *testing.T, nom string) []byte {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", nom))
	if err != nil {
		t.Fatalf("fixture %s : %v (régénérer avec scripts/fixtures-documents.ps1)", nom, err)
	}
	return data
}

func lireDocx(t *testing.T, nom string) []markdown.Block {
	t.Helper()
	blocs, err := Docx(fixture(t, nom))
	if err != nil {
		t.Fatalf("analyse de %s : %v", nom, err)
	}
	return blocs
}

func compte(blocs []markdown.Block, kind markdown.Kind) int {
	n := 0
	for _, b := range blocs {
		if b.Kind == kind {
			n++
		}
	}
	return n
}

func blocContenant(t *testing.T, blocs []markdown.Block, extrait string) markdown.Block {
	t.Helper()
	for _, b := range blocs {
		if strings.Contains(b.Text, extrait) {
			return b
		}
	}
	t.Fatalf("aucun bloc ne contient %q", extrait)
	return markdown.Block{}
}

// portee rend le texte couvert par les spans d'un style, en unités UTF-16
// comme les bornes elles-mêmes.
//
// La couverture est prise du premier début à la dernière fin : un producteur a
// le droit de couper une mise en forme en plusieurs runs, et le test ne doit
// pas dépendre de ce découpage.
func portee(t *testing.T, b markdown.Block, style markdown.Style) string {
	t.Helper()

	debut, fin := -1, -1
	for _, s := range b.Spans {
		if s.Style != style {
			continue
		}
		if debut < 0 || s.Start < debut {
			debut = s.Start
		}
		if s.End > fin {
			fin = s.End
		}
	}
	if debut < 0 {
		t.Fatalf("aucun span de style %s dans %q", style, b.Text)
	}

	unites := utf16.Encode([]rune(b.Text))
	if debut > len(unites) || fin > len(unites) {
		t.Fatalf("span %s hors bornes : [%d,%d] pour %d unités", style, debut, fin, len(unites))
	}
	return string(utf16.Decode(unites[debut:fin]))
}

// sansEntree recopie une archive en écartant une entrée.
//
// Sert à vérifier ce qui se passe quand une partie facultative manque, sans
// avoir à fabriquer un second jeu de fixtures.
func sansEntree(t *testing.T, data []byte, nom string) []byte {
	t.Helper()

	source, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatalf("lecture de l'archive : %v", err)
	}

	var out bytes.Buffer
	w := zip.NewWriter(&out)
	for _, f := range source.File {
		if f.Name == nom {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			t.Fatalf("ouverture de %s : %v", f.Name, err)
		}
		contenu, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			t.Fatalf("lecture de %s : %v", f.Name, err)
		}
		fw, err := w.Create(f.Name)
		if err != nil {
			t.Fatalf("création de %s : %v", f.Name, err)
		}
		if _, err := fw.Write(contenu); err != nil {
			t.Fatalf("écriture de %s : %v", f.Name, err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatalf("fermeture de l'archive : %v", err)
	}
	return out.Bytes()
}
