# Chantier — sélection globale dans l'éditeur virtualisé

Ordre de travail pour reprendre le sujet à froid. Lire d'abord `CLAUDE.md`,
puis `docs/CHANTIER-EDITEUR.md`, en particulier les sections 2.2, 2.3 et 3.6.
Le présent document prolonge l'éditeur intégré ; il ne remplace ni sa machine de
découpage ni ses mesures de performance.

**État au 31 août 2026 : chantier proposé, aucun lot commencé.** Le périmètre a
été relu une fois contre le code ; cette relecture est intégrée. Les lots
restent volontairement indépendants : « Tout copier » peut être livré seul, et
« Tout sélectionner » sans le glissé sur plusieurs pages.

**Décision d'architecture proposée :** c'est un nouveau chantier fonctionnel,
pas un nouvel éditeur. Le document complet, les offsets UTF-16, les tranches et
la fenêtre bornée restent en place. Lorsqu'une sélection dépasse cette fenêtre,
la saisie locale est suspendue et la `LazyColumn` porte temporairement une
sélection globale. On évite ainsi de faire gérer le même geste à la fois par le
`BasicTextField` et par un second moteur de poignées.

### Ce que la relecture contre le code a changé

- un lot « Tout copier » précède « Tout sélectionner » : il livre la valeur
  attendue sans un seul geste nouveau, et répond par l'usage à la question de
  savoir si la sélection globale est réellement nécessaire ;
- les lots sont renumérotés de 0 à 6 pour l'insérer ;
- le contrat de presse-papiers n'est plus une question ouverte : deux
  contraintes le tranchent (§2.3) ;
- deux invariants ont été ajoutés, tous deux fondés sur du code existant :
  effondrer la sélection avant tout remontage de fenêtre, et annuler la
  sélection avant toute frappe ;
- `LocalTextToolbar` est une API publique : une condition d'arrêt du §9 était
  trop pessimiste, et le lot « Tout sélectionner » n'a pas besoin d'un bouton
  ajouté à la barre supérieure ;
- les lots de glissé sont resserrés en un seul tableau : ils dépendent tous
  d'une réponse que le lot 0 n'a pas encore.

---

## 1. Le problème exact

L'éditeur actuel sait :

- sélectionner normalement dans sa petite fenêtre active ;
- agrandir cette fenêtre jusqu'à 640 unités UTF-16 ou 12 retours à la ligne
  lorsqu'une poignée atteint un bord ;
- conserver les ancres globales pendant cet agrandissement.

Il ne sait pas :

- exécuter « Tout sélectionner » sur la note entière ;
- poursuivre une sélection au-delà de la fenêtre dure ;
- montrer une sélection qui traverse plusieurs `Text` de la `LazyColumn` ;
- faire défiler la note pendant qu'une poignée reste près d'un bord ;
- copier, couper ou supprimer une plage globale.

Ce n'est pas un défaut de calcul d'offset. Un `BasicTextField` ne possède que
son propre texte et son moteur de sélection ne peut ni surligner un autre
composable, ni sélectionner une tranche qui n'est pas composée. Étendre son
contenu jusqu'au document entier annulerait la virtualisation et rétablirait la
panne de performance qui a motivé `CHANTIER-EDITEUR.md`.

### 1.1 Résultat utilisateur visé

À terme, les gestes suivants doivent être possibles :

1. copier toute la note ;
2. demander « Tout sélectionner », voir la plage, puis la copier ;
3. commencer une sélection dans le texte visible et traverser plusieurs
   tranches dans les deux sens ;
4. approcher une poignée du haut ou du bas pour faire défiler la note ;
5. relâcher après plusieurs pages et conserver exactement la plage choisie ;
6. copier, couper ou supprimer cette plage sans perdre le reste du document ;
7. toucher ailleurs pour quitter la sélection et retrouver une petite fenêtre
   de saisie au bon offset.

Le premier point est une **fin**, les autres un **moyen**. La rédaction
antérieure les confondait — « demander Tout sélectionner, puis copier » — et
faisait dépendre le résultat le plus attendu de toute la machinerie de geste.

### 1.2 Un défaut déjà présent, et silencieux : l'aperçu

