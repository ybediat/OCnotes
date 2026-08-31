# Chantier — sélection globale dans l'éditeur virtualisé

Ordre de travail pour reprendre le sujet à froid. Lire d'abord `CLAUDE.md`,
puis `docs/CHANTIER-EDITEUR.md`, en particulier les sections 2.2, 2.3 et 3.6.
Le présent document prolonge l'éditeur intégré ; il ne remplace ni sa machine de
découpage ni ses mesures de performance.

**État au 31 août 2026 : chantier proposé, aucun lot commencé.** Le besoin est
confirmé, mais son périmètre doit être relu avant d'écrire du code. Les lots sont
volontairement indépendants : le lot 1, « Tout sélectionner » et copier, peut
être livré seul si le glissé sur plusieurs pages s'avère trop coûteux ou trop
fragile avec Compose.

**Décision d'architecture proposée :** c'est un nouveau chantier fonctionnel,
pas un nouvel éditeur. Le document complet, les offsets UTF-16, les tranches et
la fenêtre bornée restent en place. Lorsqu'une sélection dépasse cette fenêtre,
la saisie locale est suspendue et la `LazyColumn` porte temporairement une
sélection globale. On évite ainsi de faire gérer le même geste à la fois par le
`BasicTextField` et par un second moteur de poignées.

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

1. demander « Tout sélectionner », puis copier toute la note ;
2. commencer une sélection dans le texte visible et traverser plusieurs
   tranches dans les deux sens ;
3. approcher une poignée du haut ou du bas pour faire défiler la note ;
4. relâcher après plusieurs pages et conserver exactement la plage choisie ;
5. copier, couper ou supprimer cette plage sans perdre le reste du document ;
6. toucher ailleurs pour quitter la sélection et retrouver une petite fenêtre
   de saisie au bon offset.

### 1.2 Hors périmètre initial

| Hors périmètre | Pourquoi |
|---|---|
| Sélections multiples | Ce n'est pas un usage Android standard et cela multiplierait toutes les opérations. |
| Sélection rectangulaire | La source Markdown est un flux de texte, pas une grille de code. |
| Recherche/remplacement | Autre fonction, autre modèle d'interaction. |
| Annulation multi-opérations | L'éditeur n'a pas encore de pile d'annulation globale ; ne pas la cacher dans ce chantier. |
| Mise en forme globale | `ApplyFormatJSON` travaille aujourd'hui sur la fenêtre active. Elle pourra être étudiée après copie/coupe/suppression, jamais requise pour le premier lot. |
| Remplacement de l'éditeur par une WebView | Le chantier doit d'abord éprouver l'extension de l'architecture actuelle. |
| Fidélité parfaite aux poignées de chaque constructeur | Le comportement doit être clair et accessible ; reproduire MIUI pixel par pixel n'est pas un objectif. |

---

## 2. Ce qui existe déjà — vérifié dans le code

| Brique | Emplacement | Ce qu'elle apporte |
|---|---|---|
| Document complet | `EditorUiState.document` | Une seule source de vérité à laquelle rapporter les offsets globaux. |
| Brouillon actif | `EditorUiState.valeur` | Le seul texte mutable pendant la saisie locale. |
| Matérialisation | `materialiser()` | Produit le document courant avant toute sélection ou opération globale. |
| Tranches exactes | `TrancheEditeur(debut, fin)` | Pavage sans perte en unités UTF-16. |
| Montage autour d'ancres globales | `monterFenetre()` | Replace une sélection globale dans une petite fenêtre lorsque c'est possible. |
| Agrandissement local | `etendreFenetrePourSelection()` | Premier franchissement de contexte, jusqu'à la limite dure. |
| Hit testing | `TextLayoutResult.getOffsetForPosition()` dans `TrancheInactive` | Traduit déjà un point local en offset UTF-16 de la tranche. |
| Liste virtualisée | `EditeurVirtualise()` | Ne compose que les tranches visibles et un seul champ actif. |
| Clé active stable | clé Compose `active` | Préserve le champ et l'IME pendant un remontage local. |
| Sauvegarde sûre | `restoreImages`, puis `writeNote` | Toute mutation globale peut réutiliser le chemin existant. |

La sélection globale doit se brancher sur ces briques. Elle ne doit créer ni un
second document complet mutable, ni une liste de copies de tranches, ni une
nouvelle représentation persistée.

### 2.1 Deux limites existantes à ne pas confondre

