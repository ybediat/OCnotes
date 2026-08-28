# OpenNote — Mise en place de l'environnement

Cible : Windows 11. Vérifié le 2026-08-28 sur cette machine.

## État initial constaté

| Outil | État |
|---|---|
| JDK 21 | présent |
| git 2.54 | présent |
| node, python | présents |
| **Go** | **absent** |
| **Android SDK / NDK / adb** | **absent** |
| gcc, Docker | absents |

## Brique 0 — installation

### 1. Go

```bash
winget install --id GoLang.Go -e
```

Rouvrir le terminal, puis vérifier :

```bash
go version
```

### 2. gomobile

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
```

`%USERPROFILE%\go\bin` doit être dans le `PATH`.

### 3. Android SDK + NDK

Le plus simple est d'installer **Android Studio**, qui embarque le SDK, le NDK
et `adb` :

```bash
winget install --id Google.AndroidStudio -e
```

Puis, dans *SDK Manager → SDK Tools*, cocher **NDK (Side by side)** et
**Android SDK Command-line Tools**.

Variables d'environnement à définir (session utilisateur) :

```
ANDROID_HOME     = %LOCALAPPDATA%\Android\Sdk
ANDROID_NDK_HOME = %LOCALAPPDATA%\Android\Sdk\ndk\<version>
PATH            += %ANDROID_HOME%\platform-tools
```

### 4. Initialisation de gomobile

```bash
gomobile init
```

### 5. Vérification finale

```bash
go version && gomobile version && adb devices
```

Le téléphone doit apparaître dans `adb devices` avec le statut `device`
(options développeur + débogage USB activés, et autorisation acceptée sur le
téléphone).

---

## Brique 1a — spike d'authentification

**À faire avant d'écrire la moindre ligne de Go.** Ce test valide que les App
Tokens OpenCloud fonctionnent sur ton serveur sans intervention de l'admin.
C'est le risque numéro un du projet.

1. Dans OpenCloud : *Réglages du compte → App Tokens → + New*. Nommer le token
   (« OpenNote dev »), choisir une expiration, **copier le token immédiatement**
   (il n'est affiché qu'une fois).

2. Lancer le script :

```bash
powershell -ExecutionPolicy Bypass -File scripts/spike-auth.ps1 -ServerUrl https://cloud.exemple.fr -Username monlogin
```

Le script demande le token en saisie masquée et enchaîne quatre appels :

| Test | Ce qu'il prouve |
|---|---|
| `GET /ocs/v1.php/cloud/capabilities` | l'URL est bien un OpenCloud et l'auth Basic passe le proxy |
| `GET /graph/v1.0/me/drives` | l'API LibreGraph répond, on récupère les espaces et leur `webDavUrl` |
| `PROPFIND /dav/spaces/{id}/` | le WebDAV moderne répond et on sait parser le XML |
| `PUT` puis `DELETE` d'un fichier test | l'écriture fonctionne et on récupère un ETag |

### Interprétation des échecs

| Symptôme | Cause probable | Suite |
|---|---|---|
| `401` sur tous les appels | `auth-app` inactif ou `PROXY_ENABLE_BASIC_AUTH` désactivé | demander l'activation à l'admin, sinon basculer sur OIDC dès la v1 |
| `401` avec le login mais OK avec l'UUID | IdP en mode autoprovisioning | utiliser l'UUID comme username (page Préférences) |
| `capabilities` OK mais `drives` en `404` | version d'OpenCloud sans LibreGraph | saisie manuelle de l'URL WebDAV en repli |
| `PROPFIND` en `405` | endpoint `/dav/spaces/` absent | tester `/remote.php/dav/spaces/` |

Le script écrit les réponses brutes dans `scripts/out/`. **Ces fichiers servent
de fixtures aux tests unitaires de la brique 1b** — ils reflètent le vrai
serveur cible plutôt que la documentation.

---

## Brique 1a-bis — spike des opérations WebDAV

```bash
powershell -ExecutionPolicy Bypass -File scripts/spike-webdav.ps1 -ServerUrl https://cloud.exemple.fr -Username monlogin
```

Crée un bac à sable `opennote-spike-<horodatage>/` sur l'espace personnel, y
exécute toutes les opérations dont l'app aura besoin, puis supprime tout — y
compris si un test échoue en cours de route.

| Test | Ce qu'il décide |
|---|---|
| `MKCOL` imbriqué | les sous-dossiers sont possibles (brique 2) |
| nom accentué avec espaces | l'encodage d'URL tient pour des notes en français |
| `PROPFIND Depth 1` sur une vraie arborescence | fixture avec dossiers **et** fichiers pour le parser |
| `If-Match` à jour → `204` | l'écriture optimiste fonctionne |
| **`If-Match` périmé → `412`** | **toute la brique 3 en dépend** |
| `MOVE` | le renommage de notes |
| aller-retour `GET` octet pour octet | l'UTF-8 survit au stockage |

Si le `412` ne se produit pas, le serveur accepte d'écraser une version plus
récente : la brique 3 doit alors abandonner `If-Match` et comparer le contenu
avant chaque push, ce qui est nettement plus coûteux.

> `scripts/out/` est ignoré par git : les réponses contiennent des noms de
> fichiers et des identifiants de compte. Ne les committer qu'après relecture
> et anonymisation.
