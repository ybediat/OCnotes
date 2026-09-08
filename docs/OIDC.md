# Connexion OIDC expérimentale

OCnotes conserve la connexion par App Token comme méthode principale. Le
bouton **Se connecter avec le navigateur** utilise Authorization Code avec
PKCE et exige que l'administrateur du serveur ait enregistré OCnotes comme
client public.

## Client du serveur OpenCloud intégré

Dans la section `idp.clients` de la configuration du service IDP, ajoutez ce
client **à la liste complète**. Dès que `idp.clients` est défini, OpenCloud ne
génère plus automatiquement les clients standard (`web`, `OpenCloudAndroid`,
`OpenCloudIOS`, `OpenCloudDesktop`) : ils doivent donc rester présents dans
votre fichier, puis OCnotes est ajouté comme cinquième entrée.

```yaml
- id: OCnotesAndroid
  name: OCnotes Android App
  trusted: false
  secret: ""
  redirect_uris:
    - eu.ocnotes://oauth2redirect
  post_logout_redirect_uris: []
  origins: []
  application_type: native
```

Le client est public : aucun secret statique ne doit être embarqué dans
l'APK. OCnotes demande les scopes `openid profile email offline_access` et
génère une preuve PKCE S256 pour chaque connexion.


Il n'est pas nécessaire de remplacer `OpenCloudAndroid` dans WebFinger.
OCnotes utilise WebFinger pour découvrir l'issuer et les scopes, puis emploie
son identifiant propre `OCnotesAndroid`. L'application OpenCloud officielle
peut ainsi continuer à utiliser son client et son URI de retour.

## Contrôle rapide

La requête suivante doit renvoyer une redirection vers la page de connexion,
et non une erreur `invalid_client` ou `invalid_redirect_uri` :

```text
GET /signin/v1/identifier/_/authorize
    ?client_id=OCnotesAndroid
    &redirect_uri=eu.ocnotes%3A%2F%2Foauth2redirect
    &response_type=code
    &scope=openid%20profile%20email%20offline_access
    &code_challenge=<preuve-PKCE>
    &code_challenge_method=S256
```

Après modification de la configuration, redémarrer le service IDP ou le
conteneur OpenCloud qui le porte.
