# Chantier — l'éditeur virtualisé

Ordre de travail pour reprendre le sujet à froid. Lire d'abord `CLAUDE.md`, puis
**la section 7 bis de `docs/ARCHITECTURE.md`**, qui porte les mesures, et
`docs/FACADE.md`, qui décrit la frontière Go ↔ Kotlin.

**État au 31 août 2026 : intégré dans l'éditeur de production.** Le champ
monolithique et le prototype debug ont été retirés. L'éditeur réel utilise une
`LazyColumn` de source brute et une seule fenêtre active ; sauvegarde, aperçu,
mise en forme et sortie passent tous par le document matérialisé.

Le premier retour sur appareil a conduit à réduire le contexte initial, aligner
ses bords sur les mots et conserver l'identité Compose du champ pendant son
rééquilibrage. Une sélection qui atteint un bord agrandit maintenant ce même
champ jusqu'à sa limite dure. L'ancienne voie de sections Markdown rendues côté
Go a été supprimée avec sa façade : il ne reste qu'un découpage d'édition.

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

## 3. État intégré

### 3.1 Découpage et fenêtre glissante

`DocumentEditeur.kt` porte la machine pure testée sur la JVM :

- tranches inactives bornées à 640 unités UTF-16 ou 12 retours à la ligne ;
- fenêtre initiale ciblée à 192 unités ou 4 retours, centrée sur le curseur ;
- bords avancés ou reculés jusqu'à un séparateur proche pour ne pas couper un
  mot en usage normal — **mais jamais au-delà des deux budgets** : l'alignement
  est un confort, la borne est une limite, et quand les deux s'opposent c'est la
  borne brute qui reprend la main ;
- marge entre cible et limite dure, afin de laisser plusieurs centaines de
  caractères de frappe avant un rééquilibrage ;
- agrandissement du champ actif jusqu'à 640 unités ou 12 retours lorsqu'une
  poignée de sélection atteint son bord, sans sélectionner automatiquement le
  contexte nouvellement chargé ;
- conservation exacte des caractères et des paires de substitution UTF-16.

Un mot de plus de 64 unités autour d'une borne autorise encore la coupure dure.
Ce cas ne parvient normalement pas à l'éditeur : `PrepareEditJSON` ouvre en
lecture seule les mots démesurés qui menacent le moteur de mise en page.

**Une fenêtre qui vient de naître ne doit jamais demander son rééquilibrage.**
C'est l'invariant que le montage et `doitReequilibrer` partagent, et il tenait
mal : l'alignement sur les mots pouvait rendre une fenêtre de 650 unités, et le
contexte de quatre retours s'ajoutait à une sélection qui en portait déjà douze,
pour un total de 17. Le symptôme n'était ni un plantage ni un ralentissement
visible, mais un **remontage à chaque déplacement du curseur** — donc un
historique d'annulation vidé sans que rien ne le dise.

Les deux budgets s'énoncent désormais une seule fois, sur un intervalle du
document, et le montage consulte exactement la règle que l'éditeur appliquera
ensuite. Les écrire deux fois, c'est laisser naître une fenêtre que le test
suivant refuse.

### 3.2 État, données et opérations

`EditorUiState.document` est l'instantané complet ; `valeur` est uniquement le
brouillon actif. Les transitions `activerFenetre`, `modifierFenetre` et
`materialiser` sont pures. Le `ViewModel` utilise le texte matérialisé pour :

- l'enregistrement différé et l'enregistrement de sortie ;
- `restoreImages`, puis `writeNote` ;
- le rendu de l'aperçu complet ;
- le changement de fenêtre.

La mise en forme travaille sur la fenêtre et sa sélection relative. Une
révision empêche un résultat asynchrone ancien d'écraser une frappe plus récente
ou de faire disparaître prématurément l'indicateur de brouillon.

### 3.3 Continuité du clavier

La ligne active garde la clé Compose constante `active` pendant le glissement.
Un rééquilibrage ne change plus le compteur `activation` : le
`BasicTextField`, son `FocusRequester` et la connexion IME sont conservés. Une
vraie activation par toucher incrémente toujours ce compteur et place le
curseur à l'offset obtenu par `TextLayoutResult.getOffsetForPosition`.

