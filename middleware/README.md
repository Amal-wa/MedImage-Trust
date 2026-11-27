# Middleware MedImage-Trust (3 modules Maven)

Référence : dossier **v2.4**, [`docs/MedImage-Trust_v2.4_complet.pdf`](../docs/MedImage-Trust_v2.4_complet.pdf).
Le PDF v2.3 est obsolète.

## Arbre

```
middleware/
├── pom.xml          parent  tn.medimage:medimage-middleware:0.1.0-SNAPSHOT (packaging pom ; modules core, api, iam)
├── core/            jar     tn.medimage:medimage-core   ← ce qui est commun aux deux WAR
│   └── src/main/java/tn/medimagetrust/common/web/      NoStore, NoStoreFilter
├── api/             war     tn.medimage:medimage-api    → api-1.0.war   ← TOUT le code développé
│   ├── src/main/java/tn/medimagetrust/
│   │   ├── crypto/          Nour     SHA-256, HMAC, HKDF, AES-GCM, Ed25519, JCS : seul package qui manipule des clés
│   │   ├── embedding/       Amel     RONI (profil v2), partitions W/S/F, QIM sur DCT pour F, LSB pour S et W, contrôle final
│   │   ├── fingerprint/     Nourhen  code de Tardos : génération, insertion, analyse des fuites
│   │   ├── watermark/       Nour     preuve W : charge MIT-W-v1 de 208 octets signée Ed25519, vérification en échec fermé
│   │   ├── audit/           Nourhen  journal d'audit chaîné, ancrage quotidien signé, alertes A1 à A7, rétention
│   │   └── api/rest/        Nour     RestApplication (@ApplicationPath("/api")), HealthResource
│   ├── src/test/java/tn/medimagetrust/…   tous les tests, mêmes packages ; ArchitectureTest (ArchUnit)
│   └── docs/        crypto.md, embedding.md, fingerprint.md, watermark.md, audit.md (ex-README des modules)
└── iam/             war     tn.medimage:medimage-iam    → iam-1.0.war
    └── src/main/java/tn/medimagetrust/iam/              IamApplication (@ApplicationPath("/iam")), HealthResource
```

Responsables d'après la répartition du dossier v2.4 (§10.1). À venir dans api : `dicom` (lecture et contrôle DICOM)
et `stego` (jeton S).

## Rôle des trois modules

| Module | Packaging | Rôle |
|---|---|---|
| `core` | jar | Code commun aux deux WAR, embarqué dans leur `WEB-INF/lib` : aujourd'hui le filtre `@NoStore` (`Cache-Control: no-store`). |
| `api` | war (`api-1.0.war`) | Tout le code développé : les cinq anciens modules devenus packages, et l'API REST `/api`. |
| `iam` | war (`iam-1.0.war`) | Identités et accès. Squelette : seule la sonde `GET /iam/health` existe ; l'authentification viendra dans une étape dédiée. |

### Règle : ce qui est partagé par iam et api va dans core

Si iam a besoin plus tard de code d'api (par exemple crypto ou audit), ce code est **déplacé dans core** à ce
moment-là, parce qu'un WAR ne peut pas dépendre d'un autre WAR. On ne le copie jamais. Les règles ArchUnit d'iam
(`IamArchitectureTest`) refusent toute dépendance d'iam vers les packages d'api.

## Dépendances entre packages (gardées par ArchUnit)

Maven ne sépare plus les packages d'api : ce sont les tests d'architecture
(`api/src/test/java/tn/medimagetrust/ArchitectureTest.java`, ArchUnit 1.5.1) qui gardent les frontières des anciens
modules. Une violation rend la CI rouge.

```
                 api.rest  (peut dépendre de tout ; rien ne dépend de lui)
                    │
     ┌──────────────┼───────────────┬──────────────┐
     ▼              ▼               ▼              ▼
 fingerprint    watermark         audit         common.web (core)
   │    │        │    │             │
   │    └──┐  ┌──┘    │             │
   ▼       ▼  ▼       ▼             ▼
 crypto   embedding  crypto       crypto
```

