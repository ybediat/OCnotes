# Préparer une release Android

Ce guide est destiné aux mainteneurs. Une release ne doit pas rendre publique
une clé, un mot de passe, un jeton ou un fichier de configuration local.

## Avant de signer

1. Exécutez les tests Go et Android.
2. Vérifiez que l'arbre Git est propre et que la version de l'application est
   celle attendue dans `android/app/build.gradle.kts`.
3. Poussez le tag, laissez la CI Linux construire, puis **téléchargez son
   artefact** :

```bash
gh run download <identifiant-du-run> -D ci-artefact
```

**C'est cet APK-là qu'on signe, jamais un build local.** F-Droid reconstruit
sous Linux et compare octet à octet hors signature ; le mode 2 échoue fermé,
donc une divergence n'entraîne pas une publication dégradée mais aucune
publication. Mesuré sur la 0.1.2, à partir du même commit : 18 187 434 octets
sous Linux contre 19 306 710 sous Windows, `libgojni.so` compris — Go 1.27 en
local contre 1.26.0 en CI suffit à l'expliquer, et les contrôles de version du
script laissent désormais passer cet écart avec un simple avertissement.

`scripts/build-android-linux.sh` reste le moyen de vérifier que la chaîne
aboutit, et son APK convient si on l'exécute sur la même image que la CI. Mais
par défaut, l'artefact de CI fait foi.

## Clé de signature

La clé de production se crée une seule fois, hors du dépôt :

```powershell
.\scripts\create-signing-key.ps1
```

Le script demande le mot de passe directement à `keytool` et refuse d'écraser
une clé existante. Conservez le mot de passe dans un gestionnaire de mots de
passe et au moins deux sauvegardes chiffrées de la clé sur des supports distincts.

Ne commitez jamais une clé ou une sauvegarde, même si son extension est ignorée
par Git.

## Signer et vérifier l'APK

Sur une machine qui possède la clé et les Android Build Tools 34.0.0 :

```powershell
.\scripts\sign-android-release.ps1 -Keystore "C:\chemin\vers\opennote-release.p12"
```

Pour signer l'APK non signé téléchargé depuis un artefact de CI, indiquez son
chemin avec `-Source` :

```powershell
.\scripts\sign-android-release.ps1 `
  -Keystore "C:\chemin\vers\opennote-release.p12" `
  -Source "C:\telechargements\app-release-unsigned.apk"
```

`-Source` est un alias de `-UnsignedApk`, conservé pour compatibilité.

Le script produit par défaut :

```text
dist/OpenNote-release-signed.apk
```

Il demande le mot de passe dans le terminal, vérifie la signature avec
`apksigner` et affiche l'empreinte SHA-256 de l'APK final. Installez ensuite
l'APK sur un appareil de test avant toute publication.

## Publication

Avant de publier un binaire :

- vérifiez que le dépôt et les artefacts ne contiennent aucune donnée privée ;
- publiez le checksum SHA-256 de l'APK si un téléchargement direct est proposé ;
- conservez l'APK non signé utilisé pour contrôler la reproductibilité ;
- notez les changements visibles par les utilisateurs ;
- assurez-vous que les mises à jour utilisent toujours la même clé de signature.

Ne publiez aucune clé ni aucun mot de passe dans les artefacts de CI, les logs,
les issues ou les releases.

## F-Droid

**L'APK signé ci-dessus est celui que F-Droid publiera.** C'est le mode 2 :
F-Droid reconstruit depuis les sources, vérifie que son résultat est identique
au binaire officiel hors signature, puis y recopie la signature OpenNote. Une
seule signature circule, donc un utilisateur passe de F-Droid au téléchargement
direct sans désinstaller. Recette et conséquences dans
[`fdroid/README.md`](fdroid/README.md).

Ce mode rend l'étape 3 non négociable : l'APK publié doit venir de la CI Linux.
Un APK construit ailleurs fait échouer la vérification, et le mode 2 échoue
fermé — l'application n'est alors pas publiée du tout.

Une release destinée à F-Droid demande en plus : un tag `V<version>` poussé sur
le dépôt public — c'est lui que suit `UpdateCheckMode: Tags` —, un journal
`fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` par langue
traduite, et l'APK signé publié en pièce jointe de la release GitHub à l'URL que
`Binaries` attend : `.../releases/download/V<version>/OpenNote-<version>.apk`.
