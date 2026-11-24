# medimage-watermark — preuve d'origine W (SEC-D03)

Responsable : Nour. Charge W binaire fixe de 208 octets, étiquette `MIT-W-v1`, signée Ed25519 par l'établissement
émetteur, vérifiée en échec fermé (CU06). Référence : dossier v2.4 (`docs/MedImage-Trust_v2.4_complet.pdf`), §8.3
(format) et §8.4 (vérification V1 à V7).

Dépend de `medimage-crypto` (API v2.4, voir [`../medimage-crypto/README.md`](../medimage-crypto/README.md)).

## Format (inchangé)

| Offset | Octets | Champ |
|---|---|---|
| 0 | 1 | version `0x01` |
| 1 | 2 | kid (entier 0 à 65 535, gros-boutiste) |
| 3 | 4 | issuerId |
| 7 | 16 | emissionId (UUID) |
| 23 | 8 | issuedAt (secondes UNIX UTC) |
| 31 | 1 | flow (`0x01` clinique, `0x02` recherche) |
| 32 | 32 | roiDigest |
| 64 | 32 | maskDigest |
| 96 | 32 | sDigest |
| 128 | 16 | fCommit |
| 144 | 64 | signature Ed25519 sur `"MIT-W-v1" ‖ 0x00 ‖ octets 0 à 143` |

## Adaptation à l'API crypto v2.4

| Avant (ancienne API) | Maintenant |
|---|---|
| `signBinary(String.valueOf(kid), m)` | `signBytes(kid, m)` : clé SK dérivée de la KEK (`MIT-SIG-v1`), **seul le kid actif signe** |
| `verifyBinary(String, m, sig)` | `verifyBytes(kid, m, sig)` : clé publique SK du kid ; kid inconnu → `SIGNATURE_INVALIDE` (exception attrapée) |
| `computeFCommit(String.valueOf(kid), …)` | `computeFCommit(kid, …)` |
| `KeyMetadata(String kid, …)`, `findByKid(String)` | `KeyMetadata(int kid, …)` (contrôlé par `Kid.check`), `findByKid(int)` |
| `WatermarkPayload.kidAsString()` | supprimé (plus utilisé) |

**Préfixe de domaine : une seule fois.** `CryptoService.signBytes` signe les octets tels quels, sans ajouter de
préfixe. `WatermarkPayload.buildSignedInput` pose `"MIT-W-v1" ‖ 0x00` devant l'en-tête de 144 octets. Le message signé
fait donc exactement 153 octets. `WatermarkSignedMessageTest` le prouve : la signature d'une charge W se vérifie avec
`Ed25519.verify(crypto.publicKey(kid), …)` sur ce message reconstruit à la main, et échoue sur l'en-tête seul comme sur
un préfixe doublé.

Le format des 208 octets, l'ordre des vérifications (V3 format, V4 kid, V5 signature, V6 condensats : masque, ROI,
puis S) et les motifs de `VerificationReason` sont inchangés.

## Cohérence avec le module embedding (constatée, non branchée)

| Point | Constat |
|---|---|
| Écriture et lecture des 208 octets | correspond à `LsbChannel.writeW(byte[208])` / `readW()` d'embedding (26 blocs W, bit 0, poids fort d'abord) : `generateWatermark` produit bien 208 octets |
| `roiDigest` | doit venir de `Digests.roiDigest(image, profil)` d'embedding : `"MIT-ROI-v1" ‖ rows(4) ‖ cols(4) ‖ bitsStored(1) ‖ pixels hors RONI sur 2 octets`. **Écart** : `WatermarkService.computeRoiDigest` code `bitsAllocated` sur 4 octets et prend des octets de pixels déjà sérialisés par l'appelant : condensats différents |
| `maskDigest` | doit venir de `Digests.maskDigest(profil)`. `computeMaskDigest(rows, cols, masque)` a la même structure ; il ne donne le même résultat que si l'appelant passe `RoniProfile.packedMask()` |
| `sDigest` | `SHA-256(64 octets de S)` : identique à `Digests.sDigest` |
| Vérification | le vérificateur doit relire W sur la copie pour chaque masque de `RoniDetector.candidates(copie)`, **un par un** (`LsbChannel.reader(copie, Partitions.of(candidat)).readW()`), et garder le seul candidat dont la preuve se vérifie (§4.1). Un candidat de moins de 290 blocs (`TOO_FEW_RONI_BLOCKS`) vaut « preuve absente » |

