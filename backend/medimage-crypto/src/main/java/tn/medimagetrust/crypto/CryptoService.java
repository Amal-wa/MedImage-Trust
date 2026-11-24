package tn.medimagetrust.crypto;

import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Façade publique du module crypto pour Amal (stego/embedding) et Nourhen (fingerprint/audit).
 */
public final class CryptoService {

    private final KeyProvider keyProvider;

    public CryptoService(KeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    /** Empreinte SHA-256 d'un bloc de données (image, ROI, masque ou jeton). */
    public byte[] digest(byte[] data) {
        return Sha256.hash(data);
    }

    /** Canonicalise un JSON (RFC 8785) puis le signe avec la clé active de l'hôpital. */
    public SignedPayload signCanonicalJson(String rawJson) {
        String kid = keyProvider.activeSigningKeyId();
        byte[] canonical = Jcs.canonicalize(rawJson);
        byte[] signature = Ed25519.sign(keyProvider.getSigningPrivateKey(kid), canonical);
        return new SignedPayload(kid, canonical, signature);
    }

    /** Vérifie la signature Ed25519 d'un JSON canonicalisé avec la clé publique du kid. */
    public boolean verifyCanonicalJson(String kid, String rawJson, byte[] signature) {
        byte[] canonical = Jcs.canonicalize(rawJson);
        return Ed25519.verify(keyProvider.getSigningPublicKey(kid), canonical, signature);
    }
    /** Signe une charge utile binaire avec préfixe de domaine (ex. MIT-W-v1 || 0x00 || payload) via Ed25519. */
    public byte[] signBinary(String kid, byte[] domainPrefixedMessage) {
        return Ed25519.sign(keyProvider.getSigningPrivateKey(kid), domainPrefixedMessage);
    }

    /** Vérifie une signature Ed25519 sur une charge utile binaire avec préfixe de domaine. */
    public boolean verifyBinary(String kid, byte[] domainPrefixedMessage, byte[] signature) {
        return Ed25519.verify(keyProvider.getSigningPublicKey(kid), domainPrefixedMessage, signature);
    }

    /** Chiffre le jeton patient S (pour Amal) avec la clé dérivée K_tok et l'ID d'objet en AAD. */
    public byte[] encryptTokenS(String kid, String objectId, byte[] tokenS) {
        SecretKey kTok = deriveSubKey(kid, "MIT-KTOK-v1");
        return AesGcm.encrypt(kTok, tokenS, objectId.getBytes(StandardCharsets.UTF_8));
    }

    /** Déchiffre le jeton patient S (pour Amal) en vérifiant l'ID d'objet (AAD). */
    public byte[] decryptTokenS(String kid, String objectId, byte[] encryptedTokenS) {
        SecretKey kTok = deriveSubKey(kid, "MIT-KTOK-v1");
        return AesGcm.decrypt(kTok, encryptedTokenS, objectId.getBytes(StandardCharsets.UTF_8));
    }

    /** Calcule l'engagement destinataire fCommit sur 128 bits (pour Nourhen / Tardos). */
    public byte[] computeFCommit(String kid, byte[] recipientData) {
        byte[] kF = deriveSubKey(kid, "MIT-KF-v1").getEncoded();
        return Hmac.fCommit128(kF, recipientData);
    }

    /**
     * PRF HMAC-SHA-256 sous la clé K_F de l'établissement pour un kid donné (axe fingerprinting).
     * K_F est dérivée une seule fois (HKDF, label MIT-KF-v1) et reste encapsulée dans la fonction
     * retournée : l'appelant n'obtient jamais les octets de la clé.
     * Ajout demandé par l'axe fingerprinting (module medimage-fingerprint).
     */
    public UnaryOperator<byte[]> kfHmac(String kid) {
        final byte[] kF = deriveSubKey(kid, "MIT-KF-v1").getEncoded();
        return message -> Hmac.compute(kF, message);
    }

    private SecretKey deriveSubKey(String kid, String label) {
        byte[] master = keyProvider.getMasterSecretKey(kid).getEncoded();
        byte[] derived = Hkdf.derive256(master, null, label);
        return new SecretKeySpec(derived, "AES");
    }

    /** Résultat d'une signature versionnée par kid. */
    public record SignedPayload(String kid, byte[] canonicalBytes, byte[] signature) {}
}
