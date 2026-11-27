# medimage-crypto

> Ancien `README.md` du module Maven `medimage-crypto`, devenu le package `tn.medimagetrust.crypto` du module `api` (voir [`../../README.md`](../../README.md)). Les commandes se lancent depuis la racine du dépôt.


Seul module qui manipule des clés. Primitives de la JCA/JCE uniquement, aucune implémentation locale (dossier v2.4,
§8.1) : SHA-256, HMAC-SHA-256, HKDF-SHA-256 (`javax.crypto.KDF`, JDK 25), AES-256-GCM, Ed25519, JCS (RFC 8785).
Aucune dépendance hors JDK.

Référence : dossier v2.4 (`docs/MedImage-Trust_v2.4_complet.pdf`), §8.2 (primitives et usages), §8.3 (formats),
§8.6 (cycle de vie des clés), figure 8.1.

## Hiérarchie des clés : un seul secret, la KEK

La KEK (32 octets, une par `kid`) est la **seule** clé stockée. Toutes les autres en sont dérivées à la demande par
`KeyDerivation`, à chaque appel, et ne sont jamais écrites ni conservées.

| Clé | Étiquette HKDF | Usage |
|---|---|---|
| K_tok | `MIT-KTOK-v1` | jeton S (puis sous-clé par émission) |
| K_F | `MIT-KF-v1` | fingerprint : PRF HMAC-SHA-256, engagement `fCommit` |
| K_store | `MIT-KSTORE-v1` | fichiers au repos (puis sous-clé par fichier) |
| graine SK | `MIT-SIG-v1` | clé de signature Ed25519 de la preuve W (et des JSON signés) |
| graine K_log | `MIT-LOG-v1` | clé de signature Ed25519 des ancrages quotidiens du journal d'audit |

### Format exact des dérivations

HKDF-SHA-256 (RFC 5869), **sans sel** (équivalent à un sel de 32 zéros), sortie de 32 octets.

```
clé du kid   = HKDF(IKM = KEK(kid), info = étiquette ASCII ‖ 0x00 ‖ kid(2 octets gros-boutiste))
K_S          = HKDF(IKM = K_tok,     info = "MIT-S-v1"    ‖ 0x00 ‖ emissionId(16))
clé fichier  = HKDF(IKM = K_store,   info = "MIT-FILE-v1" ‖ 0x00 ‖ fileId(16))
```

Exemple : `info` de K_F pour le kid `0x0203` = `4d 49 54 2d 4b 46 2d 76 31 00 02 03`.

Paires Ed25519 : `Ed25519.keyPairFromSeed(graine)` (RFC 8032 §5.1.5). Clé privée par
`EdECPrivateKeySpec(NamedParameterSpec.ED25519, graine)` ; clé publique par le `KeyPairGenerator` Ed25519 du JDK,
initialisé avec une source déterministe privée qui renvoie exactement la graine, une seule fois. Vérifié sur le
vecteur officiel RFC 8032 §7.1, TEST 1 (`Ed25519SeedTest`).

### AAD

| Usage | AAD |
|---|---|
| Jeton S | `"MIT-S-v1"` ‖ emissionId(16) ‖ issuerId(4, gros-boutiste) ‖ kid(2, gros-boutiste) — 30 octets |
| Fichier au repos | `"MIT-FILE-v1"` ‖ fileId(16) ‖ kid(2, gros-boutiste) — 29 octets |

Étiquettes en ASCII, sans terminateur, sans séparateur dans l'AAD. Nonce : 96 bits aléatoires (`SecureRandom`) à
chaque chiffrement ; étiquette GCM de 128 bits.

### kid

Entier dans `[0, 65 535]`, codé sur 2 octets gros-boutiste partout (charges S et W, ancrages du journal) :
`Kid.check`, `Kid.toBytes(int)`, `Kid.fromBytes(byte[], offset)`. Hors bornes : `IllegalArgumentException` dont le
message ne contient pas la valeur.

## API publique

