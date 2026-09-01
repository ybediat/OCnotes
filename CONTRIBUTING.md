# Contribuer à OpenNote

Merci de vouloir améliorer OpenNote. Le projet est encore en alpha : les retours,
corrections ciblées et tests sur des appareils variés sont particulièrement utiles.

## Avant de commencer

1. Cherchez une issue existante ou ouvrez-en une pour discuter d'une évolution
   importante avant d'écrire beaucoup de code.
2. Créez une branche à partir de `main`.
3. Gardez une pull request centrée sur un seul sujet. Une correction de bug, un
   changement d'interface et une refonte ne devraient pas être mélangés.

Les échanges, le code, les commentaires, les messages utilisateur et la
documentation sont en français. Les traductions sont les bienvenues, à condition
de modifier aussi la langue de référence et les tests concernés.

## Premier cycle de contribution

Le guide [de développement](docs/DEVELOPMENT.md) décrit l'installation et le
build Android. Pour valider une modification courante :

```bash
go test ./... -short
go vet ./...
gofmt -l .
cd android && ./gradlew testDebugUnitTest lintDebug
```

`gofmt -l .` ne doit afficher aucun fichier. Les scripts shell et le wrapper
Gradle utilisent des fins de ligne LF ; Git les préserve via `.gitattributes`.

## Où placer une modification

| Sujet | Emplacement habituel |
|---|---|
| Règles métier, synchronisation, réseau | `internal/` |
| API exposée à Android | `mobile/` |
| Interface, navigation et état Android | `android/app/src/main/` |
| Tests Kotlin | `android/app/src/test/` |
| Tests Go et fixtures anonymisées | à côté du paquet concerné |
| Textes affichés | `android/app/src/main/res/values*/strings.xml` |

Le cœur Go ne doit pas dépendre d'Android. Si une fonction est ajoutée ou modifiée
dans `mobile/`, régénérez l'AAR avec `gomobile bind` avant de compiler
l'application Android.

## Pull request

Décrivez clairement le problème résolu, les composants concernés et les commandes
de test exécutées. Pour une modification d'interface, joignez une capture
d'écran anonymisée si elle aide à la relecture.

Ne mélangez pas dans une pull request les fichiers générés (`.aar`, APK,
répertoires Gradle), fichiers de configuration locale ou données de test
capturées sur un compte réel : ils sont volontairement ignorés par Git.

## Données sensibles

Ne publiez jamais de jeton, mot de passe, clé de signature, URL de serveur
privée, nom d'utilisateur, contenu de note ou journal non expurgé. Les tests
d'intégration et le CLI utilisent des variables d'environnement ; voir
[TESTING.md](docs/TESTING.md).

Pour une vulnérabilité, suivez [SECURITY.md](SECURITY.md) plutôt que de la
décrire avec des informations exploitables dans une issue publique.