| # | Règle | Module |
|---|---|---|
| 1 | Refus par défaut : toute méthode publique d'une classe `@Path`, ou annotée `@GET/@POST/@PUT/@DELETE/@PATCH`, porte `@RolesAllowed`, `@PermitAll` ou `@DenyAll` (sur elle ou sa classe). Vérifiée aussi sur une ressource de test volontairement non annotée (`ArchitectureRuleSelfTest`) | api, iam |
| 2 | `embedding` ne dépend d'aucun autre package du projet (il ne connaît aucune clé) | api |
| 3 | `crypto` ne dépend d'aucun autre package du projet | api |
| 4 | `audit` ne dépend que de `crypto` | api |
| 5 | `fingerprint` et `watermark` ne dépendent que de `crypto` et d'`embedding`, pas l'un de l'autre | api |
| 6 | Seul `api.rest` peut dépendre de tous les packages ; rien ne dépend de `api.rest` ni de `iam` ; `common` (core) ne dépend d'aucun package métier ; `iam` ne dépend pas d'api | api, iam |
| 7 | Aucun `System.out`, `System.err` ni `printStackTrace()` en production | api, iam |
| 8 | `java.util.Random` (et ses sous-classes autres que `SecureRandom`) interdit dans `crypto`, `fingerprint` et `watermark` | api |
| 9 | Aucun cycle entre les packages `tn.medimagetrust.*` | api |

Le simulateur de collusions (`CollusionSimulator`, `SimulationReport`, `SimulationMain`) est dans les **sources de
test** d'api : outil de CI, absent du WAR, ce qui garde les règles 7 et 8 sans exception.

## Commandes

Prérequis : JDK 25 (`JAVA_HOME` doit pointer vers lui) et Maven 3.9. Depuis la racine du dépôt :

```
mvn -B -f middleware/pom.xml clean verify                                  # tout : tests, ArchUnit, deux WAR
mvn -B -f middleware/pom.xml -pl api test -Dtest='tn.medimagetrust.embedding.**'   # un package
mvn -B -f middleware/pom.xml -pl api test -Dgroups=SEC-M01                 # tests liés à une exigence (tags JUnit)
mvn -B -f middleware/pom.xml -pl api test "-Dgroups=memory" "-DargLine=-Xmx300m"   # budget mémoire 36 Mpx
mvn -B -f middleware/pom.xml -pl api test -Dtest='tn.medimagetrust.Architecture*'  # règles d'architecture
mvn -B -f middleware/pom.xml -DskipTests package                           # api-1.0.war et iam-1.0.war
mvn -B -f middleware/pom.xml -pl core -am install -DskipTests              # une fois, avant exec:java
mvn -B -f middleware/pom.xml -pl api test-compile exec:java "-Dexec.args=3000 target/reports"   # simulation SEC-D04
```

Le test mémoire (tag `memory`) est exclu par défaut ; un profil activé par `-Dgroups` lève cette exclusion.

## Les deux WAR

Le script de l'enseignant `add-api.cli` déploie `${api.name}-${api.version}.war` :

| WAR | `api.name` | `api.version` | Fichier | Contexte REST |
|---|---|---|---|---|
| api | `api` | `1.0` | `api/target/api-1.0.war` | `/api` |
| iam | `iam` | `1.0` | `iam/target/iam-1.0.war` | `/iam` |

Ces valeurs sont à reprendre dans les futurs fichiers `.properties` de dev et de prod. Les deux WAR embarquent
`WEB-INF/lib/medimage-core-0.1.0-SNAPSHOT.jar`.

## Versions gérées par le parent

| Élément | Version |
|---|---|
| `tn.medimage:medimage-core` | `${project.version}` (0.1.0-SNAPSHOT) |
| `org.junit.jupiter:junit-jupiter` | 5.11.4 (`test`) |
| `jakarta.platform:jakarta.jakartaee-api` | 11.0.0 (`provided`) |
| `com.tngtech.archunit:archunit-junit5` | 1.5.1 (`test`) |
| `maven-surefire-plugin` | 3.5.2 |
| `maven-war-plugin` | 3.4.0 |
| `exec-maven-plugin` | 3.5.0 |

## Documentation des packages

- [`api/docs/crypto.md`](api/docs/crypto.md) : hiérarchie des clés (KEK seule), formats HKDF et AAD, API.
- [`api/docs/embedding.md`](api/docs/embedding.md) : profil RONI v2, condensats, `FBlockChannel`, bilan mémoire, décisions à valider.
- [`api/docs/fingerprint.md`](api/docs/fingerprint.md) : code de Tardos v2.4 sur `FBlockChannel`, simulation SEC-D04.
- [`api/docs/watermark.md`](api/docs/watermark.md) : charge W de 208 octets, cohérence avec embedding, écarts.
- [`api/docs/audit.md`](api/docs/audit.md) : journal chaîné, ancrage, infrastructure à fournir.