| Classe | Méthode | Rôle |
|---|---|---|
| `KeyProvider` | `int activeKid()` ; `SecretKey kek(int kid)` | source de la KEK ; kid inconnu → `IllegalArgumentException` sans détail |
| `InMemoryKeyProvider` | `registerNewKek(int kid)` | KEK aléatoire de 32 octets, kid rendu actif ; un kid déjà enregistré est refusé |
| `Kid` | `check`, `toBytes`, `fromBytes` | kid sur 16 bits |
| `Ed25519` | `keyPairFromSeed(byte[32])` (ajout) | paire déterministe depuis une graine |
| `CryptoService` | `int activeKid()` | kid actif (utilisé par le service d'audit) |
| | `signCanonicalJson(String)` → `SignedPayload(int kid, …)` ; `verifyCanonicalJson(int kid, String, byte[])` | JSON canonique (JCS) + Ed25519 sous SK |
| | `signBytes(int kid, byte[])` ; `verifyBytes(int kid, byte[], byte[])` | charge binaire fixe `MIT-W-v1` du module watermark (§8.3) ; seul le kid actif signe |
| | `publicKey(int kid)` | clé publique SK, pour le registre |
| | `sealTokenS(int kid, byte[16] emissionId, int issuerId, byte[32])` → 60 octets | jeton S : nonce(12) ‖ chiffré(32) ‖ étiquette(16) |
| | `openTokenS(int kid, byte[16] emissionId, int issuerId, byte[60])` → 32 octets | toute anomalie → même `IllegalArgumentException("Déchiffrement refusé")`, sans cause |
| | `encryptFile(int kid, byte[16] fileId, byte[])` ; `decryptFile(…)` | fichiers au repos ; même politique de refus |
| | `computeFCommit(int kid, byte[])` ; `kfHmac(int kid)` | K_F : engagement sur 128 bits et PRF encapsulée |
| | `logKeyPair(int kid)` | paire K_log, réservée à `CryptoLogKeyProvider` (module audit) |
| `Sha256`, `Hmac`, `Hkdf`, `AesGcm`, `Jcs` | inchangées | primitives |

**Supprimées** : `KeyProvider.activeSigningKeyId()`, `getSigningPrivateKey(String)`, `getSigningPublicKey(String)`,
`getMasterSecretKey(String)` ; `InMemoryKeyProvider.registerNewKeyVersion(String)` ;
`CryptoService.encryptTokenS(String, String, byte[])` et `decryptTokenS(…)` ; toutes les variantes à kid `String`.

La mise en forme des 64 octets de la partition S (version, kid, bourrage, ou 64 octets aléatoires sans jeton,
§8.3) appartient au futur module `medimage-stego`, pas à crypto. `AesGcm.aadForObject(String)` est conservée
(primitive inchangée, couverte par ses tests) mais ne sert plus au jeton S.

## Tests

`mvn -B -f middleware/pom.xml -pl api test -Dtest='tn.medimagetrust.crypto.**'` : 67 tests, dont

- les vecteurs officiels déjà présents (RFC 4231 pour HMAC, RFC 5869 pour HKDF) ;
- RFC 8032 §7.1 TEST 1 : clé publique et signature du message vide identiques au vecteur (`Ed25519SeedTest`) ;
- jeton S : aller-retour, refus si kid, émission, émetteur, nonce, chiffré ou étiquette changent, tailles invalides,
  reconstruction indépendante de K_S et de l'AAD, et **100 000 chiffrements sans nonce en double** (`TokenSTest`) ;
- fichiers : aller-retour, fichier déplacé sous un autre `fileId` ou relu avec un autre kid refusé, octet modifié
  refusé (`FileEncryptionTest`) ;
- encodage exact des `info` HKDF, absence de sel, cinq clés distinctes, kid sur 16 bits, messages sans détail
  (`KeyHierarchyTest`).

## Reste à faire pour la production

- **`ElytronKeyProvider`** (module web) : implémenter `KeyProvider` sur le credential store WildFly Elytron. Une
  entrée par kid contenant la KEK, ouverture au démarrage par un mot de passe masqué (`credential-reference`), jamais
  en clair, ni en variable d'environnement, ni dans un journal. Le contrat complet est dans la Javadoc de
  `KeyProvider`.
- Gestion du kid actif et de la rotation (registre SEC06) : qui crée le kid suivant, et quand il devient actif.
- Registre des clés publiques des autres établissements (`publicKey(kid)` à publier ; SEC-K05).
- Le module `medimage-audit` dispose de `CryptoLogKeyProvider` ; `InMemoryLogKeyProvider` reste réservé aux tests.
