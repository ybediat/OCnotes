# Connexion OIDC expérimentale

OCnotes conserve la connexion par App Token comme méthode principale. Le
bouton **Se connecter avec le navigateur** utilise Authorization Code avec
PKCE et exige que l'administrateur du serveur ait enregistré OCnotes comme
client public.

Deux façons d'y parvenir, selon la façon dont le serveur authentifie :

- **IdP externe** (Keycloak, Authentik, Zitadel, Entra ID…) — **méthode
  recommandée.** Le client se déclare sur le fournisseur ; la configuration
  d'OpenCloud n'est pas touchée. Voir [IdP externe](#idp-externe-keycloak-authentik-zitadel-entra-id).
- **IdP intégré d'OpenCloud** — possible, mais l'admin devient responsable de
  **toute** la liste des clients. Lire l'avertissement ci-dessous avant de s'y
  engager.

## Client du serveur OpenCloud intégré

> ⚠️ **Déclarer un client, c'est reprendre la liste entière.**
>
> Dès que la section `clients` du service IDP est définie, OpenCloud **cesse
> de générer** les clients standard (`web`, `OpenCloudDesktop`,
> `OpenCloudAndroid`, `OpenCloudIOS`). La doc OpenCloud est explicite : la
> section « doit contenir la configuration de **tous** les clients, y compris
> les standard ». Il n'y a **ni fusion, ni dossier *drop-in*, ni
> enregistrement dynamique** : impossible de n'ajouter qu'OCnotes.
>
> Conséquence : si une mise à jour d'OpenCloud change un client par défaut
> (nouveau `redirect_uri` du client `web`, par exemple) et que votre liste
> figée ne suit pas, c'est l'interface web ou les applications officielles qui
> cassent — pas OCnotes.

Pour limiter ce risque :

1. **La liste par défaut n'est dans aucun fichier de votre serveur** : c'est un
   défaut *compilé* du service IDP. `opencloud init` ne l'écrit pas. La
   référence qui fait foi est la page
   [IDP YAML config](https://docs.opencloud.eu/docs/dev/server/services/idp/yaml-config)
   de la documentation — un export autogénéré de la config par défaut — **à la
   version de votre serveur** (sélecteur de version de la doc, ou le dossier
   `services/idp` du dépôt `opencloud-eu/opencloud` au tag correspondant).
2. **Recopiez ce bloc `clients` de référence** dans votre fichier, en
   remplaçant `{{OC_URL}}` par l'URL publique de votre serveur dans l'entrée
   `web`.
3. **Ajoutez l'entrée `OCnotesAndroid`** en fin de liste, sans toucher aux
   autres.
4. **À chaque montée de version d'OpenCloud**, rouvrez la même page de doc pour
   la nouvelle version, `diff` contre votre fichier, reportez les écarts sur
   les entrées standard.

Liste par défaut au moment d'écrire ces lignes — **à revérifier** contre la
page ci-dessus pour votre version, elle peut changer :

```yaml
clients:
  - id: web
    name: OpenCloud Web App
    trusted: true
    secret: ""
    redirect_uris:
      - '{{OC_URL}}/'
      - '{{OC_URL}}/oidc-callback.html'
      - '{{OC_URL}}/oidc-silent-redirect.html'
    post_logout_redirect_uris: []
    origins:
      - '{{OC_URL}}'
    application_type: ""
  - id: OpenCloudDesktop
    name: OpenCloud Desktop Client
    trusted: false
    secret: ""
    redirect_uris:
      - http://127.0.0.1
      - http://localhost
    post_logout_redirect_uris: []
    origins: []
    application_type: native
  - id: OpenCloudAndroid
    name: OpenCloud Android App
    trusted: false
    secret: ""
    redirect_uris:
      - oc://android.opencloud.eu
    post_logout_redirect_uris:
      - oc://android.opencloud.eu
    origins: []
    application_type: native
  - id: OpenCloudIOS
    name: OpenCloud iOS App
    trusted: false
    secret: ""
    redirect_uris:
      - oc://ios.opencloud.eu
    post_logout_redirect_uris:
      - oc://ios.opencloud.eu
    origins: []
    application_type: native
  # --- ajout OCnotes, ne touche pas aux entrées ci-dessus ---
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

Le client OCnotes est public : aucun secret statique ne doit être embarqué dans
l'APK. OCnotes demande les scopes `openid profile email offline_access` et
génère une preuve PKCE S256 pour chaque connexion.

### Où poser cette liste

Selon l'installation :

| Déploiement | Fichier |
|---|---|
| Paquet Linux `.deb` / `.rpm` (systemd, utilisateur `opencloud`) | `/var/lib/opencloud/config/idp.yaml` |
| Binaire / installation locale | `$HOME/.opencloud/config/idp.yaml` |
| Docker / compose | `/etc/opencloud/idp.yaml` (volume monté) |

Trois pièges de configuration :

- **Les variables d'environnement l'emportent toujours** sur les fichiers, et
  `idp.yaml` l'emporte sur `opencloud.yaml`. Si des `IDP_*` ou un bloc
  `idp: { clients: [...] }` de `opencloud.yaml` définissent déjà des clients,
  votre `idp.yaml` peut être masqué ou fusionné de façon inattendue.
- **`idp.yaml` peut être régénéré** par l'outillage de déploiement (templates,
  ré-init, montée de version), ce qui écraserait votre liste. Si c'est le cas
  chez vous, mettez plutôt le bloc `clients` dans `opencloud.yaml`.
- Les clés `flow`, `pkce` et `scopes` ne font **pas** partie des entrées
  `clients` : le protocole et les scopes sont portés par la requête de
  l'application, inutile de les ajouter au YAML.

Il n'est pas nécessaire de remplacer `OpenCloudAndroid` dans WebFinger :
OCnotes s'en sert pour découvrir l'issuer et les scopes, puis emploie son
identifiant propre `OCnotesAndroid`. L'application OpenCloud officielle
continue d'utiliser son client et son URI de retour.

## Contrôle rapide (IdP intégré)

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

## IdP externe (Keycloak, Authentik, Zitadel, Entra ID…)

Quand OpenCloud délègue l'authentification à un fournisseur externe plutôt qu'à
son IdP intégré, OCnotes n'a rien de spécifique à faire côté protocole : il lit
l'`issuer` annoncé par le **WebFinger du serveur OpenCloud**, puis le
`/.well-known/openid-configuration` de cet issuer. Trois conditions sont à
réunir côté fournisseur, une côté OpenCloud.

### Côté fournisseur : un client public natif

Déclarer un client avec **exactement** cet identifiant — il est compilé dans
l'APK, l'administrateur ne peut pas en choisir un autre :

| Champ | Valeur |
|---|---|
| Client ID | `OCnotesAndroid` |
| Type | public / natif, **sans secret** |
| Flux autorisé | Authorization Code |
| PKCE | obligatoire, méthode `S256` |
| Redirect URI | `eu.ocnotes://oauth2redirect` |
| Scopes accordables | `openid`, `profile`, `email`, `offline_access` |

`offline_access` — ou l'équivalent qui déclenche l'émission d'un refresh token —
est indispensable : sans lui, la session meurt à l'expiration du premier access
token, sans renouvellement silencieux possible.

Le fournisseur doit émettre un **ID token** portant un claim `sub` stable :
OCnotes s'en sert comme identifiant de compte local. Un `sub` qui change d'une
connexion à l'autre ferait réapparaître un nouveau compte à chaque fois.

Certains IdP refusent les schémas d'URI personnalisés (`eu.ocnotes://…`) dans un
client « web » et exigent le type « natif » / « application mobile » : c'est
celui-là qu'il faut choisir.

### Côté OpenCloud : accepter les jetons de ce client

OCnotes envoie l'access token du fournisseur en `Bearer` aux API LibreGraph et
WebDAV. OpenCloud ne l'accepte que si sa propre configuration OIDC pointe le
**même issuer** et si l'**audience** (`aud`) du jeton est reconnue. Selon le
fournisseur, il faut soit ajouter `OCnotesAndroid` à la liste des audiences
admises par OpenCloud, soit configurer le fournisseur pour qu'il place
l'audience attendue par OpenCloud dans les jetons délivrés à ce client. Un
`401` systématique sur `me/drives` juste après une connexion navigateur réussie
est le symptôme d'une audience non reconnue.

### WebFinger

Aucune entrée à ajouter pour OCnotes : il réutilise le lien `issuer` que le
serveur OpenCloud publie déjà pour ses propres clients. Si le WebFinger expose
un bloc `properties` avec `http://opencloud.eu/ns/oidc/scopes`, OCnotes en tire
la liste des scopes ; sinon il retombe sur `openid profile email
offline_access`.

### Contrôle rapide

1. `GET <serveur>/.well-known/webfinger?resource=<serveur>&rel=http://openid.net/specs/connect/1.0/issuer`
   → un lien `href` vers l'issuer externe, en HTTPS.
2. `GET <issuer>/.well-known/openid-configuration`
   → `authorization_endpoint`, `token_endpoint`, et
   `code_challenge_methods_supported` contenant `S256`.
3. Une requête `authorize` sur cet `authorization_endpoint`, avec
   `client_id=OCnotesAndroid` et
   `redirect_uri=eu.ocnotes%3A%2F%2Foauth2redirect`, doit mener à la page de
   connexion du fournisseur — pas à `invalid_client` ni
   `invalid_redirect_uri`.

### Limites

Le redirect URI est un schéma personnalisé : une autre application installée
peut l'enregistrer aussi. PKCE empêche l'échange du code par un tiers, pas son
interception. Il n'y a pas d'enregistrement dynamique — l'identifiant
`OCnotesAndroid` est figé dans le binaire.
