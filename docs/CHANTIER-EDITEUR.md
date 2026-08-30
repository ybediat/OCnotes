# Chantier — l'éditeur virtualisé

Ordre de travail pour reprendre le sujet à froid. Lire d'abord `CLAUDE.md`, puis
**la section 7 bis de `docs/ARCHITECTURE.md`**, qui porte les mesures, et
`docs/FACADE.md`, qui décrit la frontière Go ↔ Kotlin.

**État au 30 août 2026 :** le diagnostic, le découpage Go et la façade existent
et sont testés. Le modèle d'état Kotlin est amorcé, mais l'écran utilise encore
le `TextField` monolithique : aucun gain de performance n'est livré à ce stade.

Ce document a été révisé avant le prototype Compose. Le premier montage rendait
les sections inactives comme l'aperçu Markdown. La révision retient du **texte
source brut** dans l'éditeur et garde l'aperçu rendu comme mode séparé.

---

## 1. Le problème, mesuré

L'éditeur pose tout le document dans un seul `TextField`. Compose ré-enregistre
alors sa display list entière à chaque image, y compris pour les lignes hors
écran.

| Grandeur | Mesure |
|---|---:|
| Budget d'une image | 16 ms |
| Dessin au repos, curseur clignotant, note de 295 ko | 550 ms, deux fois par seconde |
| Dessin à 76 lignes, note de 9 ko | 18,2 ms — budget déjà dépassé |
| Frappe à 1 633 lignes, note de 205 ko | 750 ms par caractère |

La phase mesure + layout reste à 0,11 ms. Le coût vient de l'enregistrement de
la display list et suit la quantité de texte portée par le champ. Changer
simplement d'API de saisie ne traite donc pas le défaut.

**Objectif :** le coût de dessin d'un champ de saisie ne doit plus dépendre de
la taille du document. Un seul champ est composé, et son contenu reste sous une
borne mesurée, même pour une note faite d'un unique paragraphe.

---

## 2. Décision révisée

### 2.1 L'éditeur affiche la source, l'aperçu affiche le rendu

L'éditeur devient une `LazyColumn` de tranches de **texte Markdown brut**. Les
tranches inactives sont des `Text`, avec la même typographie et les mêmes
retraits que le champ actuel. Une seule fenêtre autour du curseur devient un
`TextField`.

```
document allégé — une String Kotlin
   │
   ├── tranches d'affichage, bornes UTF-16
   │
   └── LazyColumn
         ├── source inactive  → Text
         ├── fenêtre active   → TextField  ← le seul composé
         ├── source inactive  → Text
         └── …

mode aperçu séparé → RenderNoteJSON(document matérialisé) → VueMarkdown
```

Le rendu Markdown dans les tranches inactives a été écarté pour quatre raisons :

1. toucher un titre, une liste ou un lien ferait changer son apparence au
   moment précis où l'utilisateur veut y placer le curseur ;
2. un offset dans le texte rendu ne correspond pas à un offset dans sa source,
   ce qui rend le premier toucher imprécis ;
3. `VueMarkdown` contient déjà une `LazyColumn` et ne doit pas être imbriquée
   verticalement dans celle de l'éditeur ;
4. préserver le rendu de chaque tranche interdit de couper une liste, un bloc
   ou un paragraphe démesuré. La borne de performance ne serait donc pas dure.

Afficher la source retire la contrainte n° 4 : une tranche est une vue, jamais
un document Markdown autonome. Elle peut se terminer dans un paragraphe sans
changer un seul caractère de la note ni le rendu de l'aperçu complet.

### 2.2 Ce que « source de vérité » signifie pendant la frappe

Il existe une seule exception contrôlée au document complet : le brouillon de
la fenêtre active.

- `document` est l'instantané complet auquel les bornes courantes se rapportent ;
- `valeur` est le `TextFieldValue` de la fenêtre active ;
- `materialiser()` remplace dans `document` l'ancienne fenêtre par `valeur.text`.

Le contenu courant est donc toujours calculable par une seule opération :

```kotlin
document.substring(0, active.start) +
    valeur.text +
    document.substring(active.end)
```

Les tranches ne gardent jamais leur propre copie mutable. Le brouillon actif est
la seule surcouche et toute écriture, tout aperçu et toute sortie passent par
`materialiser()`.

