# Contrat de la façade Go ↔ Kotlin

Ce document décrit l'API que `gomobile bind` expose à Android depuis le paquet
`mobile/`. **C'est un contrat gelé** : l'UI Compose se construit dessus, le
cœur Go évolue derrière sans le casser.

Toute la logique métier vit sous `internal/`. La façade ne fait que sérialiser,
désérialiser et déléguer.

---

## Génération du binding

```bash
gomobile bind -target=android/arm64 -androidapi 26 -ldflags="-s -w" -o android/app/libs/opennote.aar ./mobile
```

`-ldflags="-s -w"` retire les tables de symboles du binaire Go : `libgojni.so`
passe de 14 Mo à 7,2 Mo, mesuré. Le build Android signale de toute façon qu'il
ne sait pas stripper cette bibliothèque lui-même.

`golang.org/x/mobile` doit figurer dans le `go.mod` : le code généré importe
son paquet `bind`.

Le paquet Java généré est `mobile`, avec deux classes — vérifié par `javap` sur
l'AAR produit :

| Classe | Contenu |
|---|---|
| `mobile.Mobile` | les fonctions de paquet, statiques : `newApp`, `defaultRoot`, `errorCode`, `isAuthError`, `isConflictError`, `isNotFoundError` |
| `mobile.App` | les méthodes d'instance, en camelCase : `stateJSON()`, `listFolderJSON(String)`, `restore(String)`… |

`pendingCount()` est bien typé `long` côté Java.

Les types JSON du paquet Go sont **non exportés** exprès : `gomobile bind` lie
tout type exporté, et refuserait leurs champs de type slice. Le test
`TestSignaturesCompatiblesGomobile` vérifie cette règle à chaque `go test`,
sans avoir besoin du NDK.

---

## Cycle de vie

```kotlin
val app = Mobile.newApp(context.filesDir.absolutePath)   // (*App, error)
```

`NewApp` ouvre le cache et relit la configuration. Il ne contacte pas le réseau.

### Démarrage type

1. `StateJSON()` → si `connected` est faux, afficher l'écran de connexion.
2. Sinon, lire le token dans les `EncryptedSharedPreferences` et appeler
   **`Restore(token)`**. Aucun appel réseau : la session est remontée depuis la
   configuration. Aller directement au navigateur, sur `lastPath`.
3. **Ensuite seulement**, appeler `Connect(serverUrl, username, token)` en
   arrière-plan pour valider le token et rafraîchir. Un échec `AUTH` ici doit
   renvoyer vers l'écran de connexion ; un échec réseau se laisse ignorer,
   l'application reste utilisable sur le cache.
4. Si `Restore` échoue parce qu'aucun espace n'est enregistré :
   `Connect`, puis `ListDrivesJSON()`, puis `SelectWorkspace(driveId, root)`.

> **Ne jamais démarrer par `Connect`.** `Connect` valide les identifiants
> auprès du serveur, donc il échoue sans réseau — et la bibliothèque reste
> alors nulle, ce qui fait échouer *tous* les appels de navigation, y compris
> ceux qui savent se replier sur le cache. Une application local-first doit
> pouvoir s'ouvrir dans le métro : c'est précisément le rôle de `Restore`.

> **Le token n'est jamais persisté côté Go.** C'est Android qui le garde, dans
> des `EncryptedSharedPreferences` adossées au Keystore matériel, et qui le
> repasse à chaque démarrage. Le fichier `config.json` écrit par Go ne contient
> aucun secret — un test le vérifie.

---

## Méthodes

Les **fonctions de paquet** (première colonne sans récepteur) s'appellent en
Kotlin sur l'objet `Mobile` : `Mobile.newApp(...)`, `Mobile.defaultRoot()`,
`Mobile.errorCode(...)`, `Mobile.isAuthError(...)`. Les **méthodes** s'appellent
sur l'instance : `app.stateJSON()`. La première lettre passe en minuscule.

