# Scripts de déploiement WildFly

WildFly 42 sert lui-même la PWA et les deux WAR par des hôtes virtuels Undertow (dossier v2.6, §7.1) :

| Hôte | Rôle | dev | prod |
|---|---|---|---|
| www (hôte par défaut) | PWA | `www.medimage.localhost` | `www.${MIT_DOMAIN}` |
| admin | PWA d'administration | `admin.medimage.localhost` | `admin.${MIT_DOMAIN}` |
| api | `api-1.0.war`, `/api` | `api.medimage.localhost` | `api.${MIT_DOMAIN}` |
| iam | `iam-1.0.war`, `/iam` | `iam.medimage.localhost` | `iam.${MIT_DOMAIN}` |

`MIT_DOMAIN` est un nom deSEC en `*.dedyn.io` (deSEC gère les enregistrements CAA et le défi DNS-01).

## Fichiers

| Fichier | Rôle |
|---|---|
| `wildfly/fournis/*.cli` | les trois scripts de base fournis par l'enseignant, **jamais modifiés** (sommes figées dans `wildfly/fournis.sha256`) |
| `wildfly/default-host.cli`, `add-host.cli`, `add-api.cli` | nos copies, avec une seule correction (CORS avec cookies) : voir [`wildfly/MODIFICATIONS.md`](wildfly/MODIFICATIONS.md) |
| `wildfly/tls.cli` | magasin PKCS#12, gestionnaire de clés et `server-ssl-context` Elytron, **TLSv1.3 seulement**, sur l'écouteur HTTPS |
| `wildfly/limits.cli` | `max-post-size` (81 Mio) et `read-timeout` (120 s) de l'écouteur HTTPS ; pool `medimage-imaging` (4 fils, file de 20, délai de 30 s) |
| `wildfly/api-properties.cli` | propriétés système d'api : `medimage.iam.jwks-url`, `medimage.iam.issuer`, `medimage.token.audience` |
| `wildfly/dev.properties`, `prod.properties` | toutes les propriétés des `.cli` ; en prod, `@MIT_DOMAIN@` est remplacé à l'exécution |
| `wildfly/versions.properties` | version de WildFly épinglée (42.0.0.Final) et sa somme SHA-256 |
| `lib/common.sh` | fonctions communes (journal, jboss-cli, démarrage, configuration, téléchargement vérifié) |
| `install-wildfly.sh <env>` | installation et configuration complète |
| `deploy.sh <env>` | déploiement des WAR et de la PWA, rechargement, test de fumée |
| `update-wildfly.sh <env>` | mise à jour automatique de WildFly, avec retour arrière |
| `check-fournis.sh` | échoue si un script de `wildfly/fournis/` a changé (CI) |

Les valeurs de `limits.cli` viennent du dossier v2.6 : plafond de fichier de 80 Mio et fichier de 81 Mio rejeté
(§6.2, SEC-F02) ; délai d'upload de 120 s (§6.2) ; pool borné à 4 fils avec file de 20 (§7.3, SEC08) ; délai de
traitement de 30 s (SEC-F04).

## Ordre d'exécution

```
scripts/install-wildfly.sh dev     # une fois : télécharge, vérifie, configure (tls, limits, hôtes, API)
mvn -B -f middleware/pom.xml -DskipTests package
scripts/deploy.sh dev              # à chaque livraison : WAR, PWA, rechargement, test de fumée
scripts/update-wildfly.sh dev      # quand une nouvelle version de WildFly sort
```

`install-wildfly.sh` :
1. télécharge l'archive de `versions.properties` et vérifie sa **somme SHA-256** (arrêt si elle diffère) ;
2. dev : génère un certificat autosigné EC P-256 pour `*.medimage.localhost` avec `keytool` (le rôle que tient mkcert
   sur un poste), et exporte `tls/medimage-dev.crt` (curl) et `tls/truststore.p12` (Java) ; prod : convertit le
   certificat Let's Encrypt en PKCS#12 ;
