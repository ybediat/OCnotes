# Documentation technique

## Objet

OCnotes est une application Android de prise de notes Markdown, utilisable en
stockage local seul ou synchronisée avec un serveur OpenCloud. Les notes
restent des fichiers : l'application ne leur impose ni base de données distante
ni format propriétaire.

Le projet vise Android 8 (API 26) et versions ultérieures. Son interface est
écrite en Kotlin avec Jetpack Compose ; le cœur métier est écrit en Go.

## Architecture

```text
Android / Compose
        │
        ▼
Façade mobile Go (gomobile)
        │
        ├── configuration et stockage local
        ├── notes, dossiers et recherche
        ├── synchronisation et résolution de conflits
        ├── client OpenCloud (LibreGraph et WebDAV)
        └── analyse Markdown et documents en lecture seule
```

Le code Go sous `internal/` ne dépend pas d'Android. Il concentre les règles
métier et les échanges réseau, ce qui permet de l'exécuter et de le tester sur
ordinateur. Le paquet `mobile/` sérialise les appels exposés à Android, sans
porter de règle métier. L'application Kotlin s'occupe de l'interface, du cycle
de vie Android et du stockage chiffré du jeton de connexion.

## Données et synchronisation

OCnotes est *local-first* : une modification est d'abord enregistrée dans le
stockage local, puis placée dans une file persistante. Elle est envoyée au
serveur lorsque le réseau est disponible.

En mode `local`, le Store devient le dépositaire unique : aucune opération de
synchronisation n'est mise en file et aucun contenu n'est évincé. Le passage
vers un serveur permet soit d'adopter les notes locales, soit de repartir des
notes distantes. Dans l'autre sens, l'application pousse d'abord les écritures
en attente et rapatrie les contenus manquants avant d'oublier le serveur.

Les écritures distantes utilisent les ETags et les préconditions HTTP. Si une
note a été modifiée à la fois localement et sur le serveur, OCnotes n'écrase
pas silencieusement la version distante : la situation est signalée afin que
l'utilisateur puisse choisir la suite.

L'authentification utilise un App Token OpenCloud. Le jeton est conservé côté
Android avec le mécanisme de chiffrement de la plateforme ; il ne doit jamais
être ajouté à un fichier du dépôt, à une commande partagée ou à un rapport de
bug.

## Formats pris en charge

- Markdown et texte brut : édition et aperçu Markdown.
- `.docx` et `.odt` : lecture seule et rendu en aperçu lorsque le contenu peut
  être interprété sans risque.

Les fichiers restent dans leur format d'origine. OCnotes n'écrit pas dans les
documents bureautiques.

## Organisation du dépôt

| Répertoire | Rôle |
|---|---|
| `android/` | application Android, ressources et tests Kotlin |
| `cmd/ocnotes-cli/` | outil en ligne de commande pour le cœur Go |
| `internal/config/` | configuration non sensible |
| `internal/documents/` | lecture des documents bureautiques |
| `internal/markdown/` | analyse, formatage et rendu Markdown |
| `internal/notes/` | navigation et opérations sur les notes |
| `internal/opencloud/` | client HTTP, LibreGraph et WebDAV |
| `internal/store/` | cache local, file de synchronisation et conflits |
| `mobile/` | façade Go liée à Android par gomobile |
| `scripts/` | scripts de construction et d'assistance au développement |

## Construire

Les prérequis sont Go, JDK 17, le SDK Android (API 26 et 35), le NDK et
`gomobile`. Sur Linux, le script suivant vérifie l'environnement, régénère le
binding Go, exécute les tests puis produit un APK release non signé :

```bash
bash scripts/build-android-linux.sh
```

Pour un cycle local minimal :

```bash
go test ./... -short
cd android && ./gradlew testDebugUnitTest
```

Le binding Go doit être régénéré après une modification de l'API exposée par
`mobile/` :

```bash
gomobile bind -target=android/arm64,android/amd64 -androidapi 26 -trimpath \
  -ldflags="-s -w" -o android/app/libs/ocnotes.aar ./mobile
```

## Contribution et sécurité

Le code, les commentaires, les messages et la documentation sont en français.
Avant une contribution, exécuter les tests Go et Android, ainsi que `go vet` et
`gofmt -l .`.

Ne publiez jamais de jeton, mot de passe, URL de serveur privée, contenu de
note, clé de signature ou journal non expurgé. Les fichiers de configuration
locale, clés et sorties de scripts sont exclus par `.gitignore`.

Les arrêts inattendus produisent au plus un rapport dans le cache privé Android.
Il contient la version, l'environnement Android, les types d'exception et les
cadres de pile, mais jamais les messages d'exception : ceux du binding Go
peuvent contenir un chemin ou une URL. Au lancement suivant, l'interface permet
de le supprimer, de le partager avec la feuille Android ou de le copier avant
d'ouvrir le formulaire GitHub. Aucun envoi n'est automatique. À partir de
l'API 30, `ApplicationExitInfo` ajoute seulement la catégorie d'un crash natif
ou d'un ANR ; ses traces brutes ne sont pas recopiées.

Les vulnérabilités se signalent conformément à [SECURITY.md](../SECURITY.md).
