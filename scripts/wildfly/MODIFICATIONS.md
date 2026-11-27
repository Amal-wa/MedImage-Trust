# Différences entre nos scripts jboss-cli et les scripts de base fournis

Les trois scripts fournis par l'enseignant sont gardés **intacts** dans [`fournis/`](fournis/) (consigne : isoler les bases
fournies). Leurs sommes SHA-256 sont figées dans [`fournis.sha256`](fournis.sha256) ; la CI (job `fournis`,
`scripts/check-fournis.sh`) échoue si l'un d'eux change.

Nos versions sont des copies dans `scripts/wildfly/`. Elles reçoivent :

- **une seule correction** (un défaut des scripts fournis qui casse l'application) : CORS avec cookies. Chaque ligne ajoutée porte
  le commentaire `# CORRECTION MedImage-Trust` ;
- **des durcissements** (en-têtes de sécurité, aucune fonction changée), demandés après la revue du prompt 08. Chaque
  ligne ajoutée porte le commentaire `# DURCISSEMENT MedImage-Trust`.

## Correction : origine exacte et `Vary: Origin`

**Raison.** Les scripts fournis posent `Access-Control-Allow-Origin: *` avec `Access-Control-Allow-Credentials:
true`. Les navigateurs refusent cette combinaison dès qu'un cookie ou un en-tête d'authentification est envoyé
(Fetch Standard, « CORS check ») : la PWA ne pourrait pas appeler api ni iam avec ses identifiants. Il faut renvoyer
l'**origine exacte** de la requête, seulement si elle correspond au motif du domaine, et ajouter `Vary: Origin` pour que
les caches partagés ne servent pas la réponse d'une origine à une autre. Le dossier v2.6 (§7.1) demande « CORS limité
aux origines exactes du domaine ».

**Pourquoi un `expression-filter`.** Le filtre `response-header` d'Undertow pose une valeur constante : `%{i,Origin}`
n'y serait pas interprété. Le gestionnaire `set(attribute='%{o,…}', value='%{i,Origin}')` du langage de prédicats
d'Undertow recopie l'en-tête `Origin` reçu. Le nom du filtre reste `Access-Control-Allow-Origin` : les `filter-ref`
existants des trois scripts continuent de s'y rattacher sans changement, avec le même prédicat (`$escaped`, l'origine
doit correspondre au motif du domaine).

| Fichier | Ligne (fourni → copie) | Avant | Après |
|---|---|---|---|
| `default-host.cli` | 19 → 19-24 | `/subsystem=undertow/configuration=filter/response-header="Access-Control-Allow-Origin":add(header-name="Access-Control-Allow-Origin",header-value="*")` | 3 lignes de commentaire, puis `/subsystem=undertow/configuration=filter/expression-filter="Access-Control-Allow-Origin":add(expression="set(attribute='%{o,Access-Control-Allow-Origin}',value='%{i,Origin}')")` ; 1 ligne de commentaire, puis `/subsystem=undertow/configuration=filter/response-header="Vary-Origin":add(header-name="Vary",header-value="Origin")` |
| `default-host.cli` | après 25 → 31-32 | — | 1 ligne de commentaire, puis `/subsystem=undertow/server=default-server/host=default-host/filter-ref="Vary-Origin":add(predicate=$escaped)` |
| `add-host.cli` | après 17 → 18-19 | — | 1 ligne de commentaire, puis `/subsystem=undertow/server=default-server/host=${host.key}-host/filter-ref="Vary-Origin":add(predicate=$escaped)` |
| `add-api.cli` | après 15 → 18-19 | — | 1 ligne de commentaire, puis `/subsystem=undertow/server=default-server/host=${api.name}-host/filter-ref="Vary-Origin":add(predicate=$escaped)` |

**Vérifié** sur WildFly 42.0.0.Final en mode `embed-server` (jboss-cli, sans démarrer le serveur) : les trois scripts
passent, et le modèle contient bien `expression-filter=Access-Control-Allow-Origin` avec l'expression ci-dessus.
Vérifié aussi en HTTP sur un WildFly 42 démarré localement (ports décalés) : une requête vers l'hôte api avec
`Origin: https://www.medimage.localhost` reçoit `Access-Control-Allow-Origin: https://www.medimage.localhost` et
`Vary: Origin`.

## Durcissements

