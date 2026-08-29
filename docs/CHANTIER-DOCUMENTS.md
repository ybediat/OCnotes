# Chantier — lecture seule des `.docx` et `.odt`

Ordre de travail pour un agent qui reprend le sujet à froid. Lire d'abord
`CLAUDE.md`, puis `docs/FACADE.md` (le contrat gelé) et la section « L'aperçu :
Go analyse, Compose dessine » de `CLAUDE.md`.

---

## 1. Ce qu'on veut, et ce qu'on ne veut pas

**On veut** ouvrir un `.docx` ou un `.odt` posé dans le dossier de notes depuis
l'interface web, et l'afficher **en lecture seule** dans l'aperçu existant :
paragraphes, titres, gras/italique/souligné/barré, listes, tableaux, liens.

**On ne veut pas**, et ce n'est pas négociable dans ce chantier :

| Hors périmètre | Pourquoi |
|---|---|
| `.doc` (Word 97, binaire) | Fichier composé OLE2, table de morceaux, structures propriétaires. Aucune bibliothèque Go crédible. Des semaines de travail fragile pour un format que plus personne ne produit. |
| PDF | Autre problème : extraction de texte **et** reconstruction de mise en page. À traiter séparément, jamais en même temps. |
| Modifier un document | L'application n'écrit que du Markdown. Un `.docx` réécrit par nos soins serait un `.docx` cassé. |
| Images embarquées | Le modèle rend déjà un repère `image`. Décoder et afficher les médias est un chantier à part. |
| Mise en page fidèle | Colonnes, en-têtes, pieds de page, notes de bas de page, objets embarqués : on rend du **texte structuré**, pas une page. |
| Une dépendance nouvelle | `archive/zip` et `encoding/xml` sont dans la bibliothèque standard. Les deux formats sont des ZIP contenant du XML. Si vous vous surprenez à chercher un module, c'est que vous avez pris un mauvais virage. |

---

## 2. Ce qui existe déjà et qu'il faut réutiliser

**Ne réinventez rien de cette liste.** La moitié du chantier est déjà payée.

| Brique | Où | Ce qu'elle vous donne |
|---|---|---|
| Modèle d'affichage | `markdown.Block`, `markdown.Span` dans `internal/markdown/render.go` | Une liste **plate** de blocs que Compose sait déjà dessiner. Votre analyseur doit produire *exactement* ça, pas un modèle à lui. |
| Sérialisation | `noteBlock` / `noteSpan` dans `mobile/app.go` | Le JSON du contrat. Un bloc de plus s'y ajoute sans rien casser. |
| Rendu Compose | `android/app/src/main/java/eu/opennote/ui/editor/MarkdownView.kt` | Titres, puces, tâches, citations, tableaux, code, image — déjà dessinés. |
| Lecture seule | `preparedEdit.Editable` (`mobile/app.go`), `EditorUiState.modifiable` | **Toute la mécanique « la note s'ouvre en aperçu et refuse la saisie » existe.** Bandeau compris. Un document réutilise ce chemin tel quel. |
| Extensions | `notes.IsNote` / `IsMarkdown` / `IsPlainText` / `WithExtensionOf` dans `internal/notes/names.go` | Trois questions déjà séparées, et un renommage qui préserve l'extension. |
| Garde-fou de mise en page | `markdown.ShortenLongWords` | Empêche un mot sans espace de faire tuer l'application. Voir le piège n° 3. |

---

## 3. Les quatre étapes, dans cet ordre

L'ordre est choisi pour que **les deux plus grosses étapes soient entièrement
testables sur desktop**, sans NDK, sans émulateur, sans toucher au contrat gelé.
Vous pouvez faire l'étape 4 en premier si vous voulez voir la plomberie tôt,
mais ne commencez pas par l'étape 3 : elle n'a rien à transporter tant que
l'analyseur n'existe pas.

### Étape 1 — `internal/documents`, l'analyseur `.docx`

Nouveau paquet, **indépendant d'Android comme tout ce qui est sous `internal/`**.

```go
// Package documents lit les formats bureautiques en lecture seule.
package documents

// Docx analyse un .docx et renvoie ses blocs d'affichage.
func Docx(data []byte) ([]markdown.Block, error)
```

Un `.docx` est une archive ZIP. Ce qui vous intéresse :

- `word/document.xml` — le contenu ;
- `word/_rels/document.xml.rels` — la cible des liens hypertexte, qui ne sont
  **pas** dans le document lui-même.

Correspondance avec le modèle :

| OOXML | `markdown.Block` |
|---|---|
| `w:p` | `KindParagraph` |
| `w:pPr/w:pStyle` valant `Heading1`…`Heading6` | `KindHeading` + `Level` |
| `w:numPr` | `KindBullet` ou `KindOrdered`, `Depth` = `w:ilvl` |
| `w:tbl` / `w:tr` / `w:tc` | `KindTableRow` + `Cells` |
| `w:r` avec `w:rPr/w:b`, `w:i`, `w:u`, `w:strike` | un `Span` par attribut |
| `w:hyperlink` (attribut `r:id`) | `Span` de style `link`, `Href` résolu par le `.rels` |

