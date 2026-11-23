package tn.medimagetrust.crypto;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authentification par code HMAC-SHA-256 et engagement fCommit (128 bits). */
public final class Hmac {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int KEY_BYTES = 32; // 256 bits
    private static final SecureRandom RNG = new SecureRandom();

    private Hmac() {
    }

    /** Génère une clé secrète HMAC de 256 bits (32 octets). */
    public static byte[] generateKey() {
        byte[] key = new byte[KEY_BYTES];
        RNG.nextBytes(key);
        return key;
    }

    /** Calcule le code HMAC-SHA-256 complet (32 octets). */
    public static byte[] compute(byte[] key, byte[] message) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("La clé HMAC ne peut pas être vide");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(message);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("Clé HMAC invalide", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 indisponible", e);
        }
    }

    /** Vérifie un HMAC complet (32 octets) en temps constant (anti-timing attack). */
    public static boolean verify(byte[] key, byte[] message, byte[] expectedMac) {
        if (expectedMac == null || expectedMac.length != 32) {
            return false;
        }
        byte[] actual = compute(key, message);
        return MessageDigest.isEqual(actual, expectedMac);
    }

    /** Engagement destinataire fCommit : 128 bits de poids fort (16 premiers octets du HMAC). */
    public static byte[] fCommit128(byte[] key, byte[] message) {
        return Arrays.copyOf(compute(key, message), 16);
    }
}
