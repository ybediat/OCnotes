# OpenNote — Architecture

Application Android légère d'édition de notes Markdown stockées sur un serveur
**OpenCloud**, avec un cœur métier écrit en Go.

Statut : conception validée, implémentation non commencée.
Dernière mise à jour : 2026-08-28.

---

## 1. Décisions structurantes

| Sujet | Décision | Raison |
|---|---|---|
| Cœur métier | **Go pur**, sans dépendance UI | testable sur desktop, réutilisable, remplaçable côté UI |
| UI Android | **Kotlin / Jetpack Compose** via `gomobile bind` | la saisie de texte native (clavier, autocorrection, poignées de sélection) est *la* fonction critique d'une app de notes ; aucun toolkit GUI Go ne l'égale |
| Authentification v1 | **App Token** OpenCloud en Basic auth, derrière une interface `Authenticator` | ~50 lignes, fonctionne sur tout serveur auto-hébergé sans enregistrement OAuth |
| Authentification v2 | OIDC + PKCE, branché sur la même interface | aucun impact sur le reste du code |
| Données | **Local-first** : cache local, écriture instantanée, push en arrière-plan | utilisable hors connexion ; indispensable sur mobile |
| Plateformes | Android + un CLI desktop de test | le CLI permet d'itérer sur le cœur sans déployer sur téléphone |
| iOS | hors périmètre | exige un Mac + compte Apple à 99 $/an |

### Conséquence du choix gomobile bind

L'UI Compose n'est **pas** portable sur desktop. La cible « desktop » est donc
`cmd/opennote-cli`, un binaire de test et d'administration, pas une GUI.
Une vraie GUI desktop serait une troisième couche UI — décision reportée.

---

## 2. Ce qui a été vérifié côté OpenCloud

