# Publier OpenNote sur F-Droid

Ce dossier ne sert qu'à préparer l'inclusion. Il ne fait rien lors du build.

[`eu.opennote.yml`](eu.opennote.yml) est la recette proposée. Sa place
définitive est le dépôt `fdroiddata`, sous `metadata/eu.opennote.yml` ; elle est
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
convient désormais — le module accepte `-Popennote.ndkVersion=<révision>` et le
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
