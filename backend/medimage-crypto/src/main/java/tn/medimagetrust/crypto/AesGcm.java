package tn.medimagetrust.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Chiffrement authentifié AES-256-GCM : nonce aléatoire de 96 bits + AAD. */
public final class AesGcm {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcm() {
    }

    public static SecretKey generateKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(KEY_BYTES * 8, RANDOM);
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES indisponible", e);
        }
    }

    /** AAD à partir de l'identifiant d'objet : lie le chiffré à cet objet. */
    public static byte[] aadForObject(String objectId) {
        return objectId.getBytes(StandardCharsets.UTF_8);
    }

    /** Renvoie nonce (12 octets) || chiffré || tag (16 octets). */
    public static byte[] encrypt(SecretKey key, byte[] plaintext, byte[] aad) {
        requireAes256(key);
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(plaintext);

            byte[] out = new byte[NONCE_BYTES + encrypted.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
            System.arraycopy(encrypted, 0, out, NONCE_BYTES, encrypted.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Chiffrement impossible", e);
        }
    }

    /** Déchiffre. Toute anomalie lève la même exception, sans détail sur la cause. */
    public static byte[] decrypt(SecretKey key, byte[] blob, byte[] aad) {
        requireAes256(key);
        if (blob.length < NONCE_BYTES + TAG_BYTES) {
            throw new IllegalArgumentException("Déchiffrement refusé");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 0, NONCE_BYTES));
            cipher.updateAAD(aad);
            return cipher.doFinal(blob, NONCE_BYTES, blob.length - NONCE_BYTES);
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException("Déchiffrement refusé");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Déchiffrement impossible", e);
        }
    }

    private static void requireAes256(SecretKey key) {
        byte[] encoded = key.getEncoded();
        if (encoded == null || encoded.length != KEY_BYTES) {
            throw new IllegalArgumentException("Clé AES-256 (32 octets) requise");
        }
    }
}
