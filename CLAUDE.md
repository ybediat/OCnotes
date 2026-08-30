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
9,2 Mo, dont environ 0,7 Mo pour goldmark et l'aperçu (8,5 Mo avant).

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

**Un script PowerShell accentué doit porter un BOM UTF-8.** PowerShell 5.1,
celui de cette machine, lit un `.ps1` sans BOM dans la codepage ANSI :
« référence » devient « rÃ©fÃ©rence » — et le script écrit ce mojibake dans
les fichiers qu'il produit. Le défaut ne se voit qu'à l'exécution, jamais à
l'écriture, et il survit à une relecture attentive du source.

**`./gradlew lintDebug` passe** : zéro erreur, 39 avertissements. Il échouait
sur un `MissingPermission` dans `SyncNotifier.kt`, que ce document décrivait
comme « la notification postée sans vérifier `POST_NOTIFICATIONS`, défaut réel
et antérieur ». C'était faux, et faux dès l'écriture : la permission était
vérifiée, demandée à l'exécution et déclarée au manifeste depuis `2665103`,
soit la veille. Seulement, la garde vivait dans une méthode privée voisine, et
`MissingPermission` ne suit pas les appels — il ne voyait pas ce qu'il
cherchait. La vérification est désormais écrite en toutes lettres dans
`notifyConflicts`, et lint se tait.

La leçon vaut plus que le correctif : **un échec de lint n'est pas un défaut
tant qu'on n'a pas lu le code visé.** Celui-ci a été énoncé une fois, puis
repris sur parole, et il coûtait cher — `MissingTranslation` et
`ExtraTranslation` sont réglés en erreurs pour le chantier de traduction, et
une tâche qui ne peut pas être verte ne sert plus de signal.

Lint signale aussi deux `Typos` sur le mot « exemple », qu'il lit comme un
anglais mal orthographié : la règle ne connaît que l'anglais, et le dépôt
écrit ses ressources en français.

## Architecture

```
internal/opencloud/   HTTP, auth App Token, LibreGraph, WebDAV
internal/notes/       Library : arbre, nommage, bootstrap        (au-dessus de opencloud)
internal/store/       cache local, file offline, conflits         (au-dessus de notes)
internal/markdown/    mise en forme, titre, rendu de l'aperçu      (indépendant)
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
`docs/CHANTIER-DOCUMENTS.md` est un ordre de travail autonome : lecture seule
des `.docx` et `.odt`, à prendre à froid.
`docs/CHANTIER-EDITEUR.md` en est un second : virtualiser l'éditeur, dont la
section 7 bis de `docs/ARCHITECTURE.md` porte les mesures.

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

## Formats de fichier : trois questions, trois fonctions

L'application lit le Markdown **et** le texte brut — OpenCloud crée ses
fichiers en `.txt`, et un dossier alimenté depuis l'interface web en contient.
Une seule fonction répondait autrefois à trois questions distinctes, et les
confondre a un coût immédiat :

- **`notes.IsNote`** — « faut-il l'afficher dans la liste ? » Dit oui au `.txt`.
- **`notes.IsMarkdown`** — « faut-il l'interpréter ? » Dit non au `.txt` : un
  `#` y est un dièse, un `-` un tiret.
- **`notes.WithExtension`** — « quelle extension écrire ? » Répond toujours
  `.md` : l'application ne *crée* que du Markdown.

Le renommage a sa propre règle, **`WithExtensionOf`** : il reprend l'extension
du fichier renommé. Sans elle, `journal.txt` renommé en `carnet` perdait son
extension — l'utilisateur avait demandé un nom, pas une conversion.

`DisplayName` ne retire que l'extension Markdown. Un `.txt` garde la sienne
parce qu'il peut cohabiter avec un `.md` du même nom dans le même dossier.

## L'aperçu : Go analyse, Compose dessine

