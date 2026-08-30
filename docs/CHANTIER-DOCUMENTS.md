# Chantier — lecture seule des `.docx` et `.odt`

Ordre de travail pour un agent qui reprend le sujet à froid. Lire d'abord
`CLAUDE.md`, puis `docs/FACADE.md` (le contrat gelé) et la section « L'aperçu :
Go analyse, Compose dessine » de `CLAUDE.md`.

**État au 30 août 2026.** Les lots 0 et 1 sont faits : les quatre fixtures
existent, le relevé de leur XML est en section 6 — lisez-le, il corrige quatre
lignes des tables de correspondance — et `internal/documents` analyse le
`.docx` avec dix cas de test. **Le prochain lot est le 2**, l'`.odt`, qui
partage déjà `documents.go` avec le `.docx` : le constructeur de blocs, le
rognage, les bornes et `niveauDepuisNom` sont écrits pour les deux.

La base est verte — `go vet ./...`, `gofmt -l .` et `go test ./... -short` ne
signalent rien.

Ce document a été **révisé après relecture du code**. La première version
décrivait bien l'analyse XML et passait à côté de cinq choses que le code fait
réellement, dont celle qui peut détruire un fichier de l'utilisateur. C'est la
section 3, et c'est elle qui décide de l'ordre des lots.

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

## 2. Ce qui existe déjà — vérifié, pas supposé

**Ne réinventez rien de cette liste.** La moitié du chantier est déjà payée, et
chaque ligne a été relue dans le code avant d'être écrite ici.

| Brique | Où | Ce qu'elle vous donne |
|---|---|---|
| Modèle d'affichage | `markdown.Block`, `markdown.Span`, `internal/markdown/render.go:16-76` | Dix `Kind`, cinq `Style`, une liste **plate**. Votre analyseur doit produire *exactement* ça, pas un modèle à lui. |
| Sérialisation | `noteBlock` / `noteSpan`, `mobile/app.go:980-1010` | Le JSON du contrat. Un champ de plus s'y ajoute sans rien casser (`omitempty` partout). |
| Rendu Compose | `MarkdownView.kt:118-129` | Tous les `kind` sont dessinés, et le `else ->` final tolère un bloc inconnu sans planter. |
| Lecture seule | `EditorViewModel.kt:161-165`, bandeau `EditorScreen.kt:170` | **Toute la mécanique « la note s'ouvre en aperçu et refuse la saisie » existe.** Un document réutilise ce chemin tel quel. |
| Extensions | `notes.IsNote` / `IsMarkdown` / `IsPlainText` / `WithExtensionOf`, `internal/notes/names.go` | Trois questions déjà séparées — mais voir l'écart n° 2, elles ne suffisent plus à quatre. |
| Garde-fou de mise en page | `markdown.ShortenLongWords` | Empêche un mot sans espace de faire tuer l'application. Voir le piège n° 3. |
| Cache local | `internal/store` | Travaille en `[]byte`, blob nommé par empreinte SHA-256 : **binaire-compatible, rien à faire**. Un document n'est jamais `Dirty`, donc jamais poussé. |
| Recherche | `Classement.kt` | Filtre sur le **nom** seul. Aucun index de contenu à protéger d'un binaire. |

---

## 3. Cinq écarts entre le plan et le code

### 1. Le danger n'est pas la lecture, c'est l'écriture

Le plan d'origine parlait longuement d'analyse XML et pas une fois du seul
chemin qui détruit des données. `App.WriteNote` (`mobile/app.go:596`) écrit
**n'importe quel** chemin dans le cache et l'enfile pour le serveur, sans
regarder l'extension. Le jour où un enregistrement se déclenche sur un `.docx`
ouvert — un `LaunchedEffect` mal placé, une bascule d'aperçu, un brouillon
restauré — le document de l'utilisateur est remplacé par du texte, en silence,
sur un serveur partagé.

C'est le même motif que « ne jamais écrire sans restituer » dans `CLAUDE.md`.
La réponse est la même : **un refus explicite côté Go**, pas une confiance dans
l'interface. `WriteNote` et `PrepareEditJSON` doivent échouer sur un chemin de
document, avec un code entre crochets.

### 2. Élargir `IsNote` casse la création de notes

