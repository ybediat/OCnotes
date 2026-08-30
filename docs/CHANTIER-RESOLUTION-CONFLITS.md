# Chantier — résolution explicite des conflits

Plan pour permettre à l'utilisateur de choisir quoi faire après un conflit de
synchronisation, sans affaiblir la règle local-first : **en cas de doute, ne
jamais écraser une version distante silencieusement**.

Lire d'abord `CLAUDE.md`, `docs/ARCHITECTURE.md` (§ 5), `docs/FACADE.md` et
`docs/CHANTIER-SYNCHRONISATION.md`.

---

## 1. Objectif

Après un conflit de contenu ou de structure, l'application doit conserver un
état persistant et proposer trois choix :

| Choix | Effet |
|---|---|
| Garder le serveur | Abandonner la copie locale de conflit ; la version serveur reste la référence. |
| Garder le local | Tenter de publier la copie locale à la place de la référence serveur, avec l'ETag observé lors du conflit. |
| Garder les deux | Conserver l'état actuel : la référence serveur et la copie locale coexistent. |

Le troisième choix ne modifie rien : il ferme seulement le dialogue. C'est le
repli sûr si l'utilisateur hésite.

## 2. Limites

- Pas de fusion automatique Markdown.
- Pas d'écrasement inconditionnel du serveur.
- Pas de résolution de dossier : les mutations différées de dossier restent
  refusées en v1.
- Pas de verrouillage WebDAV : OpenCloud ne propose pas DAV classe 2.

Si le serveur a encore changé après l'apparition du dialogue, « garder le
local » doit créer un nouveau conflit au lieu d'écraser cette troisième version.

---

## 3. Modèle de données

Créer dans `internal/store` un conflit persistant, distinct du rapport
éphémère de `Push` :

- identifiant stable ;
- opération d'origine (`write`, `delete`, `move`) ;
- chemin de référence serveur ;
- chemin de la copie locale si elle existe ;
- ETag de la référence serveur au moment où le conflit a été constaté ;
- date de création.

L'état vit dans l'index du cache et survit à l'arrêt brutal. Une ancienne base
ne portant pas cette liste reste lisible avec une liste vide.

Pour une suppression conflictuelle, il n'y a pas de copie locale : « garder
serveur » et « garder les deux » sont donc équivalents. L'interface doit le
dire plutôt que présenter une action trompeuse.

## 4. Plan d'action

### Lot 0 — tests et contrat de sûreté

Écrire les tests avant l'implémentation :

1. conflit d'écriture persistant après redémarrage ;
2. « garder serveur » retire uniquement la copie locale ;
3. « garder local » avec ETag inchangé remplace la référence ;
4. « garder local » avec ETag devenu périmé crée un nouveau conflit ;
5. conflit de déplacement : la copie à destination devient la référence après
   choix local ;
6. résolution hors connexion : aucune donnée n'est supprimée et l'action reste
   en attente.

**Sortie :** chaque choix a un effet explicite, et désactiver sa protection
fait échouer le test associé.

### Lot 1 — persister et exposer les conflits

Étendre l'index du `Store`, puis fournir les opérations métier suivantes :

- lister les conflits ouverts ;
- abandonner une copie locale ;
- demander la promotion sûre d'une copie locale ;
- marquer « garder les deux » comme traité sans supprimer de contenu.

La promotion locale doit utiliser l'ETag retenu dans le conflit ; elle ne doit
jamais appeler `Save` avec un ETag vide sur une note existante.

**Sortie :** les conflits restent visibles après redémarrage et sont résolus
entièrement dans le cœur Go, testable sans Android.

### Lot 2 — façade Go ↔ Kotlin

Ajouter à `mobile.App` :

- `ConflictsJSON() (string, error)` ;
- `ResolveConflictJSON(requestJSON string) (string, error)`.

Définir dans `docs/FACADE.md` un objet de conflit et une requête de résolution.
Les listes restent toujours des tableaux JSON, jamais `null`. Une réponse doit
indiquer le conflit créé à nouveau si l'ETag a changé pendant la résolution.

**Sortie :** les DTO Kotlin se désérialisent sans analyser un message d'erreur.

### Lot 3 — interface et dialogue

Depuis les Réglages, afficher les conflits persistants avec leur chemin et
leur opération. Le dialogue propose :

- **Garder le serveur** ;
- **Garder le local** ;
- **Garder les deux**.

Pour une suppression conflictuelle, masquer « garder le local » si aucune
copie n'existe. Après une résolution, rafraîchir la liste et programmer une
synchronisation si une écriture a été mise en file.

Les textes passent par `Texte` et sont traduits en français, anglais et
espagnol.

**Sortie :** l'utilisateur comprend ce qui sera conservé avant de confirmer.

### Lot 4 — validation finale

Exécuter :

```powershell
go test ./...
go vet ./...
gofmt -l .
cd android; .\gradlew.bat testDebugUnitTest
```

Rejouer sur le serveur OpenCloud de test, dans un dossier à préfixe unique :

1. provoquer un conflit depuis deux clients ;
2. choisir chaque résolution ;
3. vérifier les contenus et ETag finaux ;
4. supprimer le dossier de test.

**Sortie :** aucune action de résolution ne perd de contenu, y compris si le
serveur change entre l'affichage du dialogue et sa confirmation.

---

## 5. Décisions à prendre avant le lot 1

- Faut-il conserver un historique des conflits déjà traités, ou seulement les
  conflits ouverts ? Recommandation v1 : ne conserver que les ouverts.
- Après « garder les deux », le conflit doit-il disparaître tout de suite ?
  Recommandation v1 : oui, la conservation des deux versions est déjà la
  résolution choisie.
- Lorsqu'un nouvel ETag invalide « garder local », faut-il rouvrir le même
  conflit ou en créer un nouveau ? Recommandation v1 : créer un nouveau
  conflit, car il représente une nouvelle version distante à examiner.
