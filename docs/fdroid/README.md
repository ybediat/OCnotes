# Publier OpenNote sur F-Droid

Ce dossier ne sert qu'à préparer l'inclusion. Il ne fait rien lors du build.

[`eu.opennote.yml`](eu.opennote.yml) est la recette proposée. Sa place
définitive est le dépôt `fdroiddata`, sous `metadata/eu.opennote.yml` ; elle est
versionnée ici pour être jointe à la demande d'inclusion et pour suivre le dépôt
— une recette écrite une fois puis oubliée diverge à la première release.

## Deux valeurs à remplir avant envoi

**Le SHA-256 de l'archive Go.** La recette installe Go à la version exacte de
`go.mod` et vérifie son empreinte. Elle s'obtient ainsi :

```bash
curl -fsSL 'https://go.dev/dl/?mode=json&include=all' | jq -r '.[] | select(.version=="go1.26.0") | .files[] | select(.filename=="go1.26.0.linux-amd64.tar.gz") | .sha256'
```

**Le nom de la révision du NDK.** La recette demande `r27d`, soit
`27.3.13750724`. À confirmer contre la liste des NDK connus de `fdroidserver` :
si cette révision n'y figure pas, n'importe quelle `r27` convient désormais —
le module accepte `-Popennote.ndkVersion=<révision>` et le script Linux ne
compare plus que des minima.

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

Aucune configuration de signature non plus : F-Droid signe avec sa propre clé.
`android/app/build.gradle.kts` n'en déclare aucune, il n'y a donc rien à
retirer — c'est voulu, la chaîne de signature du projet vit dans
[`../RELEASING.md`](../RELEASING.md) et ne touche jamais Gradle.

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

## Qui signe : une décision, pas un détail

La recette est en **mode 1** — F-Droid construit et signe avec sa clé. C'est le
mode par défaut et le plus simple à faire accepter.

`../SIGNING.md` vise le **mode 2** : F-Droid reconstruit, vérifie l'identité
binaire hors signature, et publie l'APK signé par OpenNote. Seul ce mode permet
de passer d'une installation F-Droid à un APK téléchargé directement sans
désinstaller.

Le choix se fait **avant** la RFP, parce qu'il ne se reprend pas : les deux
signatures sont incompatibles, et changer d'avis oblige les utilisateurs venus
de F-Droid à désinstaller. Le mode 2 demande en plus un build reproductible,
non démontré à ce jour — et un échec de reproductibilité bloque la publication
au lieu de la dégrader.

Les deux lignes à décommenter (`Binaries`, `AllowedAPKSigningKeys`, empreinte
comprise) sont dans la recette, avec le raisonnement complet.

## Envoyer la demande

L'inclusion se demande par une RFP sur <https://gitlab.com/fdroid/rfp/-/issues>,
en joignant cette recette. Le dépôt remplit déjà les conditions de fond :
licence MIT, aucune dépendance non libre, aucun binaire versionné hors wrapper
Gradle — validé à chaque exécution de CI contre le registre officiel —, tags de
version et métadonnées Fastlane.
