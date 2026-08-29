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

```bash
cd android && ./gradlew testDebugUnitTest
```

Le second exécute `ChainesEnDurTest`, le garde-fou de localisation. Il tourne
sur la JVM, sans appareil ni émulateur, mais exige que `opennote.aar` soit là :
le module principal doit compiler avant que ses tests puissent l'être.

`-ldflags="-s -w"` fait passer `libgojni.so` de 14 Mo à 7,2 Mo — le build
Android ne sait pas stripper cette bibliothèque lui-même. APK release mesuré :
8,5 Mo.

### Ce que l'outillage ne dit pas

Tout ce qui suit a été constaté sur cette machine, et coûte une demi-heure à
qui le redécouvre.

**Le SDK Android est installé et le réseau fonctionne.** `local.properties`
pointe vers `%LOCALAPPDATA%/Android/Sdk`, `assembleDebug` aboutit, Gradle
résout ses dépendances. Un commentaire de `libs.versions.toml` a longtemps
affirmé le contraire ; il a été corrigé.

**`gomobile bind` ne trouve rien tout seul.** Ni `ANDROID_HOME` ni
`ANDROID_NDK_HOME` ne sont dans l'environnement, et le chemin du NDK contient
son numéro de version :

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk"
$env:ANDROID_NDK_HOME = "$env:ANDROID_HOME/ndk/30.0.16138531"
```

**Le `.aar` n'est pas régénéré par Gradle.** Toute fonction exportée ajoutée
dans `mobile/` exige de relancer `gomobile bind` à la main. Sinon Kotlin
compile contre l'ancien binding et se plaint d'une référence introuvable sur un
symbole que vous venez pourtant d'écrire — le symptôme ne désigne pas sa cause.

**Écrire les fichiers en LF.** Le dépôt est en LF alors que `core.autocrlf` est
à `true`. Un outil d'édition qui traduit les fins de ligne (Python en mode
texte sous Windows, par exemple) produit du CRLF, et `gofmt -l` signale alors
le fichier **entier** comme mal formaté — un diff illisible pour une cause
invisible.

**`--offline` ne suffit pas pour une dépendance jamais téléchargée.** JUnit
n'était pas dans le cache Gradle tant qu'aucun test Kotlin n'existait :
`./gradlew --offline testDebugUnitTest` échoue là où la même commande sans
`--offline` aboutit.

**`assembleDebug` ne lance aucune tâche lint** — vérifié, zéro sur 41. Les
règles de `build.gradle.kts` ne s'appliquent qu'à `./gradlew lintDebug`,
demandé explicitement. C'est voulu : la traduction ne doit pas ralentir
l'itération sur un écran.

**Les tests Kotlin partent du dossier du module**, `android/app`, pas de la
racine du dépôt. `ChainesEnDurTest` remonte l'arborescence plutôt que de le
supposer.

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

Les règles du cœur — validation de nom, panne de stockage, URL de serveur —
portent elles aussi un code, sur le même principe. `ErrorCode` reconnaît la
*forme* `[NOM_EN_MAJUSCULES]` et non une liste fermée, mais **cherche d'abord
les codes de transport** : une erreur du cache enveloppe couramment une erreur
réseau (`store: [STORAGE_IO] … : opencloud: [NOTFOUND] …`), et c'est la cause
profonde qui décide de la réaction. `TestErrorCodePrioriteTransport` protège
cet ordre. Liste complète dans `docs/FACADE.md`.

## Localisation — le modèle en place, et comment s'y tenir

Les coutures sont posées et vérifiées. Ce qui reste est mécanique, mais **le
modèle se contourne facilement sans le vouloir** : cette section dit comment
ajouter un texte sans le casser.

### Le principe : décrire, pas rédiger

Un ViewModel n'a pas de `Context`, donc pas de ressources. Lui en donner un
serait doublement mauvais — il devient intestable sans Robolectric, et surtout
la langue se **fige au moment de l'émission** : un `StateFlow` portant déjà
« Tout est à jour » ne repasserait pas en anglais après un changement de
langue. Les ViewModels émettent donc un `ui.common.Texte` — un identifiant de
ressource et ses paramètres — que le composable rédige à chaque recomposition.

`Texte` a quatre variantes : `Ressource`, `Pluriel`, `Liste` (une énumération
dont le séparateur est lui-même une ressource) et `Brut`, réservé à ce qui
n'est pas une ressource — un nom de note, le repli d'une erreur Go.

### Ajouter un texte : trois cas, trois gestes

**1. Dans un composable** → `stringResource(R.string.…)`. Rien d'autre.

**2. Dans un ViewModel, une notification, un `LaunchedEffect`** → `Texte`, posé
dans l'état, résolu par l'écran.

Le piège est le lieu de la résolution : `Texte.resoudre()` est un composable,
donc **il ne peut pas être appelé depuis une coroutine**. Un `LaunchedEffect`
en est une. Il faut résoudre au-dessus, dans la composition, et ne passer que
la chaîne :

```kotlin
val message = etat.erreur?.resoudre()
LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it) } }
```

`BrowserScreen` et `EditorScreen` portent ce motif. `SyncNotifier` fait
exception et résout tout de suite, avec `resoudre(context.resources)` : une
notification est postée pour être lue dans la seconde, il n'y a pas de
recomposition à attendre.

**3. Une règle refusée par le cœur Go** → quatre gestes solidaires :

1. un code dans le paquet Go concerné (`notes.CodeNameTooLong`…), inséré entre
   crochets **devant** la phrase française, qui reste pour la CLI et les
   journaux ;
2. un cas dans `texteLocal()` de `ui/common/ErreurTexte.kt` ;
3. une clé dans `strings.xml` ;
4. une ligne dans le tableau de `docs/FACADE.md`.

Un paramètre qui est une **constante du cœur** ne se recopie pas dans
`strings.xml` : il s'expose par la façade, comme `MaxNameBytes()` et
`ForbiddenNameChars()`. Un paramètre que Kotlin a déjà sous la main — le nom
saisi, l'URL tapée — n'a pas à traverser la frontière du tout. C'est ce qui a
permis de n'encoder **aucun** argument dans les messages d'erreur.

### Migrer un des écrans restants

Sortir les chaînes vers `strings.xml`, les lire avec `stringResource`, puis
**retirer le fichier de `ECRANS_A_MIGRER`** dans `ChainesEnDurTest`. Le test
vérifie les deux sens : il refuse un littéral hors liste, et refuse aussi qu'un
fichier migré y reste — sinon le garde-fou deviendrait aveugle sur ce fichier
sans le dire.

Dans `strings.xml`, une apostrophe s'écrit `\'` : sans
l'échappement, la compilation des ressources échoue.

