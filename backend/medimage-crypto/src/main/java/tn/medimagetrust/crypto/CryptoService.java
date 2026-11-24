package tn.medimagetrust.crypto;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Objects;
import java.util.function.UnaryOperator;
import javax.crypto.SecretKey;

/**
 * Façade publique du module crypto. Toutes les clés sont dérivées de la KEK du kid ({@link KeyProvider}) à chaque
 * appel, par {@link KeyDerivation} ; aucune n'est conservée ni renvoyée en clair, sauf la paire de journal
 * ({@link #logKeyPair(int)}) destinée au seul service d'audit.
 *
 * <p>Un kid est un entier dans {@code [0, 65 535]} ({@link Kid}).</p>
 */
public final class CryptoService {

    /** Taille de l'identifiant d'émission ou de fichier. */
    public static final int ID_BYTES = 16;
    /** Taille du texte clair du jeton S (dossier v2.4, §8.3). */
    public static final int TOKEN_PLAINTEXT_BYTES = 32;
    /** Taille du jeton S scellé : nonce (12) ‖ chiffré (32) ‖ étiquette (16). */
    public static final int TOKEN_SEALED_BYTES = 12 + TOKEN_PLAINTEXT_BYTES + 16;

    private static final int NONCE_TAG_BYTES = 12 + 16;
    private static final String REFUSED = "Déchiffrement refusé";

    private final KeyProvider keyProvider;

    public CryptoService(KeyProvider keyProvider) {
        this.keyProvider = Objects.requireNonNull(keyProvider, "fournisseur de clés");
    }

    /** Empreinte SHA-256 d'un bloc de données (image, ROI, masque ou jeton). */
    public byte[] digest(byte[] data) {
        return Sha256.hash(data);
    }

    /** Kid actif de l'établissement (celui des nouvelles émissions et des nouveaux ancrages). */
    public int activeKid() {
        return Kid.check(keyProvider.activeKid());
    }

    // ---------------------------------------------------------------------------------------------- signature (SK)

    /** Canonicalise un JSON (RFC 8785) puis le signe avec la clé SK du kid actif. */
    public SignedPayload signCanonicalJson(String rawJson) {
        int kid = activeKid();
        byte[] canonical = Jcs.canonicalize(rawJson);
        return new SignedPayload(kid, canonical, Ed25519.sign(signingKeyPair(kid).getPrivate(), canonical));
    }

    /** Vérifie la signature Ed25519 d'un JSON canonicalisé avec la clé publique SK du kid. */
    public boolean verifyCanonicalJson(int kid, String rawJson, byte[] signature) {
        byte[] canonical = Jcs.canonicalize(rawJson);
        return Ed25519.verify(publicKey(kid), canonical, signature);
    }

    /**
     * Signe une charge binaire (charge fixe {@code MIT-W-v1} du module watermark, dossier v2.4 §8.3) avec la clé SK
     * du kid. Seul le kid actif peut signer : après rotation, un ancien kid ne sert plus qu'à vérifier.
     *
     * @throws IllegalArgumentException si {@code kid} n'est pas le kid actif ou si le message est nul
     */
    public byte[] signBytes(int kid, byte[] message) {
        if (message == null || Kid.check(kid) != activeKid()) {
            throw new IllegalArgumentException("Signature refusée");
        }
        return Ed25519.sign(signingKeyPair(kid).getPrivate(), message);
    }

    /** Vérifie une signature produite par {@link #signBytes(int, byte[])} ; {@code false} si elle est invalide. */
    public boolean verifyBytes(int kid, byte[] message, byte[] signature) {
        if (message == null || signature == null) {
            return false;
        }
        return Ed25519.verify(publicKey(kid), message, signature);
    }

    /** Clé publique SK du kid, pour le registre des clés publiques. */
    public PublicKey publicKey(int kid) {
        return signingKeyPair(kid).getPublic();
    }

    private KeyPair signingKeyPair(int kid) {
        return Ed25519.keyPairFromSeed(KeyDerivation.signingSeed(keyProvider, kid));
    }

    // ---------------------------------------------------------------------------------------------- journal (K_log)

    /**
     * Paire Ed25519 de signature des ancrages du journal, dérivée de la graine {@code MIT-LOG-v1} du kid. Réservée
     * au service d'audit ({@code CryptoLogKeyProvider}) ; ne jamais la journaliser ni la sérialiser.
     */
    public KeyPair logKeyPair(int kid) {
        return Ed25519.keyPairFromSeed(KeyDerivation.logSeed(keyProvider, kid));
    }

    // ---------------------------------------------------------------------------------------------- jeton S (K_tok)

    /**
     * Chiffre le texte clair de 32 octets du jeton S. Renvoie nonce(12) ‖ chiffré(32) ‖ étiquette(16) = 60 octets.
     *
     * <p>Sous-clé par émission {@code K_S = HKDF(K_tok, "MIT-S-v1" ‖ 0x00 ‖ emissionId)} ;
     * AAD = {@code "MIT-S-v1" ‖ emissionId(16) ‖ issuerId(4) ‖ kid(2)} (dossier v2.4, §8.2).</p>
     *
     * @throws IllegalArgumentException si une taille est invalide ou si le kid est inconnu
     */
    public byte[] sealTokenS(int kid, byte[] emissionId16, int issuerId, byte[] plaintext32) {
        if (plaintext32 == null || plaintext32.length != TOKEN_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("Texte clair de 32 octets requis");
        }
        SecretKey kS = KeyDerivation.subKey(KeyDerivation.kTok(keyProvider, kid), KeyDerivation.S_SUBKEY, emissionId16);
        return AesGcm.encrypt(kS, plaintext32, tokenAad(kid, emissionId16, issuerId));
    }