`SelectionContainer`, déjà utilisé dans l'aperçu, ne résout pas le problème. Il
sélectionne des composables présents dans son arbre ; une `LazyColumn` ne compose
pas les pages hors écran. Il ne possède pas non plus le brouillon du champ
actif ni le contrat de mutation de l'éditeur.

La sélection native du `BasicTextField` reste utile tant que les deux ancres
sont dans sa fenêtre. La supprimer dès le premier lot ferait régresser un cas
qui fonctionne déjà : correction d'un mot, menu système, précision des poignées
et interaction avec l'IME.

### 2.2 Les images intégrées sont une vraie frontière de copie

`PrepareEditJSON` remplace une donnée `data:image/...;base64,...` par un jeton
court tel que `opennote-image:0`. Les données originales restent hors du champ
et ne reviennent que lors de `restoreImages` avant enregistrement.

Une copie globale ne doit jamais appeler aveuglément `restoreImages` sur toute
la sélection : une image de plusieurs mégaoctets reviendrait dans le
presse-papiers et réintroduirait la pression mémoire que l'allègement évite. À
l'inverse, copier le jeton brut vers une autre note produirait un lien interne
impossible à restituer.

**Décision provisoire pour le prototype :** les opérations internes — plage,
coupe, suppression — travaillent sur le document allégé exact. Le lot 1 doit
mesurer et documenter séparément ce qui part dans le presse-papiers lorsqu'une
plage contient un jeton. Tant que ce contrat n'est pas choisi et testé, le lot
ne peut pas être déclaré fini.

---

## 3. Modèle d'état proposé

### 3.1 Deux modes, jamais deux propriétaires

L'éditeur possède deux modes exclusifs :

1. **saisie locale** : fonctionnement actuel, avec une fenêtre active et la
   sélection relative de son `TextFieldValue` ;
2. **sélection globale** : le brouillon est d'abord matérialisé, le clavier et
   le champ actif sont suspendus, puis deux offsets globaux décrivent la plage.

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
4. poser `selectionGlobale` ;
5. suspendre le focus de saisie et masquer le clavier ;
6. afficher toutes les tranches comme du texte léger avec surbrillance.

Le point 1 est une obligation de données. Sans lui, les offsets de la sélection
se rapporteraient à un document antérieur dès que le brouillon actif a changé
de longueur.

### 3.3 Sortie de sélection globale

Trois sorties sont distinctes :

- **annuler ou toucher ailleurs** : conserver le document et remonter une
  fenêtre locale au nouvel offset ;
- **copier** : conserver la sélection ou la fermer selon la convention Android
  retenue après essai sur appareil ;
- **couper/supprimer** : remplacer `[debut, fin[` dans le document matérialisé,
  incrémenter la révision, remonter une fenêtre au point de jonction et passer
  par l'enregistrement différé existant.

Une coupe est transactionnelle : si la copie vers le presse-papiers échoue, le
texte n'est pas supprimé.

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
surbrillance. Deux implémentations sont à comparer sur une petite fixture :

- `AnnotatedString` avec un fond sur l'intervalle local ;
- `TextLayoutResult.getPathForRange()` dessiné derrière le texte.

Le chemin de sélection est probablement préférable : il suit les retours à la
ligne visuels et évite qu'un simple fond de caractères représente mal les fins
de ligne. La décision doit venir d'un essai sur lignes repliées, emoji et
paragraphes vides, pas d'une préférence abstraite.

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

### 4.3 Démarrage du geste

Le premier prototype doit distinguer :

- toucher simple : activer la saisie, comme aujourd'hui ;
- appui long puis glissé : commencer une sélection globale ;
- poignée native dans le champ actif : conserver la sélection locale tant
  qu'elle tient dans la fenêtre.

Le passage continu d'une poignée native à la sélection globale est le risque
principal. `BasicTextField` possède déjà le geste. Un parent peut éventuellement
observer les événements sans les consommer, mais il faut vérifier sur la version
Compose du projet qu'il reçoit encore la position après capture par le champ.

**Ne pas commencer par contourner ce point avec des API internes Compose.** Si
la reprise continue est impossible avec les API publiques, le produit doit
choisir entre :

1. une transition explicite après la limite de 640 — l'utilisateur reprend une
   poignée globale ;
2. un mode « Sélectionner » explicite qui suspend d'abord la saisie ;
3. s'arrêter au lot « Tout sélectionner ».

Ce choix vaut mieux qu'un geste qui fonctionne sur un clavier ou une version
Compose et casse silencieusement au suivant.

