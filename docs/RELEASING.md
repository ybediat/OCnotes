# Préparer une release Android

Ce guide est destiné aux mainteneurs. Une release ne doit pas rendre publique
une clé, un mot de passe, un jeton ou un fichier de configuration local.

## Avant de signer

1. Exécutez les tests Go et Android.
2. Vérifiez que l'arbre Git est propre et que la version de l'application est
   celle attendue dans `android/app/build.gradle.kts`.
3. Poussez le tag et laissez la CI Linux construire. **Le script de signature
   récupère son artefact tout seul** : il n'y a rien à télécharger à la main.

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
.\scripts\sign-android-release.ps1 -Keystore "$env:USERPROFILE\.ocnotes\signing\ocnotes-release.p12"
```

Sans autre indication, le script signe **l'APK non signé produit par la CI pour
le commit courant** : il interroge `gh`, télécharge l'artefact sous
`dist/ci-<commit>/` et signe celui-là. C'est le seul APK que F-Droid puisse
reconstruire à l'identique. Si aucune exécution réussie n'existe pour ce commit,
le script s'arrête en le disant plutôt que de se rabattre sur autre chose.

Deux échappatoires, toutes deux explicites :

- `-Source <chemin>` impose un APK précis, par exemple un artefact déjà
  téléchargé ;
- `-Local` reprend le build de `android/app/build/outputs`, avec un
  avertissement. À réserver à un essai d'installation sur appareil — cet APK
  n'est pas publiable.

`-Source` est un alias de `-UnsignedApk`, conservé pour compatibilité. Sans
`-OutputApk`, la sortie est nommée d'après le `versionName` **lu dans l'APK
lui-même** avec `aapt2 dump badging` — jamais deviné à partir de ce qui traîne
déjà dans `dist/` : `OCnotes-<version>.apk`, par exemple `OCnotes-0.1.3.apk`.
C'est le nom qu'attend l'URL `Binaries` de la recette F-Droid. Vous pouvez
toujours imposer un nom différent avec `-OutputApk`.

Le script demande le mot de passe dans le terminal, vérifie la signature avec
`apksigner` et affiche l'empreinte SHA-256 de l'APK final. Installez-le sur un
appareil de test avant toute publication.

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
au binaire officiel hors signature, puis y recopie la signature OCnotes. Une
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
`Binaries` attend : `.../releases/download/V<version>/OCnotes-<version>.apk`.
