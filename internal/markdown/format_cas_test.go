package markdown

import (
	"bufio"
	"os"
	"strings"
	"testing"
)

// casPartage est un cas de testdata/cas-mise-en-forme.txt.
type casPartage struct {
	ligne        int
	action       Action
	avant, apres string
}

// lireCasPartages lit le fichier de cas commun à Go et à Android. Les champs
// sont rognés (espaces normales seulement) et « \n » devient un saut de ligne.
func lireCasPartages(t *testing.T) []casPartage {
	t.Helper()
	f, err := os.Open("testdata/cas-mise-en-forme.txt")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	var cas []casPartage
	sc := bufio.NewScanner(f)
	for n := 1; sc.Scan(); n++ {
		ligne := sc.Text()
		if strings.TrimSpace(ligne) == "" || strings.HasPrefix(ligne, "#") {
			continue
		}
		champs := strings.Split(ligne, "¦")
		if len(champs) != 4 {
			t.Fatalf("ligne %d : 4 champs attendus, %d trouvés", n, len(champs))
		}
		champ := func(i int) string {
			return strings.ReplaceAll(strings.Trim(champs[i], " "), `\n`, "\n")
		}
		cas = append(cas, casPartage{n, Action(strings.ToLower(champ(0))), champ(1), champ(2)})
	}
	return cas
}

func TestCasPartages(t *testing.T) {
	for _, c := range lireCasPartages(t) {
		got, err := Apply(parseDoc(t, c.avant), c.action)
		if err != nil {
			t.Fatalf("ligne %d : %v", c.ligne, err)
		}
		if rendu := renderDoc(got); rendu != c.apres {
			t.Errorf("ligne %d, %s\nentrée   %q\nobtenu   %q\nattendu  %q", c.ligne, c.action, c.avant, rendu, c.apres)
		}
	}
}