`internal/markdown/render.go` utilise goldmark comme **analyseur seul** — son
moteur HTML est jeté — et renvoie une liste **plate** de blocs. Compose les
dessine en `Text` et `AnnotatedString`, ce qui donne la typographie Material3,
le thème sombre et la sélection sans rien écrire pour eux. Un WebView aurait
demandé de réécrire les trois en CSS ; une bibliothèque Markdown Kotlin aurait
mis la règle dans la seule couche du dépôt sans test de comportement.

Deux choses ne traversent jamais la frontière, et c'est voulu :

- **le HTML brut**, ignoré — il n'y a pas de moteur pour l'interpréter, et une
  note vient d'un serveur partagé ;
- **la source d'une image.** L'éditeur web d'OpenCloud insère les images en
  `data:image/jpeg;base64,…`, soit plusieurs mégaoctets d'URL. Un bloc `image`
  ne porte que le texte alternatif.

Les bornes de span sont en **unités UTF-16**, comme partout ailleurs — mais ici
elles se comptent *au fil de l'écriture* du texte, pendant le parcours de
l'AST. goldmark repère ses nœuds en octets ; convertir après coup demanderait
de retraduire chaque borne, avec une occasion de se tromper par borne.

## Le piège du mot sans espace, constaté sur appareil

Une note contenant une image insérée depuis l'interface web d'OpenCloud
**faisait tuer l'application par le système**. Pas une exception Java, pas un
`OutOfMemoryError` : une mort de processus muette, suivie d'une purge mémoire
de tout le téléphone. Le tas Java, lui, n'avait jamais dépassé 11 Mo.

La cause n'est pas la taille. Un fichier de 285 ko de prose s'ouvre sans
broncher ; 44 ko de base64 tuent l'appareil. Ce qui compte est le **nombre de
points de coupure** : une image en base64 est un mot unique de dizaines de
milliers de caractères sans une seule espace, et le moteur de retour à la ligne
d'Android s'y épuise en mémoire native.

D'où deux dispositifs, dans cet ordre :

1. **`markdown.ExtractInlineData`** sort les `data:…` du texte avant qu'il
   n'atteigne le champ de saisie et les remplace par des jetons
   `opennote-image:N`. `RestoreInlineData` les remet à l'écriture. Le jeton est
   une URL bien formée : le texte allégé reste du Markdown valide, donc
   l'aperçu continue de le lire.
2. **`markdown.Editable`** attrape le reste — un fichier qui porte un mot
   démesuré sans rapport avec une image. La note s'ouvre alors en aperçu seul.
3. **`markdown.ShortenLongWords`**, appliqué à *tous* les blocs de l'aperçu.
   Sans lui, le repli du point 2 ne protégeait rien : un `Text` et un
   `TextField` partagent le même moteur de mise en page, et l'aperçu serait
   mort sur le pavé qu'il était censé sauver. C'est une troncature
   d'**affichage** — elle ne touche jamais ce qui part sur le serveur.

**Ne jamais écrire sans restituer.** C'est le seul chemin du dépôt qui peut
détruire des données de l'utilisateur en silence : enregistrer le texte à
jetons remplacerait l'image par `opennote-image:0` dans la vraie note, sur le
serveur, sans le moindre message. `TestInlineDataAllerRetour` et
`TestPrepareEditJSONAllerRetour` sont là pour ça, et la restitution se fait du
dernier jeton au premier — `opennote-image:1` est un préfixe de
`opennote-image:12`.

Corollaire pour le diagnostic : sur cette ROM Xiaomi, `crash_dump helper failed
to exec`. Un crash natif ne laisse **aucun tombstone**. Chercher `am_kill` et
`am_proc_died` dans `adb logcat -b events`, et l'absence d'exception Java dans
le tampon `crash`, dit plus que l'attente d'une pile qui ne viendra jamais.

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

### L'extraction est faite

`ECRANS_A_MIGRER` est **vide** : les huit écrans sont passés à
`stringResource`, et `strings.xml` porte environ 150 clés. La liste vide reste
dans `ChainesEnDurTest` plutôt que d'être supprimée — le jour où un écran
arrivera avec ses phrases en dur, la tentation sera de l'y inscrire, et
`listeDeMigrationAJour` refuse déjà qu'elle serve de tiroir.

