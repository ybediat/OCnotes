# Publier OCnotes sur F-Droid

Ce dossier ne sert qu'à préparer l'inclusion. Il ne fait rien lors du build.

[`eu.ocnotes.yml`](eu.ocnotes.yml) est la recette proposée. Sa place
définitive est le dépôt `fdroiddata`, sous `metadata/eu.ocnotes.yml` ; elle est
versionnée ici pour être jointe à la demande d'inclusion et pour suivre le dépôt
— une recette écrite une fois puis oubliée diverge à la première release.

## Ce qui reste à confirmer

**Le SHA-256 de l'archive Go est renseigné** :
`aac1b08a0fb0c4e0a7c1555beb7b59180b05dfc5a3d62e40e9de90cd42f88235`. À
recalculer à chaque changement de version dans `go.mod` :

```bash
curl -fsSL 'https://go.dev/dl/?mode=json&include=all' | jq -r '.[] | select(.version=="go1.26.0") | .files[] | select(.filename=="go1.26.0.linux-amd64.tar.gz") | .sha256'
```

**Le nom de la révision du NDK**, en revanche, reste à confirmer. La recette
demande `r27d`, soit `27.3.13750724`. À vérifier contre la liste des NDK connus
de `fdroidserver` : si cette révision n'y figure pas, n'importe quelle `r27`
convient désormais — le module accepte `-Pocnotes.ndkVersion=<révision>` et le
script Linux ne compare plus que des minima.

## Ce que la recette fait, et pourquoi

Le cœur métier est en Go. `gomobile bind` le compile en un AAR **avant** que
Gradle ne démarre ; c'est l'étape `prebuild`. Trois points s'y jouent :

- **`scanignore`.** L'AAR produit est un binaire dans l'arbre de build, et le
  scanner de sources de F-Droid les rejette. Sans cette ligne, le build s'arrête
  sur « Found binary » alors que le fichier vient d'être construit depuis les
  sources du dépôt, à l'étape précédente.
- **`GOPATH` reste à son défaut**, `$HOME/go`, donc hors de l'arbre de build :
  les exécutables `gomobile` et `gobind` ne sont jamais vus par le scanner et
  n'ont pas à être ignorés à leur tour.
- **`git rev-parse --show-toplevel`** en tête de `prebuild` évite de supposer si
  l'étape démarre à la racine du dépôt ou dans `subdir`.

## Ce que la recette ne contient pas

`Summary` et `Description` ne sont pas recopiés : F-Droid lit
`fastlane/metadata/android/<locale>/` **dans ce dépôt**. Le titre, les
descriptions, les journaux nommés par `versionCode`, l'icône 512×512 et les
captures y sont déjà. Les modifier ici serait les faire diverger.

Aucune configuration de signature côté Gradle : `android/app/build.gradle.kts`
n'en déclare aucune, et c'est voulu. La clé n'intervient qu'après le build, sur
la machine qui la détient, par `scripts/sign-android-release.ps1` — jamais
pendant la compilation, jamais dans un fichier du dépôt. Le mode 2 ne change
rien à cela : F-Droid construit un APK non signé, puis y recopie la signature
de l'APK officiel qu'il a vérifié.

## Architectures

La recette construit `arm64-v8a` et `x86_64`, dans `gomobile bind` comme dans
`abiFilters`. Les deux vont ensemble : une ABI ajoutée à l'un sans l'autre donne
soit un `.so` inutilisé, soit un APK qui plante au premier appel JNI.

Sept fichiers portent cette liste — le module Gradle, les deux scripts de build,
cette recette et trois documents. Plutôt que de s'y fier de mémoire :

```bash
grep -rn "target=android/arm64\|abiFilters" --include="*.sh" --include="*.ps1" --include="*.md" --include="*.yml" --include="*.kts" .
```

`x86_64` couvre les émulateurs, ChromeOS et Waydroid. Elle demande peu de
confiance supplémentaire : c'est la même `GOARCH=amd64` que celle sur laquelle
tourne `go test ./...` à chaque itération de développement.

`armeabi-v7a` n'est pas exclue par principe mais faute de pouvoir la tester :
les images système ARM 32 bits s'arrêtent à l'API 25, et `minSdk` vaut 26. Le
cœur Go, lui, est propre en 32 bits — pas de `sync/atomic`, pas de `unsafe`, pas
de cgo, toutes les tailles en `int64` — et la suite unitaire complète passe
compilée et exécutée en 32 bits :

```bash
GOARCH=386 go test ./... -short
```

Il ne manque donc qu'un appareil réel pour l'ajouter.

## Qui signe : mode 2, tranché

F-Droid reconstruit l'application, vérifie que son APK est identique au binaire
officiel hors signature, puis publie **celui signé par OCnotes**. Une seule
signature circule : un utilisateur passe d'une installation F-Droid à un
téléchargement direct, ou à Obtainium, sans désinstaller. C'est la raison du
choix, et elle est du côté de l'utilisateur.

Le mode 2 **échoue fermé** : si la reconstruction ne correspond pas, F-Droid ne
publie pas — il ne se rabat pas sur sa propre signature. D'où la règle suivante,
qui n'est pas un conseil.

### L'APK publié vient de la CI Linux, jamais d'un build local

Mesuré le 4 septembre 2026 sur la 0.1.2, à partir du même commit :

```text
Linux (CI)   9bfb1e2ab4faf0c3   18 187 434 octets
Windows      9179361f746f720f   19 306 710 octets
```

Jusqu'au `libgojni.so` diffère. Une des causes est visible : Go 1.27.0 sur le
poste de développement contre 1.26.0 en CI — l'assouplissement des contrôles de
version, qui existe pour F-Droid, laisse désormais passer cet écart avec un
simple avertissement. F-Droid construit sous Linux ; un APK produit sous Windows
ne correspondra jamais.

`scripts/sign-android-release.ps1` en tient compte : sans indication de source,
il télécharge l'artefact de la CI pour le commit courant et signe celui-là. Le
build local reste accessible par `-Local`, à la demande et avec un
avertissement.

## Envoyer la demande

L'inclusion se demande par une RFP sur <https://gitlab.com/fdroid/rfp/-/issues>,
en joignant cette recette. Le dépôt remplit déjà les conditions de fond :
licence MIT, aucune dépendance non libre, aucun binaire versionné hors wrapper
Gradle — validé à chaque exécution de CI contre le registre officiel —, tags de
version et métadonnées Fastlane.

Le texte de la demande, prêt à coller, est dans [`RFP.md`](RFP.md) — en anglais,
langue du tracker.
