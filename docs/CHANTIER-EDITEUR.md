# Chantier — l'éditeur virtualisé

Ordre de travail pour un agent qui reprend le sujet à froid. Lire d'abord
`CLAUDE.md`, puis **la section 7 bis de `docs/ARCHITECTURE.md`** — elle porte
les mesures qui justifient tout ce document — et `docs/FACADE.md` pour le
contrat gelé.

Rien de ce chantier n'a été écrit. Les mesures, elles, ont été prises sur
appareil : ne les refaites pas, servez-vous-en.

---

## 1. Le problème, en trois chiffres

L'éditeur pose **tout** le document dans un seul `TextField`. Compose
ré-enregistre alors la display list entière à chaque image, à 0,14–0,24 ms par
ligne, y compris pour les lignes hors écran.

| | mesuré |
|---|---|
| budget d'une image | 16 ms |
| dessin à 76 lignes (9 ko) | 18,2 ms — **déjà dépassé** |
| frappe à 1 633 lignes (205 ko) | **750 ms par caractère** |

Ce n'est ni la mise en page — 0,11 ms dans toutes les conditions — ni la
mémoire. C'est la phase de dessin, et elle est proportionnelle à la taille du
document au lieu de l'être à ce qui est visible.

**Objectif du chantier :** qu'aucun champ de saisie ne contienne plus d'un
écran de texte, quel que soit le document.

---

## 2. Ce qu'on veut, et ce qu'on ne veut pas

**On veut** que l'éditeur reste un écran de saisie continu qu'on fait défiler,
mais dont le coût de dessin ne dépende plus de la taille de la note.

**On ne veut pas**, et ce n'est pas négociable :

| Hors périmètre | Pourquoi |
|---|---|
| `BasicTextField` + `TextFieldState` comme remède | **Mesuré inutile.** La fenêtre mesure+layout tient 0,11 ms de 445 octets à 295 ko. L'aller-retour du `TextFieldValue` par le `StateFlow` n'est pas le coût. Peut se faire un jour pour la latence de frappe, jamais au titre de ce chantier. |
| Un champ de saisie en hauteur libre | **Essayé, plante.** `Can't represent a width of 1058 and height of 531251 in Constraints`. Compose plafonne la hauteur à 262 143 px, soit ~1 300 lignes. Le commentaire de `EditorScreen.kt` le rappelle sur place. |
| Un découpage aux titres | La note de test ne contient **aucun** titre ATX. Le repli par paragraphes serait le cas nominal, pas le cas limite : autant le prendre comme règle unique. |
| Un WebView, CodeMirror, ProseMirror | Virtualiserait gratuitement, et sortirait tout le modèle d'édition de Go pour le mettre en JavaScript. `CLAUDE.md` argumente déjà contre le WebView pour l'aperçu ; l'argument vaut double ici. |
| Un éditeur par blocs complet | Fusion au retour arrière en tête de bloc, scission à l'entrée, sélection inter-blocs, annulation transverse. Ce sont des règles qui méritent des tests, et elles vivraient en Compose, la seule couche du dépôt sans test de comportement. **Version 2, si le besoin se confirme.** |
| Recherche/remplacement sur tout le document | N'existe pas aujourd'hui. Ne l'inventez pas ici. |
| Un seuil, deux éditeurs | Voir §3 : le montage proposé **dégénère de lui-même** vers l'éditeur actuel quand la note est courte. Deux chemins de code seraient une dette pour rien. |

---

## 3. Le montage retenu

Une `LazyColumn` de **sections**. Chaque section est rendue en lecture seule par
le chemin de l'aperçu, qui existe et dessine en 1,77 ms sur la note de 295 ko.
La section touchée — **et elle seule** — devient un `TextField`.

```
document (une String Kotlin, source unique de vérité)
   │
   ├── markdown.Sections → [ (début, fin), (début, fin), … ]   unités UTF-16
   │
   └── LazyColumn
         ├── section 0   → blocs rendus, en lecture seule
         ├── section 1   → TextField  ← la seule qui a le focus
         ├── section 2   → blocs rendus
         └── …
```