`WithExtension` répond « rien à faire » dès que `IsNote(name)` est vrai
(`names.go:187`), et `Library.Create` l'appelle (`library.go:229`). Ajouter
`.docx` à `IsNote` — ce que l'étape « listing » exige — fait qu'une note créée
sous le nom « rapport.docx » devient un `.docx` **contenant du Markdown**.

La quatrième question était donc restée cachée derrière les trois autres :

- `IsNote` — « faut-il l'afficher dans la liste ? » ;
- `IsMarkdown` — « faut-il l'interpréter ? » ;
- `IsDocument` — « faut-il l'analyser et interdire la saisie ? » ;
- **« ce nom désigne-t-il un format que l'application sait écrire ? »** — c'est
  la condition de `WithExtension`, et elle ne peut plus être `IsNote`.

### 3. `notes.Render` ne sait pas échouer

Signature actuelle : `Render(name string, content []byte) []markdown.Block`.
Un ZIP tronqué, un `document.xml` absent, une bombe de décompression : un
document échoue, et il faut le dire.

**Décision : changer la signature** en
`Render(name string, content []byte) ([]markdown.Block, error)` plutôt que
d'ajouter un second point d'entrée. Deux sites d'appel à corriger, et la règle
écrite dans le commentaire de la fonction reste vraie — le format se décide là
et nulle part ailleurs. Deux dispatchers, c'est deux endroits où l'on oublie
d'ajouter un format.

### 4. `RenderNoteJSON` est la porte d'entrée du binaire

`RenderNoteJSON(name, content string)` prend son contenu **de Kotlin**, en
`string`. C'est exactement le chemin par lequel un `.docx` traverserait la
frontière en UTF-8 mutilé — le piège n° 1, mais par la porte de service :
`EditorViewModel` a déjà `repository.renderNote(nom, contenu)` sous la main.

`RenderNoteJSON` doit **refuser un nom de document**, avec un code
`[UNSUPPORTED]`. Trois lignes, et un test.

### 5. La traduction n'est plus mono-langue

Depuis `6998061`, il y a `values-en/` et `values-es/` (158 clés chacune),
déclarées dans `res/xml/locales_config.xml`, et `MissingTranslation` est réglé
en **erreur** dans `build.gradle.kts`. Le plan d'origine disait « une clé de
plus dans `strings.xml` » ; c'est aujourd'hui **trois fichiers**, sous peine de
casser `./gradlew lintDebug`.

### Intendance

Le renommage de module (`opennote` → `github.com/ybediat/OpenNote`) est encore
non commité sur quinze fichiers. **À commiter avant de créer
`internal/documents`**, sinon le paquet neuf naît à cheval sur deux états et le
premier `git diff` est illisible.

---

## 4. Les sept lots

Les lots 0 à 4 sont entièrement sur desktop : ni NDK, ni émulateur, ni contrat
gelé touché avant le lot 4. C'est environ les trois quarts du travail.

### Lot 0 — les fixtures — **fait**

`scripts/fixtures-documents.ps1` écrit un HTML source et le fait convertir deux
fois par LibreOffice, en `.docx` puis en `.odt`. Les deux fixtures décrivent
alors le même contenu, ce qui rend possible le test le plus rentable du
chantier : `TestDocxEtOdtConvergent`.

Le lot ne s'arrêtait pas à la génération : il fallait **ouvrir le XML produit et
noter ce qu'il contient vraiment** — nom des styles de titre, forme des listes,
marquage de la ligne d'en-tête d'un tableau. Écrire l'analyseur sur une idée du
format, c'est le piège que ce lot existe pour éviter, et il a rapporté quatre
corrections. Le relevé est en section 6.

**Critère de sortie, atteint** : quatre fichiers dans
`internal/documents/testdata/`, un script qui les régénère, et la section 6 de
ce document remplie.

### Lot 1 — `internal/documents`, l'analyseur `.docx` — **fait**

Nouveau paquet, **indépendant d'Android comme tout ce qui est sous `internal/`**.

Ce qui a été écrit : `documents.go` (bornes, lecture d'archive, constructeur de
blocs en unités UTF-16, rognage, `niveauDepuisNom` — tout est partagé avec le
lot 2) et `docx.go` (les trois tables résolues avant la passe de contenu, puis
le parcours). Dix tests, dont cinq **vus échouer** en désactivant le garde-fou
qu'ils protègent : résolution de `styles.xml`, protection de mise en page,
lecture de l'en-tête de tableau, span de lien, rognage des bords.

