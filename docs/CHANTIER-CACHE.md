# Chantier — quota et éviction sûre du cache local

Ordre de travail pour empêcher le cache hors ligne de remplir le stockage du
téléphone sans fragiliser le modèle local-first. Lire d'abord `CLAUDE.md`, puis
`docs/ARCHITECTURE.md` et `docs/FACADE.md`.

**État au 30 août 2026.** Le cache ne possède actuellement ni quota, ni limite
de nombre de fichiers, ni éviction automatique. Il conserve le contenu de toute
note ouverte, créée ou modifiée, jusqu'à sa suppression, à la déconnexion ou à
l'effacement des données de l'application. La seule limite est donc le disque
du téléphone ; une écriture finit par échouer avec `STORAGE_IO`.

Ce chantier introduit un quota réglable et une éviction des contenus
récupérables, sans jamais évincer un travail local.

---

## 1. Objectif et règle non négociable

**Objectif :** borner l'espace occupé par le contenu hors ligne tout en gardant
l'application pleinement utilisable sans réseau.

La règle non négociable est la suivante : **une note `Dirty`, une copie de
conflit et toute donnée nécessaire à une opération en attente ne sont jamais
évincées automatiquement.** Si elles remplissent seules le quota ou le disque,
l'application doit le dire clairement ; elle ne doit jamais supprimer un travail
local pour retrouver de la place.

---

## 2. Ce qui existe aujourd'hui

| Élément | Emplacement | Conséquence |
|---|---|---|
| Blobs de contenu | `internal/store/store.go` | Un fichier par contenu en cache, sous un nom SHA-256 du chemin. |
| Métadonnées persistées | `Entry` dans `internal/store/store.go` | Chemin, ETag, `Dirty`, `BaseHash`, taille, date de modification locale. Pas de date de dernier accès. |
| Inventaire léger | `internal/store/index.go` | Tous les noms, tailles et dates distantes sont retenus, même sans contenu. Cela doit rester : quelques dizaines d'octets par note. |
| Lecture locale | `Store.Get` / `App.ReadNote` | Une note ouverte peut devenir disponible hors ligne. |
| Purges actuelles | `Forget`, `Delete`, `Clear` | Suppression ciblée, suppression locale, et déconnexion ; aucune éviction selon l'espace. |
| Réglages Android | `SettingsScreen` / préférences existantes | Point d'entrée naturel pour le quota et une action de nettoyage. |

Le quota ne porte donc pas sur l'inventaire, la configuration ni la file
persistante : il porte sur les blobs de contenus récupérables.

---

## 3. Politique retenue

### 3.1 Valeur par défaut et réglage

Définir une valeur par défaut de **250 Mo**. La présenter dans les réglages avec
des choix simples :

- 50 Mo ;
- 250 Mo (par défaut) ;
- 1 Go ;
- 5 Go ;
- illimité.

Éviter une saisie numérique libre dans la première version : elle multiplie les
cas de validation, de traduction et d'accessibilité sans bénéfice concret.

Afficher l'usage réel — par exemple « 84 Mo utilisés sur 250 Mo » — et proposer
un geste explicite **Libérer l'espace**. Cette action évince seulement les
contenus récupérables ; elle ne touche jamais la file, les brouillons, les
conflits ou l'inventaire.

### 3.2 Éviction LRU

Quand la taille dépasse le quota, évincer les entrées propres (`Dirty == false`)
les moins récemment consultées jusqu'à revenir sous le quota. Une entrée évincée
reste dans l'inventaire : elle reste visible dans la liste et sera retéléchargée
à sa prochaine ouverture avec le réseau.

Il faut ajouter à `Entry` une date `LastAccess`, persistée dans `index.json`.
La mettre à jour après une lecture réussie du blob et après toute écriture ou
réception depuis le serveur. `LocalMod` ne convient pas : une note ancienne mais
souvent lue serait évincée injustement.

Les tailles doivent être calculées à partir des fichiers réellement présents,
pas seulement de `Entry.Size` : l'index peut être ancien ou un blob peut être
absent après une interruption.

### 3.3 Quand évincer

Deux moments complémentaires :

1. Avant une écriture de blob, calculer la place nécessaire et évincer les
   candidats propres. Cela évite de dépasser durablement le quota.
2. Après modification du réglage ou action utilisateur, lancer une purge
   complète vers le nouveau seuil.

Une lecture réseau qui doit écrire un gros contenu suit la même règle. Si
l'espace manque après éviction de tous les candidats, retourner une erreur
catégorisée `STORAGE_IO` et préserver l'état existant.

---

## 4. Cas délicats