**Critère de sortie** : `go test ./internal/documents` au vert sur une fixture
réelle, sans qu'aucune ligne d'Android n'ait bougé.

### Étape 2 — `.odt`, même paquet, même signature

```go
func Odt(data []byte) ([]markdown.Block, error)
```

Même principe : ZIP, mais le contenu est dans `content.xml`.

| ODF | `markdown.Block` |
|---|---|
| `text:p` | `KindParagraph` |
| `text:h` + `text:outline-level` | `KindHeading` + `Level` — **plus simple qu'en OOXML**, le niveau est un attribut, pas un nom de style à interpréter |
| `text:list` / `text:list-item` | `KindBullet` / `KindOrdered` |
| `table:table` / `table:table-row` / `table:table-cell` | `KindTableRow` |
| `text:span` + `text:style-name` | un `Span`, **après résolution du style** — voir le piège n° 5 |
| `text:a` (`xlink:href`) | `Span` de style `link` |

**Critère de sortie** : mêmes tests, mêmes exigences, sur une fixture `.odt`.

### Étape 3 — la frontière binaire

C'est la seule étape qui touche `docs/FACADE.md`. Voir le piège n° 1 avant
d'écrire une ligne.

Ajouter à `mobile/app.go` :

```go
// RenderFileJSON prépare l'affichage d'un fichier que l'application ne sait
// que lire. Le binaire ne franchit jamais la frontière.
func (a *App) RenderFileJSON(filePath string) (string, error)
```

Elle lit **à l'intérieur de Go**, depuis le cache puis le serveur, exactement
comme `ReadNote` — factorisez la partie commune de `ReadNote` dans un
`readBytes(notePath string) ([]byte, error)` privé plutôt que de la recopier,
sans quoi le repli hors connexion et le `recentlyOffline()` divergeront.

Le format se déduit du nom, comme partout ailleurs dans ce dépôt : ajoutez le
choix dans `notes.Render` (`internal/notes/library.go`), à côté du choix
Markdown / texte brut. **Ne mettez aucune règle d'extension côté Kotlin.**

Ne pas oublier : une ligne dans le tableau des méthodes de `docs/FACADE.md`, et
une section décrivant le format de sortie.

### Étape 4 — la troisième catégorie, et l'interface

Aujourd'hui `notes.IsNote` répond « oui » au Markdown et au texte brut. Il faut
une troisième réponse : **lisible mais jamais modifiable**.

1. `notes.IsDocument(name)` dans `internal/notes/names.go`, et `IsNote` qui
   l'inclut, pour que ces fichiers apparaissent dans le listing.
2. `DisplayName` garde l'extension, comme pour le `.txt` — un `rapport.docx` et
   un `rapport.md` cohabitent.
3. `WithExtension` (création) ne doit **jamais** répondre autre chose que `.md` :
   l'application ne crée pas de documents. `WithExtensionOf` (renommage), lui,
   préserve déjà l'extension existante — rien à faire.
4. Côté façade, un document sort avec `editable: false`. C'est tout ce dont
   l'interface a besoin : `EditorViewModel` ouvre déjà l'aperçu et masque la
   bascule dans ce cas.
5. Le bandeau de lecture seule dit aujourd'hui « élément trop long ». Un
   document mérite sa propre phrase — **une clé de plus dans `strings.xml`**,
   jamais un littéral dans le code.

**Critère de sortie** : un `.docx` posé sur le serveur apparaît dans la liste,
s'ouvre en aperçu, ne propose pas de saisie, et se renomme sans changer
d'extension.

---

## 4. Les pièges, chacun payé une fois

### 1. Un binaire ne traverse pas gomobile dans une chaîne

`App.ReadNote` renvoie une `string` Go, que gomobile décode en UTF-8 vers un
`String` Java. Un `.docx` en ressortirait truffé de caractères de remplacement,
irrécupérable. **C'est la raison d'être de l'étape 3** : le fichier est lu,
décompressé et analysé du côté Go, et seuls des blocs franchissent la frontière.

Si vous vous surprenez à vouloir un `[]byte` côté Kotlin, arrêtez-vous : la
réponse est de déplacer le traitement en Go, pas d'élargir le contrat.

### 2. Bornez la taille décompressée

Le fichier vient d'un serveur partagé. Une archive de quelques kilo-octets peut
se décompresser en gigaoctets. Lisez `word/document.xml` à travers un
`io.LimitedReader` et renoncez au-delà d'une borne explicite, avec une erreur
portant un code entre crochets comme le reste du cœur (voir la section
« Frontière avec Android » de `CLAUDE.md`).

### 3. Appliquez `markdown.ShortenLongWords` à vos blocs

Dans `internal/markdown`, tous les blocs passent par `protegerLaMiseEnPage`
avant d'être publiés. **Votre paquet ne bénéficie pas de cet entonnoir.**