## Comment crypto et fingerprint travaillent ensemble

- `CryptoPrf` (package fingerprint) adapte `CryptoService` : `hmac` → `kfHmac(kid)` ; `commit128` →
  `computeFCommit(kid, ...)`. L'engagement `fCommit` n'a donc qu'une implémentation, celle de crypto.
- Le kid est un entier sur 16 bits (`tn.medimagetrust.crypto.Kid`), le même que dans W, S et les ancrages du journal.
- `FingerprintService.forKid(crypto, kid)` crée un service lié à un kid. Le kid de l'émission doit être enregistré
  avec l'émission (registre SEC06) : pour analyser une ancienne copie après rotation, il faut recréer le service avec
  ce kid.

## Écarts connus avec la v2.4

| Sujet | État actuel | Attendu par la v2.4 | Suite |
|---|---|---|---|
| Watermark ↔ embedding | `roiDigest` de watermark incompatible avec `Digests` d'embedding ; lecture W par `LsbChannel` et boucle sur `RoniDetector.candidates()` pas encore branchées | condensats uniques (§8.3), vérification V2-V3 (§8.4) | Nour (voir [`api/docs/watermark.md`](api/docs/watermark.md)) |
| Watermark ↔ fingerprint | `fCommit` calculé différemment dans les deux packages | une seule source pour `fCommit` (§8.2) | Nour, Nourhen |
| Vérification de W | clé dérivée de la KEK locale, pas le registre des clés publiques | registre des clés publiques (§4.2, SEC-K05) | Nour |
| Production des clés | `ElytronKeyProvider` (credential store WildFly) pas encore écrit | KEK seule dans Elytron (§8.2, §8.6) | api, côté REST |
| Découverte de `NoStoreFilter` | vérifiée sans serveur (`NoStoreFilterPackagingTest`) ; contrôlée sur serveur par le test de fumée de `scripts/deploy.sh` (job CD dev), **pas encore exécuté** | en-tête `Cache-Control: no-store` sur `/api/health` et `/iam/health` | premier passage du job CD dev |
| Test de fumée `/api/whoami` | endpoint absent : `deploy.sh` et `LoadBench` émettent l'avertissement « endpoint whoami absent (prompt 12) » sur un 404, sans échec (`TODO(prompt-12)`) | `401` en `application/problem+json`, sans trace, contrôle strict | prompt 12 |
| Banc d'attaques | JPEG Q75 et redimensionnement 75 % effacent F en 12 bits (récupération ≈ 50 %) : pas QIM fixe Δ = 12 quelle que soit la profondeur ; en 8 bits, JPEG Q75 ≈ 71 % | récupération ≥ 95 % | correction prévue au prompt 09 (Δ proportionnel à la profondeur) ; question posée à l'enseignant (voir [`docs/ci-cd.md`](../docs/ci-cd.md)) |
| iam | squelette : seule la sonde `/iam/health` | authentification, autorisation (SEC03) | prompt dédié |

Écarts propres à embedding (bornage à 0, robustesse de F, SEC-M03 c) : voir [`api/docs/embedding.md`](api/docs/embedding.md),
section « Décisions à valider ».

Déjà corrigés : jeton S (AAD et sous-clé par émission), dérivation de toutes les clés depuis la seule KEK, kid sur
16 bits (crypto) ; code F en DCT/QIM sur `FBlockChannel` au lieu du LSB v2.3 (fingerprint) ; budget mémoire à 36 Mpx
(embedding) ; pipeline CI/CD complet, déclenché sur toutes les branches (prompt 08, [`docs/ci-cd.md`](../docs/ci-cd.md)).

## Points tranchés en équipe

| Point | Décision | Source |
|---|---|---|
| Insertion | DCT/QIM pour F, puis LSB pour S et W (implémenté dans `embedding`) | consigne du professeur, v2.4 §4.1 |
| AAD du jeton S | `MIT-S-v1 ‖ emissionId ‖ issuerId ‖ kid` (`CryptoService.sealTokenS`) | v2.4 §8.2 |
| Format de W | charge binaire fixe `MIT-W-v1` de 208 octets, signature Ed25519 sur `MIT-W-v1 ‖ 0x00 ‖ octets 0 à 143` | v2.4 §8.3 |
| Rôles dans `ATTRIBUTION.md` | Nour watermarking, Nourhen fingerprinting, Amel stéganographie | v2.4 §10.1 |
| Structure Maven | trois modules : core, api, iam ; frontières entre packages gardées par ArchUnit | consigne de l'enseignant |