| Signature Go | Rôle |
|---|---|
| `NewApp(dataDir string) (*App, error)` | *fonction de paquet* — ouvre le cache et la configuration |
| `Restore(appToken string) error` | remonte la session **sans réseau** — le chemin de démarrage normal |
| `Connect(serverURL, username, appToken string) error` | ouvre la session et valide les identifiants auprès du serveur |
| `Disconnect() error` | efface session, configuration **et cache** |
| `StateJSON() (string, error)` | état pour choisir l'écran |
| `ListDrivesJSON() (string, error)` | espaces disponibles |
| `SelectWorkspace(driveID, root string) error` | choisit l'espace et le dossier de notes |
| `DefaultRoot() string` | nom de dossier proposé au premier démarrage (`Notes`) |
| `ListFolderJSON(dir string) (string, error)` | contenu d'un dossier |
| `ReadNote(notePath string) (string, error)` | contenu d'une note |
| `WriteNote(notePath, content string) error` | enregistre localement, **jamais d'erreur réseau** |
| `RefreshNote(notePath string) error` | relit depuis le serveur |
| `CreateNoteJSON(dir, name, content string) (string, error)` | crée une note |
| `CreateFolderJSON(dir, name string) (string, error)` | crée un sous-dossier |
| `Rename(itemPath, newName string) (string, error)` | renomme, renvoie le nouveau chemin |
| `Delete(itemPath string) error` | supprime (récursif sur un dossier) |
| `SuggestName(title string) string` | nom de fichier valide depuis un titre |
| `TitleOf(name, content string) string` | titre à afficher |
| `SyncJSON() (string, error)` | une passe de synchronisation |
| `PendingCount() int` | opérations en attente |
| `ApplyFormatJSON(requestJSON string) (string, error)` | mise en forme Markdown |
| `FormatActionsJSON() (string, error)` | liste des actions de la barre d'outils |
| `RenderNoteJSON(name, content string) (string, error)` | blocs d'affichage pour l'aperçu en lecture seule |
| `PrepareEditJSON(name, content string) (string, error)` | allège une note avant de l'ouvrir en saisie |
| `RestoreImages(text, imagesJSON string) (string, error)` | **obligatoire avant toute écriture** d'une note allégée |
| `ErrorCode(message string) string` | code d'une erreur |
| `IsAuthError` / `IsConflictError` / `IsNotFoundError` `(message string) bool` | tests de catégorie |
| `MaxNameBytes() int` | longueur maximale d'un nom, en octets |
| `ForbiddenNameChars() string` | caractères refusés à la création d'un nom |
| `MaxEditableWord() int` | longueur maximale d'un mot affichable en saisie |
| `IsPlainText(name string) bool` | *fonction de paquet* — le fichier s'affiche tel quel, sans interprétation |

Tous les chemins sont **relatifs au dossier de notes**, sans slash initial.
Une chaîne vide désigne la racine.