### Ce qu'il ne faut pas faire

- **Pas de `Context` dans un ViewModel.** C'est le raccourci qui annule tout le
  dispositif.
- **Pas de `if (n > 1)`.** Un texte qui compte est un `<plurals>` : le nombre
  de formes dépend de la langue — trois en polonais, six en arabe.
  `Texte.Pluriel` passe la quantité **deux fois**, pour choisir la forme et
  comme premier paramètre de format, ce qui évite l'oubli classique du `%d`.
- **`Texte.Brut` n'est pas une échappatoire** pour du français en dur.
- **Ne pas ajouter un écran neuf à `ECRANS_A_MIGRER`.** Cette liste ne fait que
  décrire une dette existante ; y ajouter une ligne, c'est éteindre le
  garde-fou là où il sert le plus.
- **Toute catégorie ou tout code Go doit avoir son pendant Kotlin.** `OFFLINE`
  n'en avait pas : la catégorie retombait en `LOCAL`, et « opencloud: serveur
  injoignable » s'affichait brut à l'écran. Une catégorie orpheline ne casse
  rien — elle fuit en silence.

### Les deux garde-fous, et leur portée

`ChainesEnDurTest` analyse les sources Kotlin et refuse tout littéral d'au
moins deux mots hors des fichiers listés. Il existe parce que `HardcodedText`,
la règle de lint prévue pour ça, ne lit que les dispositions XML — Compose lui
est invisible. Même rôle que `mobile/gomobile_test.go` face au NDK absent :
vérifier une contrainte de projet sans outil supplémentaire.