| Cas | Comportement attendu |
|---|---|
| Note locale non synchronisée | Jamais évincée. Si le disque est plein, l'enregistrement échoue explicitement plutôt que de perdre une autre note sale. |
| Copie de conflit | Jamais évincée automatiquement tant que l'utilisateur n'a pas résolu ou supprimé le conflit. |
| Blob propre manquant | L'entrée devient une métadonnée d'inventaire ; la prochaine lecture la récupère du serveur. |
| Hors ligne et contenu évincé | La note reste visible mais son ouverture explique qu'elle doit être téléchargée à nouveau ; ne pas afficher un contenu vide comme une note réelle. |
| Passage à un quota inférieur | Éviction immédiate des seuls candidats sûrs, puis signalement si les éléments protégés dépassent encore le seuil. |
| Quota « illimité » | Désactive seulement l'éviction par quota ; les erreurs de disque plein restent correctement remontées. |
| Déconnexion | Conserve la purge complète actuelle ; le réglage de quota peut rester dans les préférences d'affichage, sans contenu utilisateur. |

La transition « blob absent » doit être conçue proprement : aujourd'hui `Entry`
suppose qu'un blob existe. Soit rendre cette absence explicite dans `Entry`, soit
retirer l'entrée tout en laissant `Known` dans l'inventaire. La seconde approche
est probablement la plus simple, mais doit préserver l'ETag si cela évite un
téléchargement superflu.

---

## 5. Plan d'action

### Lot 0 — figer le contrat et les tests

Écrire les tests Go avant l'interface :

1. éviction LRU de plusieurs notes propres ;
2. une note consultée récemment survit au détriment d'une note plus ancienne ;
3. une note `Dirty` n'est jamais évincée ;
4. une copie de conflit n'est jamais évincée ;
5. quota abaissé sous l'occupation protégée : aucune perte, erreur ou état explicite ;
6. blob évincé puis relecture réseau ;
7. blob évincé puis ouverture hors ligne ;
8. redémarrage : dates d'accès et occupation restent cohérentes.

**Sortie :** les règles de non-perte et le comportement hors ligne sont
exécutables depuis `internal/store` sans Android.

### Lot 1 — modèle de quota dans `internal/store`

Ajouter un quota exprimé en octets, une mesure d'occupation et une opération
`Prune`/`Evict` testable. Centraliser la décision juste avant `writeBlob` ;
aucun appelant ne doit avoir à penser à libérer de l'espace.

Assurer des écritures atomiques : l'éviction et la mise à jour de l'index
restent cohérentes même si l'application est arrêtée entre deux opérations.

**Sortie :** le cache applique un quota fixe en Go sans changer la façade
Android.

### Lot 2 — persistance et compatibilité

Faire évoluer l'index avec une version de migration claire. Les caches anciens
sans `LastAccess` doivent recevoir une date de repli déterministe (par exemple
`LocalMod`), jamais être considérés comme corrompus.

Vérifier l'index incomplet et les blobs orphelins : les supprimer seulement
s'ils ne sont référencés par aucune entrée, sans toucher aux écritures en attente.

**Sortie :** une mise à jour de l'application ne vide pas le cache ni ne perd la
file de synchronisation.

### Lot 3 — façade et réglages Android

Exposer au minimum : quota courant, occupation réelle et déclenchement d'une
purge manuelle. Ajouter le réglage dans l'écran de synchronisation/cache avec
les cinq choix définis au §3.1.

Toutes les nouvelles chaînes doivent exister dans les langues déjà prises en
charge et passer le lint de traductions. Le réglage doit persister et être
appliqué dès le prochain accès au cache.

**Sortie :** l'utilisateur peut comprendre l'espace consommé, modifier le
quota et libérer l'espace sans risque.

### Lot 4 — expérience hors ligne et erreurs

Définir le message lorsqu'une note dont le contenu a été évincé est demandée
sans réseau. Ce n'est ni une note vide, ni une erreur de synchronisation : son
nom est connu, son contenu doit être téléchargé.

Afficher une erreur de stockage actionnable lorsque tous les candidats sont
protégés : indiquer que des modifications locales doivent être synchronisées,
ou que l'utilisateur doit libérer de l'espace au niveau du téléphone.

**Sortie :** aucun état de cache ne donne l'impression erronée qu'une note a
été supprimée ou vidée.

### Lot 5 — validation

```powershell
go test ./...
go vet ./...
gofmt -l .
```

Tester également sur appareil avec un quota très faible, réseau coupé, puis
retour réseau. Vérifier l'occupation affichée avec le stockage de l'application
et contrôler que les brouillons survivent à toutes les purges.

---

## 6. Décisions à ne pas dégrader

- L'inventaire distant reste local et léger : il ne doit pas être purgé avec les contenus.
- La synchronisation garde la priorité sur l'économie d'espace : aucune donnée non poussée n'est sacrifiée pour respecter un chiffre.
- Une purge manuelle est sélective et réversible : le contenu peut être retéléchargé, les métadonnées et le travail local restent.
- Le quota est une préférence locale de l'appareil, pas une propriété du compte ni une donnée synchronisée vers le serveur.