L'agrandissement pendant une sélection suit la même règle : la clé active et
le compteur restent stables. Les deux ancres sont converties en offsets globaux
avant le remontage, puis replacées relativement au champ agrandi, y compris
pour une sélection effectuée de droite à gauche.

La mise au point ne laisse plus le comportement Android par défaut recentrer
agressivement le champ. La liste applique une zone de confort : aucune demande
de défilement tant que le curseur reste dans les deux tiers supérieurs de la
hauteur disponible ; dans le dernier tiers, elle ne remonte que jusqu'à cette
frontière. Une demande portant sur toute la grande fenêtre éditable est ignorée
afin de ne pas faire sortir son début par le haut.

### 3.4 Nettoyage effectué

Le prototype sous `src/debug`, son processus spécial et son application debug
ont été retirés. `SectionsJSON`, `notes.Sections`, `SectionDto`,
`OpenNoteRepository.sections()` et l'ancien découpage sémantique
`internal/markdown/sections.go` ont également été retirés : ils appartenaient
au montage abandonné qui rendait chaque tranche comme un document Markdown.

### 3.5 Validation

- 24 tests JVM couvrent le pavage, les deux budgets, les emoji, les mots, la
  fenêtre glissante, la matérialisation, les changements de fenêtre, les
  sélections inversées, l'agrandissement aux deux bords, la composition IME et
  les quatre cas de défilement automatique ;
- cinq groupes de tests ont été vus échouer avant correction : sens de sélection perdu,
  rééquilibrage absent à la fin d'une composition, puis agrandissement absent
  aux bords gauche et droit, et enfin politique de défilement absente ;
- compilation debug, compilation release et lint passent ;
- l'APK intégrée a été installée sur le Redmi Note 12 ;
- **mesurée sur cet appareil le 31 août 2026** : défilement acquis et reproduit
  sur huit passes, frappe encore au-dessus de sa cible — section 6, relevés en
  7 bis de `docs/ARCHITECTURE.md` ;
- **quatre tests de plus** sur les deux budgets d'une fenêtre au montage, dont
  trois vus échouer sur le défaut réel — 650 unités et 17 retours — et le
  quatrième sur une violation injectée dans le chemin d'agrandissement ;
- **quatre tests de sûreté** sur les formes de texte de la section 6, tous vus
  échouer quand la matérialisation perd un caractère ;
- **quatre tests sur le toucher dans le vide**, dont un vu échouer quand on
  retire la condition « le dernier élément visible est aussi le dernier du
  document ». 51 tests JVM au total.

Distinguer ce que chaque ligne établit : les tests JVM disent que le découpage
est juste, la compilation qu'il tient, l'appareil qu'il s'affiche, et le banc
seul qu'il est rapide. Les quatre cas de sûreté et la relecture du fichier
écrit — section 6 — restent à faire ; le seul aller-retour vérifié à ce jour
l'a été incidemment, cinq caractères écrits puis retirés rendant un fichier
identique à l'octet près.

Le dernier contrôle manuel consiste à confirmer sur cette APK que le clavier
reste ouvert pendant un rééquilibrage réel, puis qu'une poignée peut continuer
son glissé après l'agrandissement sans saut visuel gênant.

### 3.6 Le vide sous la fin du document — corrigé

`EditeurVirtualise` ne compose que les tranches. Sous la dernière, la
`LazyColumn` n'a plus d'élément, donc plus rien qui reçoive un toucher. Arrivé
en bas d'une note, on touche sous la dernière ligne et il ne se passe rien : ni
curseur, ni clavier. Le champ monolithique, lui, occupait toute la hauteur et se
focalisait où qu'on tape.

Le correctif est un détecteur de toucher posé **au-dessus** de la liste, dans le
même `Modifier` : il ne voit que les gestes qu'aucune tranche n'a consommés, et
le défilement annule les siens. `toucheSousLeTexte` décide, et sa seconde
condition est celle qu'on oublie — il ne suffit pas d'être sous le dernier
élément *visible*, encore faut-il qu'il soit le dernier du *document*. Sans
elle, un toucher dans une marge au milieu d'une note de 295 ko enverrait le
curseur à la fin du fichier. Quatre tests JVM tiennent la règle, dont un vu
échouer quand on retire cette condition.