Trois choix pris pendant l'extraction, qui se défont facilement sans le
vouloir :

- **Un libellé partagé va dans `action_*`.** « Annuler », « Supprimer »,
  « Renommer », « Créer », « Retour » désignent le même geste sur plusieurs
  écrans. Une clé par écran les ferait diverger à la première traduction, et
  rien ne le signalerait.
- **Un symbole n'est pas un texte.** Les faces de bouton de la barre de mise
  en forme sont des ressources — « G » pour gras devient « B » pour bold —
  mais « • », « [ ] » ou « \`\`\` » portent `translatable="false"`. Le libellé
  d'une action et sa description TalkBack sont deux clés distinctes : l'une
  tient sur un bouton, l'autre se lit à voix haute.
- **La date et la taille viennent de la plateforme**, pas de `strings.xml` :
  `DateTimeFormatter.ofLocalizedDate` et `Formatter.formatShortFileSize`
  connaissent l'ordre des composantes et les unités de chaque langue. Seul
  leur assemblage — le séparateur — est une ressource. La locale se lit dans
  la composition (`LocalConfiguration.current.locales[0]`), jamais par
  `Locale.getDefault()` : c'est la raison d'être de `Texte`, elle vaut aussi
  pour un format.

Une fonction non composable qui rédige du texte doit devenir `@Composable`
pour lire ses ressources — `sousTitre`, `explication`, `apparenceDe` l'ont
toutes été. Elles rendent une `String` déjà rédigée et non un identifiant de
ressource, parce que chacune a un repli qui n'en a pas : le type d'espace
inconnu, l'identifiant d'action que le cœur Go vient d'ajouter.

Dans `strings.xml`, une apostrophe s'écrit `\'`. Sans l'échappement, aapt
échoue sur **« Invalid unicode escape sequence »** — un message qui ne désigne
pas sa cause. Attention aux outils qui mangent les antislashs en chemin (un
heredoc shell, par exemple) : ils produisent exactement cette panne.

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
Ceux-là se voient sous la pseudo-langue `en-XA`, qui affiche accentué et
allongé tout ce qui est traduit, sans qu'aucun `values-en/` soit à maintenir.
Un littéral délibéré se marque `i18n-ok` en commentaire sur la même ligne.

**Le sélecteur de langue de MIUI n'expose pas les pseudo-langues** — inutile
de les chercher dans les options développeur de l'appareil de test, la ROM
filtre la liste. On passe donc par la locale d'application, qui ne demande ni
root ni réglage système :

```powershell
adb shell cmd locale set-app-locales eu.opennote.debug --locales en-XA
adb shell cmd locale set-app-locales eu.opennote.debug --locales ""   # retour
```

Deux conditions, faciles à oublier : `isPseudoLocalesEnabled` doit être vrai
sur le type de build `debug`, sinon l'APK ne contient aucune ressource `en-XA`
et la commande ne change rien visiblement ; et le paquet porte le suffixe
`.debug`, sans quoi la commande vise une application qui n'est pas installée
et échoue sans un mot. `ar-XB` est générée en même temps, pour l'écriture de
droite à gauche.

`ExtraTranslation` est réglé en erreur dans `build.gradle.kts` : une clé
présente dans une traduction mais absente de `values/` signale une clé
supprimée de la référence que la traduction traîne encore. Le cas ne se
produit jamais par accident, et la règle ne fait aucun bruit au quotidien.

**`MissingTranslation` est en pause** — `disable`, pas `warning`. Voir « Ce
qui reste » pour le motif et pour la ligne à retirer le jour venu.

Les deux **ne tournent que sur `./gradlew lintDebug`** : `assembleDebug` ne
lance aucune tâche lint, et `testDebugUnitTest` non plus. Quand quelqu'un dit
« les tests ne passent pas » à propos de traduction, il parle de lint.

### Intégrer une traduction rendue

