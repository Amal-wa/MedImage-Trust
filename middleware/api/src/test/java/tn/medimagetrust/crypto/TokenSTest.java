package tn.medimagetrust.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Jeton S : sous-clé par émission, AAD liée à l'émission (v2.4 §8.2, §8.3 ; SEC-D01, SEC-K02). */
class TokenSTest {

    private static final int KID = 1;
    private static final int ISSUER = 0x0A0B0C0D;
    private static final int NONCE_TEST_COUNT = 100_000;

    private InMemoryKeyProvider keys;
    private CryptoService crypto;
    private byte[] emission;
    private byte[] plaintext;

    @BeforeEach
    void setUp() {
        keys = new InMemoryKeyProvider();
        keys.registerNewKek(KID);
        keys.registerNewKek(2);
        crypto = new CryptoService(keys);
        SecureRandom rnd = new SecureRandom();
        emission = new byte[16];
        rnd.nextBytes(emission);
        plaintext = new byte[32];
        rnd.nextBytes(plaintext);
    }

    /** Vérifie le refus et l'absence de détail : même type, même message, aucune cause. */
    private static void assertRefused(Supplier<byte[]> op) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, op::get);
        assertEquals("Déchiffrement refusé", e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    @DisplayName("Aller-retour : 60 octets scellés, texte clair retrouvé")
    void allerRetour() {
        byte[] sealed = crypto.sealTokenS(KID, emission, ISSUER, plaintext);
        assertEquals(60, sealed.length);
        assertArrayEquals(plaintext, crypto.openTokenS(KID, emission, ISSUER, sealed));
    }

    @Test
    @DisplayName("Refus si le kid, l'émission ou l'émetteur change (AAD et sous-clé)")
    void refusSiContexteChange() {
        byte[] sealed = crypto.sealTokenS(KID, emission, ISSUER, plaintext);
        byte[] autreEmission = emission.clone();
        autreEmission[15] ^= 0x01;
        assertRefused(() -> crypto.openTokenS(2, emission, ISSUER, sealed));
        assertRefused(() -> crypto.openTokenS(99, emission, ISSUER, sealed));
        assertRefused(() -> crypto.openTokenS(KID, autreEmission, ISSUER, sealed));
        assertRefused(() -> crypto.openTokenS(KID, emission, ISSUER + 1, sealed));
    }

    @Test
    @DisplayName("Refus si un octet du nonce, du chiffré ou de l'étiquette est modifié")
    void refusSiOctetModifie() {
        byte[] sealed = crypto.sealTokenS(KID, emission, ISSUER, plaintext);
        for (int i : new int[] {0, 11, 12, 30, 43, 44, 50, 59}) { // nonce, chiffré, étiquette
            byte[] t = sealed.clone();
            t[i] ^= 0x01;
            assertRefused(() -> crypto.openTokenS(KID, emission, ISSUER, t));
        }
    }

    @Test
    @DisplayName("Tailles invalides : refus au scellement comme à l'ouverture")
    void taillesInvalides() {
        assertThrows(IllegalArgumentException.class, () -> crypto.sealTokenS(KID, emission, ISSUER, new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> crypto.sealTokenS(KID, emission, ISSUER, new byte[33]));
        assertThrows(IllegalArgumentException.class, () -> crypto.sealTokenS(KID, emission, ISSUER, null));
        assertThrows(IllegalArgumentException.class, () -> crypto.sealTokenS(KID, new byte[15], ISSUER, plaintext));
        assertThrows(IllegalArgumentException.class, () -> crypto.sealTokenS(KID, null, ISSUER, plaintext));
        assertThrows(IllegalArgumentException.class, () -> crypto.sealTokenS(65_536, emission, ISSUER, plaintext));
        byte[] sealed = crypto.sealTokenS(KID, emission, ISSUER, plaintext);
        assertRefused(() -> crypto.openTokenS(KID, emission, ISSUER, java.util.Arrays.copyOf(sealed, 59)));
        assertRefused(() -> crypto.openTokenS(KID, emission, ISSUER, java.util.Arrays.copyOf(sealed, 61)));
        assertRefused(() -> crypto.openTokenS(KID, emission, ISSUER, null));
        assertRefused(() -> crypto.openTokenS(KID, new byte[17], ISSUER, sealed));
    }

    @Test
    @DisplayName("Format exact : AAD = \"MIT-S-v1\" ‖ emissionId ‖ issuerId(4) ‖ kid(2) ; K_S = HKDF(K_tok, …)")
    void formatExactSousCleEtAad() {
        byte[] aad = CryptoService.tokenAad(0x0102, emission, ISSUER);
        assertEquals(8 + 16 + 4 + 2, aad.length);
        assertArrayEquals("MIT-S-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                java.util.Arrays.copyOf(aad, 8));
        assertArrayEquals(emission, java.util.Arrays.copyOfRange(aad, 8, 24));
        assertArrayEquals(new byte[] {0x0A, 0x0B, 0x0C, 0x0D, 0x01, 0x02}, java.util.Arrays.copyOfRange(aad, 24, 30));

        // Reconstruction indépendante : K_tok → K_S → AES-GCM avec la même AAD.
        byte[] sealed = crypto.sealTokenS(KID, emission, ISSUER, plaintext);
        byte[] kTok = Hkdf.derive(keys.kek(KID).getEncoded(), null, KeyDerivation.kidInfo("MIT-KTOK-v1", KID), 32);
        byte[] info = new byte[8 + 1 + 16];
        System.arraycopy("MIT-S-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, info, 0, 8);
        System.arraycopy(emission, 0, info, 9, 16);
        SecretKey kS = new SecretKeySpec(Hkdf.derive(kTok, null, info, 32), "AES");
        assertArrayEquals(plaintext, AesGcm.decrypt(kS, sealed, CryptoService.tokenAad(KID, emission, ISSUER)));
    }

    @Test
    @DisplayName("Critère d'acceptation : 100 000 chiffrements, 0 nonce en double")
    void centMilleNoncesSansDoublon() {
        Set<ByteBuffer> nonces = new HashSet<>(2 * NONCE_TEST_COUNT);
        int duplicates = 0;
        for (int i = 0; i < NONCE_TEST_COUNT; i++) {
            byte[] sealed = crypto.sealTokenS(KID, emission, ISSUER, plaintext);
            if (!nonces.add(ByteBuffer.wrap(java.util.Arrays.copyOf(sealed, 12)))) {
                duplicates++;
            }
        }
        System.out.println("[MESURE] jeton S : " + NONCE_TEST_COUNT + " chiffrements, nonces en double = "
                + duplicates);
        assertEquals(0, duplicates);
        assertEquals(NONCE_TEST_COUNT, nonces.size());
    }
}
