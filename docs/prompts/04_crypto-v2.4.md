# Prompt Claude Code 04 — `medimage-crypto` conforme au dossier v2.4

## Avant (PowerShell, dans `C:\Users\amelb\Documents\MedImage-Trust`)

```powershell
git fetch --all --prune
git status                                       # arbre propre ; chore/harmonisation déjà poussée
git switch -c fix/crypto-v2.4 origin/chore/harmonisation
Test-Path .\docs\MedImage-Trust_v2.4_complet.pdf  # True (copie locale, non suivie)
```

Lance `claude` et colle le bloc ci-dessous.

---

## PROMPT À COLLER

Tu travailles sur le dépôt **MedImage-Trust** (Jakarta EE 11 / WildFly / Java 25), branche `fix/crypto-v2.4`. Le module `backend/medimage-crypto` (SHA-256, HMAC, HKDF, AES-256-GCM, Ed25519, JCS) fonctionne, mais trois points ne suivent pas le dossier de référence v2.4 (`docs/MedImage-Trust_v2.4_complet.pdf`, §8.2 et §8.3) ni les consignes du professeur. L'équipe m'a autorisée à les corriger. Tu les corriges, et tu adaptes **au minimum** les modules qui appellent crypto (fingerprint, audit) pour que tout compile.

### Règles impératives
1. **Ni `git commit` ni `git push`.**
2. **Ne lis aucun fichier hors du dépôt courant.** Si le PDF v2.4 est absent de `docs/`, arrête-toi et signale-le.
3. Ne change **pas** les classes primitives qui sont justes (`Sha256`, `Hmac`, `Hkdf`, `AesGcm`, `Ed25519`, `Jcs`), sauf l'ajout signalé ci-dessous. Garde leurs tests et leurs vecteurs officiels (RFC 4231, RFC 5869).
4. Dans `medimage-fingerprint` et `medimage-audit` : **uniquement** les changements de signature imposés par ce prompt (type du kid, nouvel adaptateur). Aucune modification d'algorithme. Tous leurs tests doivent passer, sans être affaiblis.
5. Aucune dépendance externe. Messages et Javadoc en français. Aucun secret, aucune clé, aucun octet de clé dans un message d'erreur ou un `toString()`.

### Correction 1 — Un seul secret par établissement : la KEK (v2.4 §8.2, figure 8.1)
Aujourd'hui, `InMemoryKeyProvider` tire au hasard une paire Ed25519 **et** une clé maître **indépendantes** pour chaque kid. Le dossier impose : **la KEK est la seule clé stockée** (dans le credential store Elytron en production) ; toutes les autres en sont **dérivées à la demande, jamais écrites**.

- `KeyProvider` ne fournit plus que : `int activeKid()` et `SecretKey kek(int kid)`. Un kid inconnu lève une exception sans détail.
- `KeyDerivation` (nouvelle classe, package-private si possible) dérive par HKDF-SHA-256 (`Hkdf`, API `javax.crypto.KDF`), sans sel, avec `info = étiquette ASCII ‖ 0x00 ‖ kid sur 2 octets gros-boutiste` :

| Clé | Étiquette | Usage |
|---|---|---|
| K_tok | `MIT-KTOK-v1` | jeton S |
| K_F | `MIT-KF-v1` | fingerprint (HMAC) |
| K_store | `MIT-KSTORE-v1` | fichiers au repos |
| graine SK | `MIT-SIG-v1` | clé de signature Ed25519 (W) |
| graine K_log | `MIT-LOG-v1` | clé de signature Ed25519 des ancrages du journal |

- **Ed25519 depuis une graine de 32 octets** : la clé privée par `EdECPrivateKeySpec(NamedParameterSpec.ED25519, graine)` ; la clé publique correspondante par `KeyPairGenerator` Ed25519 initialisé avec un `SecureRandom` déterministe qui renvoie exactement la graine (classe privée, utilisée **uniquement** pour ça, documentée). Ajoute à `Ed25519` une méthode `keyPairFromSeed(byte[32])`.
- **Test obligatoire** : vecteur officiel **RFC 8032 §7.1, TEST 1** : graine `9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60` → clé publique `d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a`, et signature du message vide `e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b`.
- `InMemoryKeyProvider` : une KEK aléatoire de 32 octets par kid (`registerNewKek(int kid)`). Le `ElytronKeyProvider` de production sera fait dans le module web ; écris seulement son contrat dans la Javadoc de `KeyProvider`.

### Correction 2 — Le kid sur 16 bits, partout
Le dossier code le kid sur **2 octets** (charges S et W, ancrages du journal). Le module audit utilise déjà un `int` de 0 à 65 535. Aligne `crypto` et `fingerprint` : le kid devient un `int` dans [0, 65 535] (hors bornes : `IllegalArgumentException` sans détail). Fournis `Kid.toBytes(int)` et `Kid.fromBytes(byte[], offset)` (2 octets, gros-boutiste) dans crypto, et utilise-les partout.

