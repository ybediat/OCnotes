# Chantier — synchronisation sûre des opérations structurelles

Ordre de travail pour reprendre la synchronisation sans perdre des données.
Lire d'abord `CLAUDE.md`, `docs/ARCHITECTURE.md` (§ 5) et `docs/FACADE.md`.
Les constats de ce document ont été relus dans le code et, pour les points
WebDAV les plus importants, vérifiés contre le serveur OpenCloud de test.

**État au 30 août 2026 — terminé.** La synchronisation des contenus est bien construite :
local-first, file persistante, ETag, `If-Match` sur les écritures, copie locale
en cas de conflit et reprise après panne réseau. En revanche, les opérations
structurelles différées (`DELETE` et `MOVE`) pouvaient détruire silencieusement
une modification distante. Ce chantier a corrigé cette asymétrie avant toute
optimisation de cadence ou de performance.

---

## 1. Objectif et limites

**Objectif :** aucune modification faite sur un autre appareil ne doit être
supprimée ou déplacée silencieusement lorsqu'une opération locale hors ligne
est finalement propagée.

**Hors périmètre :**

| Hors périmètre | Raison |
|---|---|
| Fusion automatique Markdown | Une fusion incomplète perdrait plus facilement du texte qu'une copie de conflit. |
| Verrouillage WebDAV | Le serveur ne propose pas la classe DAV 2 (`LOCK` / `UNLOCK`). |
| Modifier le protocole du serveur | Le client doit rester sûr avec le serveur existant. |
| Synchronisation intégrale de tous les contenus | Le chargement paresseux des notes est un choix acceptable ; ce chantier protège les mutations. |

La règle v1 reste donc : **en cas de doute, préserver les deux versions et
demander à l'utilisateur de choisir**, jamais deviner.

---

## 2. Ce qui existe déjà et doit être conservé

| Brique | Emplacement | Ce qu'elle apporte |
|---|---|---|
| File persistante ordonnée | `internal/store/sync.go` | Les opérations survivent à l'arrêt de l'application et sont rejouées dans l'ordre. |
| Écritures dédupliquées | `enqueueLocked` | L'éditeur ne produit qu'une écriture en attente par chemin. |
| Conflit de contenu | `pushWrite` / `resolveConflict` | `If-Match`, comparaison à trois versions via `BaseHash`, copie locale non destructive. |
| Repli hors ligne | `mobile/app.go` | Création, renommage, déplacement et suppression restent utilisables sans réseau. |
| Planification Android | `sync/SyncScheduler.kt` | Réseau requis, anti-rebond après écriture, périodique et retour au premier plan. |
| Signalement | `SyncNotifier.kt` | Les conflits sont visibles, avec la copie conservée. |

Ne pas contourner le `Store` depuis Kotlin : les invariants de cache, de file et
de persistance doivent continuer à vivre dans le cœur Go, où ils sont testés.

---

## 3. Constats vérifiés

### 3.1 Les écritures de contenu sont protégées, pas les mutations structurelles

`OpWrite` utilise l'ETag connu et, sur `412`, appelle `resolveConflict`.
À l'inverse, `OpDelete` appelle `remote.Delete` et `OpMove` appelle
`remote.MoveTo` sans version attendue (`internal/store/sync.go`). Les méthodes
WebDAV correspondantes envoient seulement `DELETE`, ou `MOVE` avec
`Destination` et `Overwrite: F` (`internal/opencloud/space.go`).

Scénario actuellement dangereux :

1. l'appareil A supprime ou déplace `a.md` hors connexion ;
2. l'appareil B modifie `a.md` et synchronise ;
3. A revient en ligne et rejoue sa file ;
4. la modification de B est supprimée, ou déplacée, sans conflit.

### 3.2 Le serveur de test ignore `If-Match` sur `DELETE` et `MOVE`

Essai effectué le 30 août 2026 sur l'espace personnel du serveur de test :

1. création d'un fichier, relevé de son ETag, puis modification du fichier ;
2. `DELETE` avec l'ancien ETag : réponse **204**, fichier effectivement absent
   ensuite (`GET` → **404**) ;
