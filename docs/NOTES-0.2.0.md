# OCnotes 0.2.0 — OCIS en test, comptes multiples, export

Cette version ouvre l'application à **ownCloud Infinite Scale** (en test),
change la façon de gérer **plusieurs comptes** et permet de **sortir ses notes**
en archive. Rien ne change pour un usage à un seul compte, hormis les
garde-fous ajoutés.

## Nouveautés

### Copier des notes d'un compte à un autre
Sélectionnez des notes dans le navigateur, puis « Copier vers un autre compte » :
vers un compte local, ou un compte serveur dont l'espace est choisi. Chaque copie
est vérifiée contre l'original ; une copie qui diffère n'est pas comptée.

### Un serveur à côté d'un compte local
Depuis un compte local, deux choix :
- **Ajouter un compte à côté** : vos notes locales restent intactes, le serveur
  s'ouvre dans un nouveau compte ;
- **Convertir ce compte** : il devient un compte serveur, et vous choisissez
  d'envoyer vos notes locales ou de les supprimer.

Un compte déjà ajouté sur l'appareil n'est plus dupliqué : le profil existant est
ouvert, avec une explication.

### Export en archive zip
Réglages → « Exporter les notes ». Toutes vos notes, avec leurs dossiers, dans
une archive que n'importe quel ordinateur ouvre. Les noms illégaux sous Windows
sont corrigés et listés dans `_renommages.txt`. Le compte-rendu indique les notes
exportées, les notes illisibles ignorées et les noms modifiés.

### Renommer un compte local
Un compte local peut maintenant être renommé depuis ses réglages.

## Plus sûr

- **Supprimer un compte local** avertit que ses notes n'existent nulle part
  ailleurs et exige une confirmation explicite.
- **Supprimer un compte serveur ou s'en déconnecter** avec des modifications pas
  encore envoyées avertit du nombre de modifications qui seraient perdues et
  invite à synchroniser d'abord.
- Une configuration de cache perdue est mieux rattrapée.

## ownCloud Infinite Scale : ça fonctionne (en test)

OCnotes n'est plus limité à OpenCloud. Testé contre **OCIS 7 et OCIS 8.2.0** (Docker) :

- les opérations de fichiers passent les tests d'intégration avec un App Token ;
- la **connexion OIDC par navigateur** aboutit, et la synchronisation a été
  vérifiée de bout en bout ;
- un `409` renvoyé par OCIS sur un `PUT` avec `If-Match` périmé est traité comme
  un **conflit**, exactement comme le `412` d'OpenCloud : rien à configurer.

La compatibilité reste **expérimentale** : le renouvellement du jeton sur la
durée n'a pas encore été vérifié, et seules les versions 7 et 8.2.0 ont été essayées. Si vous
utilisez OCIS, vos retours sont précieux. La procédure d'enregistrement du client
OCnotes est dans [docs/OIDC.md](OIDC.md#owncloud-infinite-scale-expérimental).

## Langues
Français, anglais, espagnol et allemand, y compris tous les nouveaux écrans.

## Mise à jour
Installez par-dessus la version précédente : les notes, comptes et réglages sont
conservés. L'APK est signé avec la même clé que les versions précédentes.

**SHA-256 de l'APK** : `à renseigner après la signature`

Signaler un problème : https://github.com/ybediat/OCnotes/issues
