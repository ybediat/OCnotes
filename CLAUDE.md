# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Le projet se conduit **en français** : code, commentaires, messages d'erreur,
tests et documentation.

## Ce qu'est OpenNote

Application Android d'édition de notes Markdown stockées sur un serveur
**OpenCloud** (fork d'ownCloud Infinite Scale). Cœur métier en Go, interface en
Kotlin/Compose, les deux reliés par `gomobile bind`.

Trois principes structurent tout le reste :

- **Le cœur ignore Android.** Rien sous `internal/` ne connaît gomobile ni
  Compose : tout s'y compile et s'y teste sur desktop.
- **Local-first.** Une écriture va dans un cache local et une file d'attente
  persistée, puis part vers le serveur. L'application doit rester utilisable
  hors connexion, y compris au démarrage.
- **`mobile/` est un adaptateur, pas une couche métier.** Il sérialise,
  désérialise, délègue. Toute règle qui mérite un test vit en dessous.

## Commandes

Go n'est pas toujours dans le `PATH` d'une session neuve :
`$env:PATH = "C:\Program Files\Go\bin;$env:USERPROFILE\go\bin;$env:PATH"`.

```bash
go test ./... -short          # suite unitaire, sans réseau
go test ./internal/markdown -run TestBasculeInline -v    # un seul test
go vet ./... && gofmt -l .    # à passer avant de conclure
```

### Tests d'intégration

Ignorés tant que les trois variables ne sont pas définies, pour qu'un
`go test ./...` n'écrive jamais par accident sur un serveur. Chaque test
travaille dans un bac à sable horodaté, supprimé même en cas d'échec.

```powershell
$env:OPENNOTE_IT_SERVER = "https://..."; $env:OPENNOTE_IT_USER = "admin"; $env:OPENNOTE_IT_TOKEN = "..."
```

```bash
go test ./... -run TestIntegration -v
```

### CLI de test desktop

Exécute le vrai client Go contre un vrai serveur, sans téléphone. Le token
passe par l'environnement (`OPENNOTE_SERVER`, `OPENNOTE_USER`,
`OPENNOTE_APP_TOKEN`), jamais en argument.

```bash
go build -o bin/opennote-cli.exe ./cmd/opennote-cli
```

Commandes : `drives`, `ls`, `tree`, `cat`, `put`, `mkdir`, `mv`, `rm`.

### Build Android

```bash
gomobile bind -target=android/arm64 -androidapi 26 -ldflags="-s -w" -o android/app/libs/opennote.aar ./mobile
```

```bash
cd android && ./gradlew assembleDebug
```

`-ldflags="-s -w"` fait passer `libgojni.so` de 14 Mo à 7,2 Mo — le build
Android ne sait pas stripper cette bibliothèque lui-même. APK release mesuré :
8,5 Mo.

## Architecture

```
internal/opencloud/   HTTP, auth App Token, LibreGraph, WebDAV
internal/notes/       Library : arbre, nommage, bootstrap        (au-dessus de opencloud)
internal/store/       cache local, file offline, conflits         (au-dessus de notes)
internal/markdown/    mise en forme, extraction de titre          (indépendant)
internal/config/      réglages non sensibles                      (indépendant)
mobile/               façade gomobile — contrat gelé
cmd/opennote-cli/     harnais desktop
android/              Gradle + Compose
scripts/              spikes PowerShell contre un vrai serveur
```

Les interfaces sont déclarées **côté consommateur** : `notes.Backend` (satisfait
par `*opencloud.Space`) et `store.Remote` (satisfait par `*notes.Library`).
C'est ce qui permet de tester chaque couche contre une implémentation en
mémoire.

`docs/FACADE.md` est **le contrat gelé** entre Go et Kotlin : signatures,
formats JSON, codes d'erreur. Le modifier, c'est casser l'UI.
`docs/ARCHITECTURE.md` porte les décisions et les pièges confirmés.