### 4.4 Défilement automatique

Le défilement automatique appartient au lot 3, après le glissé visible. Il
utilise un `LazyListState` conservé par `EditeurVirtualise` :

- une zone étroite en haut et en bas déclenche le mouvement ;
- la vitesse augmente avec la profondeur du doigt dans cette zone ;
- elle est plafonnée pour que l'utilisateur puisse viser une ligne ;
- le mouvement s'arrête immédiatement à la sortie de zone, au relâchement ou à
  la fin du document ;
- après chaque déplacement, l'extrémité mobile est recalculée depuis la tranche
  visible sous le doigt.

Aucune boucle ne doit tourner lorsque le doigt ne bouge plus hors des zones de
bord. Une coroutine d'autoscroll doit être unique et annulée avec le geste.

### 4.5 Poignées

En mode global, les poignées natives du `BasicTextField` ne représentent plus
la vraie plage. Elles doivent disparaître avec le focus local. Les poignées
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
2. ajouter un `LazyListState` sans changer le comportement visible ;
3. construire une fixture de plusieurs tranches avec leurs géométries ;
4. vérifier sur appareil si le parent observe un glissé capturé par le champ ;
5. décider le contrat de copie des jetons `opennote-image:` ;
6. décider où l'action « Tout sélectionner » est réellement accessible.

**Critère de sortie :** une note de décision courte dans ce document, des tests
JVM vus échouer puis passer pour les calculs, et une réponse constatée — oui ou
non — sur la continuité du geste natif. Aucun code produit n'est requis si la
preuve conclut à l'arrêt.

### Lot 1 — « Tout sélectionner » et copier

Ce lot résout le reproche le plus évident sans glissé transverse :

1. matérialiser la note ;
2. créer `SelectionGlobale(0, document.length)` ;
3. masquer le clavier et rendre la surbrillance des tranches visibles ;
4. exposer copier et annuler ;
5. envoyer au presse-papiers le contenu défini au lot 0 ;
6. restaurer une fenêtre locale sans déplacement brutal de la liste.

L'action doit être découvrable depuis le contexte de sélection attendu sur
Android. Un bouton permanent obscur dans la barre supérieure n'est acceptable
que pour le prototype, pas comme conclusion silencieuse.

**Critère de sortie :** sur une note de 295 ko, toute la plage est représentée
par deux offsets, seules les tranches visibles sont redessinées, la copie est
exacte selon le contrat choisi et le retour à la saisie ne perd aucun caractère.

Le projet peut raisonnablement s'arrêter ici et déclarer les lots suivants non
retenus. Ce serait déjà une amélioration complète et vérifiable.

### Lot 2 — sélection transverse dans la zone visible

1. appui long sur une tranche inactive ;
2. glissé à travers plusieurs tranches visibles, dans les deux sens ;
3. conservation du sens des ancres ;
4. surbrillance correcte des lignes repliées et des tranches intermédiaires ;
5. poignées globales repositionnables tant qu'elles restent visibles ;
6. copie de la plage.

Pas d'autoscroll dans ce lot. Le doigt s'arrête au bord de la zone visible.

**Critère de sortie :** aucun saut d'offset aux frontières, aucune paire de
substitution coupée, aucun champ géant composé et une sélection stable après
relâchement.

### Lot 3 — sélection sur plusieurs pages

1. zones d'autoscroll haute et basse ;
2. vitesse progressive et plafonnée ;
3. mise à jour de l'extrémité malgré le recyclage des tranches ;
4. arrêt propre aux deux extrémités du document ;
5. reprise et déplacement d'une poignée après plusieurs écrans ;
6. maintien du budget de dessin pendant le glissé.

**Critère de sortie :** sélectionner plusieurs pages dans les deux sens sur le
Redmi Note 12, sans emballement du défilement, sans perdre l'ancre et sans
composer le document entier.

### Lot 4 — couper et supprimer

Les mutations globales arrivent après une sélection fiable :

1. copie transactionnelle, puis suppression pour « Couper » ;
2. suppression directe après confirmation seulement si la convention Android
   le justifie — pas de dialogue ajouté par réflexe ;
3. remplacement exact de `[debut, fin[` ;
4. nouvelle révision et drapeau `modifie` ;
5. fenêtre locale remontée au point de jonction ;
6. sauvegarde différée et sortie par le chemin existant.

**Critère de sortie :** les tests prouvent que préfixe et suffixe restent
identiques, y compris avec emoji, retours à la ligne, document entier et plage
inversée. Une coupe dont la copie échoue ne modifie rien.