Vérifié sur appareil, note d'essai portant une image : défilé jusqu'à la fin,
touché dans le vide sous la dernière ligne, le clavier se lève et le caractère
tapé arrive bien à la fin du document. Le défilement d'une note de 295 ko et le
toucher sur le texte restent intacts.

**Une correction à ce sujet, parce qu'elle vaut plus que le correctif.** Ce
défaut avait d'abord été décrit comme « sur une note courte, la moitié basse de
l'écran est inerte », sur la foi d'un toucher resté sans effet à mi-hauteur. La
capture d'écran l'a démenti : le texte de cette note descendait jusqu'en bas, le
toucher tombait dessus, et son échec venait de l'injection ADB, capricieuse ce
jour-là — piège n° 6 du banc. Le défaut existait bel et bien, mais pas là où on
l'avait vu. Un symptôme observé une fois n'est pas un diagnostic.

### 3.7 Limite encore assumée : sélection globale

Ce palier rend la sélection transverse aux petits contextes initiaux, mais pas
encore au document entier : elle s'arrête à la fenêtre dure de 640 unités
UTF-16 ou 12 retours. L'action système « Tout sélectionner » ne voit elle aussi
que le texte porté par le `BasicTextField` actif.

Ce comportement est une étape, pas la cible définitive d'un logiciel de texte.
La prochaine expérimentation devra porter une sélection globale indépendante
du champ — notamment pour « Tout sélectionner », copier et supprimer — tout en
ne composant toujours qu'une petite fenêtre éditable. Le présent palier prépare
ce travail : les deux ancres ont déjà une représentation globale stable et la
matérialisation du document complet reste unique.

Le périmètre, les paliers et les conditions d'arrêt de cette expérimentation
sont décrits dans [CHANTIER-SELECTION-GLOBALE.md](CHANTIER-SELECTION-GLOBALE.md).

---

## Annexe — état antérieur à l'intégration

La suite est conservée comme journal de décision. Elle décrit le prototype et
les étapes qui ont mené à l'intégration ; **ce n'est plus un ordre de travail à
exécuter**.

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

**Fait le 31 août 2026.** Relevés complets en section 7 bis de
`docs/ARCHITECTURE.md`.

| Grandeur | Avant | Attendu | Mesuré |
|---|---:|---:|---:|
| Dessin moyen par image, note de 295 ko | 360,8 ms | ≤ 16 ms | **0,75 à 1,34 ms** (8 passes) ✅ |
| Médiane par image | 500 ms | ≤ 20 ms | **11 à 14 ms** (8 passes) ✅ |
| Frappe, dessin moyen | 424,7 ms (205 ko) | ≤ 16 ms | **4,4 à 5,5 ms** (295 ko) ✅ |
| Frappe, médiane par image | 750 ms (205 ko) | ≤ 20 ms | **23 à 26 ms** (7 passes) ❌ |

Le défilement est acquis, reproduit sur huit passes, avec plus d'un ordre de
grandeur de marge.

La frappe demande une lecture en deux temps. Les colonnes que cette section
désigne elle-même comme décisives — `PerformTraversalsStart → DrawStart` et
`DrawStart → SyncQueued` — valent 0,11 ms et 4,46 ms, contre 0,11 et 424,7 ms
avant : **l'objectif du chantier est atteint.** Mais la médiane par image reste
à 23-26 ms, et ce n'est pas un défaut d'échantillon : le protocole a été porté
de 5 à 40 caractères, l'échantillon est passé de 18 à 105 images, et le chiffre
n'a pas bougé.

Ce qui reste se lit dans la décomposition complète (7 bis) : 6,45 ms s'écoulent
avant qu'une ligne de l'application ne s'exécute, et le GPU tient 9 à 10 ms.
Deux postes que ce chantier n'a jamais prétendu traiter, et dont le premier est
gonflé par l'injection ADB des touches. **Trancher demande une frappe à la
main ; ce banc ne sait pas la produire.**