### 2.3 Les invariants non négociables

1. **Au plus un `TextField` est composé.** Les autres tranches sont des `Text`.
2. **Le champ actif est borné.** La borne porte sur les retours à la ligne et
   sur la longueur UTF-16, afin qu'un paragraphe sans `\n` ne contourne pas la
   protection.
3. **Aucun redécoupage ni appel Go à chaque caractère.** La frappe ne modifie
   que `valeur`, la sélection et le drapeau `modifie`.
4. **Les bornes et `document` forment un même instantané.** Après matérialisation,
   elles sont recalculées avant d'activer une autre fenêtre.
5. **L'enregistrement conserve le chemin existant.** Texte matérialisé,
   `restoreImages`, puis `writeNote` ; `enregistrable` n'est jamais affaibli.
6. **L'aperçu rend le document entier matérialisé.** Les tranches d'édition ne
   deviennent jamais une nouvelle sémantique Markdown.

### 2.4 Ce qui reste hors périmètre

| Hors périmètre | Pourquoi |
|---|---|
| `BasicTextField` ou `TextFieldState` comme remède | Le coût mesuré est le dessin de tout le texte, pas l'aller-retour de `TextFieldValue`. Une autre API peut servir l'interaction du prototype, jamais tenir lieu de virtualisation. |
| Un champ de saisie en hauteur libre | Essayé : Compose refuse les contraintes au-delà de 262 143 px sur l'appareil de test. |
| Un découpage aux titres | La note réelle ne contient aucun titre ATX et un paragraphe unique doit rester borné. |
| WebView, CodeMirror ou ProseMirror | Ils déplaceraient le modèle d'édition hors de Go et Compose, dans une troisième pile JavaScript. |
| Recherche/remplacement global | Cette fonction n'existe pas aujourd'hui ; le chantier ne l'invente pas. |
| Plusieurs documents mutables cachés dans les tranches | Une tranche est une vue du document, jamais une unité persistée séparément. |

Le prototype doit cependant éprouver les comportements qui ressemblent à ceux
d'un éditeur par blocs — frontière, sélection transverse et annulation. Les
déclarer « hors périmètre » sans les essayer ferait seulement déplacer le défaut
dans l'usage.

---

## 3. Ce qui existe déjà

### 3.1 Découpage sémantique Go — fait et vérifié

`internal/markdown/sections.go` fournit `Sections`, `RenderSection`,
`RenderSections`, `SectionsPlain` et `Slice`. Les tests vérifient notamment :

- le pavage exact du document en unités UTF-16 ;
- le recollage sans perte ;
- l'identité du rendu Markdown section par section ;
- les définitions de lien en référence ;
- le découpage borné du texte brut.

Sur la note réelle de 295 ko, le chemin mesuré produit 67 sections. Cette
brique est correcte, mais elle répond au premier montage — rendre chaque
section comme du Markdown autonome — et n'est plus la condition du prototype
source brute.

**Ne pas la supprimer avant le prototype.** À sa sortie, décider explicitement
si elle reste utile ailleurs, si elle est simplifiée, ou si elle doit être
retirée avec ses appels. Ne pas conserver silencieusement un second découpage
inutilisé.

### 3.2 Façade et DTO — faits et vérifiés

`App.SectionsJSON`, `notes.Sections`, `SectionDto` et
`OpenNoteRepository.sections()` existent. La façade ne transporte pas le texte
des sections, seulement leurs bornes UTF-16 et leurs blocs.

Quatre tests de façade vérifient le format et le recollage exact. Là encore, la
charge `blocks` appartient au premier montage. Le prototype ne doit pas
l'utiliser juste parce qu'elle existe.

### 3.3 Écran — seulement amorcé

`EditorUiState` déclare déjà `document`, `sections` et `focus`, mais :

- le chargement ne remplit ni `document` ni `sections` ;
- `valeur` porte encore le document entier ;
- `EditorScreen` compose toujours un unique `TextField` monolithique ;
- l'aperçu et l'enregistrement lisent encore `valeur.text` comme document entier.

Le code compile, mais l'étape 3 n'est pas fonctionnelle et aucune mesure « après »
n'existe.

---

## 4. Prochaine étape — prototype Compose isolé

