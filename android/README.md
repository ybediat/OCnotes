# OpenNote — application Android

Interface Jetpack Compose posée sur le cœur métier Go, lié par `gomobile bind`.

> **État : jamais compilé.** Ni le SDK Android, ni Gradle, ni le NDK ne sont
> installés sur la machine où ce code a été écrit. Il n'a donc pas été
> construit, ni exécuté. Attendez-vous à une passe de correction au premier
> build (versions du catalogue, imports, API Compose).

## Prérequis

1. Android Studio (ou le SDK en ligne de commande) avec le **NDK**, exigé par
   `gomobile bind`.
2. La chaîne Go et `gomobile`, installés selon [`../docs/SETUP.md`](../docs/SETUP.md).
3. Le wrapper Gradle. Les scripts `gradlew` et `gradle-wrapper.jar` sont des
   binaires qui ne sont pas dans ce dépôt : générez-les une fois, depuis
   `android/`, avec une installation locale de Gradle 8.9 ou plus récente.

   ```bash
   gradle wrapper --gradle-version 8.9
   ```

## Générer le binding Go

**À faire avant tout build.** Le module `:app` déclare le `.aar` en dépendance
fichier ; sans lui, la compilation échoue sur des symboles `mobile.App` et
`mobile.Mobile` introuvables.

Depuis la racine du dépôt :

```bash
gomobile bind -target=android -androidapi 26 -o android/app/libs/opennote.aar ./mobile
```

Le `.aar` est ignoré par git (`*.aar` dans le `.gitignore` racine) : il se
régénère, il ne se versionne pas. Relancez la commande à chaque modification
de `mobile/` ou de `internal/`.

Pour restreindre le poids de l'APK, le module ne conserve que l'ABI
`arm64-v8a` (`ndk.abiFilters` dans `app/build.gradle.kts`). Pour couvrir les
appareils 32 bits, ajoutez `armeabi-v7a` **aux deux endroits** : ici et dans la
commande `gomobile bind` (`-target=android/arm64,android/arm`).

## Construire

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## Ce que fait chaque couche

```
data/         OpenNoteRepository — seul point de contact avec mobile.App :
              Dispatchers.IO, parsing kotlinx.serialization, erreurs typées
              TokenStore        — App Token dans les EncryptedSharedPreferences
sync/         SyncScheduler     — WorkManager : périodique, premier plan,
                                  anti-rebond après écriture
              SyncWorker        — une passe de synchronisation
              SyncNotifier      — notification des conflits
ui/<écran>/   un ViewModel + des composables sans logique métier
```

Le contrat de la frontière est décrit dans
[`../docs/FACADE.md`](../docs/FACADE.md). Il est gelé : cette couche s'y
conforme, elle ne le renégocie pas.

## Trois points à ne pas casser

**Le démarrage passe par `restore`, jamais par `connect`.** `restore(token)`
remonte la session depuis la configuration sans aucun appel réseau ; l'écran
des notes s'affiche, puis `connect(...)` valide le token en arrière-plan. Un
refus `AUTH` ramène à la saisie, une panne réseau s'ignore. Démarrer par
`connect` ferait échouer le lancement hors connexion — et avec lui *tous* les
appels de navigation, y compris ceux qui savent se replier sur le cache.

**Le token ne quitte pas les `EncryptedSharedPreferences`.** Le cœur Go ne le
persiste jamais : il le reçoit à chaque démarrage via `Restore` puis `Connect`. Ne l'écrivez
ni dans `SharedPreferences` ordinaires, ni dans un fichier, ni dans un journal.

**Les bornes de sélection ne subissent aucune conversion.** La barre d'outils
passe `TextFieldValue.selection.start` et `.end` tels quels à
`ApplyFormatJSON`, et réapplique la sélection renvoyée telle quelle. Les deux
côtés comptent en unités de code UTF-16. Une conversion en octets déplacerait
le curseur dès la première note accentuée.