| Fichier | Ligne (fourni → copie) | Ajout | Raison |
|---|---|---|---|
| `add-api.cli` | après 14 → 15-16 | 1 ligne de commentaire, puis `/subsystem=undertow/server=default-server/host=${api.name}-host/filter-ref=content-type-options:add(predicate="true")` | `X-Content-Type-Options: nosniff` sur les hôtes api et iam (les scripts fournis ne le posent que sur www et admin ; filtre `content-type-options` défini par `default-host.cli`). Règle ZAP 10021 bloquante. |
| `default-host.cli` | avant `run-batch` → 38-39 | 1 ligne de commentaire, puis `/subsystem=undertow/servlet-container=default/setting=jsp:write-attribute(name=x-powered-by,value=false)` | le moteur JSP ajoute `X-Powered-By` aux pages JSP par défaut (`x-powered-by=true`) ; nos WAR n'en ont pas, mais le serveur ne doit jamais l'envoyer. Règle ZAP 10037. |
| `default-host.cli` | après `run-batch` → 41-55 | 3 lignes de commentaire, puis 4 blocs `if (outcome == success) of …:read-resource` qui retirent `filter-ref=server-header`, `filter-ref=x-powered-by-header` (hôte par défaut) et les filtres `response-header=server-header` et `x-powered-by-header` | ces filtres, présents dans les configurations de WildFly 8 à 10, envoyaient `Server: WildFly/…` et `X-Powered-By: Undertow/…`. Absents de WildFly 42 : les blocs ne font rien sur une installation neuve, et protègent une configuration ancienne. Règles ZAP 10036 et 10037. |

**Constat sur WildFly 42.0.0.Final** (démarré localement, configuration par défaut puis nos scripts) : aucune
réponse (200 ou 404) ne porte d'en-tête `Server` ni `X-Powered-By` ; après nos scripts, `X-Content-Type-Options:
nosniff` est présent sur www, admin, api et iam. Le test de fumée de `deploy.sh` vérifie ces trois points sur les
quatre hôtes à chaque déploiement.

Aucune autre ligne ne diffère : `diff fournis/X.cli X.cli` ne montre que la correction et ces durcissements.

## Constats sur les scripts fournis, non corrigés

1. **Échappements perdus dans la regex CORS.** `\\.` et `\\d` arrivent dans le modèle sous la forme `.` et `d` :
   `^(https://(?:.+.)?medimage.localhost(?::d{1,5})?…`. Le point accepte n'importe quel caractère et une origine avec
   un port explicite (`https://x.medimage.localhost:8443`) est refusée. Sans effet pour nos origines sur le port 443.
2. **`add-api.cli` ne pose pas `Cache-Control`** sur les hôtes api et iam : `Cache-Control: no-store` y est posé par
   l'application (`NoStoreFilter`), ce que vérifie le test de fumée. (`nosniff`, absent lui aussi des scripts fournis, est
   ajouté par un durcissement, voir plus haut.)
3. **Un seul alias par hôte.** `:resolve-expression(expression=${…aliases})` échoue si la valeur contient une
   virgule (« is not a valid parameter name ») : les `.properties` donnent un seul alias par hôte. Le nom nu du
   domaine et `localhost` sont servis par l'hôte par défaut (www).
4. **`add-host.cli` et `default-host.cli`** : parenthèse fermante absente à la fin de la ligne `set corsRegex=…`
   (ligne 6 et ligne 5). jboss-cli 34 l'accepte : vérifié en mode `embed-server`.

## Nos scripts ajoutés (sans équivalent fourni)

| Fichier | Rôle |
|---|---|
| `tls.cli` | magasin PKCS#12, gestionnaire de clés et `server-ssl-context` Elytron en TLSv1.3 seulement, sur l'écouteur HTTPS |
| `limits.cli` | `max-post-size` et `read-timeout` de l'écouteur HTTPS ; `managed-executor-service` `medimage-imaging` |
| `api-properties.cli` | propriétés système d'api : `medimage.iam.jwks-url`, `medimage.iam.issuer`, `medimage.token.audience` |
| `dev.properties`, `prod.properties` | toutes les propriétés lues par les `.cli` |
| `versions.properties` | version de WildFly épinglée et sa somme SHA-256 |

Dans nos scripts, les valeurs passent par `set x=\`:resolve-expression(expression="${…}")\`` puis `$x`, comme dans
les scripts fournis : jboss-cli ne remplace pas un `${…}` placé directement dans la valeur d'un paramètre d'opération (il
l'enverrait au serveur comme expression non résolue, constaté en `embed-server`).