### Correction 3 — Jeton S conforme au §8.2 / §8.3
Aujourd'hui, `encryptTokenS(kid, objectId, tokenS)` chiffre avec K_tok directement et `AAD = objectId`. Le dossier impose une **sous-clé par émission** et une **AAD qui lie le jeton à son émission**. Remplace les deux méthodes par :

```java
/** Chiffre le texte clair de 32 octets du jeton S. Renvoie nonce(12) ‖ chiffré(32) ‖ étiquette(16) = 60 octets. */
public byte[] sealTokenS(int kid, byte[] emissionId16, int issuerId, byte[] plaintext32)
/** Déchiffre ; toute anomalie (mauvais kid, émission, émetteur, octet modifié) → la même exception, sans détail. */
public byte[] openTokenS(int kid, byte[] emissionId16, int issuerId, byte[] sealed60)
```
- sous-clé : `K_S = HKDF(K_tok, info = "MIT-S-v1" ‖ 0x00 ‖ emissionId(16))` ;
- `AAD = "MIT-S-v1" ‖ emissionId(16) ‖ issuerId(4, gros-boutiste) ‖ kid(2)` ;
- nonce aléatoire de 96 bits (déjà fait par `AesGcm`).

La mise en forme des 64 octets de la partition S (version, kid, bourrage, ou 64 octets aléatoires sans jeton) appartient au futur module **stego**, pas à crypto.

**Tests** :
- aller-retour ;
- refus si l'on change **un** des éléments suivants : kid, emissionId, issuerId, un octet du chiffré, un octet de l'étiquette ;
- tailles invalides → refus ;
- **test du professeur : 100 000 chiffrements, 0 nonce en double**.

### Correction 4 — Fichiers au repos (v2.4 §8.2, consigne du professeur « AES-256-GCM + AAD liée à l'objet »)
```java
public byte[] encryptFile(int kid, byte[] fileId16, byte[] data)   // nonce ‖ chiffré ‖ étiquette
public byte[] decryptFile(int kid, byte[] fileId16, byte[] sealed)
```
- clé par fichier : `HKDF(K_store, info = "MIT-FILE-v1" ‖ 0x00 ‖ fileId)` ;
- `AAD = "MIT-FILE-v1" ‖ fileId(16) ‖ kid(2)`.

**Tests** : aller-retour ; un fichier chiffré **déplacé** sous un autre fileId est refusé ; un octet modifié est refusé.

### Correction 5 — Signature et fingerprint, sur le nouveau modèle de clés
- `CryptoService` signe avec la clé dérivée de la graine `MIT-SIG-v1` du kid actif et vérifie avec la clé publique dérivée du kid donné. Garde `signCanonicalJson` / `verifyCanonicalJson` (JCS + Ed25519, consigne du professeur). Ajoute `signBytes(int kid, byte[])` / `verifyBytes(int kid, byte[], byte[] sig)` pour la charge binaire fixe `MIT-W-v1` du module watermark (v2.4 §8.3), et `publicKey(int kid)` pour le registre.
- `computeFCommit(int kid, byte[])` et `kfHmac(int kid)` : même comportement, avec K_F dérivée comme au tableau ci-dessus.
- `logKeyPair(int kid)` : paire Ed25519 dérivée de la graine `MIT-LOG-v1`.

### Adaptations minimales des autres modules
- **fingerprint** : `CryptoPrf` et `FingerprintService.forKid(...)` prennent un `int kid`. Rien d'autre.
- **audit** : ajoute une seule classe, `CryptoLogKeyProvider implements LogKeyProvider`, qui s'appuie sur `CryptoService.logKeyPair(kid)`. Plus un test qui ancre avec elle et vérifie l'ancrage. `InMemoryLogKeyProvider` reste pour les tests existants.

### Documentation
- `medimage-crypto/README.md` (à créer) : la hiérarchie des clés (tableau ci-dessus), le format exact des `info` HKDF et des AAD, l'API publique, et ce qui reste pour la production (`ElytronKeyProvider` côté web).
- `backend/README.md` : retire de la liste des « écarts connus » les points corrigés ici (jeton S, dérivation, kid).

### Pour finir
- `mvn -B clean test` à la racine de `backend/` : tous les modules passent.
- Résumé :
  - les API supprimées ou ajoutées ;
  - les fichiers modifiés par module, avec le pourquoi ;
  - le nombre de tests par module ;
  - le résultat du vecteur RFC 8032 et du test des 100 000 nonces ;
  - les décisions à valider.
- **Aucun commit, aucun push.**

---

## Après (PowerShell)

```powershell
git status
cd backend; mvn -B clean test; cd ..
gitleaks detect --source . --no-banner -v
git add -A backend
git commit -m "fix(crypto): KEK seule + derivation HKDF, kid 16 bits, jeton S et fichiers conformes v2.4, RFC 8032"
git push -u origin fix/crypto-v2.4
```