## Pièges du serveur OpenCloud

Tous constatés en conditions réelles, aucun documenté en amont.

**Les identifiants d'espace contiennent un `$`** (`storageId$spaceId`), et les
identifiants de ressource un `!` en plus. Ne jamais percent-encoder ce `$` : en
Go, poser le chemin décodé dans `url.URL{Path:…}` et laisser `String()` faire —
`$` est un sub-delim RFC 3986 que Go n'échappe pas. `url.PathEscape` sur le
chemin complet serait faux, il encoderait aussi les `/`.

**PROPFIND renvoie deux blocs `propstat`** sur une collection : un en `200`
avec les propriétés trouvées, un en `404` avec `getcontentlength` et
`getcontenttype` vides. Il faut filtrer sur le statut, sinon un dossier passe
pour un fichier de taille nulle.

**`If-None-Match: *` est ignoré** : un `PUT` le portant écrase quand même. La
protection des notes créées hors connexion repose donc sur une vérification
d'existence explicite dans `store.pushWrite`.

**Pas de verrouillage WebDAV** (`Dav: 1, 3` — la classe 2 manque). La
concurrence repose uniquement sur les ETag.

**`me/drives` renvoie un espace `virtual`** (« Shares ») qui n'est pas un
stockage. `opencloud.PersonalDrive` l'écarte.

**Le serveur accepte tous les caractères testés dans les noms** — `? * : < > | %`
et emoji compris. Il est donc **plus permissif que le cache local**, qui doit
écrire de vrais fichiers. D'où deux règles : le cache nomme ses fichiers par
une empreinte SHA-256 du chemin, et `notes.ValidateName` refuse à la *création*
des noms que l'application sait pourtant *lire*.

## Deux confusions de référentiel qui ont déjà coûté cher

**`state.root` n'est pas un chemin utilisable.** Il est relatif à l'espace ;
tous les chemins de la façade sont relatifs au dossier de notes. Les confondre
fait chercher `Notes/Notes`. Ce bug est arrivé côté Kotlin.

**Les positions de texte sont en unités UTF-16**, pas en octets ni en runes —
c'est l'unité de `TextRange` dans Compose, pour que la frontière Kotlin n'ait
aucune conversion à faire. `é` : 2 octets, 1 rune, 1 unité. `😀` : 4, 1, 2.

## Discipline de test, apprise à ses dépens

**Un test qui passe ne prouve rien tant qu'on ne l'a pas vu échouer.** Deux
tests de ce dépôt passaient sans rien vérifier : l'un se reconnectait au vrai
serveur pour « tester le hors connexion », l'autre s'appuyait sur un serveur
factice qui honorait un en-tête que le vrai ignore. Avant de conclure qu'un
correctif marche, le désactiver et vérifier que le test échoue avec le symptôme
attendu.

**Le serveur factice doit imiter le vrai, pas l'idéal.** `mobile/fakeserver_test.go`
reproduit délibérément les défauts constatés — `If-None-Match` ignoré, double
`propstat`. Ne pas « corriger » ces imitations.

**Les fixtures viennent du vrai serveur.** `internal/opencloud/testdata/`
contient des réponses capturées par `scripts/spike-webdav.ps1`, avec les
identifiants anonymisés.

`mobile/gomobile_test.go` rejoue les contraintes de types de gomobile sur
l'arbre syntaxique du paquet : une violation apparaît dès `go test`, sans NDK.
C'est pourquoi les structures JSON de `mobile/` sont **non exportées** —
gomobile lie tout type exporté et refuserait leurs champs de type slice.

## Frontière avec Android

`Restore(token)` remonte la session **sans réseau** et doit être le premier
appel au démarrage. `Connect` valide les identifiants auprès du serveur : il
échoue hors connexion, et la bibliothèque reste alors nulle.

Le token n'est **jamais** persisté côté Go. Android le garde dans des
`EncryptedSharedPreferences` et le repasse à chaque démarrage ; `config.json`
ne contient aucun secret, et un test le vérifie.

