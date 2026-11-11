# Note — sauvegarder les profils locaux

Rédigée le 10/11/2026, à la suite du contrôle de la conservation des données.
Rien n'est décidé ni codé : c'est un état des possibilités, à relire à froid.

> **Mise à jour du 11/11/2026.** Les options B et C sont codées
> (`SauvegardeAgent`, `SauvegardeProfils`). Le reste de la note décrit l'état
> d'avant et garde sa valeur d'explication. Ce qui a été tranché :
>
> - **une sauvegarde non chiffrée de bout en bout est refusée.** Le contrôle
>   est fait dans l'agent : `disableIfNoEncryptionCapabilities` n'est lu que
>   par l'agent par défaut d'Android, que le nôtre remplace. Sans verrouillage
>   d'écran, ou avant Android 9, rien ne part ; le transfert direct d'un
>   téléphone à l'autre reste permis ;
> - **est local un profil que son `config.json` dit local.** Le registre
>   Android retarde parfois sur le cœur Go, dans les deux sens ;
> - le registre ne part pas : un instantané réduit aux profils locaux le
>   remplace, fusionné à la restauration (obstacles 2 et 3) ;
> - les Réglages montrent la taille, le quota annoncé par le transport, la
>   date du dernier envoi et le dernier refus.
>
> Reste non prouvé : l'aller-retour réel sur un téléphone (section « Comment
> l'essayer »). L'agent ne se teste pas sur la JVM.

## Le constat

Un profil local n'existe que dans `files/accounts/<uuid>/cache/` sur le
téléphone. Aujourd'hui rien n'en sort tout seul :

- `AndroidManifest.xml` porte `allowBackup="false"` ;
- `res/xml/backup_rules.xml` (Android 11 et moins) et
  `res/xml/data_extraction_rules.xml` (Android 12 et plus) excluent tout,
  sauvegarde Google **et** transfert d'un téléphone à l'autre.

Désinstaller, effacer les données, perdre, casser ou remplacer le téléphone
détruit donc le profil. La seule sortie est l'export zip manuel
(Réglages → « Exporter les notes »).

Ce réglage était voulu (carnet : `PERSISTANCE-LOCAL`), pour deux raisons : le
jeton est chiffré par une clé du Keystore qui ne quitte pas l'appareil, et le
cache d'un profil serveur se reconstruit depuis le serveur. La seconde raison
ne vaut pas pour un profil local. Le commentaire de `backup_rules.xml` la
répète encore telle quelle.

## Ce qu'Android propose

Deux mécanismes distincts, réglables séparément depuis Android 12.

**La sauvegarde automatique (« Auto Backup »)**

- copie `files/`, les préférences et les bases vers le compte Google de
  l'utilisateur ; `cacheDir` et `noBackupFilesDir` n'en font jamais partie ;
- tourne environ une fois par jour, téléphone au repos, en charge et en Wi-Fi ;
- **plafond de 25 Mo par application** : au-delà, la sauvegarde échoue en
  entier, sans message à l'utilisateur. Une bibliothèque avec des images en
  base64 peut le dépasser ;
- chiffrée de bout en bout par le code de verrouillage sur Android 9 et plus,
  si l'écran est verrouillé. On peut **exiger** ce chiffrement :
  `disableIfNoEncryptionCapabilities="true"` sur `<cloud-backup>`, et
  `requireFlags="clientSideEncryption"` dans l'ancien format ;
- ne restaure qu'à **l'installation** de l'application (ou à la configuration
  d'un téléphone neuf). « Effacer les données » ne déclenche aucune
  restauration ;
- sans services Google, rien ne part. Les ROM alternatives branchent Seedvault
  sur la même interface : ce qui est ouvert ici vaut aussi pour elles.

**Le transfert d'appareil à appareil**

- copie directe, par câble ou Wi-Fi, quand l'utilisateur configure un nouveau
  téléphone à partir de l'ancien ;