`VueMarkdown` enveloppe une `LazyColumn` dans un `SelectionContainer`
(`ui/editor/MarkdownView.kt`). Un `SelectionContainer` ne sélectionne que les
composables présents dans son arbre, et une `LazyColumn` ne compose pas les
blocs hors écran : **une copie depuis l'aperçu ne rend donc que ce qui a été
composé**, sans le moindre avertissement.

C'est le premier endroit où un utilisateur qui veut copier sa note va essayer.
Le mécanisme ne laisse pas de doute ; son ampleur exacte — combien de blocs
survivent au recyclage — n'a pas été constatée sur appareil. C'est une mesure
de dix minutes, à faire au lot 0, parce qu'elle décide si l'aperçu doit être
corrigé par le même geste que l'éditeur.

### 1.3 Hors périmètre initial

| Hors périmètre | Pourquoi |
|---|---|
| Sélections multiples | Ce n'est pas un usage Android standard et cela multiplierait toutes les opérations. |
| Sélection rectangulaire | La source Markdown est un flux de texte, pas une grille de code. |
| Recherche/remplacement | Autre fonction, autre modèle d'interaction. |
| Annulation multi-opérations | L'éditeur n'a pas encore de pile d'annulation globale ; ne pas la cacher dans ce chantier. |
| Mise en forme globale | `ApplyFormatJSON` travaille aujourd'hui sur la fenêtre active. Elle pourra être étudiée après copie/coupe/suppression, jamais requise pour les premiers lots. |
| Remplacement de l'éditeur par une WebView | Le chantier doit d'abord éprouver l'extension de l'architecture actuelle. |
| Fidélité parfaite aux poignées de chaque constructeur | Le comportement doit être clair et accessible ; reproduire MIUI pixel par pixel n'est pas un objectif. |

---

## 2. Ce qui existe déjà — vérifié dans le code

| Brique | Emplacement | Ce qu'elle apporte |
|---|---|---|
| Document complet | `EditorUiState.document` | Une seule source de vérité à laquelle rapporter les offsets globaux. |
| Brouillon actif | `EditorUiState.valeur` | Le seul texte mutable pendant la saisie locale. |
| Matérialisation | `materialiser()` | Produit le document courant avant toute sélection ou opération globale. |
| Mode sans champ actif | `focus = -1`, `valeur` vide | L'état « aucune saisie » existe déjà et sert déjà deux fois (§2.2). |
| Tranches exactes | `TrancheEditeur(debut, fin)` | Pavage sans perte en unités UTF-16. |
| Montage autour d'ancres globales | `monterFenetre()` | Replace une sélection **locale** dans une petite fenêtre. Refuse une plage globale (§2.4). |
| Agrandissement local | `etendreFenetrePourSelection()` | Premier franchissement de contexte, jusqu'à la limite dure. |
| Hit testing | `TextLayoutResult.getOffsetForPosition()` dans `TrancheInactive` | Traduit déjà un point local en offset UTF-16 de la tranche. |
| Liste virtualisée | `EditeurVirtualise()` | Ne compose que les tranches visibles et un seul champ actif. |
| Clé active stable | clé Compose `active` | Préserve le champ et l'IME pendant un remontage local. |
| Sauvegarde sûre | `restoreImages`, puis `writeNote` | Toute mutation globale peut réutiliser le chemin existant. |

La sélection globale doit se brancher sur ces briques. Elle ne doit créer ni un
second document complet mutable, ni une liste de copies de tranches, ni une
nouvelle représentation persistée.

**Ce qui manque vraiment est court** : la `LazyColumn` d'`EditeurVirtualise`
n'a aucun `LazyListState` — elle laisse Compose en créer un — donc rien ne peut
aujourd'hui lire sa position ni la faire défiler par programme.

### 2.1 Deux limites existantes à ne pas confondre

`SelectionContainer` ne résout pas le problème, pour la raison décrite au §1.2 :
il sélectionne des composables présents dans l'arbre. Il ne possède pas non plus
le brouillon du champ actif ni le contrat de mutation de l'éditeur.

La sélection native du `BasicTextField` reste utile tant que les deux ancres
sont dans sa fenêtre. La supprimer dès le premier lot ferait régresser un cas
qui fonctionne déjà : correction d'un mot, menu système, précision des poignées
et interaction avec l'IME.

### 2.2 Le mode « aucun champ actif » n'est pas à construire