Trois propriétés en découlent, et ce sont elles qui rendent le montage bon
marché :

**Le document reste une seule `String` Kotlin.** Les sections ne sont que des
couples de bornes. Le chemin d'enregistrement ne change donc **pas du tout** :
`restoreImages` puis `writeNote` sur le texte entier, comme aujourd'hui. Tout ce
qui protège déjà les données — `enregistrable`, la restitution des images, la
détection de conflit — continue de s'appliquer sans qu'on y touche.

**Les bornes sont en unités UTF-16, donc `substring` est exact des deux côtés.**
Une `String` Kotlin est indexée en UTF-16 ; `Doc.Start` / `Doc.End` en Go aussi.
Le texte d'une section ne traverse jamais la frontière : Kotlin le découpe
lui-même. Le recollage est `texte.substring(0, début) + nouveau + texte.substring(fin)`.

**Une note courte donne une seule section.** La `LazyColumn` contient alors un
unique `TextField` portant tout le document : exactement l'éditeur
d'aujourd'hui. Pas de seuil à choisir, pas de bascule, pas de second chemin.

---

## 4. Ce qui existe déjà et qu'il faut réutiliser

**Ne réinventez rien de cette liste.**

| Brique | Où | Ce qu'elle vous donne |
|---|---|---|
| Rendu d'un texte en blocs | `markdown.Render` (`internal/markdown/render.go`) | Appelé sur le texte d'une section, il rend cette section. Rien à écrire. |
| Dessin des blocs | `VueMarkdown` (`ui/editor/MarkdownView.kt`) | Titres, listes, tâches, citations, tableaux, code. Déjà en `LazyColumn`. |
| Allègement des images | `PrepareEditJSON` / `RestoreImages` (`mobile/app.go`) | Le découpage se fait sur le texte **allégé**. Voir le piège n° 2. |
| Mise en forme | `ApplyFormatJSON`, `markdown.Doc` | Opère sur `(texte, début, fin)`. Passez-lui le texte de la **section**, pas le document. Aucune modification côté Go. |
| Garde d'enregistrement | `EditorUiState.enregistrable` | `charge && modifiable`. Le raisonnement qui l'a produit vaut toujours : ne l'affaiblissez pas. |
| Lecture seule | `preparedEdit.Editable`, `BandeauLectureSeule` | Une note au mot démesuré s'ouvre en aperçu. Ce chemin ne bouge pas. |
| Troncature d'affichage | `markdown.ShortenLongWords` | Appliquée à tous les blocs rendus. Elle protège aussi vos sections en lecture. |

---

## 5. Les quatre étapes, dans cet ordre

Les deux premières sont **entièrement testables sur desktop**, sans NDK ni
appareil. Ne commencez pas par la troisième.

### Étape 1 — `markdown.Sections` et le recollage, en Go

Nouveau fichier `internal/markdown/sections.go`.

```go
// Section est une tranche éditable du document, en unités de code UTF-16.
type Section struct {
    Start int
    End   int
}

// Sections découpe un texte en tranches qu'un champ de saisie peut porter.
func Sections(text string) []Section
```

Les règles, chacune avec son test :

1. **Les sections pavent le document exactement** : la première commence à 0, la
   fin de chacune est le début de la suivante, la dernière finit à la longueur
   du texte. Aucun trou, aucun recouvrement. Un texte vide donne une section
   vide, pas zéro section.
2. **Une section vise ~40 lignes et ne dépasse pas 80.** Le budget mesuré est de
   80 lignes pour 16 ms ; visez la moitié pour laisser de la marge au reste de
   l'image.