Sa règle est volontairement grossière — aucun faux positif sur le dépôt
actuel — et laisse donc passer les libellés d'un seul mot (« Annuler »).
Ceux-là se voient sous la pseudo-langue `en-XA` des options développeur, qui
affiche accentué et allongé tout ce qui est traduit, sans qu'aucun `values-en/`
soit à maintenir. Un littéral délibéré se marque `i18n-ok` en commentaire sur
la même ligne.

`MissingTranslation` et `ExtraTranslation` sont réglés en erreurs dans
`build.gradle.kts`. Ils ne signalent rien tant qu'il n'y a qu'une langue, et
**ne tournent que sur `./gradlew lintDebug`** : `assembleDebug` ne lance aucune
tâche lint. La traduction ne bloquera donc jamais le travail au quotidien.

### Ce qui reste

**Les ~81 chaînes des huit écrans de `ECRANS_A_MIGRER`.** Mécanique, sans
risque, faisable un écran à la fois.

**Les dates.** `BrowserScreen` tronque l'ISO à 10 caractères. `java.time` est
disponible dès l'API 26 : `DateTimeFormatter.ofLocalizedDate`.

### Décisions prises

**La traduction se fait à la fin, en une passe, après l'extraction.** Les
langues cibles ne sont pas arrêtées. Traduire plus tôt reviendrait à traduire
un tiers de l'application, puis à rouvrir chaque `values-<langue>/` huit fois
de plus ; et un écran en cours de conception change de formulation trois ou
quatre fois. Le français est la langue de référence : `values/` est la seule
que le code touche au moment d'écrire.

**Une langue s'ajoute par deux gestes solidaires** : un `values-<langue>/` et
une ligne dans `res/xml/locales_config.xml`. L'un sans l'autre donne soit un
choix qui ne change rien, soit des ressources qu'Android n'annonce pas.
Android 13+ propose alors « Langue de l'application » dans les réglages
système — **pas de sélecteur à construire dans l'application**, ce qui
éviterait d'ajouter `androidx.appcompat` pour son rétroportage.

**« Sans titre » et « (conflit <horodatage>) » restent en français,
invariants**, et ne passent pas par la façade. Ce ne sont pas des textes
d'interface mais de vrais noms de fichiers sur le serveur, visibles depuis
l'interface web et les autres appareils. Les faire suivre la langue de chaque
téléphone produirait « (conflit …) » et « (conflict …) » côte à côte dans le
même dossier partagé. Trois mots de français constants coûtent moins cher que
cette incohérence, et que la mécanique d'injection qu'il aurait fallu
construire.

## État et limites

Le cœur Go est **vérifié** : ~200 cas unitaires plus une suite d'intégration.
L'interface Compose **compile et tourne**, mais sa couverture repose sur des
essais manuels — aucun test instrumenté. Le seul test côté Android est
`ChainesEnDurTest`, qui analyse les sources plutôt que le comportement : il
protège une règle de projet, il ne dit rien de ce que l'application fait.

Distinguer « écrit », « compile » et « testé » dans tout rapport d'avancement.

Restent ouverts, par ordre de priorité décidé : **l'extraction des chaînes
d'écran** (section ci-dessus, mécanique désormais), l'aperçu Markdown
rendu (brique 4-bis, goldmark), OIDC en alternative à l'App Token, et la
signature de l'APK pour distribution.

Le chemin de module est `opennote` alors que le dépôt existe
(`github.com/ybediat/OpenNote`) — renommage mécanique jamais fait.