Un traducteur part d'une copie de `values/strings.xml` et n'y remplace que les
valeurs — c'est ce qu'on lui demande. Sa copie porte alors quatre défauts que
l'œil ne voit pas, les quatre constatés sur les deux premières langues :

1. **les entrées `translatable="false"` sont recopiées.** Une clé en trop est
   une erreur (`ExtraTranslation`) au même titre qu'une clé manquante ;
2. **les guillemets protecteurs sautent.** Le séparateur de sous-titre rendu
   sans eux, aapt supprime ses espaces de bord et la ligne d'une note devient
   « 30 August 2026·1.2 kB » ;
3. **une apostrophe n'est pas échappée.** Un « Don't » anglais fait échouer la
   compilation sur « Invalid unicode escape sequence », qui ne désigne pas sa
   cause ;
4. **l'en-tête se présente encore comme la langue de référence.**

`scripts/normalise-traduction.ps1` corrige les quatre, rend compte de ce qu'il
a touché, signale les clés manquantes ou en trop, et **refuse de s'exécuter
sur `values-en/`** — qui porte l'en-tête long servant de modèle. Il est
idempotent.

```powershell
.\scripts\normalise-traduction.ps1 -Fichier android/app/src/main/res/values-de/strings.xml -Langue allemande
```

**Le fichier va dans `values-<langue>/strings.xml`, jamais dans `values/`.**
Déposé dans `values/` sous un nom comme `strings-en.xml`, il est lu comme une
seconde langue par défaut : la compilation échoue sur autant de doublons qu'il
y a de clés, et rien dans le message ne dit que c'est une affaire de dossier.

Ce que le script ne sait pas voir et que lint attrape : les formes de pluriel
propres à la langue. L'espagnol demande une forme `many` que ni le français ni
l'anglais n'ont — le traducteur avait recopié `one` et `other`.

### Ce qui reste

**Deux langues sont en place**, `values-en/` et `values-es/`. L'anglais sert
de modèle : son en-tête porte les règles de traduction, dans l'ordre où l'on
s'y trompe. Les langues suivantes reçoivent un en-tête court qui y renvoie —
recopier la consigne dans chaque fichier garantirait qu'elle devienne fausse
quelque part.

**`MissingTranslation` est en pause, et c'est une décision.** Traduire deux
langues pendant que l'interface bouge encore faisait échouer `lintDebug` à
chaque chaîne ajoutée à `values/`. Le rappel n'apprenait rien — on sait que la
traduction est en retard, c'est le principe même de traduire à la fin — et un
avertissement qu'on s'habitue à ignorer finit par masquer ceux qui comptent.
La règle est donc `disable` et non `warning` : ne pas laisser derrière soi un
signal qu'il faut réapprendre à trier à chaque passage.

Une chaîne sans traduction retombe sur le français, ce qu'Android fait de
toute façon. Rien n'est cassé à l'écran, seulement pas encore traduit.

**Une seule ligne à retirer** de `build.gradle.kts` pour rouvrir le chantier :
`disable.add("MissingTranslation")`. Elle rétablit d'un coup l'inventaire des
manques, langue par langue — c'est exactement l'outil qu'il faudra à ce
moment-là, et c'est pourquoi il n'y a rien d'autre à construire pour suivre
la dette entre-temps.

**Ce qui ne marche pas, et qu'il est tentant d'essayer** : retirer les langues
de `locales_config.xml`. Vérifié, non supposé — une chaîne non traduite
ajoutée à `values/`, puis le fichier ramené à `fr` seul, et l'erreur reste.
Lint regarde les dossiers `values-<langue>/` présents, pas ce que
l'application déclare au système. On perdrait l'annonce de la langue, donc la
possibilité de la tester, en gardant l'erreur : le pire des deux.

**Les quatre chaînes de `SyncWorker`** restent en dur, volontairement : elles
partent dans `Log.w`, pas à l'écran. `HORS_INTERFACE` les couvre, et cette
liste-là n'a pas vocation à se vider.

### Décisions prises