Sources : [WebDAV](https://docs.opencloud.eu/docs/user/admin/web-dav/),
[WebDAV dev](https://docs.opencloud.eu/docs/dev/server/apis/http/webdav/),
[App Tokens](https://docs.opencloud.eu/docs/user/admin/app-tokens/),
[LibreGraph Spaces](https://docs.opencloud.eu/de/docs/dev/server/apis/http/graph/spaces/).

### Endpoints

| Usage | Endpoint |
|---|---|
| Découverte des espaces | `GET /graph/v1.0/me/drives` |
| WebDAV (recommandé) | `/dav/spaces/{space-id}/{chemin}` |
| WebDAV (alias) | `/remote.php/dav/spaces/{space-id}/{chemin}` |
| WebDAV legacy — **à ne pas utiliser** | `/remote.php/webdav/`, `/webdav/`, `/dav/` |
| Capacités serveur | `GET /ocs/v1.php/cloud/capabilities` |
| Découverte OIDC (pour la v2) | `GET /.well-known/openid-configuration` |

`GET /graph/v1.0/me/drives` renvoie les drives accessibles ; chaque drive porte
un `id`, un `name`, un `driveType` (`personal`, `project`…) et un objet `root`
contenant `webDavUrl`. C'est cette réponse qui alimente l'écran « choix de
l'espace » — l'utilisateur n'a jamais à coller un UUID à la main.

### Méthodes WebDAV disponibles

`PROPFIND`, `GET`, `HEAD`, `PUT`, `MKCOL`, `DELETE`, `COPY`, `MOVE`,
`PROPPATCH`.

**Pas de `LOCK` / `UNLOCK`** : le serveur annonce `Dav: 1, 3, extended-mkcol`.
L'absence de la classe 2 signifie que le verrouillage WebDAV n'est pas
disponible. La gestion de concurrence repose donc **uniquement sur les ETag**
(`If-Match`), ce qui valide le choix fait en §5 — mais sans filet de secours.

### Authentification

Un **App Token** se crée depuis les réglages du compte OpenCloud (nom + date
d'expiration, affiché une seule fois). Il s'utilise comme mot de passe :

```
Authorization: Basic base64(username:app-token)
```

Le username est le login habituel, **sauf** si le fournisseur d'identité est en
mode autoprovisioning : dans ce cas seul l'UUID du compte fonctionne (visible
dans la page Préférences).

> **Validé le 2026-08-28** sur `opencloud.a.rsrh.ovh` (OpenCloud 7.0.0, derrière
> openresty). L'App Token passe en Basic auth sans configuration serveur
> particulière : `capabilities`, `me/drives`, `PROPFIND`, `PUT` et `DELETE`
> répondent tous correctement. **La stratégie d'auth v1 est confirmée.**

---

## 2 bis. Pièges confirmés sur le serveur réel

Quatre comportements observés dans les fixtures que la documentation ne
mentionne pas, et qui contraignent directement le code.

### Les identifiants d'espace contiennent un `$`

Le format réel est `{storageId}${spaceId}` :

```
428276b3-c8db-4d39-9a03-0230e8347c7e$34c5fd19-31ea-4d3e-94bb-8029b8903d31
```

Conséquences :

- **Ne jamais percent-encoder ce `$`.** Le serveur l'attend littéralement.
  En Go, construire l'URL avec `url.URL{Path: …}` puis `.String()` : le `$` est
  un *sub-delim* RFC 3986, autorisé tel quel dans un chemin, et Go ne l'échappe
  pas. Utiliser `url.PathEscape` sur le chemin complet serait doublement faux —
  ça échapperait aussi les `/`.
- Dans un script shell ou PowerShell, ne jamais écrire un identifiant d'espace
  en dur dans une chaîne interpolée : `"$34c5fd19-…"` serait lu comme une
  variable.

Les identifiants de ressource (`oc:fileid`) ajoutent un second séparateur :
`{storageId}${spaceId}!{opaqueId}`.

### PROPFIND renvoie deux blocs `propstat` par entrée

Sur une collection, le serveur répond avec **un `propstat` en `200 OK`** pour
les propriétés trouvées et **un second en `404 Not Found`** pour celles qui
n'existent pas sur ce type de ressource (`getcontentlength` et `getcontenttype`
sur un dossier) :

```xml
<d:response>
  <d:href>/dav/spaces/…/</d:href>
  <d:propstat>
    <d:prop><d:getetag>"6aaca5f8…"</d:getetag><d:resourcetype><d:collection/></d:resourcetype></d:prop>
    <d:status>HTTP/1.1 200 OK</d:status>
  </d:propstat>
  <d:propstat>
    <d:prop><d:getcontentlength></d:getcontentlength><d:getcontenttype></d:getcontenttype></d:prop>
    <d:status>HTTP/1.1 404 Not Found</d:status>
  </d:propstat>
</d:response>
```

**Le parser doit filtrer sur le `<d:status>` du `propstat` et ne lire que les
propriétés du bloc `200`.** Un parser naïf qui collecte tous les `<d:prop>`
d'un `<d:response>` récupérerait des chaînes vides et conclurait à tort qu'un
dossier est un fichier de taille nulle.

### Tous les drives ne sont pas des espaces de stockage

`me/drives` renvoie aussi un drive `driveType: "virtual"` nommé *Shares*, avec
un ETag factice (`DECAFC00FEE`). Ce n'est pas une racine de notes valide.
Le sélecteur d'espace doit retenir `personal` en priorité et **exclure
`virtual`**. Les autres valeurs possibles sont `project` et `mountpoint`.

### `If-None-Match: *` est ignoré

Mesuré le 2026-08-28 par `TestIntegrationWriteNewEtIfNoneMatch` : un `PUT`
portant `If-None-Match: *` **écrase quand même** une note existante, sans
erreur. Le serveur n'implémente pas cette précondition.

La conséquence est directe. Une note créée hors connexion n'a pas d'ETag : au
moment de la pousser, rien ne dit qu'un fichier du même nom n'est pas apparu
entre-temps. S'en remettre à `If-None-Match` laisse donc écraser le travail
fait ailleurs — c'est arrivé en vrai, depuis un téléphone en mode avion.

La synchronisation **vérifie donc explicitement l'existence** avant de pousser
une création (`store.pushWrite`). L'en-tête reste envoyé comme seconde
barrière, sans qu'on compte dessus.

### Le rafraîchissement à l'ouverture n'est pas un confort

Une note ouverte sans être relue depuis le serveur garde un ETag qui vieillit.
Dès que quelqu'un la modifie ailleurs, la prochaine écriture depuis le
téléphone part avec une version périmée, le serveur la refuse, et le mécanisme
de conflit crée un doublon — alors qu'il n'y avait rien à arbitrer.

C'est ce qui rendait les conflits envahissants à l'usage. `ReadNote` relit donc
toute note **propre** avant de la rendre. Une note portant des modifications
locales n'est jamais rafraîchie : le brouillon prime jusqu'à la
synchronisation.

### Une écriture qui n'écrit rien n'est pas une écriture

Le rafraîchissement ci-dessus ne corrigeait qu'une moitié du problème, et
l'autre moitié le rouvrait par l'autre bout : l'éditeur enregistre en quittant
l'écran, sans regarder si le texte a bougé. **Consulter une note suffisait donc
à la salir.** Or une note sale n'est pas rafraîchie à l'ouverture — c'est la
règle juste au-dessus. Son ETag vieillissait précisément pendant la fenêtre où
il ne devait pas, et la première modification faite depuis le navigateur
devenait un conflit, avec sa copie. Autant de copies que de notes simplement
lues : quatre copies pour un seul vrai conflit, constaté à l'usage.

Deux gardes, indépendantes, dans le cœur Go plutôt que dans l'interface — c'est
la seule couche que tous les chemins d'écriture traversent, et la seule qui se
teste sans appareil :

- **`store.Put` ignore un contenu identique à celui du cache.** Ni `Dirty`, ni
  file, ni `LocalMod` : rien ne bouge. Sauf si la note est sale sans avoir
  d'écriture en file — un renommage déplace l'entrée sans déplacer l'opération
  en attente — auquel cas le chemin normal la remet en file.
- **`Entry.BaseHash`** retient l'empreinte du dernier contenu sur lequel le
  cache et le serveur étaient d'accord. `Dirty` dit qu'une écriture reste à
  propager ; la base dit si elle a *quelque chose* à propager. Ce n'est pas la
  même question, et les confondre coûtait une copie.

La base sert deux fois. `pushWrite` saute l'envoi quand le serveur détient déjà
exactement ce contenu — un PUT inutile changerait l'ETag et la date pour tous
les autres appareils. Et `resolveConflict` peut enfin compter trois versions au
lieu de deux : un refus du serveur dit que la version distante a bougé, pas que
la locale a quelque chose à lui opposer. Quand `local == base`, le serveur
l'emporte en silence.

Un index écrit avant l'apparition du champ porte une base vide. Il n'est pas
invalidé pour autant — il transporte la file d'attente, et la jeter perdrait
des écritures faites hors connexion. Une base vide veut dire « on ne sait
pas », et l'ancien comportement s'applique : la copie est conservée.

Ce n'est **pas** de la gestion de conflit. Rien n'est fusionné, rien n'est
comparé ligne à ligne. On distingue seulement « je n'ai rien à dire » de « nous
avons tous les deux quelque chose à dire ».

### On n'écrit que ce qu'on a su lire

Le même enregistrement de sortie portait un défaut plus grave que la copie en
trop, et qui ne relevait plus de la synchronisation : l'état initial de
l'éditeur porte un **texte vide**, et un chargement qui échoue le laisse vide en
repassant `chargement` à faux. Quitter l'écran à ce moment-là enregistrait cette
chaîne vide par-dessus la note. Une note jamais mise en cache, ouverte sans
réseau, était ainsi **effacée sur le serveur sans avoir jamais été affichée**.

`EditorUiState.charge` est ce troisième état — ni « en cours », ni « fini »,
mais « lu ». Seul le chemin de succès le pose, et `EditorUiState.enregistrable`
en fait la condition de toute écriture. La règle est une propriété de l'état
plutôt qu'une suite d'appels, et c'est délibéré : la course qu'elle protège
n'est pas reproductible à la main, alors qu'une fonction de deux booléens se
vérifie sur la JVM, sans appareil ni Robolectric. C'est
`EditeurEnregistrableTest`.

Corollaire à l'écran : un chargement échoué n'affiche plus de champ de saisie
mais un `EtatVide`. Un champ vide laisserait croire à une note vide, et ce qu'on
y taperait partirait par-dessus un contenu qu'on n'a pas réussi à lire.

### ETag présent sur le PUT

Le `PUT` renvoie `Etag` **et** `Oc-Etag` (même valeur, entre guillemets). Lire
l'en-tête standard `ETag` suffit ; il n'y a pas besoin d'un `PROPFIND`
supplémentaire après écriture pour rafraîchir l'ETag local.

> Note annexe : le serveur annonce le protocole **TUS** (`Tus-Resumable: 1.0.0`,
> extensions `creation-with-upload`, `checksum`). Sans intérêt pour des notes
> Markdown de quelques kilo-octets, mais utile à savoir si des pièces jointes
> arrivent un jour.

### Le serveur accepte tous les caractères testés dans les noms

Mesuré par `TestIntegrationNomsDeFichiers` : **14 noms sur 14 acceptés**, écrits,
relus à l'identique et retrouvés dans un listing. Y compris les caractères qui
ont un sens dans une URL :

```
espaces   accents   ( )   &   +   #   ?   %   '   ,   ;   =   @   ~   ^   emoji
```

Go encode correctement chacun d'eux via `url.URL.Path`, et OpenCloud les
restitue tels quels.

**Conséquence pour la brique 3, et elle est contre-intuitive : le serveur est
plus permissif que le cache local.** Une note nommée `point d'interrogation ?.md`
existe sans problème sur le serveur, mais ne peut pas être écrite sous ce nom
dans un fichier Windows (`? < > : " | * \ /` sont interdits), ni sous un nom
contenant `/` sur Android.

Le cache local **ne doit donc pas refléter les noms du serveur**. Il faut une
indirection : nommer les fichiers du cache par une empreinte (ou par le
`oc:fileid`, qui est stable et sûr) et conserver le nom réel dans l'index. Un
cache qui recopierait les noms échouerait sur des notes parfaitement valides.

---

## 3. Arborescence

```
opennote/
├── go.mod
├── internal/
│   ├── opencloud/     client HTTP, auth, graph/drives, WebDAV        [Go pur]
│   ├── notes/         arbre de notes, sous-dossiers, chemins         [Go pur]
│   ├── store/         cache local, file offline, ETags, conflits     [Go pur]
│   ├── markdown/      helpers de mise en forme + rendu preview       [Go pur]
│   └── config/        URL serveur, driveID, racine, préférences      [Go pur]
├── mobile/            façade gomobile bind (contraintes de types)
├── cmd/opennote-cli/  harnais de test desktop
└── android/           projet Gradle, UI Jetpack Compose
```

`internal/` ne connaît ni Android, ni gomobile, ni Compose. Il se compile et se
teste sur Windows avec `go test ./...`.

---

## 4. La frontière Go ↔ Kotlin

`gomobile bind` n'accepte qu'un sous-ensemble très restreint de types dans la
signature des fonctions exportées :

- entiers signés, flottants, `string`, `bool`, `[]byte`
- structs et interfaces dont **tous** les membres exportés respectent ces règles
- `error` en dernière valeur de retour → devient une exception Java

**Interdits : maps, slices (sauf `[]byte`), channels, génériques, entiers non signés.**

La façade `mobile/` contourne cette contrainte en sérialisant les données
composites en **JSON**. Les charges utiles sont petites (listes de fichiers,
métadonnées), le coût est négligeable, et ça découple les versions Go et Kotlin.

Forme visée de la façade :

```go
package mobile

type App struct{ /* champs non exportés */ }

func NewApp(dataDir string) *App

// Connexion et espaces
func (a *App) Connect(serverURL, username, appToken string) error
func (a *App) ListDrivesJSON() (string, error)
func (a *App) SelectRoot(driveID, path string) error

// Navigation et notes
func (a *App) ListFolderJSON(path string) (string, error)
func (a *App) ReadNote(path string) (string, error)
func (a *App) WriteNote(path, content string) error
func (a *App) CreateFolder(path string) error
func (a *App) Rename(oldPath, newPath string) error
func (a *App) Delete(path string) error

// Mise en forme — délègue à internal/markdown, testé côté Go
func (a *App) ApplyFormatJSON(reqJSON string) (string, error)

// Événements poussés vers Kotlin
type SyncListener interface {
    OnSyncStateChanged(stateJSON string)
    OnError(message string)
}

func (a *App) SetSyncListener(l SyncListener)
```

Règle : **aucune logique métier dans `mobile/`**. C'est un adaptateur — il
sérialise, désérialise, et délègue à `internal/`.

---

## 5. Modèle de synchronisation (local-first)

1. Toute écriture va d'abord dans le cache local et est immédiatement visible.
2. Une opération est empilée dans une file persistante (survit au kill de l'app).
3. Un worker draine la file dès que le réseau est disponible.
4. Chaque note garde l'**ETag** de sa dernière version connue du serveur.
5. Le `PUT` part avec `If-Match: <etag>`.
   - `200` / `204` → succès, on met à jour l'ETag.
   - `412 Precondition Failed` → le serveur a une version plus récente : **conflit**.
6. Résolution de conflit v1, volontairement bête et non destructive : la version
   locale est écrite à côté sous `note (conflit 2026-08-28T14-32-05).md`, la
   version serveur devient la note de référence. Aucune fusion automatique,
   aucune perte de données.

La détection de conflit par ETag est le point technique le plus délicat du
projet. Elle est vérifiée côté Go avec un `httptest.Server`, et de bout en bout
contre un vrai serveur par `TestIntegrationConflitETag`.

### Ce que l'implémentation a figé

**Le cache ne recopie pas les noms du serveur.** Chaque note est stockée sous
une empreinte SHA-256 de son chemin, et le vrai nom vit dans l'index. C'est la
conséquence directe de la découverte du §2 bis : une note nommée
`point d'interrogation ?.md` est valide côté OpenCloud mais impossible à écrire
sous ce nom dans un fichier Windows. L'empreinte porte sur le chemin et non sur
l'`oc:fileid`, car une note créée hors connexion n'a pas encore d'identifiant
serveur.

**Les écritures répétées sont absorbées.** Un éditeur qui enregistre à chaque
frappe empilerait des centaines d'opérations identiques ; la file n'en garde
qu'une par chemin, puisque le contenu poussé est relu dans le cache au moment
de l'envoi. Une suppression annule l'écriture en attente sur le même chemin.

**Une erreur réseau interrompt la passe, un conflit non.** Les opérations sont
rejouées dans l'ordre — un déplacement suivi d'une écriture n'a pas le même
effet dans l'autre sens — donc la première panne réseau arrête tout et laisse
le reste en file. Un conflit, lui, se résout sur place et la passe continue.

**Un ETag périmé sans divergence de contenu n'est pas un conflit.** Comparer
les contenus avant de créer une copie évite de polluer le dossier de l'utilisateur
quand la cause est une passe précédente interrompue.

**Les mutations structurelles différées sont protégées côté client.** Le
serveur OpenCloud ignore `If-Match` sur `DELETE` et `MOVE` et n'annonce pas la
classe DAV 2 : il n'existe donc ni précondition fiable, ni verrou WebDAV. La
file mémorise l'ETag vu au geste local, puis relit la ressource avant de la
supprimer ou déplacer. Si l'ETag diffère, la mutation distante n'est pas
envoyée : une suppression est annulée ; un déplacement laisse la source
distante à sa place et publie la version locale sous un nom de conflit à la
destination. La lecture puis mutation reste non atomique ; c'est une réduction
forte du risque, pas une promesse de sérialisation serveur.

**Une opération structurelle sans ETag n'est jamais destructive.** C'est le
cas d'une file écrite par une ancienne version de l'application ou d'une note
jamais observée : elle produit le même état de conflit prudent. Les dossiers
ne disposent pas d'un ETag représentant leur descendance ; leurs suppressions,
renommages et déplacements différés sont donc refusés en v1.

**Une passe de synchronisation est sérialisée dans `mobile.App`.** Les trois
travaux WorkManager et la synchronisation manuelle peuvent coexister, mais un
sémaphore unique couvre la durée entière de `SyncJSON`. L'attente est bornée
par le délai de passe, sans verrouiller l'ouverture ou la fermeture de session.

**L'index est écrit par fichier temporaire renommé**, et un index illisible
fait repartir le cache à vide plutôt qu'échouer : perdre le cache est bénin,
refuser de démarrer ne l'est pas.

---

## 6. Secrets

Le Keystore Android n'est pas atteignable depuis du Go pur. Deux options :

- **v1** : chiffrement AES-GCM d'un fichier dans le stockage privé de l'app.
  Protège contre la lecture par une autre app, pas contre un appareil rooté.
- **v2 (recommandé, puisqu'on a déjà du Kotlin)** : `EncryptedSharedPreferences`
  côté Android, qui s'appuie sur le Keystore matériel. Le token est passé à Go à
  chaque démarrage via `Connect()`. **Go ne persiste alors aucun secret.**

Vu le choix gomobile bind, la v2 est accessible dès le départ et strictement
meilleure. `internal/config` ne stocke donc que du non-sensible (URL serveur,
driveID, chemin racine, préférences).

---

## 7. Briques de travail

Ordre indicatif. Les briques 4 et 8 sont parallélisables dès le début.

| # | Brique | Critère de sortie |
|---|---|---|
| **0** | Toolchain | `go version`, `gomobile version` et `adb devices` répondent |
| **1a** | ~~Spike auth~~ **fait** | `scripts/spike-auth.ps1` — 4/4 le 2026-08-28 |
| **1a-bis** | ~~Spike WebDAV~~ **fait** | `scripts/spike-webdav.ps1` — 9/9 le 2026-08-28, `412` sur `If-Match` périmé confirmé |
| **1b** | ~~Client OpenCloud~~ **fait** | `internal/opencloud` — `List`, `Stat`, `Read`, `Write`, `Mkdir`, `MkdirAll`, `Move`, `Remove` ; 25 tests au vert |
| **1c** | ~~Découverte espaces~~ **fait** | `ListDrives` + `PersonalDrive`, qui écarte l'espace virtuel |
| **2** | ~~Modèle notes~~ **fait** | `internal/notes` — `Library`, validation de nommage, bootstrap, anti-collision |
| **3** | ~~Store & sync~~ **fait** | `internal/store` — cache, file offline persistante, conflit ETag → copie `(conflit …)` |
| **4** | ~~Markdown~~ **fait** | `internal/markdown` — 13 actions de mise en forme + extraction de titre |
| **4-bis** | ~~Rendu preview~~ **fait** | `internal/markdown/render.go` — goldmark en analyseur seul, blocs plats vers Compose ; 18 cas |
| **5** | ~~Config~~ **fait** | `internal/config` — URL, compte, espace, URL WebDAV, racine ; **aucun secret**, vérifié par un test |
| **6** | ~~Façade `mobile/`~~ **écrite** | 23 méthodes, contrat gelé dans [FACADE.md](FACADE.md), compatibilité gomobile vérifiée par analyse syntaxique |
| **7** | ~~UI Compose~~ **compile** | 43 fichiers sous `android/`, AAR généré, APK produit — jamais exécutée sur un appareil |
| **9** | Build & distrib | APK release à 9,2 Mo ; signature et distribution restent à faire |
| **8** | ~~CLI desktop~~ **fait** | `cmd/opennote-cli` — `drives`, `ls`, `tree`, `cat`, `put`, `mkdir`, `mv`, `rm` |
| **9** | Build & distrib | APK signé ; distribution par APK direct / Obtainium / F-Droid |

### Où en est la vérification

Trois niveaux, à ne pas confondre.

**Briques 1 à 6 — vérifiées par des tests.** 191 cas unitaires, plus des tests
d'intégration contre un vrai serveur OpenCloud 7.0.0.

**Brique 7 — compile, mais n'a jamais tourné.** L'AAR se génère, l'APK se
construit en debug comme en release. Deux corrections ont suffi au premier
build : `\.` n'est pas un échappement Kotlin valide dans
`settings.gradle.kts`, et `android:Theme.Material.DayNight` n'existe pas dans
le framework — le mode sombre passe par `values-night/`. Une troisième a
débloqué R8 : Tink référence des annotations absentes à l'exécution.

Mais **compiler n'est pas fonctionner**. Aucun écran n'a été affiché, aucune
note écrite depuis un téléphone, aucun conflit provoqué en conditions réelles.
La première exécution reste à faire.

Cette distinction est volontairement explicite : confondre « écrit »,
« compile » et « testé » est la façon la plus sûre de laisser dormir un bug
pendant des semaines.

### Poids mesuré

Le critère « application légère » était listé comme un risque. Mesures réelles,
ABI `arm64-v8a` seule :

| | Taille |
|---|---|
| `libgojni.so` sans `-ldflags="-s -w"` | 14 Mo |
| `libgojni.so` avec | 7,2 Mo |
| APK debug | 32,9 Mo |
| APK release avant goldmark | 8,5 Mo |
| **APK release (R8 + shrink)** | **9,2 Mo** |

Le risque est écarté : 9,2 Mo pour une application Compose embarquant le
runtime Go complet, l'analyseur Markdown compris. L'aperçu a coûté environ
0,7 Mo — mesuré, pas estimé.

### Brique 4 — pourquoi elle est isolée

Les helpers de mise en forme sont des fonctions **pures** :

```
(texte, début_sélection, fin_sélection) → (nouveau_texte, nouvelle_sélection)
```

Gras, italique, titre H1-H3, liste à puces, liste numérotée, case à cocher,
lien, code inline, bloc de code, citation. Aucune dépendance UI, entièrement
testable en table-driven tests. La barre d'outils Compose se contente d'appeler
ces fonctions et de réappliquer la sélection retournée.

C'est ce qui évite de réimplémenter — et de déboguer — la logique de formatage
en Kotlin.

---

## 7 bis. L'éditeur sur une grande note — ce qui a été mesuré

Le défilement d'une grande note était décrit comme « poussif ». La mesure dit
autre chose, et de plus loin : **l'éditeur cesse d'être fluide vers 80 lignes**,
soit une dizaine de ko de prose. Il ne s'agit ni de la mise en page, ni de la
quantité de texte tenue en mémoire.

### Le banc

Redmi Note 12 (`topaz`), 1080×2400 à 440 dpi, Compose BOM 2024.12.01, build
`debug`. Réseau coupé, pour que la synchronisation n'entre pas dans la mesure.
Des notes de tailles graduées, taillées dans une vraie note pour garder une
structure de prose réaliste, injectées dans le cache local ; chacune ouverte
puis balayée par six `input swipe` identiques.

```powershell
./scripts/banc-editeur.ps1 -Note "scolarisation des enfants rrom"
```

Le script coupe le réseau, ouvre la note par la recherche, mesure l'ouverture
puis six balayages, et rétablit le réseau même en cas d'échec. Il vérifie l'état
de l'écran à chaque étape — sans quoi les chiffres sont faux sans le dire.

`framestats` donne une ligne par image. Les deux colonnes qui décident sont
`PerformTraversalsStart → DrawStart` — mesure et layout — et
`DrawStart → SyncQueued` — l'enregistrement de la display list.

**Un piège du banc lui-même, qui a produit une première série de chiffres
fausse :** un tap perdu ne se voit pas dans les résultats. Le thread UI reste
bloqué assez longtemps pour que l'appui soit avalé ; on mesure alors le
défilement de la *liste* au lieu de celui de l'éditeur, et le chiffre obtenu est
parfaitement plausible. Tout état d'écran doit être vérifié — `uiautomator dump`
et un marqueur exclusif — avant et après chaque mesure. Se fier au processus ne
suffit pas non plus : MIUI le garde en vie alors que l'application est passée en
arrière-plan.

### Ce que coûte le défilement

| note | octets | lignes | images rendues | en retard | médiane/image | dessin moyen | dessin à l'ouverture |
|---|---|---|---|---|---|---|---|
| bench-010k | 9 422 | 76 | 118 | 90 % | 48 ms | 18,2 ms | 148 ms |
| bench-025k | 25 197 | 196 | 40 | 85 % | 101 ms | 39,7 ms | 204 ms |
| bench-050k | 51 160 | 376 | 30 | 80 % | 150 ms | 70,7 ms | 233 ms |
| bench-100k | 102 377 | 838 | 23 | 78 % | 200 ms | 129,8 ms | 436 ms |
| bench-200k | 204 698 | 1 633 | 18 | 67 % | 350 ms | 243,4 ms | 866 ms |
| note réelle | 294 576 | 2 655 | 18 | 67 % | 500 ms | 360,8 ms | 1 366 ms |

Deux points de comparaison, même appareil, même geste :

- une note de 445 octets — une vingtaine de lignes — dans le même éditeur :
  **4,08 ms** de dessin, 3 % d'images en retard ;
- **la même note de 294 576 octets dans l'aperçu** : **1,77 ms** de dessin,
  212 images rendues là où l'éditeur en rend 18.

### Tout est dans la phase de dessin

`PerformTraversalsStart → DrawStart` mesure **0,11 ms dans toutes les
conditions** — 445 octets comme 295 ko, au repos comme pendant la frappe, et
jusqu'à la toute première ouverture. Le GPU tient 7 à 9 ms, le RenderThread 1,3
à 6,7 ms. Tout le temps est dans `DrawStart → SyncQueued`.

Ce n'est donc pas la mise en page qui coûte, et la conséquence est immédiate :
**passer à `BasicTextField` + `TextFieldState` ne changerait rien au
défilement.** C'était la piste évidente — supprimer l'aller-retour du
`TextFieldValue` par le `StateFlow` du ViewModel — et la mesure l'a écartée
avant qu'elle ne coûte une journée de travail.

Le coût suit le nombre de lignes, à **0,14 à 0,24 ms par ligne**, et il se paie
sur deux images sur trois pendant le défilement : la troisième réutilise la
display list. Autrement dit, chaque changement d'offset fait **ré-enregistrer
tout le document**, y compris les 99 % hors écran. C'est du repeint et non une
reconstruction : à l'ouverture, où les `StaticLayout` sont réellement
construits, la même note coûte 1 366 ms contre 361 en défilement.

### Ce que coûte la frappe

Cinq caractères tapés au milieu du texte, champ déjà en saisie.

| note | lignes | médiane/image | dessin moyen | dessin max |
|---|---|---|---|---|
| bench-025k | 196 | 105 ms | 59,0 ms | 97,6 ms |
| bench-200k | 1 633 | **750 ms** | 424,7 ms | 678,6 ms |

**750 ms par caractère sur 200 ko.** L'éditeur n'est donc pas seulement
désagréable à faire défiler : il est inutilisable en saisie bien avant la taille
qui avait motivé l'enquête. La frappe coûte environ 1,75 fois le défilement, ce
qui est cohérent — elle invalide la mise en page du texte, là où le défilement se
contente de la repeindre.

### Ce que coûte l'inaction

L'éditeur ouvert sur la note de 295 ko, le champ focalisé, **personne ne
touchant à rien** : 16 images en 8 secondes, 100 % en retard, 550 ms de dessin
chacune.

C'est le curseur qui clignote. Il invalide le nœud de dessin deux fois par
seconde, et chaque invalidation coûte un ré-enregistrement complet du document.
Le coût n'est donc pas payé pendant l'interaction : il est payé **en
permanence**, dès que le curseur est dans le texte — 550 ms de travail toutes
les 500 ms. Le thread UI ne redescend jamais.

C'est ce qui explique les appuis perdus, et ça se constate d'une seconde façon :
`uiautomator dump` ne rend plus aucun arbre sur cet écran, faute de voir la
fenêtre au repos une seule fois.

### Le second plafond, celui qui ne se négocie pas

La piste évidente contre un dessin proportionnel au document est de ne plus
repeindre du tout : sortir le défilement du champ, laisser le `TextField`
prendre sa hauteur naturelle dans un `verticalScroll` à nous, et le promouvoir
en `graphicsLayer` pour que le défilement translate une `RenderNode` au lieu de
la ré-enregistrer.

Essayé. **L'application plante à l'ouverture :**

```
java.lang.IllegalArgumentException: Can't represent a width of 1058
and height of 531251 in Constraints
  at androidx.compose.material3.TextFieldMeasurePolicy.measure(TextField.kt:703)
```

Compose empaquette largeur et hauteur dans un seul `Long` ; à cette largeur, la
hauteur dispose de 18 bits, soit 262 143 px. Ce document en réclame 531 251.
**Un champ de saisie en hauteur libre est donc impossible au-delà d'environ
1 300 lignes affichées**, quel que soit son coût de dessin. L'aperçu y échappe
pour la raison même qui le rend rapide : une `LazyColumn` ne mesure jamais la
hauteur totale de son contenu.

Le plafond de 262 143 px vient du schéma d'empaquetage de Compose ; le fait
mesuré, lui, est le refus de 531 251.

### Le texte brut fait planter l'aperçu

Constaté, reproduit deux fois sur deux, sur une copie `.txt` de la note de test
(292 026 octets). Ouvrir la note puis toucher le bouton d'aperçu tue
l'application :

```
java.lang.IllegalArgumentException: Can't represent a width of 0
and height of 444403 in Constraints
  at androidx.compose.foundation.layout.IntrinsicHeightNode.calculateContentConstraints
  at androidx.compose.foundation.layout.IntrinsicSizeModifier.measure
```

C'est le même plafond que plus haut — 262 143 px — atteint par un autre chemin,
et cette fois dans le code livré, pas dans une expérience.

La cause tient en deux lignes qui ne se regardent jamais :

- **`markdown.RenderPlain` renvoie un bloc unique** portant tout le fichier.
  Pour du Markdown, `Render` produit un bloc par paragraphe — 1 105 sur cette
  note — et la `LazyColumn` n'en compose que le visible. Pour du texte brut,
  elle n'a qu'un seul élément : **elle ne virtualise rien.**
- **`MarkdownView.kt` mesure chaque bloc en `Modifier.height(IntrinsicSize.Min)`**,
  pour que les barres de citation (`fillMaxHeight`) aient la hauteur du bloc.
  Cela force le calcul de la hauteur intrinsèque du bloc — 444 403 px — et
  `Constraints.fixedHeight` déborde.

Conséquences pratiques : OpenCloud crée ses fichiers en `.txt`, donc le cas
n'est pas exotique. Et la prémisse « l'aperçu est fluide, on peut s'appuyer
dessus », vraie et mesurée pour le Markdown — 1,77 ms sur 295 ko — **est fausse
pour le texte brut**, où l'aperçu ne s'ouvre pas du tout.

L'éditeur, lui, se comporte pareil sur les deux formats : 500 ms de médiane et
323 ms de dessin sur le `.txt`, contre 500 et 341 sur le `.md`. C'est le même
`TextField` des deux côtés.

**Corrigé, et vérifié sur l'appareil.** `RenderPlain` découpe désormais en
blocs d'au plus `maxPlainLines` lignes, en cherchant la coupure sur une ligne
vide pour que l'espacement de la `LazyColumn` tombe là où le texte respirait
déjà. Aucun caractère n'est perdu — la concaténation des blocs rend le texte
d'entrée, et `TestRenderPlainNePerdRien` le vérifie.

| aperçu du `.txt` de 292 ko | avant | après |
|---|---|---|
| résultat | **plantage** | 226 images rendues |
| médiane par image | — | 13 ms |
| dessin | — | **1,27 ms** |

Soit le même régime que l'aperçu Markdown sur la note équivalente : 1,77 ms de
dessin, 14 ms de médiane. Le texte brut n'est plus un cas à part.

Reste non mesuré, et de même nature : un fichier **Markdown** d'un seul
paragraphe démesuré produirait lui aussi un bloc unique, donc la même hauteur
intrinsèque. `ShortenLongWords` ne couvre pas ce cas — il borne la longueur d'un
mot, pas celle d'un bloc.

### Ce que ça impose

Deux bornes indépendantes sur ce qu'un champ de saisie peut contenir :

| borne | valeur | nature |
|---|---|---|
| budget de 16 ms par image | ~80 lignes | performance |
| `Constraints` de Compose | ~1 300 lignes | plantage |

La première est de loin la plus serrée : **80 lignes, c'est deux à trois
écrans.** L'unité éditable doit donc avoir la taille d'un écran, pas celle d'un
chapitre. Cela écarte le découpage « une section = un titre » et rapproche la
réponse d'une `LazyColumn` de petits champs — le seul montage où *aucun* champ
ne grandit avec le document.

Deux faits relevés sur la note réelle achèvent d'écarter le découpage aux
titres : elle ne contient **aucun titre ATX**, et aucune donnée `data:`. Un
repli par groupes de paragraphes n'y serait pas un cas limite, mais le cas
nominal.

**La virtualisation n'est pas une optimisation de l'éditeur : c'est la seule
chose qui tienne.**

### Après la virtualisation — même appareil, même note, même geste

Mesuré le 31 août 2026 sur l'écran intégré, Redmi Note 12, réseau coupé, la
note réelle de 294 576 octets. **Huit passes de défilement et sept de frappe**,
dont les fourchettes disent la dispersion.

| défilement, 6 balayages | avant | après (8 passes) |
|---|---:|---:|
| images rendues | 18 | **221 à 283** |
| images en retard | 67 % | 12 à 32 % |
| médiane par image | 500 ms | **11 à 14 ms** |
| 90e percentile | — | 17 à 18 ms |
| dessin moyen | 360,8 ms | **0,75 à 1,34 ms** |
| dessin max | — | 2,45 à 4,29 ms |
| mesure + layout | 0,11 ms | 0,13 à 0,14 ms |
| dessin à l'ouverture | 1 366 ms | 1,63 à 4,45 ms |

Le dessin ne suit plus la taille du document, et le chiffre qui le dit le mieux
n'est pas le rapport de 340 : **moins d'une milliseconde sur 295 ko, contre
4,08 ms mesurés dans l'ancien éditeur sur une note de 445 octets.** L'éditeur
virtualisé dessine la note entière moins cher que le monolithique ne dessinait
vingt lignes.

Les passes tardives sont systématiquement meilleures que les premières — 0,75
contre 1,34 ms de dessin, 12 % d'images en retard contre 32 %. L'écart n'a pas
été instruit ; il ne change pas la conclusion, les deux extrémités tenant le
budget avec plus d'un ordre de grandeur de marge.

**La frappe est trente fois moins chère, et reste au-dessus de sa cible.**

Cinq caractères ne produisent que 17 à 21 images : à 750 ms l'image, une médiane
sur vingt valeurs suffisait à conclure ; à 25 ms, non. Le protocole a donc été
porté à quarante caractères, soit une centaine d'images — et la médiane n'a pas
bougé. **Ce n'est pas un artefact d'échantillonnage.**

| frappe | avant, 205 ko | après, 295 ko, 5 car. | après, 40 car. |
|---|---:|---:|---:|
| images rendues | — | 17 à 21 | **95 à 108** |
| médiane par image | 750 ms | 24 à 36 ms | **23 à 26 ms** |
| 90e percentile | — | 31 à 48 ms | 27 à 32 ms |
| dessin moyen | 424,7 ms | 5,0 à 6,4 ms | **4,4 à 5,5 ms** |
| dessin max | 678,6 ms | 14,5 à 30,5 ms | 11,3 à 13,1 ms |

Décomposition d'une passe de 105 images, la seule instrumentée sur toutes les
phases :

| phase | frappe | défilement |
|---|---:|---:|
| avant `PerformTraversalsStart` | 6,45 ms | 2,91 ms |
| mesure + layout | 0,11 ms | 0,14 ms |
| enregistrement de la display list | **4,46 ms** | 0,75 ms |
| `RenderThread` | 2,61 ms | 2,07 ms |
| image complète | 22,52 ms | 3,85 ms |

**Les deux colonnes que la section 6 du chantier désigne comme décisives sont
tenues avec deux ordres de grandeur de marge** : 0,11 ms de mesure et layout,
4,46 ms de display list, contre 424,7 ms avant. Ce qui reste tient dans deux
postes que le chantier n'a jamais prétendu traiter : 6,45 ms avant qu'une seule
ligne de l'application ne s'exécute — attente du vsync et remise de l'événement
d'entrée, gonflée par l'injection ADB — et 9 à 10 ms de GPU au 50e percentile.

Deux conclusions, à ne pas confondre :

- l'objectif du chantier est atteint : **le coût de dessin ne dépend plus de la
  taille du document**, ni en défilement ni en frappe ;
- le critère de recette tel qu'il est écrit — « frappe, médiane par image
  ≤ 20 ms » — **ne l'est pas**, à 23-26 ms, de façon stable et reproduite. Il
  avait été rédigé quand le dessin faisait toute l'image ; il mesure désormais
  surtout des phases étrangères au sujet. Le trancher demande une frappe à la
  main, sans `adb input` — ce banc ne sait pas la produire.

**Le banc lui-même a dû être adapté, et une négligence sur ce point a détruit
des données.** Ses deux marqueurs d'écran désignaient l'ancien champ
monolithique : « un `EditText` haut » pour l'éditeur, « le seul `EditText`
mince » pour la barre de recherche. Le premier ne reconnaissait plus un éditeur
pourtant ouvert ; le second, corrigé trop tard, a pris la petite fenêtre active
de l'éditeur pour la barre de recherche et a envoyé « tout sélectionner,
supprimer, taper » dans une vraie note. Les deux critères sont désormais
relatifs à la zone défilante — au-dessus, c'est la recherche ; dedans, c'est le
contenu — et `Enter-Note` refuse de taper tant qu'il n'a pas vérifié *quel*
champ a le focus. Le détail est dans l'en-tête de `scripts/banc-editeur.ps1`.

---

## 8. Risques identifiés

| Risque | Impact | Mitigation |
|---|---|---|
| ~~App Tokens indisponibles sans config admin~~ | — | **écarté** : validé le 2026-08-28 |
| `If-Match` non honoré par le serveur | la brique 3 perd sa détection de conflit | brique 1a-bis ; repli sur une comparaison de contenu avant push |
| Pas de verrouillage WebDAV (classe 2 absente) | deux appareils peuvent écrire en même temps | conflit non destructif par ETag, aucune fusion auto |
| Divergences WebDAV entre versions d'OpenCloud | parsing cassé | tests sur les fixtures XML réelles capturées du serveur cible |
| `gomobile bind` : friction sur les types | ralentit l'intégration | frontière JSON, façade mince, décidé dès le départ |
| Sync bidirectionnelle plus complexe que prévu | dérapage planning | conflit non destructif, aucune fusion auto en v1 |
| Poids de l'APK (runtime Go + Compose) | critère « légère » | mesurer tôt ; `-ldflags="-s -w"`, ABI `arm64-v8a` seule |
| ~~Éditeur non virtualisé sur une grande note~~ | — | **écarté** : éditeur virtualisé intégré et mesuré le 2026-08-31 — 1,1 ms de dessin sur 295 ko contre 360,8. La frappe, à 25 ms de médiane, reste au-dessus des 20 ms visés sur un échantillon court (section 7 bis) |