3. même préparation, puis `MOVE` avec l'ancien ETag : réponse **201**, source
   absente (`404`) et destination présente (`200`).

Conclusion : ajouter simplement `If-Match` aux deux requêtes ne suffit pas sur
ce serveur. Une stratégie doit être appliquée par le client. Le dossier de test
créé par l'essai a été supprimé (`DELETE` → `204`).

### 3.3 Une lecture puis action n'est pas atomique

Le minimum sûr est de relire l'objet et de comparer son ETag avec celui mémorisé
avant une mutation. Mais une autre écriture peut toujours arriver entre cette
lecture et le `DELETE` ou le `MOVE`, car le serveur ignore la précondition.

Ce compromis réduit fortement le risque courant mais ne peut pas promettre une
absence absolue de course. Le code et l'interface doivent l'assumer : si une
primitive conditionnelle fiable n'existe pas, privilégier une opération
non-destructive plutôt qu'un `DELETE`/`MOVE` distant irréversible.

### 3.4 Les déclencheurs peuvent se chevaucher

Les trois travaux WorkManager ont volontairement des noms différents :
immédiat, différé et périodique. Ils peuvent donc exécuter `SyncWorker` en
parallèle. La synchronisation manuelle appelle aussi le dépôt directement.
`Store.Push` verrouille les accès à la file par petites sections, pas toute la
passe. Cela évite la corruption mémoire mais permet des requêtes doublées et
des courses réseau inutiles.

### 3.5 Les mutations structurelles ne réveillent pas la synchronisation

L'éditeur et la création de note appellent `syncAfterWrite`. En revanche,
création de dossier, renommage, déplacement et suppression dans
`BrowserViewModel` ne la programment pas. Une mutation faite hors ligne peut
donc attendre le prochain retour au premier plan ou la passe périodique d'une
heure, même une fois le réseau revenu.

---

## 4. Décision de conception à prendre avant le code

La question essentielle est la sémantique d'une mutation locale quand sa source
a changé à distance. La recommandation est la suivante :

| Opération locale hors ligne | Source distante inchangée | Source distante divergente |
|---|---|---|
| Écriture | pousser avec ETag | comportement actuel : copie locale + version serveur de référence |
| Suppression | supprimer | **ne pas supprimer** ; conserver la version serveur et signaler un conflit de suppression |
| Renommage / déplacement | déplacer | **ne pas déplacer la source distante** ; conserver la version distante à son chemin d'origine et publier la version locale sous un nom de conflit à la destination, ou demander une résolution explicite |

Le détail UX — copie à la destination ou dans le dossier source — doit être
validé avant l'implémentation. La propriété non négociable est que la version
distante reste récupérable sans intervention manuelle dans les logs.

Les dossiers demandent une décision explicite : une suppression récursive ne
dispose pas d'un ETag unique représentant toute la descendance. Pour la v1,
le plus sûr est de **ne pas autoriser la suppression différée d'un dossier non
vide**, ou de la transformer en série d'opérations par fichier avec vérification
de version. Ne jamais envoyer un `DELETE` récursif aveugle sur un dossier qui a
pu changer à distance.

---

## 5. Plan d'action

### Lot 0 — figer les scénarios et le contrat de conflit

Écrire les tests de régression avant de modifier le code :

1. suppression hors ligne, modification distante, synchronisation ;
2. déplacement hors ligne, modification distante, synchronisation ;
3. renommage hors ligne, suppression distante, synchronisation ;
4. suppression de dossier hors ligne avec un enfant modifié à distance ;
5. deux passes de synchronisation concurrentes sur la même file.

Définir dans `docs/FACADE.md` le JSON nécessaire si le conflit ne porte plus
seulement sur une écriture : type d'opération, chemin source, copie créée et
éventuellement état « action utilisateur requise ».

**Sortie :** les tests échouent sur l'implémentation actuelle pour la perte de
données, et le comportement attendu est lisible sans consulter le code.

### Lot 1 — mémoriser la version attendue dans la file

Étendre `store.Operation` avec les métadonnées nécessaires au moment du geste
local : au minimum l'ETag de la source ; pour les contenus, conserver la base
déjà portée par `Entry.BaseHash` plutôt que la dupliquer sans raison.