**La traduction se fait à la fin, en une passe, après l'extraction.** Elle a
été enfreinte sciemment : l'anglais puis l'espagnol ont été traduits pendant
que l'interface bougeait encore, pour éprouver le dispositif en grandeur
nature plutôt que sur parole. L'essai a rapporté ce qu'il a coûté — il a mis
au jour les quatre défauts d'une traduction rendue, et la friction de
`MissingTranslation` décrite ci-dessus. La décision reste valable pour les
langues suivantes : les ouvrir maintenant, c'est rouvrir chaque
`values-<langue>/` à chaque écran retouché.

Le français est la langue de référence : `values/` est la seule que le code
touche au moment d'écrire.

**Une langue s'ajoute par deux gestes solidaires** : un `values-<langue>/` et
une ligne dans `res/xml/locales_config.xml`. L'un sans l'autre donne soit un
choix qui ne change rien, soit des ressources qu'Android n'annonce pas.
Android 13+ propose alors « Langue de l'application » dans les réglages
système — **pas de sélecteur à construire dans l'application**, ce qui
éviterait d'ajouter `androidx.appcompat` pour son rétroportage.

**Un garde-fou qu'on doit apprendre à ignorer est un garde-fou en trop.**
`MissingTranslation` a été mis en pause plutôt qu'en avertissement pour cette
raison, et la même règle vaut ailleurs : entre un signal bruyant qu'on
n'écoute plus et un silence assumé jusqu'à la date où le contrôle sert, le
silence est plus honnête. Ce qui l'assume ici : une ligne commentée dans
`build.gradle.kts` qui dit quand la retirer, et un paragraphe dans « Ce qui
reste » qui dit pourquoi elle est là.

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

Restent ouverts, par ordre de priorité décidé : les **langues au-delà de
l'anglais et de l'espagnol**, OIDC en alternative à l'App Token, et la
signature de l'APK pour distribution.

**Les deux traductions n'ont pas été relues par un locuteur sur l'appareil.**
L'anglais a été vu à l'écran d'accueil, rien de plus ; l'espagnol n'a pas été
lancé du tout. Elles compilent, lint les accepte, leurs clés sont complètes —
c'est tout ce qui est établi.

**L'éditeur décroche vers 80 lignes**, et ce n'était pas le diagnostic qu'on
en avait. Mesuré au `framestats` sur appareil, relevés complets en section
7 bis de `docs/ARCHITECTURE.md` :

- ce n'est **pas** la mise en page. `PerformTraversalsStart → DrawStart` tient
  0,11 ms de 445 octets à 295 ko, frappe comprise. Tout le temps est dans
  l'enregistrement de la display list, à 0,14–0,24 ms par ligne, **refait
  intégralement à chaque changement d'offset**. Corollaire : passer à
  `TextFieldState` ne changerait rien au défilement ;
- ce n'est **pas** peu urgent. La frappe coûte 105 ms par caractère à 196
  lignes et **750 ms à 1633 lignes** : l'éditeur est inutilisable en saisie
  bien avant la taille qui avait motivé l'enquête ;
- sortir le défilement du champ pour le poser dans une couche translatable —
  la piste évidente — **fait planter l'application** : Compose ne sait pas
  représenter une hauteur de 531 251 px dans un `Constraints`, le plafond étant
  à 262 143 px, soit environ 1300 lignes.

Deux bornes en découlent pour un champ de saisie : ~80 lignes pour tenir dans
une image de 16 ms, ~1300 avant le plantage. La première commande. L'unité
éditable doit donc faire la taille d'un écran, pas celle d'un chapitre — et la
note de test ne contient **aucun titre ATX**, ce qui condamne le découpage aux
titres avant même de l'écrire. La virtualisation n'est pas une optimisation de
l'éditeur, c'est la seule chose qui tienne.

**La traduction vient après l'extraction, pas avant** — décision prise, motifs
dans la section Localisation. L'extraction étant faite, il ne reste qu'à
remplir un `values-<langue>/` et à déclarer la langue dans
`locales_config.xml`. Ce n'est pas un chantier d'architecture.