Le souligné a été fait ici, avec ses quatre gestes : `markdown.StyleUnderline`,
`SpanStyleId.SOULIGNE`, le cas dans `MarkdownView.kt`, la ligne dans
`FACADE.md`. `markdown.ProtectLayout` a été exporté au passage — un analyseur
hors du paquet `markdown` n'a pas d'autre porte vers `protegerLaMiseEnPage`, et
la réécrire ailleurs, c'est se préparer à la voir diverger.

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

| OOXML | `markdown.Block` |
|---|---|
| `w:p` | `KindParagraph` |
| `w:pPr/w:pStyle` → `styles.xml` → `w:name` valant « Heading N » | `KindHeading` + `Level` — **le `styleId` est localisé**, et `w:outlineLvl` ment : section 6 |
| `w:numPr/w:ilvl` + `w:numId` → `numbering.xml` → `w:numFmt` | `KindBullet` ou `KindOrdered`, `Depth` = `w:ilvl` |
| `w:tbl` / `w:tr` / `w:tc` | `KindTableRow` + `Cells` |
| `w:r` avec `w:rPr/w:b`, `w:i`, `w:u`, `w:strike` | un `Span` par attribut |
| `w:hyperlink` (attribut `r:id`) | `Span` de style `link`, `Href` résolu par le `.rels` |

Le souligné se fait dans ce lot, pas après : voir la décision en section 5.

**Critère de sortie** : `go test ./internal/documents` au vert sur la fixture
réelle, sans qu'aucune ligne d'Android n'ait bougé.

### Lot 2 — le `.odt`, même paquet, même signature

```go
func Odt(data []byte) ([]markdown.Block, error)
```

Même principe : ZIP, mais le contenu est dans `content.xml`.

| ODF | `markdown.Block` |
|---|---|
| `text:p` | `KindParagraph` |
| `text:h` + `text:outline-level`, **ou** `text:p` dont le style parent est `Heading_20_N` | `KindHeading` + `Level` — le second cas n'est pas une curiosité : c'est ce que devient le titre de niveau 1, voir section 6 |
| `text:list` / `text:list-item`, puce ou numéro résolu par le `text:list-style` | `KindBullet` / `KindOrdered`, `Depth` compté en descendant l'arbre |
| `table:table` / `table:table-row` / `table:table-cell` | `KindTableRow` |
| `text:span` + `text:style-name` | un `Span`, **après résolution du style** — voir le piège n° 5 |
| `text:a` (`xlink:href`) | `Span` de style `link` |

**Critère de sortie** : `TestDocxEtOdtConvergent` passe. C'est lui qui attrape
les erreurs d'interprétation du modèle, celles qu'un test format par format ne
voit pas parce qu'il compare l'analyseur à lui-même.

### Lot 3 — la quatrième question, et les refus d'écriture

Tout en Go, tout testable sur desktop. C'est le lot qui protège les données.

1. `notes.IsDocument(name)` dans `names.go`, et `IsNote` qui l'inclut, pour que
   ces fichiers apparaissent dans le listing.
2. `WithExtension` recâblé sur « format que l'application sait écrire », plus
   sur `IsNote` — écart n° 2. `WithExtensionOf` (renommage) préserve déjà
   l'extension existante : rien à faire, mais un test le fige.
3. `DisplayName` garde l'extension d'un document, comme pour le `.txt` : un
   `rapport.docx` et un `rapport.md` cohabitent.
4. `notes.Render` gagne son `error` et sa branche document — écart n° 3.
5. **`App.WriteNote` et `App.PrepareEditJSON` refusent un chemin de document**,
   code `[READONLY]` — écart n° 1.
6. `RenderNoteJSON` refuse un nom de document, code `[UNSUPPORTED]` — écart
   n° 4.

**Critère de sortie** : un test qui prouve qu'on ne peut pas écrire sur un
`.docx` depuis la façade — **et qu'on a vu échouer** en désactivant le refus.

### Lot 4 — la frontière binaire