`focus = -1` avec `valeur = TextFieldValue()` est déjà l'état posé par le
chargement d'une note non vide et par `basculerApercu`. Aucune tranche n'est
alors un `TextField` ; le clavier n'a rien à quoi s'accrocher. La sortie existe
symétriquement : `activerFenetre(etat, offset)` matérialise, redécoupe et rouvre
une fenêtre au bon endroit.

Entrer en sélection globale, c'est donc emprunter un état existant, pas en
inventer un. Ce qui reste à écrire pour le lot « Tout sélectionner » tient en
quatre choses : l'état de sélection, la surbrillance des tranches visibles,
l'action, et le presse-papiers.

### 2.3 Les images intégrées : le contrat de copie est tranché

`PrepareEditJSON` remplace une donnée `data:image/...;base64,...` par un jeton
court tel que `opennote-image:0`. Les données originales restent hors du champ
et ne reviennent que lors de `restoreImages` avant enregistrement.

Deux contraintes décident du contrat, et aucune des deux ne laisse de choix :

1. **restituer le base64 dans le presse-papiers est impossible**, pas seulement
   coûteux. Le presse-papiers Android passe par binder, dont la transaction est
   plafonnée à l'ordre du mégaoctet ; une image de plusieurs mégaoctets échoue
   ou est tronquée. Le seuil exact n'a pas été mesuré ici, et il n'a pas besoin
   de l'être : la branche est fermée dans tous les cas ;
2. **le jeton brut ne doit pas sortir de l'application.** Collé ailleurs, il
   produit un lien interne impossible à restituer, qui ressemble pourtant à une
   adresse valide.

**Contrat retenu :** les opérations internes — plage, coupe, suppression —
travaillent sur le document allégé exact. La copie vers le presse-papiers
remplace chaque `opennote-image:n` couvert par la plage par un substitut inerte
— texte alternatif s'il existe, `![image]()` sinon — **fabriqué au moment de la
copie**. Le document n'est jamais modifié par une copie.

**Un cas non couvert, et qui existe déjà :** coller *dans la même note* un
extrait portant un jeton. `RestoreInlineData` fait un `strings.ReplaceAll`
(`internal/markdown/inline_data.go`), donc un jeton dupliqué restitue l'image
**entière deux fois** dans le fichier envoyé au serveur. Un couper-coller de
paragraphe double silencieusement plusieurs mégaoctets. C'est atteignable
aujourd'hui dans la petite fenêtre ; le présent chantier le rendrait courant.
Le substitut inerte ci-dessus ferme ce chemin pour la copie globale — reste à
décider si le collage local doit être traité, et ce n'est pas ce chantier qui
l'a ouvert.

### 2.4 `monterFenetre` refuse une plage globale — et le dit en plantant

`DocumentEditeur.kt` porte, dans `monterFenetre` :

```kotlin
require(longueurSelection <= MAX_UTF16_EDITEUR)
```

Le commentaire qui l'accompagne est explicite : une sélection vient toujours du
champ actif, donc elle ne peut pas dépasser sa limite. Lui passer une plage de
plusieurs pages ne dégrade pas le résultat, cela **lève une exception**.

C'est le premier piège du chantier, parce que la sortie de sélection globale est
exactement l'endroit où l'on est tenté de repasser les deux ancres. La règle est
à l'invariant 11 : la sélection est effondrée en **un seul** offset avant tout
remontage.

---

## 3. Modèle d'état proposé

### 3.1 Deux modes, jamais deux propriétaires

L'éditeur possède deux modes exclusifs :

1. **saisie locale** : fonctionnement actuel, avec une fenêtre active et la
   sélection relative de son `TextFieldValue` ;
2. **sélection globale** : le brouillon est d'abord matérialisé, le champ actif
   est retiré comme au §2.2, puis deux offsets globaux décrivent la plage.

Forme indicative, à valider par les tests purs :

```kotlin
data class SelectionGlobale(
    val ancre: Int,
    val mobile: Int,
) {
    val debut: Int get() = minOf(ancre, mobile)
    val fin: Int get() = maxOf(ancre, mobile)
    val inversee: Boolean get() = ancre > mobile
}
```