3. **On coupe sur une ligne vide**, jamais au milieu d'un paragraphe.
4. **On ne coupe jamais dans une clôture de code ni entre deux éléments d'une
   même liste.** Chaque section est rendue *seule* par `markdown.Render` :
   couper une liste en deux la ferait redémarrer à 1, et couper une clôture
   ferait interpréter du code comme du Markdown.
5. **La règle 4 gagne contre la règle 2.** Une liste de 500 lignes donne une
   section de 500 lignes. Elle sera lente — c'est correct et documenté, et c'est
   mieux qu'un rendu faux.

Le test qui compte, celui qui doit exister avant tout le reste :

```go
// Remplacer chaque section par son propre contenu rend le document identique.
func TestSectionsAllerRetour(t *testing.T)
```

Faites-le échouer une fois avant de le croire — voir §7.

### Étape 2 — la façade

Une seule fonction à ajouter, et **le texte ne la traverse pas** :

```go
// SectionsJSON renvoie le découpage éditable et les blocs de chaque section.
func (a *App) SectionsJSON(name, content string) (string, error)
```

Elle rend `[{"start":…, "end":…, "blocks":[…]}]`, où `blocks` est le format déjà
décrit dans `docs/FACADE.md` pour `RenderNoteJSON`. Kotlin découpe le texte
lui-même à partir des bornes.

Deux gestes obligatoires : une ligne dans `docs/FACADE.md`, et **relancer
`gomobile bind`** — Gradle ne régénère pas le `.aar`.

`mobile/gomobile_test.go` vérifie les contraintes de types : la structure JSON
doit rester **non exportée**.

### Étape 3 — l'écran

`EditorScreen` passe d'un `TextField` à une `LazyColumn` de sections. L'état
gagne la liste des sections et l'indice de celle qui a le focus ; le texte
complet reste dans `EditorUiState.valeur`.

**L'invariant du chantier :** au plus un `TextField` composé à la fois. Si vous
vous surprenez à laisser la section précédente en champ de saisie « pour éviter
un clignotement », vous venez de reconstruire la chose lente.

Au commit d'une section : recoller, redemander `SectionsJSON`, replanifier
l'enregistrement différé existant. Rien d'autre.

### Étape 4 — vérifier au banc

Le chantier n'est pas fini tant que la mesure n'est pas refaite. Critère
d'acceptation, sur la note de 295 ko et le même geste qu'en section 7 bis :

| grandeur | avant | attendu après |
|---|---|---|
| dessin moyen par image | 360,8 ms | ≤ 16 ms |
| médiane par image | 500 ms | ≤ 20 ms |
| frappe, médiane par image (205 ko) | 750 ms | ≤ 20 ms |

Le banc, réseau coupé pour que la synchronisation n'entre pas dans la mesure :

```bash
adb shell dumpsys gfxinfo eu.opennote.debug reset
for i in 1 2 3 4 5 6; do adb shell input swipe 540 700 540 1700 250; done
adb shell dumpsys gfxinfo eu.opennote.debug framestats
```

Les colonnes qui décident sont `PerformTraversalsStart → DrawStart`
(mesure + layout) et `DrawStart → SyncQueued` (enregistrement de la display
list). **Vérifiez l'écran avant et après chaque mesure** — voir le piège n° 8.

---

## 6. Les pièges, chacun payé une fois

### 1. Les bornes sont en unités UTF-16

Ni octets, ni runes. `é` : 2 octets, 1 rune, 1 unité. `😀` : 4, 1, 2. C'est
l'unité de `TextRange` dans Compose et celle de `markdown.Doc` ; c'est ce qui
permet à Kotlin de découper avec `substring` sans conversion. Un découpage
calculé en octets se décalerait dès la première lettre accentuée.

### 2. Découper le texte **allégé**, jamais le texte brut

`PrepareEdit` sort les données en ligne `data:` et les remplace par des jetons.
Découper avant cette étape mettrait un pavé de plusieurs mégaoctets sans une
seule espace dans une section — et ferait tuer l'application par le système,
exactement le défaut que `ExtractInlineData` a été écrit pour corriger.