La seule étape qui touche `docs/FACADE.md`. Voir le piège n° 1 avant d'écrire
une ligne.

```go
// RenderFileJSON prépare l'affichage d'un fichier que l'application ne sait
// que lire. Le binaire ne franchit jamais la frontière.
func (a *App) RenderFileJSON(filePath string) (string, error)

// IsDocument indique un fichier lisible mais jamais modifiable.
func IsDocument(name string) bool
```

`RenderFileJSON` lit **à l'intérieur de Go**, depuis le cache puis le serveur,
exactement comme `ReadNote` — factorisez la partie commune dans un
`readBytes(notePath string) ([]byte, error)` privé plutôt que de la recopier,
sans quoi le repli hors connexion et `recentlyOffline()` divergeront.

`IsDocument` est une **fonction de paquet**, symétrique de `IsPlainText` déjà
exposée (`mobile/app.go:1203`) : c'est ce qui permet à Kotlin de savoir qu'un
fichier est un document **sans recopier la moindre extension**.

Le titre ne demande aucune API neuve : `titleOf(nom, "")` retombe sur
`DisplayName`, qui garde l'extension. « rapport.docx » s'affiche en titre, ce
qui est honnête.

**Critère de sortie** : `go test ./mobile` au vert contre le faux serveur, plus
une ligne dans le tableau des méthodes de `FACADE.md` et une section décrivant
le format de sortie.

### Lot 5 — l'interface

1. `EditorViewModel.init` branche : si `repository.isDocument(nom)`, appeler
   `renderFile(chemin)` au lieu de `readNote` + `prepareEdit`, et poser
   `modifiable = false`, `apercu = true`.
2. Le bandeau de lecture seule dit aujourd'hui « élément trop long »
   (`apercu_lecture_seule`). Un document mérite sa phrase : **une clé de plus
   dans les trois `strings.xml`** — jamais un littéral dans le code.
3. `gomobile bind`, puis `assembleDebug`, `testDebugUnitTest`, `lintDebug`.

**Critère de sortie** : ça **compile**. Pas « testé » : il n'y a aucun test
instrumenté dans ce dépôt, et le dire autrement serait mentir.

### Lot 6 — l'appareil

Un `.docx` réel posé depuis l'interface web d'OpenCloud : il apparaît dans la
liste, s'ouvre en aperçu, ne propose pas de saisie, et se renomme sans changer
d'extension. C'est le seul lot qui prouve quelque chose sur l'interface.

---

## 5. Décisions prises

**Le souligné se fait dans le lot 1.** `markdown.Style` connaît `bold`,
`italic`, `strike`, `code`, `link`. Le souligné est absent du Markdown et
omniprésent dans un traitement de texte : le repousser, c'est livrer un
visualiseur qui perd une mise en forme sur quatre. Quatre gestes solidaires —
la constante Go, la constante `SpanStyleId` (`Dto.kt`), le cas dans
`MarkdownView.kt`, la ligne dans `FACADE.md`. `MarkdownView` ignore proprement
un style inconnu, donc un oubli côté Kotlin ne casse rien : **il fuit en
silence**, ce qui est pire, et c'est pourquoi les quatre vont ensemble.

**Deux bornes, deux codes.** 8 Mo de XML décompressé (`[DOC_TOO_LARGE]`, levé
par un `io.LimitedReader`) et 20 Mo pour le fichier lui-même
(`[FILE_TOO_LARGE]`, levé avant de le charger). Les deux valeurs sont
arbitraires et généreuses ; ce qui compte est qu'elles existent et qu'elles
soient nommées, parce que le fichier vient d'un serveur partagé.

**Les deux formats d'affilée, `.docx` puis `.odt`.** Le test de convergence
n'existe qu'à partir du lot 2, et c'est le seul qui compare les analyseurs à
autre chose qu'à eux-mêmes.

---

## 6. Fixtures

`internal/documents/testdata/`, sur le modèle de `internal/opencloud/testdata/`
dont les réponses viennent d'un vrai serveur : **les fixtures de ce dépôt
viennent d'outils réels, pas d'un XML écrit à la main.** Un XML écrit à la main
testerait votre idée du format, pas le format.