`ancre` est le côté qui ne bouge pas ; `mobile` suit le doigt ou la poignée.
L'ordre est conservé. Normaliser trop tôt les deux valeurs ferait réapparaître
le défaut déjà rencontré avec les sélections de droite à gauche.

### 3.2 Entrée en sélection globale

La transition doit être unique et pure autant que possible :

1. matérialiser le brouillon actif dans `document` ;
2. recalculer les tranches sur ce même instantané ;
3. convertir les ancres locales en offsets globaux ;
4. poser `selectionGlobale` et `focus = -1` ;
5. masquer le clavier ;
6. afficher toutes les tranches comme du texte léger avec surbrillance.

Le point 1 est une obligation de données. Sans lui, les offsets de la sélection
se rapporteraient à un document antérieur dès que le brouillon actif a changé
de longueur.

### 3.3 Sortie de sélection globale

Trois sorties sont distinctes, et **toutes trois effondrent la plage avant de
remonter une fenêtre** (§2.4) :

- **annuler ou toucher ailleurs** : conserver le document et remonter une
  fenêtre locale à l'offset touché ;
- **copier** : conserver la sélection ou la fermer selon la convention Android
  retenue après essai sur appareil ;
- **couper/supprimer** : remplacer `[debut, fin[` dans le document matérialisé,
  incrémenter la révision, remonter une fenêtre au point de jonction et passer
  par l'enregistrement différé existant.

Une coupe est transactionnelle : si la copie vers le presse-papiers échoue, le
texte n'est pas supprimé.

**Une frappe ou un collage n'est pas une sortie possible** tant que les
mutations globales ne sont pas écrites — voir l'invariant 10.

### 3.4 Intersection avec une tranche

La surbrillance ne demande aucune copie globale. Pour chaque tranche composée :

```text
debutLocal = max(selection.debut, tranche.debut) - tranche.debut
finLocal   = min(selection.fin,   tranche.fin)   - tranche.debut
```

Il n'y a rien à dessiner si `debutLocal >= finLocal`. Cette fonction doit être
pure et abondamment testée : elle décide à la fois du rendu visible et de la
cohérence aux frontières.

---

## 4. Rendu et gestes proposés

### 4.1 Surbrillance virtualisée

Seules les tranches composées calculent leur intersection et dessinent la
surbrillance. Deux implémentations sont possibles :

- `AnnotatedString` avec un fond sur l'intervalle local ;
- `TextLayoutResult.getPathForRange()` dessiné derrière le texte.

**Le chemin est préférable, et l'argument est mesuré, pas esthétique.**
Reconstruire un `AnnotatedString` à chaque déplacement du doigt invalide le
layout de la tranche et fait **ré-enregistrer sa display list** — précisément le
coût qu'a identifié la section 7 bis de `docs/ARCHITECTURE.md` et que le
chantier précédent a supprimé. Un chemin dessiné en `drawBehind` ne touche pas
le layout. Accessoirement, il suit aussi les retours à la ligne visuels, ce
qu'un fond de caractères représente mal en fin de ligne.

La confirmation reste à faire sur lignes repliées, emoji et paragraphes vides.

La plage globale ne doit jamais produire un `AnnotatedString` de 295 ko à
chaque déplacement du doigt. On recalcule uniquement les quelques tranches
visibles dont l'intersection a changé.

### 4.2 Registre des tranches visibles

Chaque tranche visible fournit temporairement :

- ses bornes globales ;
- son `TextLayoutResult` ;
- ses coordonnées dans la `LazyColumn`.

Le registre est indexé par une clé stable et nettoyé à la sortie de composition.
Il sert à traduire une position du doigt en offset global : coordonnées écran
vers coordonnées locales, puis `getOffsetForPosition`, puis ajout de
`tranche.debut`.

Les tranches hors écran n'ont besoin d'aucune géométrie. Leur sélection existe
dans les deux offsets globaux et sera dessinée lorsqu'elles redeviendront
visibles.

### 4.3 Où l'action « Tout sélectionner » se branche

`LocalTextToolbar` est une **API publique** de `androidx.compose.ui.platform`,
et `TextToolbar.showMenu` reçoit `onCopyRequested`, `onPasteRequested`,
`onCutRequested` et `onSelectAllRequested`. C'est le point d'accroche prévu pour
remplacer le menu contextuel natif du champ, donc pour intercepter « Tout
sélectionner » là où l'utilisateur le cherche déjà.