Et **ne jamais écrire sans restituer** : c'est le seul chemin du dépôt qui peut
détruire des données en silence. Le chemin d'enregistrement ne change pas, donc
`restoreImages` reste où il est — vérifiez seulement que vous ne l'avez pas
contourné.

### 3. Le document est la source de vérité, pas les sections

Gardez le texte complet dans l'état et recollez dedans. Ne reconstruisez jamais
le document en concaténant des sections gardées séparément : la première
divergence entre les deux représentations serait invisible et partirait sur le
serveur.

### 4. Les bornes périment dès qu'on écrit

Après un recollage, toutes les sections suivantes sont décalées. Redemandez le
découpage. Décaler les bornes à la main par la différence de longueur est une
optimisation ; mesurez d'abord qu'elle est nécessaire.

### 5. Une section se rend seule

`markdown.Render` sera appelé sur le texte d'une section, sans le contexte
autour. D'où la règle 4 de l'étape 1. Le test à écrire : la concaténation des
blocs de toutes les sections doit être égale aux blocs du document entier.

### 6. La barre d'outils opère sur la section

`ApplyFormatJSON` reçoit `(texte de la section, début, fin)` avec des bornes
relatives à la section. Lui passer le document entier annulerait tout le
chantier : il recopierait 295 ko à chaque appui sur « gras ».

### 7. Le `.aar` n'est pas régénéré par Gradle

Toute fonction exportée ajoutée dans `mobile/` exige `gomobile bind` à la main.
Sinon Kotlin compile contre l'ancien binding et se plaint d'une référence
introuvable sur un symbole que vous venez d'écrire — le symptôme ne désigne pas
sa cause.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk"
$env:ANDROID_NDK_HOME = "$env:ANDROID_HOME/ndk/30.0.16138531"
```

### 8. Au banc, un tap perdu ne se voit pas

Le thread UI reste bloqué assez longtemps pour que l'appui soit avalé. On mesure
alors le défilement de la **liste** au lieu de celui de l'éditeur, et le chiffre
obtenu est parfaitement plausible — c'est arrivé, et la première série de
mesures était fausse. Vérifiez l'écran par `uiautomator dump` et un marqueur
exclusif avant et après chaque mesure. Se fier au processus ne suffit pas : MIUI
le garde en vie alors que l'application est en arrière-plan.

### 9. Écrire les fichiers en LF

Le dépôt est en LF alors que `core.autocrlf` est à `true`. Un outil qui traduit
les fins de ligne produit du CRLF, et `gofmt -l` signale alors le fichier
**entier** comme mal formaté.

### 10. `ChainesEnDurTest`

Le nouvel écran doit passer par `stringResource`, et **ne doit jamais être
ajouté à `ECRANS_A_MIGRER`** : cette liste ne décrit qu'une dette existante. Pas
de `Context` dans un ViewModel ; un texte émis par un ViewModel est un
`ui.common.Texte`, rédigé par le composable.

---

## 7. Discipline attendue

Celle de `CLAUDE.md`, intégralement. Les trois points qui comptent ici :

- **Un test qui passe ne prouve rien tant qu'on ne l'a pas vu échouer.** Vaut
  aussi pour une mesure : avant de conclure que le chantier a marché, remettez
  le montage d'avant et vérifiez que le banc redonne 360 ms.
- **Distinguer « écrit », « compile » et « testé ».** Le découpage Go se teste ;
  l'écran Compose n'a aucun test instrumenté, et ce qui n'a pas tourné sur un
  appareil doit être annoncé comme tel.
- **Un chiffre de performance sans protocole ne vaut rien.** Même appareil, même
  geste, même note, réseau coupé. Sinon vous comparez deux choses différentes.

Avant de conclure : `go vet ./... && gofmt -l .`, `go test ./... -short`, puis
`gomobile bind` et `cd android && ./gradlew assembleDebug testDebugUnitTest`.
