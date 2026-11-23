# Backend MedImage-Trust (Maven multi-modules)

```
backend/
├── pom.xml                    parent : Java 25, JUnit 5.11.4, Surefire 3.5.2, gestion des versions internes
├── medimage-crypto/           Nour : SHA-256, HMAC, HKDF, AES-GCM, Ed25519, JCS, CryptoService
└── medimage-fingerprint/      axe fingerprinting : code de Tardos, insertion, analyse, simulation
```

Dépendance : `medimage-fingerprint` -> `medimage-crypto`. Racine de packages commune : `tn.medimagetrust`.

## Modifications faites dans `medimage-crypto` (les seules)
1. `pom.xml` : rattaché au parent (`tn.medimage:medimage-backend`) ; mêmes versions qu'avant.
2. `CryptoService.kfHmac(String kid)` : PRF HMAC-SHA-256 sous K_F (HKDF, label `MIT-KF-v1`). La clé reste
   encapsulée dans la fonction retournée, le module fingerprint n'en voit jamais les octets.
3. `KfHmacTest` : 4 tests (déterminisme, dépendance au kid et au message, cohérence avec `computeFCommit`, kid inconnu).
Aucune autre classe n'a été modifiée : les 40 tests existants passent toujours.

## Comment les deux modules fonctionnent ensemble
- `CryptoPrf` (module fingerprint) adapte `CryptoService` : `hmac` -> `kfHmac(kid)` ; `commit128` -> `computeFCommit(kid, ...)`.
  L'engagement `fCommit` a donc une seule implémentation (celle de crypto), que le module watermark pourra vérifier à l'identique.
- `FingerprintService.forKid(crypto, kid)` crée un service lié à un kid. **Le kid de l'émission doit être enregistré
  avec l'émission** (registre SEC06) : pour analyser une ancienne copie après rotation, recréer le service avec ce kid.
- Message signé dans W : `fCommit` = `crypto.computeFCommit(kid, MIT-FC-v1 ‖ emissionId ‖ recipientId)` (voir
  `TardosCodeGenerator.commit`). Il est retourné par `FingerprintService.prepare(...)`.

## Vérifié
- Compilation et tests sur JDK 25 (javac + JUnit Platform) : crypto 44/44, fingerprint 42/42.
- Simulation de 3 000 collusions par stratégie : 0 fausse accusation, détection >= 1 complice : 100 %.
- Non exécuté ici : Maven lui-même (pas d'accès à Maven Central depuis l'environnement de préparation).
  Faites un premier `mvn -B test` à la racine de `backend/` et signalez toute erreur de résolution.

## Commandes
```
cd backend
mvn -B test                                               # tous les modules
mvn -B -DskipTests install                                # installe crypto pour les autres modules
mvn -B -pl medimage-fingerprint exec:java -Dexec.args="3000 target/reports"   # rapport de simulation
```

## Points à trancher en équipe (hors de ces deux modules)
- Format de W : JSON canonique JCS (mail du professeur, `signCanonicalJson`) ou charge binaire fixe `MIT-W-v1`
  dont `fCommit` occupe les octets 128-143 (PDF §8.3) ?
- AAD du jeton S : objectId seul (code actuel) ou `MIT-S-v1 ‖ emissionId ‖ issuerId ‖ kid` (PDF §8.2) ?
- Insertion : LSB spatial (PDF) ou DCT/QIM puis LSB (mail du professeur) ?
- `ATTRIBUTION.md` : les rôles (stéganographie / watermarking / fingerprinting) ne correspondent pas à ceux du PDF.
