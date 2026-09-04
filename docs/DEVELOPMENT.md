# Développer OCnotes

Ce guide permet de construire l'application et d'itérer sur le code sans
connaître l'historique du projet.

## Prérequis

La chaîne de référence, utilisée par la CI Linux, requiert :

- Go à la version indiquée dans `go.mod` ;
- JDK 17 ;
- SDK Android avec les plateformes 26 et 35 ;
- NDK `27.3.13750724` ;
- Gradle `8.9` ;
- `gomobile` et `gobind` à la version verrouillée de `golang.org/x/mobile`.

Le script de build en contrôle les **minima**, pas l'égalité : une version plus
récente passe avec un avertissement. C'est ce qui permet à un empaqueteur —
F-Droid en particulier — de construire avec sa propre image sans que le build
échoue sur une comparaison de chaîne. Trois variables imposent un outil précis :
`OCNOTES_GRADLE_BIN`, `OCNOTES_NDK_VERSION` et `ANDROID_NDK_HOME` ; côté
Gradle, `-Pocnotes.ndkVersion=<révision>` fait la même chose pour le NDK.

Définissez `ANDROID_SDK_ROOT` (ou `ANDROID_HOME`) et `ANDROID_NDK_HOME` pour
que `gomobile` puisse trouver le SDK et le NDK. Les clés, `local.properties`,
APK, AAR et répertoires de build ne doivent pas être versionnés.

## Vérification rapide du cœur Go

Depuis la racine du dépôt :

```bash
go test ./... -short
go vet ./...
gofmt -l .
```

Ces commandes n'ont besoin ni d'un appareil Android ni d'un serveur. Le mode
`-short` exclut les tests d'intégration réseau.

## Construire sous Linux

Le chemin le plus fiable est le script utilisé par la CI :

```bash
bash scripts/build-android-linux.sh
```

Il vérifie les versions installées, télécharge les outils Go nécessaires dans
un cache local, exécute les tests Go, régénère l'AAR, puis lance les tests, le
lint et le build Android. L'APK non signé produit se trouve dans :

```text
android/app/build/outputs/apk/release/app-release-unsigned.apk
```

Le wrapper Gradle est versionné : `./gradlew` fonctionne sur un clone neuf. Pour
employer un Gradle déjà installé, définissez `OCNOTES_GRADLE_BIN` vers
l'exécutable voulu.

## Construire sous Windows

Le script PowerShell construit une version debug et l'installe sur l'appareil
ADB détecté :

```powershell
.\scripts\build-android.ps1
```

Variantes utiles :

```powershell
# Construit sans installer sur un appareil.
.\scripts\build-android.ps1 -SansInstall

# Après une modification purement Compose, si l'AAR existant est encore valide.
.\scripts\build-android.ps1 -SansBind -SansTests
```

Le script attend Go et le SDK Android aux emplacements courants de Windows. Si
votre installation diffère, adaptez votre environnement local sans committer ces
réglages.

## Régénérer le binding Go

`mobile/` est l'interface entre Go et Kotlin. Après une modification de son API
publique, régénérez l'AAR avant tout build Android :

```bash
gomobile bind -target=android/arm64,android/amd64 -androidapi 26 -trimpath \
  -ldflags="-s -w" -o android/app/libs/ocnotes.aar ./mobile
```

Ensuite, lancez les tests Android :

```bash
cd android && ./gradlew testDebugUnitTest lintDebug
```

Gradle ne régénère pas cet AAR à votre place. Une erreur Kotlin indiquant un
symbole absent après une modification de `mobile/` est souvent le signe d'un
binding périmé.

## Choisir les tests

| Modification | Validation minimale |
|---|---|
| paquet Go | `go test ./... -short`, `go vet ./...`, `gofmt -l .` |
| écran ou ViewModel Android | `./gradlew testDebugUnitTest lintDebug` |
| API de `mobile/` | Go + régénération AAR + tests Android |
| client OpenCloud | tests Go ; intégration si un environnement dédié est disponible |
| chaîne affichée | tests Android et contrôle des traductions |

Les tests d'intégration et le CLI sont détaillés dans [TESTING.md](TESTING.md).
