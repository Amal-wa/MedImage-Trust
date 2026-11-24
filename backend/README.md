# Backend MedImage-Trust (Maven multi-modules)

Référence : dossier **v2.4**, [`docs/MedImage-Trust_v2.4_complet.pdf`](../docs/MedImage-Trust_v2.4_complet.pdf).
Le PDF v2.3 de `docs/` est obsolète.

## Modules

Responsables d'après la répartition du dossier v2.4 (§10.1).

```
backend/
├── pom.xml                  parent tn.medimage:medimage-backend : Java 25, UTF-8, versions et plugins communs
├── medimage-crypto/         Nour     SHA-256, HMAC, HKDF, AES-GCM, Ed25519, JCS : seul module qui manipule des clés
├── medimage-embedding/      Amel     RONI (profil v2), partitions W/S/F, QIM sur DCT pour F, LSB pour S et W, contrôle final
├── medimage-fingerprint/    Nourhen  code de Tardos : génération, insertion, analyse des fuites, simulation de collusions
├── medimage-audit/          Nourhen  journal d'audit chaîné, ancrage quotidien signé, alertes A1 à A7, rétention
└── medimage-web/            Nour     API Jakarta REST servie par WildFly (WAR) ; pour l'instant /api/health
```

À venir : `medimage-dicom` (lecture et contrôle DICOM), `medimage-stego` (jeton S), `medimage-watermark` (preuve W).
Racine de packages commune : `tn.medimagetrust` ; `groupId` Maven commun : `tn.medimage`.

## Dépendances entre modules

```
medimage-crypto  ◄── medimage-embedding ◄── medimage-fingerprint
       ▲
       └──────────── medimage-audit

medimage-web : dépendra de tous les modules (aujourd'hui : API Jakarta EE seulement)
```

- `medimage-fingerprint` dépend aujourd'hui de `medimage-crypto` seulement. La dépendance vers `medimage-embedding`
  arrivera avec le passage à `FBlockChannel` (voir les écarts ci-dessous). L'ordre des modules du pom parent la
  prévoit déjà.
- `medimage-embedding` ne dépend d'aucun module : il ne connaît aucune clé.
- `medimage-audit` dépend de `medimage-crypto` et de rien d'autre (cahier des charges §7.3).

## Versions gérées par le parent

| Élément | Version |
|---|---|
| Modules internes | `${project.version}` (0.1.0-SNAPSHOT) |
| `org.junit.jupiter:junit-jupiter` | 5.11.4 (`test`) |
| `jakarta.platform:jakarta.jakartaee-api` | 11.0.0 (`provided`) |
| `maven-surefire-plugin` | 3.5.2 |
| `maven-war-plugin` | 3.4.0 |
| `exec-maven-plugin` | 3.5.0 |

## Déploiement de l'API (WAR)

Le script du professeur `add-api.cli` déploie `${api.name}-${api.version}.war`. Le module web produit donc
`medimage-web/target/medimage-api-1.0.war` :

| Propriété | Valeur |
|---|---|
| `api.name` | `medimage-api` |
| `api.version` | `1.0` |

Ces deux valeurs sont à reprendre dans les futurs fichiers `.properties` de dev et de prod. Elles sont définies dans
`medimage-web/pom.xml` (`finalName` = `${api.name}-${api.version}`).

## Commandes

Prérequis : JDK 25 (`JAVA_HOME` doit pointer vers lui) et Maven 3.9.

```
cd backend
mvn -B test                                    # compile et teste les cinq modules
mvn -B clean test                              # idem, après nettoyage
mvn -B -pl medimage-audit -am test             # un seul module (-am construit aussi ses dépendances)
mvn -B -pl medimage-embedding test -Dgroups=SEC-M01        # tests liés à une exigence (tags JUnit)
mvn -B -DskipTests package                     # produit medimage-web/target/medimage-api-1.0.war
mvn -B -pl medimage-fingerprint -am install -DskipTests
mvn -B -pl medimage-fingerprint exec:java -Dexec.args="3000 target/reports"   # rapport de simulation
```

## Comment crypto et fingerprint travaillent ensemble

- `CryptoPrf` (module fingerprint) adapte `CryptoService` : `hmac` → `kfHmac(kid)` ; `commit128` →
  `computeFCommit(kid, ...)`. L'engagement `fCommit` n'a donc qu'une implémentation, celle de crypto.
- `FingerprintService.forKid(crypto, kid)` crée un service lié à un kid. Le kid de l'émission doit être enregistré
  avec l'émission (registre SEC06) : pour analyser une ancienne copie après rotation, il faut recréer le service avec
  ce kid.

## Écarts connus avec la v2.4

À traiter par les tâches suivantes ; aucun n'est corrigé ici.

| Sujet | État actuel du code | Attendu par la v2.4 | Tâche |
|---|---|---|---|
| Jeton S : AAD | `CryptoService.encryptTokenS` utilise l'identifiant d'objet seul (`AesGcm.aadForObject`) | `MIT-S-v1 ‖ emissionId ‖ issuerId ‖ kid` (§8.2) | crypto |
| Jeton S : clé | `K_tok` dérivée par kid (`MIT-KTOK-v1`), la même pour toutes les émissions | sous-clé HKDF par émission (§8.2, SEC-K04) | crypto |
| Dérivation des clés | sous-clés dérivées d'un secret par kid fourni par `KeyProvider` (`InMemoryKeyProvider`) | K_tok, K_F, K_store et les graines Ed25519 de SK et K_log dérivées par HKDF de la **seule KEK**, stockée dans le credential store Elytron (§8.2, §8.6) | crypto |
| Format du kid | `String` | entier sur 2 octets dans W, S et l'ancrage d'audit (§8.3) | crypto |
| Fingerprint | LSB v2.3 : `FPartition` (rangs modulo 10, ≥ 6 144 pixels), 3 072 bits (1 024 symboles × 3) | `FBlockChannel` du module embedding : 128 blocs × 8 bits par QIM sur DCT, 1 bit par symbole (§4.1) | fingerprint |
| CI | `.github/` hors du périmètre de cette tâche. `CODEOWNERS` est vide, `ci.yml` ne se déclenche qu'en push sur `feature/**` (pas `feat/*`, `fix/*`, `chore/*`), `cd.yml` appelle `scripts/deploy.sh`, absent du dépôt. Pas de banc d'attaques (JPEG Q75, redimensionnement) | pipeline complet (§9) | CI |

Écarts propres au module embedding (bornage à 0, robustesse de F, SEC-M03 c) : voir
`medimage-embedding/README.md`, section « Décisions à valider ».

## Points à trancher en équipe

| Point | Décision | Source |
|---|---|---|
| Insertion | **Tranché** : DCT/QIM pour F, puis LSB pour S et W (implémenté dans `medimage-embedding`) | consigne du professeur, v2.4 §4.1 |
| AAD du jeton S | **Tranché** : `MIT-S-v1 ‖ emissionId ‖ issuerId ‖ kid` ; reste à implémenter (écart ci-dessus) | v2.4 §8.2 |
| Format de W | **Tranché** : charge binaire fixe `MIT-W-v1` de 208 octets, `fCommit` aux octets 128 à 143, signature Ed25519 sur `MIT-W-v1 ‖ 0x00 ‖ octets 0 à 143` (le JSON canonique JCS n'est pas retenu pour W) | v2.4 §8.3 |
| Rôles dans `ATTRIBUTION.md` | **Tranché** : corrigés selon la v2.4 (Nour watermarking, Nourhen fingerprinting, Amel stéganographie) | v2.4 §10.1 |