`Rename` réajoute l'extension si le chemin visé est une note, et c'est **celle
du fichier renommé** : `journal.txt` renommé en `carnet` donne `carnet.txt`.
Passez le nom sans extension (le champ `display` d'un listing), c'est ce que
l'utilisateur a sous les yeux ; passer le nom avec son extension fonctionne
aussi, elle n'est pas doublée. Saisir une *autre* extension de note la change
délibérément.

### Détails de binding à connaître

- **`PendingCount() int` devient un `long` en Java.** gomobile mappe le `int`
  Go sur `long`. Côté Kotlin : `app.pendingCount().toInt()`.
- Les erreurs Go deviennent des exceptions Java ; seul le message survit, d'où
  les étiquettes de catégorie décrites plus bas.
- `NewApp` renvoie `(*App, error)` : en Kotlin, `Mobile.newApp(dir)` lève une
  exception plutôt que de renvoyer null.

---

## Formats JSON

### `StateJSON`

```json
{
  "connected": true,
  "hasWorkspace": true,
  "serverUrl": "https://cloud.exemple.fr",
  "username": "moi",
  "driveId": "storage-uuid$space-uuid",
  "driveName": "Admin",
  "root": "Notes",
  "lastPath": "Notes/Projets",
  "pending": 0
}
```

`connected` signifie qu'un serveur et un compte sont enregistrés, **pas** que le
token est en mémoire. Après un redémarrage il faut toujours rappeler `Restore`.

> ⚠️ **`root` et `lastPath` ne sont pas dans le même référentiel.**
>
> `root` est le dossier de notes **relatif à l'espace** (`"Notes"`). Il sert à
> l'affichage — un libellé dans les réglages, un titre — et **ne doit jamais
> être passé à `ListFolderJSON`, `ReadNote` ou quoi que ce soit d'autre**.
>
> `lastPath`, comme tous les chemins de cette API, est **relatif au dossier de
> notes**. Vide, il désigne ce dossier lui-même.
>
> Passer `root` comme chemin fait demander `Notes` à une façade qui préfixe
> déjà par `Notes` : le serveur cherche `Notes/Notes` et répond que le dossier
> n'existe pas. Ce bug a été rencontré en vrai au premier lancement.

### `ListDrivesJSON`

```json
[
  {"id": "…$…", "name": "Shares", "type": "virtual",  "usable": false, "selected": false},
  {"id": "…$…", "name": "Admin",  "type": "personal", "usable": true,  "selected": true}
]
```

Les espaces inutilisables sont renvoyés avec `usable: false` plutôt qu'omis :
affichez-les grisés avec une explication, ne les faites pas disparaître.
L'espace `virtual` nommé « Shares » est un agrégat de partages, pas un stockage.

> `id` contient un `$`. C'est normal, c'est la forme `storageId$spaceId`.
> Ne jamais l'échapper ni le découper.

### `ListFolderJSON`

```json
{
  "path": "Notes/Projets",
  "fromCache": false,
  "entries": [
    {"path": "Notes/Projets/Archives", "name": "Archives", "display": "Archives",
     "isDir": true,  "size": 0, "modTime": "", "pending": false},
    {"path": "Notes/Projets/a.md", "name": "a.md", "display": "a",
     "isDir": false, "size": 42, "modTime": "2026-08-28T09:00:00Z", "pending": true}
  ]
}
```

Les dossiers viennent en premier. `display` est le nom **sans extension pour le
Markdown**, à afficher tel quel. Un fichier texte garde la sienne
(`liste.txt`) : il peut cohabiter avec un `liste.md` dans le même dossier, et
deux lignes « liste » désigneraient alors deux fichiers différents.
`pending: true` signale une modification locale pas encore synchronisée — un
bon endroit pour une pastille.

> **`entries` et `conflicts` sont toujours des tableaux, jamais `null`.** Go
> sérialiserait naturellement une slice vide en `null` ; la façade les
> initialise pour l'éviter, et un test le vérifie. Kotlin n'a donc pas à gérer
> deux formes pour un dossier vide.

`fromCache: true` signifie que le réseau était indisponible et que la liste
vient du cache : prévenez que la vue peut être incomplète.

### `CreateNoteJSON` et `CreateFolderJSON`

```json
{"path": "Notes/Ma réunion.md", "name": "Ma réunion.md", "display": "Ma réunion"}
```

Le nom reçoit `.md` s'il ne l'a pas, et un suffixe `(2)`, `(3)`… si le nom est
déjà pris. Une extension de note explicitement saisie — `liste.txt` — est
respectée telle quelle.

### `SyncJSON`

```json
{
  "pushed": 3, "deleted": 0, "moved": 1,
  "conflicts": [{"path": "Notes/a.md", "copyPath": "Notes/a (conflit 2026-08-28T14-32-05).md"}],
  "remaining": 2,
  "error": "opencloud: [HTTP] PUT …: HTTP 502",
  "errorCode": "HTTP"
}
```

`errorCode` évite d'avoir à analyser `error`. Sur `AUTH`, ne pas replanifier de
passe : c'est le token qu'il faut renouveler, réessayer ne servira à rien.

**`SyncJSON` ne lève pas d'exception sur une panne réseau.** L'échec est décrit
dans `error`, avec ce qui a tout de même été propagé. Affichez
« 3 notes envoyées, 2 en attente » plutôt qu'un échec sec.

Un `conflicts` non vide mérite une notification : la version distante a
remplacé la note, et la version locale de l'utilisateur a été conservée sous
`copyPath`. Rien n'est perdu, mais il faut le lui dire.

### `ApplyFormatJSON`

Requête :

```json
{"text": "Bonjour monde", "start": 8, "end": 13, "action": "bold"}
```

Réponse :

```json
{"text": "Bonjour **monde**", "start": 10, "end": 15}
```

> **`start` et `end` sont en unités de code UTF-16**, exactement comme
> `TextRange` de Compose. Transmettez `TextFieldValue.selection.start` et
> `.end` tels quels, et réappliquez la sélection retournée sans conversion.
> Ne convertissez jamais en octets : `é` fait 2 octets mais 1 unité UTF-16, et
> `😀` en fait 4 pour 2 unités.

Actions disponibles, dans l'ordre d'une barre d'outils — `FormatActionsJSON()`
les renvoie :

```
bold  italic  strikethrough  code  link
h1  h2  h3
bullet  numbered  task
quote  codeblock
```

Chaque action est une **bascule** : la réappliquer retire la mise en forme.

### `RenderNoteJSON`

Prépare l'affichage **en lecture seule** d'une note. Fonction pure : ni réseau,
ni cache, ni session — l'aperçu marche donc hors connexion, et sur un brouillon
que l'utilisateur vient de taper sans l'avoir enregistré.

```kotlin
app.renderNoteJSON("Projets/a.md", texteAffiché)
```

> **Le nom compte autant que le contenu.** C'est lui qui décide si le texte est
> interprété. Un `.md` est analysé ; un `.txt` revient en un seul bloc `plain`,
> caractère pour caractère — dans un fichier texte, `#` est un dièse et non un
> titre. Ne devinez pas le format côté Kotlin : la liste des extensions vit
> dans `internal/notes` et n'a pas à être recopiée.

Réponse : un tableau **plat** de blocs, à parcourir dans l'ordre.

```json
[
  {"kind": "heading", "text": "Titre", "level": 1},
  {"kind": "paragraph", "text": "é😀 gras",
   "spans": [{"start": 4, "end": 8, "style": "bold"}]},
  {"kind": "task", "text": "acheter du pain", "checked": true},
  {"kind": "bullet", "text": "sous-point", "depth": 1},
  {"kind": "ordered", "text": "premier", "number": 1},
  {"kind": "code", "text": "fmt.Println()", "lang": "go"},
  {"kind": "tablerow", "cells": ["Nom", "Âge"], "header": true},
  {"kind": "image", "text": "texte alternatif"},
  {"kind": "rule"}
]
```

Le modèle est plat exprès : l'imbrication tient dans `depth` (listes) et
`quote` (citations), il n'y a pas d'arbre à descendre pour dessiner une liste.
Les champs à zéro sont omis.

| `kind` | Champs qui comptent |
|---|---|
| `paragraph` | `text`, `spans`, `depth`, `quote` |
| `heading` | `level` (1 à 6) |
| `bullet` | `depth` |
| `ordered` | `number` — le numéro à **afficher**, pas le rang |
| `task` | `checked` |
| `code` | `lang` — vide si le bloc n'annonce rien ; jamais de `spans` |
| `tablerow` | `cells`, `header` |
| `rule` | aucun |
| `image` | `text` — le texte alternatif, **jamais la source** |
| `plain` | `text` — le fichier entier, non interprété |

`style` d'un span vaut `bold`, `italic`, `strike`, `code` ou `link` ; seul
`link` porte un `href`.

> **`start` et `end` sont en unités de code UTF-16**, comme pour
> `ApplyFormatJSON` : posez-les tels quels dans un `AnnotatedString`. En
> octets, `é😀 **gras**` donnerait `{7, 11}` au lieu de `{4, 8}`, et le gras
> tomberait à côté.

Deux choses ne traversent pas, volontairement :

- **Le HTML brut est ignoré.** L'aperçu n'a pas de moteur pour l'interpréter,
  et une note vient d'un serveur partagé.
- **La source d'une image ne traverse jamais.** L'éditeur web d'OpenCloud
  insère les images en `data:image/jpeg;base64,…`, soit plusieurs mégaoctets
  d'URL. Un bloc `image` porte le texte alternatif, à afficher comme un
  repère — c'est à l'interface d'écrire « Image » quand ce texte est vide, dans
  la langue de l'appareil.


### `PrepareEditJSON` et `RestoreImages`

Ces deux méthodes vont **par paire**. Ouvrir une note en saisie sans la première
peut tuer l'application ; enregistrer sans la seconde détruit l'image dans la
note, sur le serveur, sans message.

```kotlin
val prepare = app.prepareEditJSON("Projets/a.md", contenuLu)
// … l'utilisateur modifie prepare.text …
app.writeNote(chemin, app.restoreImages(texteModifié, imagesEncodéesEnJSON))
```

```json
{
  "text": "# Photo

![vacances](opennote-image:0)

Une légende.
",
  "images": ["data:image/jpeg;base64,/9j/4AAQSkZJRgABAQ…"],
  "editable": true,
  "longestWord": 22
}
```

> ⚠️ **Un `TextField` ne survit pas à une image en base64.**
>
> L'éditeur web d'OpenCloud insère les images en `data:image/jpeg;base64,…` :
> des dizaines de milliers de caractères **sans une seule espace**. Le moteur
> de retour à la ligne d'Android y cherche des points de coupure qui n'existent
> pas, en mémoire native — l'application est tuée par le système, sans
> exception Java, et le téléphone purge ses autres applications dans la foulée.
> Constaté sur appareil, pas déduit.
>
> Ce n'est **pas** une question de taille : 285 ko de prose s'ouvrent sans
> broncher, 44 ko de base64 non. Le prédicat est la longueur du plus long mot,
> et rien d'autre.

`text` porte les données remplacées par des jetons `opennote-image:N`. Le jeton
est une URL bien formée, donc le texte allégé reste du Markdown valide :
`RenderNoteJSON` continue de le lire et d'en tirer ses blocs `image`.

`images` est toujours un tableau, jamais `null`. Gardez-le tel quel le temps de
l'édition et repassez-le à `RestoreImages` avant **chaque** écriture — l'appel
est sans effet quand le tableau est vide, il n'y a donc pas de cas à distinguer.

Un jeton que l'utilisateur a effacé ne revient pas : supprimer le repère d'une
image, c'est supprimer l'image. C'est voulu — c'est le seul geste dont il
dispose pour ça depuis un téléphone.

`editable: false` signale un mot démesuré **qui subsiste après l'extraction** :
un fichier sans rapport avec une image. Ouvrez la note en aperçu seul et
dites-le, sans proposer de retour vers la saisie.

Deux garde-fous du cœur, à connaître :

- Si le contenu porte **déjà** quelque chose qui ressemble à un jeton,
  l'extraction est abandonnée en bloc — restituer pourrait injecter une image
  là où l'utilisateur avait écrit du texte. La note retombe alors sur
  `editable: false` si elle est réellement inaffichable.
- La restitution se fait à l'envers, du dernier jeton au premier :
  `opennote-image:1` est un préfixe de `opennote-image:12`.

---

## Erreurs

`gomobile` transforme une `error` Go en exception Java dont seul le message
survit. Les erreurs du client portent donc leur catégorie entre crochets :

```
opencloud: [AUTH] GET https://…/graph/v1.0/me/drives: HTTP 401
```

Utilisez `ErrorCode(message)` ou les helpers, **jamais une recherche de texte
en français** :

| Code | Helper | Réaction attendue |
|---|---|---|
| `AUTH` | `IsAuthError` | token invalide ou expiré — redemander la saisie, ne pas réessayer |
| `CONFLICT` | `IsConflictError` | version distante plus récente |
| `NOTFOUND` | `IsNotFoundError` | note ou dossier absent |
| `OFFLINE` | `IsOfflineError` | serveur injoignable — réessayer plus tard a du sens |
| `HTTP` | — | autre erreur serveur |

### Codes locaux

Les règles du cœur portent elles aussi une étiquette, pour que l'interface les
formule **dans la langue de l'appareil** au lieu d'afficher le français du Go.
La phrase française reste derrière le code : elle sert la CLI desktop, les
journaux, et le repli d'Android.

| Code | Origine | Ce que l'interface doit dire |
|---|---|---|
| `NAME_EMPTY` | `notes` | le nom est vide |
| `NAME_RESERVED` | `notes` | `.` ou `..` |
| `NAME_TOO_LONG` | `notes` | dépasse `MaxNameBytes()` octets |
| `NAME_FORBIDDEN_CHARS` | `notes` | contient un `ForbiddenNameChars()` |
| `NAME_CONTROL_CHAR` | `notes` | contient un caractère de contrôle |
| `NAME_SPACE_EDGE` | `notes` | commence ou finit par une espace |
| `NAME_TRAILING_DOT` | `notes` | finit par un point |
| `NAME_LEADING_DOT` | `notes` | commence par un point |
| `NAME_RESERVED_DEVICE` | `notes` | nom réservé par Windows (`CON`, `LPT1`…) |
| `NAME_NO_SLOT` | `notes` | aucun nom libre trouvé |
| `ROOT_IMMUTABLE` | `notes` | le dossier de notes ne se renomme pas |
| `MOVE_INTO_SELF` | `notes` | déplacement d'un dossier dans lui-même |
| `PATH_EMPTY` | `notes` | chemin vide |
| `STORAGE_IO` | `store`, `config` | panne du stockage local |
| `SERVER_URL_MISSING` | `config` | adresse de serveur absente |
| `SERVER_URL_INVALID` | `config` | adresse de serveur mal formée |
| `USERNAME_MISSING` | `config` | nom d'utilisateur absent |

**Ne pas recopier `MaxNameBytes()` ni `ForbiddenNameChars()`** dans un texte
d'interface : ces deux accesseurs existent pour que la borne n'ait qu'une seule
source de vérité, dans `internal/notes`.

### Deux règles de lecture

**Le transport gagne sur le local.** `ErrorCode` cherche d'abord les codes de
transport, puis seulement le premier `[NOM_EN_MAJUSCULES]` venu. Une erreur du
cache enveloppe couramment une erreur réseau — `store: [STORAGE_IO] … :
opencloud: [NOTFOUND] …` — et c'est la cause profonde qui décide de la réaction.

**Un code inconnu n'est pas une erreur.** Le repli est prévu : `ErrorCode`
reconnaît la *forme* de l'étiquette, pas une liste fermée, donc une couche du
dessous peut étiqueter une nouvelle règle sans que la façade soit régénérée.
Côté Kotlin, un code sans formulation retombe sur le message brut — du français
sur un téléphone anglophone, ce qui se voit et se répare, là où une chaîne vide
ne se verrait pas.

Un message sans crochets reste une erreur locale non étiquetée.

---

## Synchronisation

**Aucune goroutine périodique côté Go.** C'est Android qui décide quand
synchroniser, via WorkManager, parce que lui seul connaît l'état de la
batterie, du réseau et du cycle de vie. Le Go exécute une passe quand on la lui
demande.

Moments naturels pour appeler `SyncJSON()` :

- au retour au premier plan ;
- après une écriture, avec un délai pour ne pas synchroniser à chaque frappe ;
- périodiquement via un `PeriodicWorkRequest` contraint au réseau ;
- sur un geste explicite de rafraîchissement.

`WriteNote` n'échoue jamais faute de réseau : elle écrit dans le cache local et
inscrit l'opération dans une file persistée. Une note écrite dans le métro
survit à la fermeture de l'application.
