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

L'authentification principale utilise un App Token OpenCloud. Une connexion
OIDC expérimentale emploie Authorization Code avec PKCE dans le navigateur,
puis des jetons Bearer renouvelables. App Token, access token et refresh token
sont conservés côté Android avec le mécanisme de chiffrement de la plateforme ;
aucun secret n'est persisté par le cœur Go.

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
d'ouvrir le formulaire GitHub. Aucun envoi n'est automatique.

À partir de l'API 30, `ApplicationExitInfo` couvre en plus les crashs natifs,
les ANR et les mises à mort par le système, que le gestionnaire Kotlin ne peut
pas voir. Le rapport en retient le motif, l'importance du processus, sa mémoire
au moment de la mort et un fil d'Ariane que l'application dépose elle-même —
l'écran courant et, dans l'éditeur, le nombre de lignes et de caractères du
document. Ni nom de note, ni contenu.

Deux textes ne sont pas décidés par l'application, et sont donc traités à part :

- **la description composée par le système** ne traverse jamais en clair. Elle
  est ramenée à un vocabulaire fermé (`input_dispatching_timeout`,
  `native_crash`, `other`…) : une formulation inconnue devient `other`, jamais
  son texte. Un filtre de caractères ne suffirait pas, un nom d'hôte survivrait
  à la suppression des `:` et des `/` ;
- **la trace d'un ANR** est réduite à ses cadres de pile, qui ne portent que des
  noms venus du programme. Les noms de fils, les états et les verrous sont
  écartés. La tombstone d'un crash natif, elle, n'est **jamais** lue : elle
  contient des registres et des extraits de mémoire, donc possiblement des
  fragments de note.

Les vulnérabilités se signalent conformément à [SECURITY.md](../SECURITY.md).