### À brancher (prochaine étape de Nour)

1. Dépendance `medimage-embedding` dans le `pom.xml` du module.
2. Émission : prendre `roiDigest`, `maskDigest`, `sDigest` de `EmbeddingSession` (ou de `Digests`), puis écrire la
   charge par `EmbeddingSession.writeW(w)` en dernier.
3. Vérification (V2, V3) : boucle sur `RoniDetector.candidates(copie)`, lecture W par `LsbChannel.reader`, condensats
   attendus recalculés par `Digests` pour chaque candidat, et S relu par `LsbChannel.readS`.
4. Remplacer ou retirer `computeRoiDigest` et `computeMaskDigest` au profit de `Digests` (encodage unique).
5. `fCommit` : le prendre dans `FingerprintService.prepare(...)` plutôt que de le recalculer (voir écarts).

## Écarts avec la v2.4 relevés (non corrigés)

1. **`roiDigest` incompatible avec embedding** (voir tableau) : `bitsAllocated` sur 4 octets au lieu de `bitsStored` sur
   1 octet, et pixels fournis bruts par l'appelant.
2. **`fCommit` incompatible avec fingerprint.** `WatermarkService.computeFCommit` calcule
   `HMAC(K_F, "MIT-FC-v1" ‖ emissionId(16) ‖ recipientId(UUID, 16))`, concaténation brute. Fingerprint calcule
   `HMAC(K_F, "MIT-FC-v1" ‖ 0x00 ‖ len ‖ emissionId ‖ len ‖ recipientId en UTF-8)` (`Messages.build`). Les deux
   engagements diffèrent pour la même émission : il faut une seule source, et le dossier (§8.2) ne fixe pas
   l'encodage de `recipientId`.
3. **Clé de vérification.** La v2.4 vérifie W avec le **registre des clés publiques** (§4.2 : « image + registre des
   clés publiques » ; SEC-K05). `verifyWatermark` vérifie avec `CryptoService.verifyBytes`, donc avec la clé dérivée de
   la KEK locale : il ne peut vérifier que les preuves de son propre établissement. Le registre
   (`PublicKeyStatusRegistry`) ne fournit pas la clé publique, seulement son statut.
4. **Émetteur différent de celui de la clé** : `keyMeta.institutionId() != issuerId` donne
   `CLE_EXPIREE_OU_REVOQUEE`. Un motif plus juste serait « clé inconnue pour cet émetteur » ; la liste fermée n'a pas
   ce motif.
5. **Messages d'erreur avec des valeurs** : `WatermarkPayload` et `FlowType` incluent la version, le kid ou le code de
   flux dans leurs messages (`"kid hors plage uint16: " + kid`). C'est attrapé par `verifyWatermark` (motif
   `PREUVE_ABSENTE`), mais ces messages ne doivent pas atteindre un journal.
6. **V1, V2 et V7 absents** du module : contrôles d'entrée DICOM, recalcul du masque et journalisation relèvent de
   dicom, embedding et web.

## Tests

`mvn -B -pl medimage-watermark -am test` : 5 tests. `WatermarkServiceTest` (2 tests, assertions inchangées : charge
valide acceptée ; preuve absente, signature, masque, ROI, S et clé révoquée refusés avec le bon motif) et
`WatermarkSignedMessageTest` (3 tests : message signé exact, seul le kid actif signe, kid du registre sur 16 bits).
