# Tester OCnotes

OCnotes sépare les tests rapides et reproductibles des vérifications contre un
serveur OpenCloud réel.

## Tests locaux

À lancer avant une pull request :

```bash
go test ./... -short
go vet ./...
gofmt -l .
cd android && ./gradlew testDebugUnitTest lintDebug
```

Les tests Go utilisent des serveurs simulés et des fixtures anonymisées. Ils ne
nécessitent ni compte OpenCloud, ni téléphone, ni connexion réseau.

## Tests d'intégration OpenCloud

Les tests d'intégration sont volontairement désactivés tant que les trois
variables suivantes ne sont pas définies :

```bash
export OCNOTES_IT_SERVER="https://cloud.exemple.fr"
export OCNOTES_IT_USER="mon-compte-de-test"
export OCNOTES_IT_TOKEN="mon-app-token"
```

Sous PowerShell :

```powershell
$env:OCNOTES_IT_SERVER = "https://cloud.exemple.fr"
$env:OCNOTES_IT_USER = "mon-compte-de-test"
$env:OCNOTES_IT_TOKEN = "mon-app-token"
```

Puis lancez :

```bash
go test ./... -run TestIntegration -v
```

Ces tests créent un dossier temporaire dans l'espace personnel accessible par
ce compte, puis tentent de le supprimer à la fin. Utilisez un compte ou un
espace dédié aux tests, avec un App Token révocable. En cas d'interruption ou
d'échec de nettoyage, recherchez et supprimez le dossier commençant par
`ocnotes-it-`.

Ne mettez jamais ces variables dans un fichier versionné, une issue, un log ou
une capture d'écran.

## CLI de diagnostic

Le CLI emploie le même client Go que l'application ; il peut donc modifier les
données d'un serveur réel. Construisez-le puis fournissez les identifiants par
variables d'environnement :

```bash
go build -o bin/ocnotes-cli ./cmd/ocnotes-cli

export OCNOTES_SERVER="https://cloud.exemple.fr"
export OCNOTES_USER="mon-compte-de-test"
export OCNOTES_APP_TOKEN="mon-app-token"
./bin/ocnotes-cli tree
```

Sous PowerShell, définissez de la même manière `OCNOTES_SERVER`,
`OCNOTES_USER` et `OCNOTES_APP_TOKEN` avec `$env:`.

Les commandes `put`, `mkdir`, `mv`, `cp` et `rm` modifient le serveur. Testez
ces commandes dans un espace dédié ; ne passez jamais le token en argument de
ligne de commande.

Pour la liste complète :

```bash
./bin/ocnotes-cli -help
```

## Vérification manuelle Android

Les tests unitaires Android ne remplacent pas un essai sur appareil. Pour une
modification de l'éditeur ou de la synchronisation, vérifiez au minimum :

1. création, modification et suppression d'une note ;
2. navigation dans un sous-dossier ;
3. passage hors ligne puis retour du réseau ;
4. déconnexion et reconnexion ;
5. affichage dans chaque langue touchée par la modification.

Pour un changement de comportement de conflit, utilisez un compte de test sur
deux clients plutôt que des notes personnelles.

### Rapport de crash

L'APK debug contient une activité sans interface qui provoque une exception
contrôlée. Elle est absente de la release et ne se lance que par composant
explicite :

```bash
adb shell am start -n \
  eu.ocnotes.debug/eu.ocnotes.diagnostic.CrashProbeActivity
```

Relancez ensuite OCnotes. Le rapport doit apparaître avant l'interface normale,
ne jamais contenir `OCNOTES_PRIVATE_PROBE`, et disparaître après « Supprimer »
ou « Partager ». Cette sonde ferme réellement le processus debug : ne pas la
lancer pendant une saisie à conserver.