Le prototype vient **avant** le branchement de l'enregistrement, des images, de
la mise en forme et de l'aperçu. Il doit répondre aux questions d'interaction
sur un appareil, sans pouvoir écrire une note réelle.

**État : prototype écrit, compilé et lancé sur le Redmi Note 12.** Il vit
entièrement dans `android/app/src/debug/` et tourne dans le processus
`:prototype`. `OpenNoteDebugApplication` n'y initialise ni `AppContainer`, ni
repository, ni synchronisation ; `ps` confirme que le processus principal
`eu.opennote.debug` n'est pas démarré avec lui.

```powershell
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n `
  eu.opennote.debug/eu.opennote.ui.editor.prototype.EditeurPrototypeActivity
```

Trois jeux en mémoire sont accessibles en haut de l'écran : note Markdown de
295 333 unités UTF-16, paragraphe unique de 40 ko et document vide. Le premier
toucher active le champ et ouvre le clavier ; le document vide accepte la
saisie et le bouton de validation la rematérialise sans disque ni réseau.

### 4.1 Montage minimal

Construire un composable expérimental alimenté par une chaîne en mémoire :

1. une `LazyColumn` unique ;
2. des tranches inactives en `Text`, style `StyleEditeur` ;
3. une seule fenêtre active en `TextField` ;
4. `TextLayoutResult.getOffsetForPosition` pour traduire le premier toucher en
   offset UTF-16 dans la source affichée ;
5. un `FocusRequester` pour poser le curseur et ouvrir le clavier après ce même
   toucher ;
6. un cas explicite pour le document vide, dont la zone de saisie doit rester
   visible et touchable.

Ne pas utiliser `VueMarkdown` dans ce prototype. Ne pas ajouter un second
conteneur à défilement vertical.

### 4.2 Taille de fenêtre

Le prototype commence avec une borne conservatrice, définie en un seul endroit :

- au plus **12 retours à la ligne** ;
- au plus **640 unités UTF-16** ;
- la première borne atteinte gagne.

La coupure préfère, dans cet ordre, un retour à la ligne, une espace, puis la
borne dure. Elle ne sépare jamais les deux unités d'une paire de substitution.

Ces valeurs sont des paramètres de prototype, pas une nouvelle vérité. Elles
doivent être confrontées au banc. Le double critère est, lui, définitif : une
borne limitée aux `\n` laisserait un paragraphe replié visuellement produire des
centaines de lignes dans un seul champ.

Le premier essai à 32 retours / 2 000 unités a bien supprimé le coût
proportionnel aux 295 ko, mais la frappe restait à environ 42 ms de médiane sur
le Redmi Note 12. La borne ci-dessus est le second réglage, choisi pour ramener
le dessin sous le budget sans changer le montage. Ce chiffre reste indicatif
tant que le banc complet n'a pas été rejoué.

Avec 12 retours / 640 unités, la note synthétique donne 527 tranches. Six
balayages produisent 284 images : médiane 10 ms, 90e percentile 17 ms. Sur les
120 lignes `framestats` conservées, `DrawStart → SyncQueued` vaut 1,335 ms en
moyenne et 1,317 ms en médiane ; mesure + layout vaut 0,122 ms en moyenne.

Sur cinq caractères injectés par ADB, le dessin vaut 1,29 ms en moyenne et
1,401 ms en médiane, mesure + layout 0,388 ms. Le temps d'image global de ce
micro-échantillon reste pollué par `adb input` et ses délais d'entrée : il ne
remplace pas le geste manuel du banc. Le résultat utile à ce stade est que la
display list n'est plus proportionnelle aux 295 ko.

### 4.3 La question des frontières

Tester d'abord une fenêtre égale à la tranche touchée. Vérifier sur le clavier
logiciel réel :

- Retour arrière au début ;
- Suppr à la fin, si le clavier l'expose ;
- flèches et déplacement des poignées ;
- insertion ou suppression du séparateur entre deux paragraphes.
- sélection et copie sur une frontière ;
- annulation après avoir quitté puis retrouvé une fenêtre.

Si l'IME ne permet pas de franchir naturellement une frontière, ne pas empiler
des traitements de touches spécifiques au clavier. Le second montage à essayer
est une **fenêtre glissante centrée sur l'offset global du curseur**, toujours
bornée par le même budget total. Elle peut couvrir des portions de plusieurs
tranches inactives ; elle reste un seul `TextField`.

La variante retenue doit être décidée par le comportement observé, pas par la
facilité de son premier code.

### 4.4 Critères de sortie du prototype

Le prototype est concluant seulement si :

- un seul toucher active la saisie au caractère attendu ;
- l'apparence et la hauteur ne sautent pas sensiblement entre `Text` et
  `TextField` ;
- le clavier ne masque pas ou ne perd pas le curseur ;
- le défilement ne change pas brutalement de position à l'activation ;
- une note de 295 ko se parcourt sans champ portant tout le document ;
- un paragraphe de plusieurs dizaines de ko reste découpé ;
- le cas de frontière est soit naturel, soit résolu par la fenêtre glissante ;
- la sélection et l'annulation ont un comportement explicite et acceptable ;
- l'appareil confirme que la fenêtre choisie tient le budget de dessin.

Si franchir les frontières, sélectionner ou annuler exige finalement de bâtir
un éditeur par blocs complet dans Compose, le prototype doit conclure **arrêt et
réévaluation**, pas maquiller ce résultat pour passer à l'intégration.

À ce stade, rapporter séparément : « écrit », « compile », « essayé sur
appareil » et « mesuré ».

---

## 5. Après validation du prototype — intégration de production

### 5.1 Extraire une machine d'état pure

Les transitions ne doivent pas être enfouies dans les callbacks Compose. Les
représenter par des fonctions Kotlin sans Android, testables sur la JVM :

```kotlin
fun materialiser(etat: EditorUiState): String
fun activer(etat: EditorUiState, offsetGlobal: Int): EditorUiState
fun modifier(etat: EditorUiState, valeur: TextFieldValue): EditorUiState
fun committer(etat: EditorUiState): EditorUiState
```

Les noms finaux peuvent changer ; les responsabilités, non.

Tests minimaux :

1. remplacement plus court, plus long et vide ;
2. bornes avant, dans et après un emoji ;
3. activation au début et à la fin du document ;
4. changement de fenêtre après modification ;
5. document vide ;
6. paragraphe sans retour à la ligne au-delà de la borne ;
7. matérialisation utilisée pour sauvegarde, aperçu et sortie ;
8. sélection relative envoyée à `ApplyFormatJSON`, puis restaurée ;
9. résultat obsolète d'une opération asynchrone qui ne doit pas écraser un
   brouillon plus récent.

Voir un test de recollage échouer avant de lui faire confiance.

### 5.2 Brancher `EditorViewModel`

À l'ouverture d'une note modifiable :

1. `readNote` ;
2. `prepareEdit` et conservation des images hors de l'état ;
3. `document = prepare.text` ;
4. calcul des tranches d'affichage ;
5. `focus = -1`, sauf document vide où une fenêtre vide peut être activée ;
6. `valeur` ne reçoit du texte qu'à l'activation.

Pendant la frappe, `onValeurChangee` ne redécoupe rien. L'enregistrement
différé écrit `materialiser()`, puis restitue les images. Un simple mouvement de
curseur ne relance pas le minuteur.

Le commit et le recalcul des bornes ont lieu uniquement quand une représentation
cohérente du document est requise : changement de fenêtre, bascule vers
l'aperçu, opération structurelle sur les tranches ou fermeture. Une sauvegarde
différée peut matérialiser sans forcer une recomposition complète.

### 5.3 Brancher l'écran

- une seule `LazyColumn` verticale ;
- clés stables tant que l'instantané ne change pas ;
- `Text` et `TextField` avec largeur, style et padding identiques ;
- la barre de mise en forme opère sur `valeur.text` et des bornes relatives ;
- le mode aperçu continue d'utiliser `VueMarkdown` sur le document entier ;
- aucune chaîne utilisateur en dur, aucune entrée dans `ECRANS_A_MIGRER`.

### 5.4 Nettoyer le premier montage

Après validation et seulement après :

- relever les usages réels de `SectionsJSON`, `RenderSection`,
  `RenderSections`, `SectionDto.blocks` et `OpenNoteRepository.sections()` ;
- conserver ce qui sert avec un rôle documenté ;
- retirer ce qui n'a plus d'appel plutôt que de maintenir deux architectures ;
- si la façade change, mettre `docs/FACADE.md` et le `.aar` à jour dans le même
  geste.

---

## 6. Vérification finale au banc

Le chantier n'est pas fini tant que l'écran intégré a été mesuré sur le même
appareil, avec la même note, le même geste et le réseau coupé.

| Grandeur | Avant | Attendu après |
|---|---:|---:|
| Dessin moyen par image, note de 295 ko | 360,8 ms | ≤ 16 ms |
| Médiane par image | 500 ms | ≤ 20 ms |
| Frappe, médiane par image, note de 205 ko | 750 ms | ≤ 20 ms |

```powershell
./scripts/banc-editeur.ps1 -Note "scolarisation des enfants rrom"
./scripts/banc-editeur.ps1 -Note "une note jetable" -Frappe
```

Les colonnes décisives sont `PerformTraversalsStart → DrawStart` et
`DrawStart → SyncQueued`. Vérifier l'écran avant et après chaque mesure : un tap
perdu peut produire un chiffre plausible sur le mauvais écran.

Ajouter quatre cas de sûreté au corpus :

- Markdown d'un seul paragraphe très long ;
- liste ou bloc de code de plus de 500 lignes ;
- texte avec accents, emoji et images en ligne allégées ;
- gros fichier `.txt`.

La mesure de performance ne remplace pas la preuve de données : après chaque
cas modifié, relire le fichier écrit et vérifier l'aller-retour exact, images
comprises.

---

## 7. Pièges à ne pas rouvrir

### UTF-16 partout

Compose, `String.substring`, `TextRange` et les bornes Go utilisent les unités
UTF-16. `é` en vaut une, `😀` deux. Une coupure dure doit reconnaître les paires
de substitution.

### Découper le texte allégé

`PrepareEdit` retire les données `data:` avant toute fenêtre de saisie. Écrire
sans `restoreImages` détruirait silencieusement les images de la note.

### Instantané et brouillon ne se mélangent pas

Les bornes découpent `document`, jamais une chaîne déjà matérialisée avec des
bornes anciennes. Soit on garde le brouillon en surcouche, soit on committe et
on recalcule ; aucun état intermédiaire n'est licite.

### Une tranche inactive n'est pas du Markdown autonome

Ne pas appeler `markdown.Render` ou `VueMarkdown` pour l'afficher. Elle montre
la source exacte. Le rendu sémantique reste celui du document entier en aperçu.

### La mise en forme travaille dans la fenêtre

`ApplyFormatJSON` reçoit `valeur.text` et la sélection relative. Lui passer le
document entier réintroduirait une copie de centaines de ko à chaque action.

### Le premier toucher doit porter le curseur

Remplacer un `Text` par un `TextField` sans exploiter son `TextLayoutResult`
imposerait deux touchers : un pour activer, un pour placer le curseur. Ce n'est
pas un détail à repousser après le prototype.

### Les retours à la ligne ne suffisent pas

Une seule ligne source peut produire des centaines de lignes visuelles. Toute
borne d'édition porte donc aussi sur la longueur UTF-16 et autorise une coupure
de vue dans un paragraphe.

### Ne pas imbriquer deux listes verticales

`VueMarkdown` possède sa propre `LazyColumn`. L'éditeur en possède une autre ;
elles ne se composent jamais l'une dans l'autre.

### Le `.aar` n'est pas régénéré par Gradle

Toute modification de la façade exportée impose `gomobile bind` avant le build
Kotlin.

### Localisation et fins de ligne

Tout texte UI passe par `stringResource` ou `Texte`. Les trois `strings.xml`
restent synchronisés. Les sources sont écrites en LF ; les scripts PowerShell
accentués gardent leur BOM UTF-8.

---

## 8. Discipline de sortie

- Un test vert n'est crédible qu'après avoir été vu échouer avec le symptôme
  attendu.
- Un prototype fluide n'est pas une intégration sûre : distinguer interface,
  sauvegarde, restitution des images et synchronisation.
- Un chiffre sans protocole comparable n'est pas une mesure.
- Une brique devenue sans usage après la révision est supprimée ou reçoit un
  rôle explicite ; elle ne reste pas « au cas où ».

Avant de conclure l'intégration :

```text
go vet ./...
gofmt -l .
go test ./... -short
gomobile bind
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
```