### Lot 5 — accessibilité et commandes de remplacement

Ce lot décide si la fonction peut être considérée comme un éditeur complet :

- annonces TalkBack de la plage et des actions ;
- taille et cible tactile des poignées ;
- navigation au clavier matériel ;
- collage ou frappe remplaçant une sélection globale ;
- comportement après rotation et recréation de l'écran ;
- interaction avec la barre de mise en forme.

La mise en forme globale n'est ajoutée que si elle reste une opération ponctuelle
sur le document matérialisé. Aucun appel Go n'est autorisé pendant le glissé.

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
10. **Aucune API interne Compose.** Une fonction qui dépend d'un détail privé
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
- jetons d'image inclus, exclus ou seulement partiellement couverts, selon le
  contrat décidé au lot 0.

Les fonctions de calcul doivent rester dans un fichier sans dépendance Android,
comme `DocumentEditeur.kt`, pour que ces tests tournent sans Robolectric.

### 7.2 Tests Compose ou instrumentation

Le chantier justifie les premiers tests instrumentés de l'éditeur :

- appui long contre toucher simple ;
- déplacement à travers trois tranches visibles ;
- autoscroll et relâchement ;
- recyclage d'une tranche sélectionnée puis retour à l'écran ;
- copie via le vrai `ClipboardManager` ;
- clavier masqué en mode global puis rouvert à la sortie ;
- action accessible avec TalkBack.

Si l'infrastructure instrumentée coûte davantage que le premier lot, la preuve
sur appareil peut précéder, mais elle ne remplace pas indéfiniment ces tests une
fois les gestes retenus.

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

Le chantier s'arrête au lot 1, ou revient à un mode explicite « Sélectionner »,
si l'une de ces conditions est confirmée :

- impossible de reprendre proprement un glissé possédé par `BasicTextField`
  avec les API publiques ;
- barre de sélection non accessible ou non remplaçable sans API interne ;
- autoscroll imprécis au point de sélectionner régulièrement la mauvaise ligne ;
- recompositions visibles qui dépassent durablement le budget de dessin ;
- comportement TalkBack incohérent entre texte local et global ;
- contrat de presse-papiers des images impossible à rendre sûr et compréhensible.

Un arrêt ne remet pas en cause l'éditeur virtualisé. Il signifie seulement que
« Tout sélectionner » est le bon niveau de fonction pour cette pile Compose.
Remplacer l'éditeur entier ne devient une option qu'après une preuve d'échec,
jamais parce que le lot 2 paraît plus long que prévu.

---

## 10. Fichiers probablement concernés

Le nom exact importe moins que la séparation des responsabilités :

- `ui/editor/SelectionGlobale.kt` — état et transitions pures ;
- `ui/editor/SelectionGlobaleTest.kt` — calculs JVM ;
- `EditorUiState` / `EditorViewModel` — entrée, sortie et mutations ;
- `EditorScreen.kt` — registre visible, gestes, surbrillance et poignées ;
- ressources `values/`, `values-en/`, `values-es/`, `values-de/` — actions et
  descriptions d'accessibilité ;
- tests instrumentés Android — gestes et presse-papiers ;
- le présent document — décisions constatées après chaque lot.

**Aucune modification Go ou gomobile n'est prévue pour les lots 0 à 4.** Le
document allégé et les offsets globaux suffisent. Une modification de
`ApplyFormatJSON` ne se discute qu'au lot 5 si la mise en forme globale est
retenue.

---

## 11. Décisions à prendre après relecture

Avant le lot 0, répondre explicitement à ces questions :

1. « Tout sélectionner » seul apporte-t-il déjà une valeur suffisante ?
2. Accepte-t-on de masquer le clavier lorsqu'une sélection devient globale ?
3. Préfère-t-on un passage automatique depuis la poignée native ou un mode
   « Sélectionner » explicite si le premier est fragile ?
4. Que doit copier une plage contenant `opennote-image:n` ?
5. Copier doit-il conserver la surbrillance ou revenir à la saisie ?
6. La mise en forme d'une sélection globale est-elle réellement attendue ?
7. Le lot 3 justifie-t-il l'introduction de tests instrumentés avant son code ?

La recommandation actuelle est : **valider d'abord le lot 1**, masquer le
clavier en mode global, ne promettre le glissé continu qu'après la preuve du
lot 0, et traiter les images comme une décision produit bloquante pour la copie
inter-application.