Deux conséquences :

- le lot « Tout sélectionner » n'a pas besoin d'un bouton ajouté à la barre
  supérieure ; la solution de repli du prototype devient inutile ;
- la condition d'arrêt « barre de sélection non remplaçable sans API interne »
  du §9 était trop pessimiste et a été corrigée.

Ce que ce chemin ne couvre pas : le Ctrl+A d'un clavier matériel, traité à
l'intérieur du champ. À reprendre au lot d'accessibilité.

Reste à confirmer au lot 0, sur la version du projet (Compose 1.7.6, BOM
2024.12.01), que le `BasicTextField` utilisé passe bien par ce composition
local. C'est un essai d'une demi-heure.

### 4.4 Démarrage du geste : deux gestes différents, pas un

La rédaction antérieure traitait « reprendre un glissé possédé par le champ »
comme une seule question. Il y en a deux, et elles n'ont probablement pas la
même réponse.

**Le glissé après appui long dans le texte** part d'un pointeur reçu par l'arbre
de composition de l'éditeur. Un parent peut l'observer en
`PointerEventPass.Initial` sans le consommer. Piste vivante.

**Le glissé d'une poignée de sélection** ne part pas du même endroit : les
poignées de Compose sont dessinées dans un `Popup`, c'est-à-dire une **fenêtre
distincte**. Les événements de pointeur de ce glissé ne traverseraient alors
jamais l'arbre de l'éditeur, quel que soit le `PointerEventPass`. Si cela se
confirme, la reprise continue depuis une poignée native est fermée avec les API
publiques, et ce n'est pas un manque d'astuce : c'est la topologie des fenêtres.

C'est la seule vérification du lot 0 qui décide de la suite. **Ne pas la
contourner avec des API internes Compose.** Si la reprise continue est
impossible, le produit choisit entre :

1. une transition explicite après la limite de 640 — l'utilisateur reprend une
   poignée globale ;
2. un mode « Sélectionner » explicite qui suspend d'abord la saisie ;
3. s'arrêter aux lots « Tout copier » et « Tout sélectionner ».

Ce choix vaut mieux qu'un geste qui fonctionne sur un clavier ou une version
Compose et casse silencieusement au suivant.

### 4.5 Défilement automatique

Le défilement automatique appartient au lot 4, après le glissé visible. Il
utilise un `LazyListState` conservé par `EditeurVirtualise` — qui n'en a pas
aujourd'hui :

- une zone étroite en haut et en bas déclenche le mouvement ;
- la vitesse augmente avec la profondeur du doigt dans cette zone ;
- elle est plafonnée pour que l'utilisateur puisse viser une ligne ;
- le mouvement s'arrête immédiatement à la sortie de zone, au relâchement ou à
  la fin du document ;
- après chaque déplacement, l'extrémité mobile est recalculée depuis la tranche
  visible sous le doigt.

Aucune boucle ne doit tourner lorsque le doigt ne bouge plus hors des zones de
bord. Une coroutine d'autoscroll doit être unique et annulée avec le geste.

### 4.6 Poignées

En mode global, les poignées natives du `BasicTextField` ne représentent plus
la vraie plage. Elles disparaissent avec le champ lui-même. Les poignées
globales sont dessinées au-dessus de la liste seulement lorsque leur offset est
visible.

Une poignée hors écran n'est pas artificiellement épinglée au bord : la plage
reste sélectionnée, et la poignée réapparaît lorsque son offset revient dans la
fenêtre. Épingler une poignée ferait croire que l'ancre se trouve à l'écran et
rendrait son déplacement ambigu.

---

## 5. Les lots — chacun possède sa sortie

### Lot 0 — contrat et preuve d'interaction

Avant une interface complète :

1. écrire la machine pure `SelectionGlobale` et ses intersections ;
2. ajouter un `LazyListState` à `EditeurVirtualise` sans changer le
   comportement visible ;
3. construire une fixture de plusieurs tranches avec leurs géométries ;
4. **vérifier sur appareil les deux gestes du §4.4 séparément** : le parent
   observe-t-il un glissé après appui long ? observe-t-il un glissé de poignée ?
