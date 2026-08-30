# OpenNote

Application Android légère pour écrire des notes **Markdown** stockées sur un
serveur **OpenCloud**.

- Cœur métier en **Go pur**, testable sur desktop.
- UI Android en **Kotlin / Jetpack Compose**, liée au cœur par `gomobile bind`.
- **Local-first** : on écrit hors connexion, la synchronisation suit.
- Connexion par **App Token** OpenCloud (OIDC prévu ensuite).

> **Statut**
>
> Le **cœur métier est terminé et vérifié** : client OpenCloud, modèle de notes,
> cache et synchronisation, mise en forme Markdown, configuration, façade
> Android. 191 cas de test unitaires, plus des tests d'intégration contre un
> vrai serveur OpenCloud 7.0.0.
>
> L'**interface Compose est écrite mais jamais compilée** — le SDK Android n'est
> pas installé sur la machine de développement. Voir `android/README.md` et la
> réserve dans [ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Documentation

| Document | Contenu |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | décisions, endpoints OpenCloud vérifiés, pièges confirmés en conditions réelles, modèle de sync, briques, risques |
| [docs/FACADE.md](docs/FACADE.md) | **le contrat gelé** de l'API Go exposée à Kotlin : méthodes, formats JSON, codes d'erreur |
| [docs/SETUP.md](docs/SETUP.md) | installation de la toolchain et procédure des spikes |

## Structure

```
internal/opencloud/   client HTTP, auth App Token, drives, WebDAV  [Go pur]
internal/notes/       arbre de notes, nommage, sous-dossiers       [Go pur]
internal/store/       cache local, file offline, ETags, conflits   [Go pur]
internal/markdown/    mise en forme + extraction de titre          [Go pur]
internal/config/      URL serveur, driveID, racine (sans secret)   [Go pur]
mobile/               façade gomobile bind — frontière Kotlin
cmd/opennote-cli/     harnais de test desktop
android/              projet Gradle, UI Compose
scripts/              spikes et outillage de développement
```

Rien sous `internal/` ne connaît Android, gomobile ou Compose : tout s'y compile
et s'y teste sur Windows.

## Par où commencer

**Le préalable à tout le reste** est le spike d'authentification : il valide que
les App Tokens OpenCloud donnent bien accès au WebDAV sur le serveur cible.
C'est le risque numéro un du projet, et il se lève sans écrire une ligne de Go.

Créer un App Token dans OpenCloud (*Réglages du compte → App Tokens → + New*),
puis :

```bash
powershell -ExecutionPolicy Bypass -File scripts/spike-auth.ps1 -ServerUrl https://cloud.exemple.fr -Username monlogin
```

Le script demande le token en saisie masquée, teste `capabilities`, la
découverte des espaces, un `PROPFIND`, puis un `PUT`/`DELETE`. Il écrit les
réponses brutes dans `scripts/out/` — **ces fichiers serviront de fixtures aux
tests unitaires du client WebDAV**, car ils reflètent le serveur réel plutôt que
la documentation.

> Statut : **passé le 2026-08-28** sur `opencloud.a.rsrh.ovh` (OpenCloud 7.0.0).
> L'authentification par App Token est confirmée viable.

Ensuite, le spike des opérations complètes :

```bash
powershell -ExecutionPolicy Bypass -File scripts/spike-webdav.ps1 -ServerUrl https://TON-SERVEUR -Username TON-LOGIN
```

Il crée un bac à sable temporaire, y teste `MKCOL`, les sous-dossiers, les noms
de fichiers accentués, `MOVE`, l'aller-retour UTF-8, et surtout **la détection
de conflit par `If-Match`** — le mécanisme sur lequel repose toute la
synchronisation. Il supprime tout à la fin, y compris en cas d'erreur.

`scripts/out/` est ignoré par git : les réponses contiennent des noms de
fichiers et des identifiants de compte.

Pour installer la toolchain Go + Android, voir [docs/SETUP.md](docs/SETUP.md).

## Le CLI de test

`opennote-cli` exécute le vrai client Go contre un vrai serveur. C'est le moyen
le plus rapide de vérifier le cœur métier sans téléphone ni émulateur.

```bash
go build -o bin/opennote-cli.exe ./cmd/opennote-cli
```

L'App Token se lit dans l'environnement — jamais en argument, où il
atterrirait dans l'historique du shell et la liste des processus :

```powershell
$env:OPENNOTE_SERVER   = "https://cloud.exemple.fr"
$env:OPENNOTE_USER     = "monlogin"
$env:OPENNOTE_APP_TOKEN = "..."

.\bin\opennote-cli.exe drives
.\bin\opennote-cli.exe mkdir Notes
.\bin\opennote-cli.exe ls Notes
.\bin\opennote-cli.exe tree
Get-Content note.md -Raw | .\bin\opennote-cli.exe put "Notes/ma note.md"
.\bin\opennote-cli.exe cat "Notes/ma note.md"
```

Commandes : `drives`, `ls`, `tree`, `cat`, `put`, `mkdir`, `mv`, `rm`.
Les options (`-server`, `-user`, `-drive`, `-timeout`) précèdent la commande.

## Tests

Le cœur métier se teste entièrement sur desktop, sans téléphone ni serveur :

```bash
go test ./...
```

Les tests de `internal/opencloud` s'appuient sur des fixtures capturées sur un
vrai serveur OpenCloud 7.0.0 (`internal/opencloud/testdata/`), avec les
identifiants d'espace et le nom d'hôte anonymisés. Elles reproduisent
notamment le double bloc `propstat` `200`/`404` et le `$` des identifiants
d'espace — deux pièges que la documentation ne mentionne pas.

### Tests d'intégration

Ils s'exécutent contre un vrai serveur, dans un dossier temporaire supprimé en
fin de test même en cas d'échec. Ils sont ignorés tant que les trois variables
ne sont pas définies — lancer `go test ./...` n'écrit jamais par accident sur
le serveur de quelqu'un :

```powershell
$env:OPENNOTE_IT_SERVER = "https://cloud.exemple.fr"; $env:OPENNOTE_IT_USER = "monlogin"; $env:OPENNOTE_IT_TOKEN = "..."
```

```bash
go test ./internal/opencloud -run TestIntegration -v
```

Ils couvrent le cycle de vie complet d'une note, la détection de conflit par
ETag, l'imbrication de dossiers, la traduction des erreurs, et surtout
l'aller-retour de noms de fichiers contenant des caractères significatifs en
URL. C'est cette dernière catégorie qui a établi les règles de nommage
documentées dans [ARCHITECTURE.md](docs/ARCHITECTURE.md#2-bis-pièges-confirmés-sur-le-serveur-réel).

## Note sur le chemin de module

`go.mod` déclare `module github.com/ybediat/OpenNote`, aligné sur l'URL du
dépôt. Les imports internes suivent (`github.com/ybediat/OpenNote/internal/…`).
