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

### ETag présent sur le PUT

Le `PUT` renvoie `Etag` **et** `Oc-Etag` (même valeur, entre guillemets). Lire
l'en-tête standard `ETag` suffit ; il n'y a pas besoin d'un `PROPFIND`
supplémentaire après écriture pour rafraîchir l'ETag local.

> Note annexe : le serveur annonce le protocole **TUS** (`Tus-Resumable: 1.0.0`,
> extensions `creation-with-upload`, `checksum`). Sans intérêt pour des notes
> Markdown de quelques kilo-octets, mais utile à savoir si des pièces jointes
> arrivent un jour.

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
projet. Elle se teste intégralement côté Go avec un `httptest.Server`.

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
| **2** | Modèle notes | arbre `*.md`, sous-dossiers, chemins normalisés, bootstrap du dossier `Notes` |
| **3** | Store & sync | cache, file offline persistante, conflit ETag → fichier `(conflit …)` |
| **4** | Markdown | helpers de formatage purs + rendu preview (goldmark) |
| **5** | Config | URL / driveID / racine persistés ; secrets délégués à Android |
| **6** | Façade `mobile/` | `gomobile bind` produit un `.aar` consommable |
| **7** | UI Compose | connexion → espace → navigateur → éditeur → réglages |
| **8** | CLI desktop | `opennote-cli ls / cat / put` fonctionne contre un vrai serveur |
| **9** | Build & distrib | APK signé ; distribution par APK direct / Obtainium / F-Droid |

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