    /**
     * Déchiffre un jeton S scellé. Toute anomalie (kid inconnu ou différent, autre émission, autre émetteur, octet
     * modifié, taille invalide) lève la même {@link IllegalArgumentException}, sans détail ni cause.
     */
    public byte[] openTokenS(int kid, byte[] emissionId16, int issuerId, byte[] sealed60) {
        try {
            if (sealed60 == null || sealed60.length != TOKEN_SEALED_BYTES) {
                throw new IllegalArgumentException(REFUSED);
            }
            SecretKey kS = KeyDerivation.subKey(KeyDerivation.kTok(keyProvider, kid), KeyDerivation.S_SUBKEY,
                    emissionId16);
            return AesGcm.decrypt(kS, sealed60, tokenAad(kid, emissionId16, issuerId));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(REFUSED);
        }
    }

    /** {@code "MIT-S-v1" ‖ emissionId(16) ‖ issuerId(4, gros-boutiste) ‖ kid(2, gros-boutiste)}. */
    static byte[] tokenAad(int kid, byte[] emissionId16, int issuerId) {
        byte[] label = KeyDerivation.S_SUBKEY.getBytes(StandardCharsets.US_ASCII);
        byte[] id = checkedId(emissionId16);
        byte[] k = Kid.toBytes(kid);
        byte[] aad = new byte[label.length + ID_BYTES + 4 + Kid.BYTES];
        int p = 0;
        System.arraycopy(label, 0, aad, p, label.length);
        p += label.length;
        System.arraycopy(id, 0, aad, p, ID_BYTES);
        p += ID_BYTES;
        aad[p++] = (byte) (issuerId >>> 24);
        aad[p++] = (byte) (issuerId >>> 16);
        aad[p++] = (byte) (issuerId >>> 8);
        aad[p++] = (byte) issuerId;
        System.arraycopy(k, 0, aad, p, Kid.BYTES);
        return aad;
    }

    // ---------------------------------------------------------------------------------------------- fichiers (K_store)

    /**
     * Chiffre un fichier au repos. Renvoie nonce(12) ‖ chiffré ‖ étiquette(16).
     *
     * <p>Clé par fichier {@code HKDF(K_store, "MIT-FILE-v1" ‖ 0x00 ‖ fileId)} ;
     * AAD = {@code "MIT-FILE-v1" ‖ fileId(16) ‖ kid(2)} (dossier v2.4, §8.2).</p>
     */
    public byte[] encryptFile(int kid, byte[] fileId16, byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Données requises");
        }
        SecretKey kFile = KeyDerivation.subKey(KeyDerivation.kStore(keyProvider, kid), KeyDerivation.FILE_SUBKEY,
                fileId16);
        return AesGcm.encrypt(kFile, data, fileAad(kid, fileId16));
    }

    /** Déchiffre un fichier ; toute anomalie lève la même {@link IllegalArgumentException}, sans détail. */
    public byte[] decryptFile(int kid, byte[] fileId16, byte[] sealed) {
        try {
            if (sealed == null || sealed.length < NONCE_TAG_BYTES) {
                throw new IllegalArgumentException(REFUSED);
            }
            SecretKey kFile = KeyDerivation.subKey(KeyDerivation.kStore(keyProvider, kid), KeyDerivation.FILE_SUBKEY,
                    fileId16);
            return AesGcm.decrypt(kFile, sealed, fileAad(kid, fileId16));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(REFUSED);
        }
    }

    /** {@code "MIT-FILE-v1" ‖ fileId(16) ‖ kid(2, gros-boutiste)}. */
    static byte[] fileAad(int kid, byte[] fileId16) {
        byte[] label = KeyDerivation.FILE_SUBKEY.getBytes(StandardCharsets.US_ASCII);
        byte[] id = checkedId(fileId16);
        byte[] k = Kid.toBytes(kid);
        byte[] aad = new byte[label.length + ID_BYTES + Kid.BYTES];
        System.arraycopy(label, 0, aad, 0, label.length);
        System.arraycopy(id, 0, aad, label.length, ID_BYTES);
        System.arraycopy(k, 0, aad, label.length + ID_BYTES, Kid.BYTES);
        return aad;
    }

    private static byte[] checkedId(byte[] id16) {
        if (id16 == null || id16.length != ID_BYTES) {
            throw new IllegalArgumentException("Identifiant de 16 octets requis");
        }
        return id16;
    }

    // ---------------------------------------------------------------------------------------------- fingerprint (K_F)

    /** Calcule l'engagement destinataire fCommit sur 128 bits (HMAC-SHA-256 tronqué sous K_F). */
    public byte[] computeFCommit(int kid, byte[] recipientData) {
        return Hmac.fCommit128(KeyDerivation.kF(keyProvider, kid), recipientData);
    }

    /**
     * PRF HMAC-SHA-256 sous la clé K_F du kid (axe fingerprinting). K_F est dérivée une seule fois et reste
     * encapsulée dans la fonction retournée : l'appelant n'obtient jamais les octets de la clé.
     */
    public UnaryOperator<byte[]> kfHmac(int kid) {
        final byte[] kF = KeyDerivation.kF(keyProvider, kid);
        return message -> Hmac.compute(kF, message);
    }

    /** Résultat d'une signature JSON versionnée par kid. */
    public record SignedPayload(int kid, byte[] canonicalBytes, byte[] signature) {}
}