5. vérifier que le `BasicTextField` du projet passe par `LocalTextToolbar` ;
6. constater l'ampleur de la copie tronquée dans l'aperçu (§1.2).

**Critère de sortie :** une note de décision courte dans ce document, des tests
JVM vus échouer puis passer pour les calculs, et une réponse constatée — oui ou
non, pour chacun des deux gestes — sur la continuité du geste natif. Aucun code
d'interface n'est requis si la preuve conclut à l'arrêt.

### Lot 1 — « Tout copier »

Le résultat le plus attendu, sans un seul geste nouveau :

1. matérialiser la note ;
2. produire le texte de copie selon le contrat du §2.3 ;
3. l'envoyer au presse-papiers depuis une action nommée ;
4. rendre compte du résultat, y compris d'un échec du presse-papiers.

Aucune sélection, aucune surbrillance, aucun registre de géométrie, aucun
`LocalTextToolbar`. Ce lot corrige aussi, par le même geste, le seul chemin par
lequel un utilisateur copie sa note aujourd'hui — l'aperçu du §1.2.

**Critère de sortie :** sur une note de 295 ko, la copie est exacte selon le
contrat, une plage contenant un jeton d'image ne fait pas sortir de base64, et
rien du document n'a changé.

Ce lot répond aussi, par l'usage, à la première question qu'on se posait : si
« Tout copier » suffit, les lots suivants perdent leur principal argument.

### Lot 2 — « Tout sélectionner » et copier

Ce lot ajoute ce que le précédent ne montre pas : *ce qui* est copié.

1. matérialiser la note ;
2. créer `SelectionGlobale(0, document.length)` et poser `focus = -1` ;
3. masquer le clavier et rendre la surbrillance des tranches visibles ;
4. exposer copier et annuler par `LocalTextToolbar` (§4.3) ;
5. envoyer au presse-papiers le contenu du lot 1 ;
6. restaurer une fenêtre locale, plage effondrée (§2.4), sans déplacement
   brutal de la liste.

**Critère de sortie :** sur une note de 295 ko, toute la plage est représentée
par deux offsets, seules les tranches visibles sont redessinées, et le retour à
la saisie ne perd aucun caractère.

Le projet peut raisonnablement s'arrêter ici et déclarer les lots suivants non
retenus. Ce serait déjà une amélioration complète et vérifiable.

### Lots 3 à 6 — seulement si le lot 0 conclut par oui

Ces quatre lots dépendent tous de la réponse du §4.4. Ils sont décrits
brièvement à dessein : les détailler avant la preuve reviendrait à écrire la
partie du document qu'on ne fera peut-être pas.

| Lot | Contenu | Critère de sortie |
|---|---|---|
| **3 — sélection transverse visible** | Appui long puis glissé à travers plusieurs tranches **visibles**, dans les deux sens ; sens des ancres conservé ; surbrillance juste sur les lignes repliées ; poignées globales repositionnables tant qu'elles restent visibles. Pas d'autoscroll : le doigt s'arrête au bord. | Aucun saut d'offset aux frontières, aucune paire de substitution coupée, aucun champ géant composé, sélection stable après relâchement. |
| **4 — plusieurs pages** | Zones d'autoscroll haute et basse, vitesse progressive et plafonnée, mise à jour de l'extrémité malgré le recyclage des tranches, arrêt propre aux deux bouts du document. | Sélectionner plusieurs pages dans les deux sens sur le Redmi Note 12, sans emballement du défilement, sans perdre l'ancre et sans composer le document entier. |
| **5 — couper et supprimer** | Copie transactionnelle puis suppression ; remplacement exact de `[debut, fin[` ; nouvelle révision et drapeau `modifie` ; fenêtre remontée au point de jonction ; sauvegarde par le chemin existant. **La frappe et le collage qui remplacent une sélection globale arrivent ici, pas plus tard** (invariant 10). | Préfixe et suffixe identiques, y compris avec emoji, retours à la ligne, document entier et plage inversée. Une coupe dont la copie échoue ne modifie rien. |
| **6 — accessibilité et finitions** | Annonces TalkBack de la plage et des actions ; taille des cibles tactiles ; Ctrl+A au clavier matériel ; comportement après rotation ; interaction avec la barre de mise en forme. La mise en forme globale n'est ajoutée que si elle reste une opération ponctuelle sur le document matérialisé. | Décide si la fonction peut être présentée comme un éditeur complet. |