La synchronisation est **pilotée par Android** via WorkManager. Aucune
goroutine périodique côté Go : seul Android connaît l'état de la batterie, du
réseau et du cycle de vie.

Les erreurs traversent la frontière en simple chaîne. Elles portent donc leur
catégorie entre crochets — `[AUTH]`, `[CONFLICT]`, `[NOTFOUND]`, `[OFFLINE]`,
`[HTTP]` — à lire avec `ErrorCode()`. **Ne jamais chercher de texte français
dans un message d'erreur** : c'est ce que faisait une première version, et
`HTTPError.Error()` ne contenait pas la phrase attendue.

## Localisation — prochain chantier

À faire tant que l'application est petite : chaque écran ajouté augmente le
coût. État mesuré le 2026-08-29.

**La couture est déjà en place, et c'est le morceau difficile.** Le schéma de
catégories `[AUTH]` / `[CONFLICT]` / `[NOTFOUND]` / `[OFFLINE]` / `[HTTP]` fait
que Kotlin **reformule** ces erreurs au lieu d'afficher le message Go. Le
français du cœur ne remonte donc jamais à l'écran pour ces cas. Le dispositif
avait été conçu parce que gomobile ne transmet qu'une chaîne — il se trouve
être exactement le bon point de découpe.

Reste trois chantiers, d'inégale difficulté.

**1. Les chaînes Kotlin en dur.** Environ 82, contre 3 seulement dans
`strings.xml`. Mécanique et sans risque : sortir vers `strings.xml`, puis créer
`values-en/`. C'est le gros du volume.

**2. Les erreurs Go de catégorie `LOCAL` passent brutes.**
`OpenNoteError.userMessage()` fait `rawMessage.substringAfter("mobile: ")` :
les messages de validation (« le nom ne peut pas contenir… », « CON est un nom
réservé par Windows ») resteraient en français quelle que soit la langue de
l'appareil. Une quinzaine de sites, dans `internal/notes` et `internal/config`.

Le correctif suit la voie déjà tracée : donner un code à ces erreurs
(`[NAME_FORBIDDEN]`, `[NAME_RESERVED]`, `[SERVER_URL_INVALID]`…) et laisser
Kotlin les formuler. **C'est le seul point qui touche à l'architecture, donc
celui par lequel commencer.**

**3. Deux mots que Go écrit dans des noms de fichiers** : `"Sans titre"`
(`notes.SanitizeName`) et `"(conflit <horodatage>)"` (`store.conflictPath`).

Ceux-là demandent une décision, pas un réflexe. Ce ne sont pas des textes
d'interface mais de **vrais noms de fichiers sur le serveur**, visibles depuis
l'interface web et depuis les autres appareils. Les faire suivre la langue de
chaque téléphone produirait `(conflit …)` et `(conflict …)` dans le même
dossier partagé. Piste retenue : les faire fournir une fois par Kotlin à
travers la façade, pour que le choix soit explicite et cohérent — pas
automatique.

**Détail annexe** : `BrowserScreen` affiche les dates en tronquant l'ISO à 10
caractères (`2026-08-29`). Neutre, mais pas localisé.

## État et limites

Le cœur Go est **vérifié** : ~200 cas unitaires plus une suite d'intégration.
L'interface Compose **compile et tourne**, mais sa couverture repose sur des
essais manuels — aucun test instrumenté.

Distinguer « écrit », « compile » et « testé » dans tout rapport d'avancement.

Restent ouverts, par ordre de priorité décidé : **la localisation** (section
ci-dessus, à traiter avant que l'application grossisse), l'aperçu Markdown
rendu (brique 4-bis, goldmark), OIDC en alternative à l'App Token, et la
signature de l'APK pour distribution.

Le chemin de module est `opennote` alors que le dépôt existe
(`github.com/ybediat/OpenNote`) — renommage mécanique jamais fait.