Adapter la persistance et la compatibilité des anciens index : une opération
sans ETag est un état ancien et incertain, donc elle ne doit jamais autoriser
une mutation destructive automatique.

**Sortie :** un `Delete` ou `Rename` hors ligne produit une opération qui sait
quelle version serveur elle croyait modifier.

### Lot 2 — résolution non destructive des suppressions et déplacements

Dans `Store.apply`, avant `OpDelete` et `OpMove` :

1. lire la source distante et obtenir son ETag ;
2. comparer à l'ETag mémorisé ;
3. si identique, appliquer l'opération ;
4. si différent, ne pas appliquer l'opération destructive ; créer l'état de
   conflit prévu au lot 0 et conserver les données locales nécessaires ;
5. si la source est absente, traiter le résultat voulu comme déjà atteint, sans
   recréer de fichier par surprise.

Documenter clairement que la lecture et la mutation ne sont pas atomiques avec
ce serveur. Si l'API OpenCloud fournit ultérieurement une mutation conditionnelle
fiable, l'ajouter comme seconde barrière, sans supprimer la logique de conflit.

**Sortie :** les scénarios du lot 0 ne perdent aucune version distante.

### Lot 3 — sérialiser une passe complète

Introduire un mutex de synchronisation unique, idéalement dans le cœur Go
autour de toute la durée de `App.SyncJSON`, afin qu'il couvre aussi le bouton
manuel et tout appel futur hors WorkManager. Il doit respecter l'annulation et
ne jamais être détenu pendant l'ouverture/fermeture de session.

Les requêtes WorkManager peuvent rester distinctes : elles deviennent des
déclencheurs coalescents vers la même passe sérialisée.

**Sortie :** un test concurrent montre qu'une seule passe touche le serveur à
la fois et que la file finale est cohérente.

### Lot 4 — planifier toute mutation locale différée

Après chaque action du navigateur susceptible d'avoir rejoint la file
(création de dossier, renommage, déplacement, suppression), demander
`syncAfterWrite`. Le nom de méthode peut être généralisé en `syncAfterLocalChange`
si cela rend son rôle plus honnête.

Conserver l'anti-rebond et la contrainte réseau actuels. Ne pas déclencher une
nouvelle passe si l'action a réussi immédiatement sur le serveur, sauf si un
rafraîchissement d'index est nécessaire.

**Sortie :** une mutation faite hors ligne est tentée peu après le retour du
réseau, sans attendre la période horaire.

### Lot 5 — validation réelle et documentation

Exécuter les tests Go, puis rejouer les scénarios contre le serveur de test
dans un dossier à préfixe unique et le supprimer en fin d'essai. Capturer dans
`docs/ARCHITECTURE.md` :

- le serveur ignore `If-Match` pour `DELETE` et `MOVE` ;
- l'absence de verrouillage DAV classe 2 ;
- la règle de résolution choisie pour chaque opération structurelle ;
- la limite de non-atomicité restante.

**Sortie :** `go test ./...` est vert et les essais réels ne produisent aucune
perte de contenu sur les scénarios de divergence.

---

## 6. Vérifications à ne pas oublier

- La file reste persistante après arrêt brutal de l'application.
- Un conflit ne bloque pas les opérations suivantes indépendantes, sauf si
  leur ordre rend cela nécessaire.
- Un conflit structurel est visible dans l'interface et la notification.
- Une même seconde ne doit pas faire entrer deux copies en conflit en collision
  de nom : le générateur de chemin doit prévoir un suffixe de repli.
- Les suppressions et déplacements en ligne conservent leur comportement rapide
  actuel ; le coût de vérification concerne seulement les opérations différées.
- `RefreshIndex` après synchronisation ne doit jamais masquer les entrées locales
  encore en attente.

---

## 7. Commandes de validation

```powershell
go test ./...
go vet ./...
gofmt -l .
```

Pour les essais serveur, n'utiliser qu'un compte et un dossier de test, avec un
préfixe unique. Ne jamais inscrire de jeton dans ce document, dans un script,
dans les fixtures ou dans Git.