---

## 6. Invariants non négociables

1. **Jamais plus d'un champ actif, jamais de champ portant le document entier.**
2. **Une sélection globale est deux offsets UTF-16, pas une copie de texte.**
3. **Entrer en mode global matérialise le brouillon une fois.** Pas à chaque
   mouvement du doigt.
4. **Le glissé n'appelle ni Go, ni le disque, ni le réseau.**
5. **Seules les tranches visibles calculent une géométrie et une surbrillance.**
6. **Le sens des ancres est conservé.** `debut/fin` normalisés ne servent qu'à
   extraire et dessiner.
7. **Toute mutation repart par le chemin de sauvegarde existant.**
8. **Les images retirées ne reviennent jamais automatiquement dans le champ ou
   le presse-papiers.**
9. **Un échec de copie ne peut pas devenir une perte par coupe.**
10. **Une frappe ou un collage pendant une sélection globale l'annule d'abord.**
    Tant que le lot 5 n'est pas écrit, la touche ne remplace rien : elle ferme
    la sélection, remonte une fenêtre et s'applique là. Ne rien décider, c'est
    laisser l'IME décider à la place, avec 295 ko affichés comme sélectionnés.
11. **La plage est effondrée en un offset avant tout remontage de fenêtre.**
    `monterFenetre` lève une exception au-delà de `MAX_UTF16_EDITEUR` (§2.4).
12. **Aucune API interne Compose.** Une fonction qui dépend d'un détail privé
    de la version 1.7.6 est un résultat d'arrêt, pas une fondation.

---

## 7. Stratégie de test

### 7.1 Tests JVM — obligatoires avant l'écran

- sélection vide, document vide et document entier ;
- ancres dans les deux sens ;
- intersection nulle, partielle et complète avec une tranche ;
- frontière exactement commune à deux tranches ;
- emoji et paires de substitution ;
- retours `\n`, lignes vides et fin exclusive ;
- transformation après suppression de la plage ;
- coupe : copie échouée, document inchangé ;
- matérialisation d'un brouillon plus court et plus long avant passage global ;
- **effondrement avant remontage** : une plage plus longue que
  `MAX_UTF16_EDITEUR` doit ressortir en saisie sans exception ;
- **frappe pendant une sélection globale** : la sélection est fermée et le
  document reste intact ;
- jetons d'image inclus, exclus ou seulement partiellement couverts : le texte
  copié ne contient ni base64 ni `opennote-image:n` ;
- **jeton dupliqué** : un texte copié puis recollé ne peut pas faire restituer
  deux fois la même image à l'enregistrement (§2.3).

Les fonctions de calcul doivent rester dans un fichier sans dépendance Android,
comme `DocumentEditeur.kt`, pour que ces tests tournent sans Robolectric.

### 7.2 Tests Compose ou instrumentation

Le chantier justifie les premiers tests instrumentés de l'éditeur :

- appui long contre toucher simple ;
- déplacement à travers trois tranches visibles ;
- autoscroll et relâchement ;
- recyclage d'une tranche sélectionnée puis retour à l'écran ;
- copie via le vrai `ClipboardManager`, y compris sur une note de 295 ko ;
- clavier masqué en mode global puis rouvert à la sortie ;
- action accessible avec TalkBack.

Si l'infrastructure instrumentée coûte davantage que les deux premiers lots, la
preuve sur appareil peut précéder, mais elle ne remplace pas indéfiniment ces
tests une fois les gestes retenus.

### 7.3 Banc manuel

Au minimum :

- petite note, note vide et note synthétique de 295 ko ;
- sélection vers le haut et vers le bas ;
- passage sur un très long paragraphe replié ;
- rotation pendant une sélection ;
- mode sombre et tailles de police système agrandies ;
- TalkBack ;
- texte avec plusieurs emoji et une image intégrée ;
- copie vers OpenNote, puis vers une autre application.

Mesurer séparément « écrit », « compile », « testé sur JVM », « essayé sur
appareil » et « mesuré ». Un geste qui semble fluide sur dix lignes n'établit
rien pour la note de référence.

---

## 8. Budget de performance