LibreOffice est installé sur cette machine
(`C:\Program Files\LibreOffice\program\soffice.exe`), donc les fixtures sont
non seulement réelles mais **régénérables** :
`scripts/fixtures-documents.ps1` écrit l'HTML source, appelle deux fois la
conversion, et range le résultat.

```powershell
pwsh scripts/fixtures-documents.ps1          # régénère les quatre fixtures
```

Quatre fichiers, deux documents :

- `exemple.docx` / `exemple.odt` — la vitrine de structure : six niveaux de
  titre, gras, italique, souligné, barré, liste à puces, liste numérotée
  imbriquée, tableau à en-tête, lien hypertexte, et un paragraphe avec des
  espaces multiples pour éprouver `xml:space` ;
- `mot-long.docx` / `mot-long.odt` — un paragraphe portant un mot de 5 000
  caractères sans une seule espace. C'est la fixture du piège n° 3 : sans
  `ShortenLongWords`, ce document tue l'application sur l'appareil.

Trois précautions, chacune payée une fois :

- **Appeler `soffice.com`, pas `soffice.exe`.** Sur Windows, le `.exe` se
  détache immédiatement : le script croit avoir converti alors que rien n'est
  écrit. Le `.com` est la variante console, qui bloque jusqu'à la fin.
- **Isoler le profil** avec `-env:UserInstallation=file:///…`. Sans ça, la
  conversion échoue sans un mot si une fenêtre LibreOffice est déjà ouverte.
- **Ne pas convertir depuis le `.docx` vers le `.odt`.** Les deux fixtures
  partent du **même HTML**, chacune par sa propre conversion. Enchaîner les
  deux ferait passer une erreur d'import pour une convergence.

### Ce que les fixtures contiennent réellement

Relevé fait sur le XML produit, jamais sur la spécification. Quatre de ces
points contredisent le plan d'origine.

**Les titres du `.docx` portent des noms de style français.** `w:pStyle` vaut
`Titre1`…`Titre6`, pas `Heading1` : LibreOffice écrit le `styleId` dans la
langue de son interface. Un analyseur qui ne reconnaît que `Heading%d` rendrait
tous les titres en paragraphes, sans le moindre message.

La résolution correcte passe par `word/styles.xml`, qui porte le **nom canonique
anglais** à côté du `styleId` localisé :

```xml
<w:style w:styleId="Titre1"><w:name w:val="Heading 1"/>…
```

Une première passe sur `styles.xml` construit `styleId → w:name`, et
« Heading N » donne le niveau. Un `.docx` de Word passe par le même chemin, son
`styleId` valant déjà `Heading1`.

**Ne vous fiez pas à `w:outlineLvl`.** Il est présent sur `Titre2`…`Titre6`
avec les valeurs 1 à 5 — il est 0-based, donc décalé — et **absent de
`Titre1`**. Un analyseur qui le lit rend un document dont tous les titres sont
d'un niveau trop bas, sauf le premier qui disparaît. Le genre de défaut qui
passe la relecture.

**Les listes du `.docx` ont bien leur `w:numPr`**, avec `w:ilvl` et `w:numId`.
Les six éléments de la fixture donnent `ilvl` = 0, 0, 0, 1, 1, 0 : la profondeur
se lit là, directement.

**Mais rien dans `document.xml` ne dit si une liste est à puces ou numérotée.**
Le style de paragraphe est `Corpsdetexte` dans les deux cas. Il faut donc
résoudre `word/numbering.xml`, contrairement à ce que disait le plan d'origine.
La bonne nouvelle est que la résolution utile est minuscule :

```
w:num[@w:numId] → w:abstractNumId → w:abstractNum/w:lvl[@w:ilvl]/w:numFmt
```

soit, sur la fixture : `numId 1 → bullet`, `numId 2 → decimal`. C'est **ce
map-là** qu'il faut, et rien de plus : ni `w:lvlOverride`, ni redéfinition de
niveau, ni numérotation continue. Le `Number` affiché reste compté par nous.

**La ligne d'en-tête de tableau est marquée des deux côtés.** `w:tblHeader`
côté OOXML, `table:table-header-rows` côté ODF : le piège n° 7 se referme mieux
que prévu, il n'y a aucune heuristique à inventer. Les cellules portent en prime
un style de paragraphe qui confirme (`Titredetableau`, `Table_20_Heading`).

