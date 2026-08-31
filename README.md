<div align="center">

# OpenNote

**Notes Markdown sur votre serveur OpenCloud — sur Android, même hors connexion.**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE.txt)
[![Go 1.26](https://img.shields.io/badge/Go-1.26-00ADD8?logo=go&logoColor=white)](go.mod)
[![Android 8+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#installation)
[![Statut : alpha](https://img.shields.io/badge/statut-alpha-orange.svg)](#état-du-projet)

</div>

## Ce que fait OpenNote

OpenNote est une application Android d'édition de notes **Markdown** stockées
sur un serveur [**OpenCloud**](https://opencloud.eu) (fork d'ownCloud Infinite
Scale). Vos notes restent de simples fichiers `.md` dans votre espace personnel :
lisibles depuis l'interface web, synchronisables avec n'importe quel autre
client, récupérables sans l'application.

- 📝 **Éditeur Markdown** avec barre de mise en forme et aperçu rendu nativement
  en Compose (typographie Material 3, thème sombre, sélection de texte).
- 📴 **Local-first** — l'application s'ouvre, se lit et s'écrit hors connexion.
  Les modifications partent dans une file d'attente persistée et se
  synchronisent dès que le réseau revient.
- 🗂️ **Navigation en arbre** dans vos dossiers de notes, avec création,
  renommage et déplacement.
- ⚔️ **Détection de conflits** par ETag : une note modifiée des deux côtés n'est
  jamais écrasée en silence.
- 📄 **Lecture des `.txt`**, parce qu'OpenCloud crée ses fichiers dans ce format.
- 🌍 **Français, anglais, espagnol, allemand.**
- 🔐 **Authentification par App Token** OpenCloud, stocké en
  `EncryptedSharedPreferences` et jamais écrit sur disque côté Go.

## Captures d'écran

> _À venir._

## Installation

Aucune version signée n'est publiée pour l'instant : l'application se construit
depuis les sources (voir [Construire depuis les sources](#construire-depuis-les-sources)).

**Prérequis côté serveur** : un serveur OpenCloud accessible en HTTPS et un App
Token créé depuis *Réglages du compte → App Tokens → + New*.

**Prérequis côté appareil** : Android 8.0 (API 26) ou supérieur.

Au premier lancement, l'application demande l'URL du serveur, le nom
d'utilisateur et l'App Token, puis crée un dossier `Notes/` dans votre espace
personnel s'il n'existe pas.

## Architecture

Le cœur métier est écrit en **Go pur** et relié à une interface
**Kotlin / Jetpack Compose** par `gomobile bind`.

```
internal/opencloud/   client HTTP, auth App Token, LibreGraph, WebDAV   [Go pur]
internal/notes/       arbre de notes, nommage, bootstrap                [Go pur]
internal/store/       cache local, file offline, ETags, conflits        [Go pur]
internal/markdown/    mise en forme, titre, rendu de l'aperçu           [Go pur]
internal/config/      réglages non sensibles                            [Go pur]
mobile/               façade gomobile — contrat gelé avec Kotlin
cmd/opennote-cli/     harnais de test desktop
android/              projet Gradle, UI Compose
scripts/              outillage de développement (PowerShell)
```

Trois principes structurent le reste :

1. **Le cœur ignore Android.** Rien sous `internal/` ne connaît gomobile ni
   Compose : tout s'y compile et s'y teste sur desktop.
2. **Local-first.** Une écriture va d'abord dans un cache local et une file
   d'attente persistée, puis part vers le serveur.
3. **`mobile/` est un adaptateur, pas une couche métier.** Il sérialise,
   désérialise, délègue. Toute règle qui mérite un test vit en dessous.

Les décisions de conception, les endpoints OpenCloud vérifiés et les pièges
constatés en conditions réelles sont documentés dans
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Construire depuis les sources

### Prérequis

- Go 1.26+
- JDK 17 et le SDK Android (API 35), NDK compris
- `gomobile` : `go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init`

### Générer le binding Go, puis l'APK

```bash
gomobile bind -target=android/arm64 -androidapi 26 -ldflags="-s -w" -o android/app/libs/opennote.aar ./mobile
```

```bash
cd android && ./gradlew assembleDebug
```

`gomobile bind` a besoin de `ANDROID_HOME` et `ANDROID_NDK_HOME` dans
l'environnement — il ne les découvre pas seul.

> ⚠️ Gradle **ne régénère pas** le `.aar`. Toute fonction ajoutée dans `mobile/`
> exige de relancer `gomobile bind` à la main, sinon Kotlin compile contre
> l'ancien binding et se plaint d'un symbole que vous venez pourtant d'écrire.

L'installation détaillée de la toolchain est décrite dans
[docs/SETUP.md](docs/SETUP.md).

## Développement

### Tests

Le cœur métier se teste entièrement sur desktop, sans téléphone ni serveur :

```bash
go test ./... -short
```

```bash
go vet ./... && gofmt -l .
```

Les tests de `internal/opencloud` s'appuient sur des fixtures capturées sur un
vrai serveur OpenCloud 7.0.0 (`internal/opencloud/testdata/`), identifiants
anonymisés. Elles reproduisent notamment le double bloc `propstat` `200`/`404`
et le `$` des identifiants d'espace — deux pièges absents de la documentation
amont.

Côté Android :

```bash
cd android && ./gradlew testDebugUnitTest
```

### Tests d'intégration

Ils s'exécutent contre un vrai serveur, dans un bac à sable horodaté supprimé
en fin de test même en cas d'échec. Ils sont **ignorés** tant que les trois
variables ne sont pas définies, pour qu'un `go test ./...` n'écrive jamais par
accident sur le serveur de quelqu'un :

```bash
export OPENNOTE_IT_SERVER="https://cloud.exemple.fr"
export OPENNOTE_IT_USER="monlogin"
export OPENNOTE_IT_TOKEN="..."
```

```bash
go test ./... -run TestIntegration -v
```

### CLI de test desktop

`opennote-cli` exécute le vrai client Go contre un vrai serveur, sans
téléphone. C'est le moyen le plus rapide de vérifier le cœur métier.

```bash
go build -o bin/opennote-cli ./cmd/opennote-cli
```

Le token se lit dans l'environnement — jamais en argument, où il atterrirait
dans l'historique du shell et la liste des processus :

```bash
export OPENNOTE_SERVER="https://cloud.exemple.fr"
export OPENNOTE_USER="monlogin"
export OPENNOTE_APP_TOKEN="..."
```

```bash
./bin/opennote-cli tree
```

Commandes : `drives`, `ls`, `tree`, `cat`, `put`, `mkdir`, `mv`, `rm`.
Les options (`-server`, `-user`, `-drive`, `-timeout`) précèdent la commande.

## Documentation

| Document | Contenu |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | décisions, endpoints OpenCloud vérifiés, pièges confirmés, modèle de synchronisation, mesures de performance |
| [docs/FACADE.md](docs/FACADE.md) | **le contrat gelé** entre Go et Kotlin : méthodes, formats JSON, codes d'erreur |
| [docs/SETUP.md](docs/SETUP.md) | installation de la toolchain |
| [CLAUDE.md](CLAUDE.md) | guide technique du dépôt : conventions, pièges, discipline de test |

## État du projet

**Alpha.** L'application fonctionne au quotidien, mais rien n'est encore
distribué et les interfaces peuvent bouger.

- Le **cœur Go est vérifié** : ~200 cas unitaires plus une suite d'intégration
  contre un vrai serveur.
- L'**interface Compose compile et tourne**, mais sa couverture repose sur des
  essais manuels — aucun test instrumenté.

Limites connues :

- **L'éditeur décroche sur les notes longues** : au-delà d'environ 80 lignes, le
  défilement sort du budget d'image, et la saisie devient coûteuse bien avant.
  La virtualisation est le chantier en cours — mesures en section 7 bis de
  [ARCHITECTURE.md](docs/ARCHITECTURE.md).
- Pas d'OIDC : l'authentification passe uniquement par App Token.
- Le HTML brut d'une note est ignoré à l'aperçu, et les images en `data:` ne
  sont pas affichées (seul leur texte alternatif l'est).
- Les traductions n'ont pas été relues par des locuteurs natifs sur appareil.
- Aucun APK signé n'est publié.

Feuille de route, par ordre de priorité : virtualisation de l'éditeur, langues
supplémentaires, OIDC, signature et distribution de l'APK.

## Contribuer

Les issues et pull requests sont bienvenues. Deux points avant de commencer :

- **Le projet se conduit en français** : code, commentaires, messages d'erreur,
  tests et documentation.
- **[CLAUDE.md](CLAUDE.md) est le guide technique du dépôt.** Il consigne les
  pièges du serveur OpenCloud, les règles de localisation et la discipline de
  test — le lire évite de redécouvrir à ses dépens ce qui y est écrit.

Avant d'ouvrir une pull request :

```bash
go test ./... -short && go vet ./... && gofmt -l .
```

```bash
cd android && ./gradlew testDebugUnitTest lintDebug
```

Les fichiers s'écrivent en **LF** (le dépôt est en LF alors que `core.autocrlf`
vaut `true` sur Windows).

## Sécurité

Pour signaler une vulnérabilité, voir [SECURITY.md](SECURITY.md).

## Licence

[MIT](LICENSE.txt).