Sans lui, un document contenant une suite de caractères sans espace démesurée
fait tuer l'application par le système — mort de processus muette, sans
exception Java, suivie d'une purge mémoire de tout le téléphone. C'est constaté
sur appareil, pas déduit : voir « Le piège du mot sans espace » dans
`CLAUDE.md`. Un `Text` et un `TextField` partagent le même moteur de mise en
page ; la lecture seule ne protège de rien toute seule.

Passez chaque bloc produit — texte **et** cellules — par
`markdown.ShortenLongWords`, ou exportez `protegerLaMiseEnPage` et appelez-la.

### 4. `.docx` : ne résolvez pas `numbering.xml`

La numérotation des listes vit dans une partie séparée, avec des définitions
abstraites, des instances concrètes et des niveaux qui se redéfinissent. Pour un
visualiseur, **comptez les éléments vous-même** et remplissez `Number` : le
résultat est le même à l'écran pour une fraction du travail. `w:ilvl` suffit
pour `Depth`.

### 5. `.odt` : les styles sont indirects

Là où OOXML pose le gras dans le `w:rPr` du run, l'ODF pose un
`text:style-name` qui renvoie à `office:automatic-styles`, plus haut dans le
même fichier. Il faut donc **une première passe** qui construit la table
`nom de style → gras/italique/souligné/barré`, avant la passe de contenu. Ne
tentez pas de tout faire en un seul parcours.

### 6. `w:t` et `xml:space="preserve"`

Les espaces significatives d'un run sont marquées par cet attribut. Les ignorer
recolle les mots. `encoding/xml` vous donne l'attribut, encore faut-il le lire.

### 7. Le souligné est un style de span qui n'existe pas encore

`markdown.Style` connaît `bold`, `italic`, `strike`, `code`, `link`. Le souligné
est courant dans un traitement de texte et absent du Markdown. L'ajouter, c'est
**quatre gestes solidaires** : la constante Go, le cas dans `MarkdownView.kt`,
la constante dans `SpanStyleId` (`Dto.kt`), et la ligne dans `docs/FACADE.md`.
`MarkdownView` ignore déjà proprement un style inconnu, donc un oubli côté
Kotlin ne casse rien — il fuit en silence, ce qui est pire.

### 8. Le `.aar` n'est pas régénéré par Gradle

Toute fonction exportée ajoutée dans `mobile/` exige de relancer `gomobile bind`
à la main. Sinon Kotlin compile contre l'ancien binding et se plaint d'une
référence introuvable sur un symbole que vous venez d'écrire — le symptôme ne
désigne pas sa cause. Les variables d'environnement sont dans `CLAUDE.md`,
section « Ce que l'outillage ne dit pas ».

### 9. Écrire les fichiers en LF

Le dépôt est en LF alors que `core.autocrlf` est à `true`. Un outil d'édition
qui traduit les fins de ligne signale le fichier **entier** comme mal formaté à
`gofmt -l`, pour une cause invisible.

---

## 5. Fixtures

`internal/documents/testdata/`, sur le modèle de `internal/opencloud/testdata/`
dont les réponses viennent d'un vrai serveur : **les fixtures de ce dépôt
viennent d'outils réels, pas d'un XML écrit à la main.**

Fabriquez-les avec LibreOffice — un document contenant délibérément un titre de
chaque niveau, du gras, de l'italique, du souligné, une liste à puces, une liste
numérotée imbriquée, un tableau à en-tête et un lien hypertexte — puis
enregistrez-le une fois en `.docx` et une fois en `.odt`. Les deux fixtures
décrivent alors le même contenu, ce qui permet de vérifier que les deux
analyseurs convergent vers les mêmes blocs. C'est le test le plus rentable du
chantier.

Un XML écrit à la main testerait votre idée du format, pas le format.

---

## 6. Discipline attendue

Elle est décrite dans `CLAUDE.md` et s'applique intégralement. Les deux points
qui comptent le plus ici :

- **Un test qui passe ne prouve rien tant qu'on ne l'a pas vu échouer.** Avant
  de conclure qu'un correctif marche, désactivez-le et vérifiez que le test
  échoue avec le symptôme attendu. C'est ce qui a validé chaque garde-fou du
  chantier précédent.
- **Distinguer « écrit », « compile » et « testé »** dans tout rapport
  d'avancement. Le cœur Go se teste ; l'interface Compose, elle, n'a aucun test
  instrumenté, et ce qui n'a pas tourné sur un appareil doit être annoncé comme
  tel.

Avant de conclure : `go vet ./... && gofmt -l .`, `go test ./... -short`, puis
`gomobile bind` et `cd android && ./gradlew assembleDebug testDebugUnitTest`.
Ce dernier fait tourner `ChainesEnDurTest` : **n'ajoutez jamais un fichier neuf
à `ECRANS_A_MIGRER`**, cette liste ne décrit qu'une dette existante.