**`xml:space="preserve"` est posé douze fois** dans un `document.xml` de dix Ko :
le lire n'est pas optionnel. À noter au passage, l'import HTML laisse une espace
finale sur beaucoup de paragraphes (« Premiere puce  »). Le texte d'un bloc
mérite donc un `TrimSpace` **une fois assemblé** — jamais run par run, ce qui
recollerait les mots.

**Les liens.** Côté OOXML, `w:hyperlink r:id="rId2"` et la cible dans
`word/_rels/document.xml.rels`, comme annoncé. Côté ODF, `text:a xlink:href` en
clair : aucune indirection.

**Le souligné et le barré.** `<w:u w:val="single"/>` et `<w:strike/>` côté
OOXML ; `style:text-underline-style="solid"` et
`style:text-line-through-style="solid"` côté ODF, dans les styles automatiques
`T1`…`T4` — l'indirection du piège n° 5, confirmée telle quelle et limitée à
quatre entrées. Une règle du format que la fixture ne montre pas, mais qui vous
attend sur un document réel : en OOXML, `w:val="none"` ou `"0"` **désactive** la
propriété. Lire la valeur quand elle est là, ne pas se contenter de la balise.

**Le `<h1>` de l'ODT n'est pas un `text:h`.** C'est la découverte la plus
coûteuse du lot :

```xml
<text:p text:style-name="P1">Titre de niveau 1</text:p>
<style:style style:name="P1" style:parent-style-name="Heading_20_1" …/>
```

Les niveaux 2 à 6 sont bien des `<text:h text:outline-level="…">`, mais le
premier reste un paragraphe dont le **style parent** est `Heading_20_1`.
L'analyseur ODF doit donc regarder les deux : l'élément `text:h` d'abord, à
défaut le `style:parent-style-name` du paragraphe. Sans ça, le titre principal
de tout document converti disparaît en paragraphe — et le test de convergence
échoue sur un écart qui n'est la faute d'aucun des deux analyseurs.

**Puce ou numérotée, côté ODF, se résout aussi par le style** :
`text:list style:name="L1"` renvoie à un `text:list-style` contenant
`text:list-level-style-bullet` ou `text:list-level-style-number`. Symétrique du
`numbering.xml`. L'imbrication, elle, est structurelle — un `text:list` dans un
`text:list-item` — donc `Depth` se compte en descendant.

**Le mot de 5 000 caractères traverse les deux conversions intact** : c'est le
plus long mot du XML dans les deux fichiers. La fixture `mot-long` teste donc
bien ce qu'elle prétend tester.

---

## 7. Les pièges, chacun payé une fois

### 1. Un binaire ne traverse pas gomobile dans une chaîne

`App.ReadNote` renvoie une `string` Go, que gomobile décode en UTF-8 vers un
`String` Java. Un `.docx` en ressortirait truffé de caractères de remplacement,
irrécupérable. **C'est la raison d'être du lot 4** : le fichier est lu,
décompressé et analysé du côté Go, et seuls des blocs franchissent la frontière.

Si vous vous surprenez à vouloir un `[]byte` côté Kotlin, arrêtez-vous : la
réponse est de déplacer le traitement en Go, pas d'élargir le contrat.

### 2. Bornez la taille décompressée

Une archive de quelques kilo-octets peut se décompresser en gigaoctets. Lisez
`word/document.xml` à travers un `io.LimitedReader` et renoncez au-delà de la
borne de la section 5, avec un code entre crochets comme le reste du cœur.

### 3. Appliquez `markdown.ShortenLongWords` à vos blocs

Dans `internal/markdown`, tous les blocs passent par `protegerLaMiseEnPage`
avant d'être publiés (`render.go:260`). **Votre paquet ne bénéficie pas de cet
entonnoir.**

Sans lui, un document contenant une suite de caractères sans espace démesurée
fait tuer l'application par le système — mort de processus muette, sans
exception Java, suivie d'une purge mémoire de tout le téléphone. C'est constaté
sur appareil, pas déduit : voir « Le piège du mot sans espace » dans
`CLAUDE.md`. Un `Text` et un `TextField` partagent le même moteur de mise en
page ; la lecture seule ne protège de rien toute seule.

Passez chaque bloc produit — texte **et** cellules — par
`markdown.ShortenLongWords`. La fixture `mot-long` existe pour ça.

