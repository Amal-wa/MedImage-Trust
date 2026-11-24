package tn.medimagetrust.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AesGcmTest {

    private static final byte[] CLAIR = "jeton S".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD = AesGcm.aadForObject("doc-001");

    @Test
    void allerRetour() {
        SecretKey key = AesGcm.generateKey();
        byte[] blob = AesGcm.encrypt(key, CLAIR, AAD);
        assertArrayEquals(CLAIR, AesGcm.decrypt(key, blob, AAD));
    }

    @Test
    void tailleSortieEgaleNoncePlusClairPlusTag() {
        SecretKey key = AesGcm.generateKey();
        assertEquals(12 + CLAIR.length + 16, AesGcm.encrypt(key, CLAIR, AAD).length);
    }

    @Test
    void deuxChiffrementsDuMemeClairDifferent() {
        SecretKey key = AesGcm.generateKey();
        byte[] a = AesGcm.encrypt(key, CLAIR, AAD);
        byte[] b = AesGcm.encrypt(key, CLAIR, AAD);
        assertFalse(Arrays.equals(Arrays.copyOf(a, 12), Arrays.copyOf(b, 12)), "nonces identiques");
        assertFalse(Arrays.equals(a, b));
    }

    @Test
    void aadDifferentRejete() {
        SecretKey key = AesGcm.generateKey();
        byte[] blob = AesGcm.encrypt(key, CLAIR, AAD);
        byte[] autreAad = AesGcm.aadForObject("doc-002");
        assertThrows(IllegalArgumentException.class, () -> AesGcm.decrypt(key, blob, autreAad));
    }

    @Test
    void octetDuChiffreAltereRejete() {
        SecretKey key = AesGcm.generateKey();
        byte[] blob = AesGcm.encrypt(key, CLAIR, AAD);
        blob[12] ^= 0x01;
        assertThrows(IllegalArgumentException.class, () -> AesGcm.decrypt(key, blob, AAD));
    }

    @Test
    void nonceAltereRejete() {
        SecretKey key = AesGcm.generateKey();
        byte[] blob = AesGcm.encrypt(key, CLAIR, AAD);
        blob[0] ^= 0x01;
        assertThrows(IllegalArgumentException.class, () -> AesGcm.decrypt(key, blob, AAD));
    }

    @Test
    void mauvaiseCleRejetee() {
        byte[] blob = AesGcm.encrypt(AesGcm.generateKey(), CLAIR, AAD);
        SecretKey autre = AesGcm.generateKey();
        assertThrows(IllegalArgumentException.class, () -> AesGcm.decrypt(autre, blob, AAD));
    }

    @Test
    void donneesTropCourtesRejetees() {
        SecretKey key = AesGcm.generateKey();
        assertThrows(IllegalArgumentException.class, () -> AesGcm.decrypt(key, new byte[10], AAD));
    }

    @Test
    void cleNonAes256Rejetee() {
        SecretKey cle128 = new SecretKeySpec(new byte[16], "AES");
        assertThrows(IllegalArgumentException.class, () -> AesGcm.encrypt(cle128, CLAIR, AAD));
    }
    @Test
    void centMilleChiffrementsSansCollisionDeNonce() {
        javax.crypto.SecretKey key = AesGcm.generateKey();
        byte[] plaintext = "jeton-patient-S".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] aad = "doc-123".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        java.util.Set<java.nio.ByteBuffer> nonces = java.util.HashSet.newHashSet(100_000);
        for (int i = 0; i < 100_000; i++) {
            byte[] ciphertext = AesGcm.encrypt(key, plaintext, aad);
            // Les 12 premiers octets sont le nonce de 96 bits
            byte[] nonce = java.util.Arrays.copyOf(ciphertext, 12);
            org.junit.jupiter.api.Assertions.assertTrue(
                nonces.add(java.nio.ByteBuffer.wrap(nonce)),
                "Collision de nonce détectée à l'itération " + i
            );
        }
    }
}