Le chantier n'a pas le droit d'échanger une impossibilité fonctionnelle contre
le retour de la panne initiale.

- aucune allocation proportionnelle au document pendant le glissé ;
- aucun redécoupage complet par événement de pointeur ;
- aucune concaténation de toutes les tranches pour peindre la sélection ;
- au plus les éléments visibles recomposés ;
- une seule coroutine d'autoscroll ;
- copie du texte sélectionné uniquement au moment de l'action « Copier » ;
- pas de restauration des données d'image pour mesurer ou dessiner.

Le banc de `CHANTIER-EDITEUR.md` reste la référence : le dessin ne doit pas
redevenir proportionnel aux 295 ko. Le 90e percentile déjà proche de 17 ms ne
laisse pas de place à une reconstruction globale à chaque pixel de glissé.

---

## 9. Conditions d'arrêt ou de réduction

Le chantier s'arrête au lot 2, ou revient à un mode explicite « Sélectionner »,
si l'une de ces conditions est confirmée :

- impossible de reprendre proprement un glissé possédé par `BasicTextField`
  avec les API publiques, pour **les deux** gestes du §4.4 ;
- `LocalTextToolbar` ne permet pas d'intercepter les actions attendues sur la
  version du projet — auquel cas une action nommée hors du menu contextuel
  reste acceptable, contrairement à ce que ce document affirmait ;
- autoscroll imprécis au point de sélectionner régulièrement la mauvaise ligne ;
- recompositions visibles qui dépassent durablement le budget de dessin ;
- comportement TalkBack incohérent entre texte local et global.

Un arrêt ne remet pas en cause l'éditeur virtualisé. Il signifie seulement que
« Tout copier » et « Tout sélectionner » sont le bon niveau de fonction pour
cette pile Compose. Remplacer l'éditeur entier ne devient une option qu'après
une preuve d'échec, jamais parce qu'un lot paraît plus long que prévu.

---

## 10. Fichiers probablement concernés

Le nom exact importe moins que la séparation des responsabilités :

- `ui/editor/SelectionGlobale.kt` — état et transitions pures ;
- `ui/editor/SelectionGlobaleTest.kt` — calculs JVM ;
- `EditorUiState` / `EditorViewModel` — entrée, sortie et mutations ;
- `EditorScreen.kt` — `LazyListState`, registre visible, gestes, surbrillance,
  poignées et `LocalTextToolbar` ;
- `MarkdownView.kt` — si la copie tronquée de l'aperçu (§1.2) est traitée ici ;
- ressources `values/`, `values-en/`, `values-es/`, `values-de/` — actions et
  descriptions d'accessibilité ;
- tests instrumentés Android — gestes et presse-papiers ;
- le présent document — décisions constatées après chaque lot.

**Aucune modification Go ou gomobile n'est prévue pour les lots 0 à 5.** Le
document allégé et les offsets globaux suffisent. Une modification de
`ApplyFormatJSON` ne se discute qu'au lot 6 si la mise en forme globale est
retenue.

---

## 11. Décisions à prendre après relecture

Restent ouvertes avant le lot 0 :

1. Accepte-t-on de masquer le clavier lorsqu'une sélection devient globale ?
2. Préfère-t-on un passage automatique depuis la poignée native ou un mode
   « Sélectionner » explicite si le premier est fragile ?
3. Copier doit-il conserver la surbrillance ou revenir à la saisie ?
4. La mise en forme d'une sélection globale est-elle réellement attendue ?
5. Le lot 4 justifie-t-il l'introduction de tests instrumentés avant son code ?
6. La copie tronquée de l'aperçu (§1.2) se corrige-t-elle ici ou dans son
   propre chantier ?

Tranchées par la relecture, et pour mémoire :

- **« Tout sélectionner » seul apporte-t-il de la valeur ?** La question était
  mal posée : c'est « Tout copier » qui porte la valeur, et il est désormais le
  lot 1, avant toute sélection.
- **Que doit copier une plage contenant `opennote-image:n` ?** Un substitut
  inerte, jamais le base64 — qui ne passerait pas le presse-papiers — ni le
  jeton. Contrat complet au §2.3.

La recommandation actuelle est : **livrer le lot 1, puis décider**, masquer le
clavier en mode global, et ne promettre le glissé continu qu'après la preuve
du lot 0.