### 4. `.docx` : résolvez `numbering.xml`, mais à peine

Le plan d'origine disait « ne le résolvez pas ». Le relevé du lot 0 montre que
c'est intenable : `document.xml` ne dit **nulle part** si une liste est à puces
ou numérotée, et le style de paragraphe est le même dans les deux cas.

Ce qu'il faut, et rien de plus, c'est le map
`numId → abstractNumId → w:lvl[@ilvl]/w:numFmt`, soit une passe et une table.
Ce qu'il ne faut surtout pas, c'est la suite : `w:lvlOverride`, redéfinitions de
niveau, numérotation continue entre listes. Pour un visualiseur, **comptez les
éléments vous-même** et remplissez `Number` : le résultat est le même à l'écran
pour une fraction du travail.

`w:ilvl` suffit pour `Depth`.

### 5. `.odt` : les styles sont indirects

Là où OOXML pose le gras dans le `w:rPr` du run, l'ODF pose un
`text:style-name` qui renvoie à `office:automatic-styles`, plus haut dans le
même fichier. Il faut donc **une première passe** qui construit la table
`nom de style → gras/italique/souligné/barré`, avant la passe de contenu. Ne
tentez pas de tout faire en un seul parcours.

### 6. `w:t` et `xml:space="preserve"`

Les espaces significatives d'un run sont marquées par cet attribut. Les ignorer
recolle les mots. `encoding/xml` vous donne l'attribut, encore faut-il le lire.

### 7. L'en-tête de tableau se lit — ne l'inventez pas

`markdown.Block` a un `Header bool`, alimenté sans ambiguïté en Markdown. La
crainte était de devoir le deviner : elle est levée. Le lot 0 a constaté
`w:trPr/w:tblHeader` dans le `.docx` et `table:table-header-rows` dans l'`.odt`.

Résistez donc à l'heuristique « la première ligne est l'en-tête » : elle
marcherait sur la fixture et mentirait sur le premier tableau sans en-tête venu.
Un producteur qui ne pose pas le marquage donne un tableau sans en-tête, ce qui
est la bonne réponse.

### 8. Le `.aar` n'est pas régénéré par Gradle

Toute fonction exportée ajoutée dans `mobile/` exige de relancer `gomobile bind`
à la main. Sinon Kotlin compile contre l'ancien binding et se plaint d'une
référence introuvable sur un symbole que vous venez d'écrire — le symptôme ne
désigne pas sa cause. Les variables d'environnement sont dans `CLAUDE.md`,
section « Ce que l'outillage ne dit pas ».

### 9. Écrire les fichiers en LF

Le dépôt est en LF alors que `core.autocrlf` est à `true`. Un outil d'édition
qui traduit les fins de ligne signale le fichier **entier** comme mal formaté à
`gofmt -l`, pour une cause invisible. Les `.ps1` de `scripts/` sont en LF **avec
BOM UTF-8** — PowerShell 5.1 lit mal les accents sans lui.

### 10. Une clé de `strings.xml` est trois fichiers

`values/`, `values-en/`, `values-es/`. `MissingTranslation` est une erreur de
lint. Voir l'écart n° 5.

---

## 8. Discipline attendue

Elle est décrite dans `CLAUDE.md` et s'applique intégralement. Les deux points
qui comptent le plus ici :

- **Un test qui passe ne prouve rien tant qu'on ne l'a pas vu échouer.** Cela
  vaut d'abord pour les refus d'écriture du lot 3 : désactivez le refus,
  vérifiez que le test échoue en écrasant le document, remettez-le.
- **Distinguer « écrit », « compile » et « testé »** dans tout rapport
  d'avancement. Le cœur Go se teste ; l'interface Compose n'a aucun test
  instrumenté, et ce qui n'a pas tourné sur un appareil doit être annoncé comme
  tel.

Avant de conclure : `go vet ./... && gofmt -l .`, `go test ./... -short`, puis
`gomobile bind` et `cd android && ./gradlew assembleDebug testDebugUnitTest`.
Ce dernier fait tourner `ChainesEnDurTest` : **n'ajoutez jamais un fichier neuf
à `ECRANS_A_MIGRER`**, cette liste ne décrit qu'une dette existante.
