# OpenNote

Application Android légère pour écrire des notes **Markdown** stockées sur un
serveur **OpenCloud**.

- Cœur métier en **Go pur**, testable sur desktop.
- UI Android en **Kotlin / Jetpack Compose**, liée au cœur par `gomobile bind`.
- **Local-first** : on écrit hors connexion, la synchronisation suit.
- Connexion par **App Token** OpenCloud (OIDC prévu ensuite).

> Statut : conception validée, implémentation non commencée.

## Documentation

| Document | Contenu |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | décisions, endpoints OpenCloud vérifiés, frontière Go/Kotlin, modèle de sync, briques de travail, risques |
| [docs/SETUP.md](docs/SETUP.md) | installation de la toolchain et procédure du spike d'authentification |

## Structure

```
internal/opencloud/   client HTTP, auth, graph/drives, WebDAV     [Go pur]
internal/notes/       arbre de notes, sous-dossiers, chemins      [Go pur]
internal/store/       cache local, file offline, ETags, conflits  [Go pur]
internal/markdown/    helpers de mise en forme + rendu preview    [Go pur]
internal/config/      URL serveur, driveID, racine, préférences   [Go pur]
mobile/               façade gomobile bind
cmd/opennote-cli/     harnais de test desktop
android/              projet Gradle, UI Compose
scripts/              outillage de développement
```

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

## Note sur le chemin de module

`go.mod` déclare `module opennote`, un chemin local. À remplacer par l'URL du
dépôt (`github.com/…/opennote`) le jour où le projet est publié.
