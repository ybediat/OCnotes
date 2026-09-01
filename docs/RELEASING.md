# Préparer une release Android

Ce guide est destiné aux mainteneurs. Une release ne doit pas rendre publique
une clé, un mot de passe, un jeton ou un fichier de configuration local.

## Avant de signer

1. Exécutez les tests Go et Android.
2. Vérifiez que l'arbre Git est propre et que la version de l'application est
   celle attendue dans `android/app/build.gradle.kts`.
3. Construisez l'APK release non signé avec la chaîne Linux de référence :

```bash
bash scripts/build-android-linux.sh
```

L'APK généré est volontairement non signé. Il sert de référence de build et ne
doit pas être remplacé par l'APK signé.

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