3. ajoute à `bin/standalone.conf` : `-Djdk.tls.server.enableStatusRequestExtension=true` (OCSP stapling, séance 3
   de l'enseignant), `-Djboss.http.port=80` et `-Djboss.https.port=443` ;
4. démarre WildFly, puis applique `tls.cli`, `limits.cli`, `default-host.cli`, `add-host.cli` (admin),
   `add-api.cli` (api), `add-api.cli` (iam, propriétés `iam.api.*`), `api-properties.cli`, et recharge.

`deploy.sh` déploie `api-1.0.war` et `iam-1.0.war` (`deploy --force`), copie `frontend/dist/` dans le site www (ou
publie une page de remplacement signalée comme telle), recharge, puis vérifie et **échoue au premier écart** :

| Vérification | Attendu |
|---|---|
| poignée de main brute, avant tout curl : `openssl s_client -connect 127.0.0.1:443 -servername api.<domaine> -tls1_3` (prod : `api.<domaine>:443`) | `Protocol : TLSv1.3`, `New, TLSv1.3, Cipher is TLS_…` et un sujet de certificat contenant le domaine ; sinon la sortie d'openssl est affichée et le déploiement s'arrête |
| `https://api.<domaine>/api/health`, `https://iam.<domaine>/iam/health` | `{"status":"UP"}` |
| en-têtes | `Strict-Transport-Security` (api, www) ; `Cache-Control: no-store` (`/api/health`, `/iam/health`) ; sur api, iam, www et admin : `X-Content-Type-Options: nosniff`, ni `X-Powered-By` ni `Server` (durcissements, voir `wildfly/MODIFICATIONS.md`) |
| `http://api.<domaine>/…` | redirection vers `https://` |
| TLS | 1.2 refusé (curl et `openssl s_client -tls1_2`), 1.3 négocié |
| `GET https://api.<domaine>/api/whoami` sans jeton | `401` en `application/problem+json`, sans trace d'appel ; un `404` (endpoint du prompt 12 pas encore livré) donne seulement l'avertissement « endpoint whoami absent (prompt 12) », toute autre réponse échoue (`TODO(prompt-12)` : rendre le contrôle strict) |
| prod seulement | `dig CAA <domaine>` contient `letsencrypt.org` ; `openssl s_client -status` montre `OCSP Response Status: successful` |

`update-wildfly.sh` lit la dernière version publiée sur l'API GitHub de `wildfly/wildfly`. Si elle est plus récente :
téléchargement, contrôle de la somme SHA-1 publiée par le projet (seule somme publiée) et calcul de la SHA-256 à
reporter dans `versions.properties` ; installation à côté de l'ancienne ; arrêt de l'ancienne, démarrage de la
nouvelle, **réapplication de toute la configuration par les `.cli`** ; `deploy.sh` et son test de fumée. Si tout
passe, le lien `/opt/medimage/wildfly` bascule ; sinon la nouvelle instance est arrêtée et l'ancienne redémarrée.
Journal : `/opt/medimage/logs/update-wildfly.log`.

## TLS 1.3 et `cipher-suite-names`

Dans Elytron, un `server-ssl-context` règle les suites de chiffrement par **deux attributs distincts** :

- `cipher-suite-filter` (valeur par défaut `DEFAULT`) : suites de **TLS 1.2 et antérieures**, en syntaxe de filtre à
  la manière d'OpenSSL ;
- `cipher-suite-names` (aucune valeur par défaut) : suites de **TLS 1.3**, par leurs noms IANA séparés par `:`.

Les suites TLS 1.3 ne sont pas couvertes par `cipher-suite-filter`. Tant que `cipher-suite-names` n'est pas défini,
Elytron n'active **aucune** suite TLS 1.3. Avec `protocols=["TLSv1.3"]` seul, le serveur n'a donc ni protocole
antérieur ni suite TLS 1.3 : il ferme la connexion dès le ClientHello, sans alerte ni certificat. Symptômes :
`curl: (35) … unexpected eof`, `Remote host terminated the handshake` (ZAP, LoadBench), et `openssl s_client` affiche
`New, (NONE), Cipher is (NONE)` et `no peer certificate available`. C'était la panne du premier passage du CD dev.

`tls.cli` définit donc les trois suites TLS 1.3 sans faiblesse connue, la plus forte d'abord (l'ordre du serveur est
respecté, `use-cipher-suites-order=true` par défaut) :

```
cipher-suite-names="TLS_AES_256_GCM_SHA384:TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256"
```

Si `medimage-ssc` existe déjà (installation créée avant cette correction), `tls.cli` met à jour `protocols` et
`cipher-suite-names` par `write-attribute`, au lieu de recréer le contexte. Vérifié le 28/11/2026 sur WildFly 42.0.0.Final
démarré par `install-wildfly.sh dev`. Après retrait de `cipher-suite-names`, la panne réapparaît à l'identique ;
après réapplication de `tls.cli`, la poignée de main TLS 1.3 aboutit (`TLS_AES_256_GCM_SHA384`). TLS 1.2 reste refusé
(alerte `protocol version`).

L'avertissement `WFLYELY00023` au démarrage concerne le magasin `applicationKS` (`application.keystore`) de la
configuration par défaut, que l'écouteur HTTPS n'utilise plus : il est sans effet.

## Ports 80 et 443 sans root

Les prédicats des `.cli` fournis testent `%p` (port local) égal à 80 ou 443 : WildFly doit écouter directement sur
ces ports (une redirection 8443 → 443 par iptables laisserait `%p = 8443`). Choix retenu : **`setcap
cap_net_bind_service=+ep` sur le binaire `java`**, plutôt que `sudo`. WildFly tourne alors avec l'utilisateur du
service, sans aucun autre privilège ; avec `sudo`, tout le serveur d'application (et les WAR) tournerait en root.
Effet de bord : un binaire doté de capacités ignore `$ORIGIN` pour trouver `libjli.so` ; on ajoute donc
`$JAVA_HOME/lib` à `/etc/ld.so.conf.d/` puis `ldconfig` (fait dans `cd.yml`).