```powershell
./scripts/banc-editeur.ps1 -Note "scolarisation des enfants rrom"
./scripts/banc-editeur.ps1 -Note "une note jetable" -Frappe
```

Le banc a dû être adapté à l'éditeur virtualisé : ses marqueurs d'écran
désignaient le champ monolithique, qui n'existe plus. Ne pas rejouer une
version antérieure du script — celle qui prenait la fenêtre active pour la
barre de recherche a détruit deux cents caractères dans une vraie note, les a
enregistrés et poussés sur le serveur.

**Les notes `bench-*` de l'ancien banc n'existent plus sur le serveur.** Les
mesures ci-dessus portent donc toutes sur la note réelle de 295 ko ; la ligne
« frappe » n'est plus comparable ligne à ligne avec l'ancienne, qui portait sur
205 ko. En recréer un jeu gradué avant la prochaine campagne.

Les colonnes décisives sont `PerformTraversalsStart → DrawStart` et
`DrawStart → SyncQueued`. Vérifier l'écran avant et après chaque mesure : un tap
perdu peut produire un chiffre plausible sur le mauvais écran.

Ajouter quatre cas de sûreté au corpus. La mesure de performance ne remplace
pas la preuve de données : après chaque cas modifié, relire le fichier écrit et
vérifier l'aller-retour exact, images comprises.

**Deux niveaux de preuve, à ne pas confondre.** `CasDeSureteTest` éprouve la
machine d'édition Kotlin sur les quatre formes de texte, sur la JVM, et les
quatre tests ont été vus échouer quand la matérialisation perd un caractère.
Mais il ne traverse ni `prepareEdit`, ni `restoreImages`, ni `writeNote` : le
câblage du `ViewModel` n'est pas testable sur la JVM, `OpenNoteRepository`
étant une classe finale dont les méthodes appellent directement le binding
gomobile. Ce maillon-là ne se vérifie que sur un vrai fichier.

| cas | machine d'édition (JVM) | fichier relu (appareil) |
|---|---|---|
| paragraphe unique démesuré | ✅ | manque une note d'essai |
| liste ou bloc de code > 500 lignes | ✅ | manque une note d'essai |
| accents, emoji, images allégées | ✅ | ✅ |
| gros fichier `.txt` | ✅ | ✅ |

Le `.txt` de 292 026 octets, le 31 août 2026 : un caractère inséré, enregistré,
puis le fichier relu depuis le cache — 292 027 octets, divergence unique au
caractère 404, les 291 622 octets suivants identiques. Le caractère retiré, la
note retrouve son empreinte d'origine. La note de 295 ko a fait le même
aller-retour deux fois, à quarante caractères.

**Le cas des images est passé, et c'était le seul qui comptait vraiment.** Une
note de 3 793 652 octets portant une unique charge `data:image/png;base64,…` de
3 792 854 octets, insérée depuis l'éditeur web d'OpenCloud. Réseau coupé pendant
l'essai, pour qu'un éventuel dégât ne parte pas sur le serveur.

- l'éditeur l'ouvre en saisie et affiche `![](opennote-image:0)` à la place de
  l'image : l'extraction fait son travail ;
- deux caractères tapés, enregistrés, puis le fichier relu depuis le cache :
  3 793 654 octets, divergence unique au caractère 26, les 3 793 626 octets
  suivants identiques ;
- **la charge base64 est intacte au bit près** — même longueur, même empreinte
  SHA-256, simplement décalée de deux octets ;
- **aucun `opennote-image` dans le fichier écrit** : la restitution a bien eu
  lieu ;
- les deux caractères retirés, la note retrouve son empreinte d'origine.

C'est le seul chemin du dépôt qui peut détruire des données sans un message :
le texte que voit l'éditeur porte des jetons, et l'enregistrer sans restitution
remplacerait l'image par son jeton dans la vraie note. Les tests Go couvraient
la restitution ; ce qui ne l'était nulle part, c'est que les images capturées au
chargement soient bien celles rendues à l'écriture. Ça l'est maintenant, mais
par une vérification manuelle : aucun test ne la rejouera.

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