- rien ne passe par Google ;
- ne couvre que le changement de téléphone, pas la perte ni la casse ;
- à vérifier : il ne serait pas soumis au plafond de 25 Mo.

## Les trois obstacles propres à OCnotes

1. **Les règles XML ne savent pas distinguer un profil local d'un profil
   serveur.** Les dossiers portent un UUID, et les règles n'acceptent que des
   chemins fixes. Inclure `accounts/` emporterait aussi le cache des profils
   serveur, file d'attente comprise.
2. **Le registre doit suivre.** Sans `accounts.json`, `AccountRegistry` ne
   reconstruit pas depuis les dossiers : il crée un profil neuf et ignore ceux
   qui sont là (`loadRegistry` rend `null`, puis `migrateOrCreate`). Des notes
   restaurées sans registre seraient sur le disque et invisibles.
3. **Avec le registre reviennent les profils serveur**, sans jeton ni cache.
   Le cas « connecté côté Go, jeton absent » est déjà traité
   (`RestoreOutcome.AUCUNE_SESSION`) ; le cas « au registre, dossier vide » est
   à vérifier.

## Les options

| | Couvre | Coût | Limite |
|---|---|---|---|
| **A. Rappel d'export** | tout, si l'utilisateur le fait | faible | repose sur un geste |
| **B. Transfert d'appareil seul** | changement de téléphone | faible | ni perte, ni casse |
| **C. Sauvegarde Google chiffrée** | perte, casse, désinstallation | moyen | 25 Mo, services Google |
| **D. Export automatique vers un dossier choisi** | tout, sans Google | moyen à fort | l'utilisateur choisit un dossier |

**A.** Afficher la date du dernier export dans les réglages d'un profil local,
et rappeler au-delà d'un délai. Aucun changement de stockage.

**B et C.** Passer `allowBackup` à `true` et remplacer les règles XML par un
`BackupAgent` (`android:backupAgent`, `android:fullBackupOnly="true"`). Son
`onFullBackup` choisit les fichiers en code : le registre, puis, pour chaque
profil dont le cœur est local, `config.json`, `index.json` et `notes/`. Les
préférences chiffrées du jeton restent exclues. B seul s'obtient en gardant
`<cloud-backup>` vide.

**D.** L'utilisateur désigne un dossier (sélecteur de documents, permission
conservée), et un travail WorkManager y dépose le zip de l'export existant.
C'est la seule option qui serve les utilisateurs F-Droid sans services Google,
et la seule qui survive à tout. Le zip est en clair : le dossier choisi décide
de sa confidentialité.

## Ce que je ferais

B et C ensemble par un `BackupAgent`, chiffrement exigé, profils locaux seuls.
Puis A, parce que le plafond de 25 Mo fait échouer C en silence : la date du
dernier export reste le seul signal fiable. D ensuite, si le public F-Droid le
demande.

## Avant de coder

- Décider si des notes peuvent partir chez Google, même chiffrées. C'est une
  promesse faite aux utilisateurs, à écrire dans la description de
  l'application.
- Rouvrir l'entrée `PERSISTANCE-LOCAL` du carnet, et corriger le commentaire de
  `backup_rules.xml`.
- Traiter l'obstacle 3 avant d'activer quoi que ce soit.

## Comment l'essayer

Sans attendre la sauvegarde nocturne, sur `eu.ocnotes.debug` :

```bash
adb shell bmgr enable true
adb shell bmgr transport com.android.localtransport/.LocalTransport
adb shell bmgr backupnow eu.ocnotes.debug
adb uninstall eu.ocnotes.debug
# réinstaller, puis vérifier que le profil local est revenu
adb shell bmgr transport com.google.android.gms/.backup.BackupTransportService
```

Le transport local reste sur le téléphone : il valide les règles, pas le
plafond de 25 Mo ni le chiffrement. La dernière commande remet le transport
Google, celui qui était actif le 10/11/2026.

Prendre une copie des données avant l'essai :

```bash
adb exec-out run-as eu.ocnotes.debug tar cf - files shared_prefs > sauvegarde.tar
```