```
sudo setcap 'cap_net_bind_service=+ep' "$JAVA_HOME/bin/java"
echo "$JAVA_HOME/lib" | sudo tee /etc/ld.so.conf.d/medimage-java.conf && sudo ldconfig
```

En prod, un service systemd avec `AmbientCapabilities=CAP_NET_BIND_SERVICE` donne le même résultat sans toucher au
binaire.

Sur un poste où 80 ou 443 est déjà pris (par exemple par `http.sys` sous Windows), `MIT_HTTP_PORT` et
`MIT_HTTPS_PORT` (défaut 80 et 443), lus par `install-wildfly.sh`, décalent les ports pour un essai local. Les
prédicats `%p` ne s'appliquent alors plus sur le port décalé : pas de redirection HTTP → HTTPS si HTTP n'est pas sur 80,
pas de HSTS si HTTPS n'est pas sur 443. Le test de fumée échoue donc sur ce point, ce qui est attendu. La CI n'utilise
jamais ces variables.

Les consoles de gestion (9990 et 9993) restent sur l'interface `management` (`127.0.0.1` par défaut) : les scripts ne
passent jamais `-bmanagement`. Seule l'interface publique passe à `0.0.0.0` en prod (`-b`).

## Développement local

Les noms `*.medimage.localhost` doivent pointer vers `127.0.0.1` (systemd-resolved le fait, sinon `/etc/hosts`) :

```
127.0.0.1 medimage.localhost www.medimage.localhost admin.medimage.localhost api.medimage.localhost iam.medimage.localhost
```

Le certificat autosigné est dans `/opt/medimage/tls/medimage-dev.crt` : à ajouter aux autorités de confiance du
navigateur pour éviter l'avertissement.

## Ce qui reste manuel en prod

1. **Domaine deSEC** : créer `<nom>.dedyn.io` sur https://desec.io, puis les enregistrements `www`, `api`, `iam`,
   `admin` (A/AAAA vers l'adresse du serveur). Créer un jeton d'API deSEC limité à ce domaine ; il sert uniquement au
   défi DNS-01, fourni par le secret `DESEC_TOKEN`, **jamais écrit dans le dépôt**.
2. **CAA** (consigne de l'enseignant), trois enregistrements sur le domaine :

   ```
   <domaine>.  CAA  0 issue     "letsencrypt.org"
   <domaine>.  CAA  0 issuewild "letsencrypt.org"
   <domaine>.  CAA  0 iodef     "mailto:<adresse de l'équipe>"
   ```

   Vérification : `dig CAA <domaine> +short` doit afficher les trois lignes (le test de fumée de prod vérifie
   `letsencrypt.org`).
3. **Certificat Let's Encrypt wildcard avec `--must-staple`**, par le défi DNS-01 de deSEC (certbot et le greffon
   `certbot-dns-desec`, à installer sur le serveur) :

   ```
   umask 077
   printf 'dns_desec_token = %s\n' "$DESEC_TOKEN" > /root/.secrets/desec.ini   # fichier 600, hors dépôt
   certbot certonly --authenticator dns-desec --dns-desec-credentials /root/.secrets/desec.ini \
       -d "*.${MIT_DOMAIN}" -d "${MIT_DOMAIN}" --must-staple --key-type ecdsa \
       --agree-tos -m "<adresse de l'équipe>" --non-interactive
   ```

   Le certificat est écrit dans `/etc/letsencrypt/live/${MIT_DOMAIN}/` ; `install-wildfly.sh prod` le convertit en
   PKCS#12. Au renouvellement (crochet `--deploy-hook` de certbot), relancer la conversion puis `:reload`.
   **Non vérifié ici** : le nom exact des options du greffon deSEC dépend de sa version ; vérifier `certbot plugins`.
4. **Premier lancement** sur le serveur : `MIT_DOMAIN=<nom>.dedyn.io scripts/install-wildfly.sh prod`, puis le job
   `deploy-prod` de `cd.yml` (approbation manuelle) pour les livraisons.

## Limites connues

- Le mot de passe du magasin TLS est généré par `install-wildfly.sh` (`/opt/medimage/tls/keystore.pass`, mode 600) et
  écrit en clair dans `standalone.xml` par la référence d'identifiant Elytron. Amélioration possible : un credential
  store Elytron, comme pour la KEK.
- `default-host.cli` (script fourni) n'est pas idempotent : `install-wildfly.sh` part toujours d'une installation neuve.
- WildFly est lancé par `nohup` (runner, poste de dev) ; en prod, préférer un service systemd.
