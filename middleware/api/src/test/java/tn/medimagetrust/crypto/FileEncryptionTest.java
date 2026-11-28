package tn.medimagetrust.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fichiers au repos : AES-256-GCM, clé par fichier, AAD liée à l'objet (v2.4 §8.2). */
class FileEncryptionTest {

    private CryptoService crypto;
    private byte[] fileId;
    private byte[] otherId;
    private final byte[] data = "contenu DICOM simulé".repeat(500).getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        InMemoryKeyProvider keys = new InMemoryKeyProvider();
        keys.registerNewKek(1);
        keys.registerNewKek(2);
        crypto = new CryptoService(keys);
        SecureRandom rnd = new SecureRandom();
        fileId = new byte[16];
        rnd.nextBytes(fileId);
        otherId = fileId.clone();
        otherId[0] ^= 0x01;
    }

    private static void assertRefused(Supplier<byte[]> op) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, op::get);
        assertEquals("Déchiffrement refusé", e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    @DisplayName("Aller-retour : nonce(12) ‖ chiffré ‖ étiquette(16)")
    void allerRetour() {
        byte[] sealed = crypto.encryptFile(1, fileId, data);
        assertEquals(12 + data.length + 16, sealed.length);
        assertArrayEquals(data, crypto.decryptFile(1, fileId, sealed));
        assertArrayEquals(new byte[0], crypto.decryptFile(1, fileId, crypto.encryptFile(1, fileId, new byte[0])));
    }

    @Test
    @DisplayName("Fichier chiffré déplacé sous un autre fileId ou relu avec un autre kid : refusé")
    void deplaceRefuse() {
        byte[] sealed = crypto.encryptFile(1, fileId, data);
        assertRefused(() -> crypto.decryptFile(1, otherId, sealed));
        assertRefused(() -> crypto.decryptFile(2, fileId, sealed));
    }

    @Test
    @DisplayName("Un octet modifié (nonce, chiffré, étiquette) : refusé")
    void octetModifieRefuse() {
        byte[] sealed = crypto.encryptFile(1, fileId, data);
        for (int i : new int[] {0, 12, sealed.length / 2, sealed.length - 1}) {
            byte[] t = sealed.clone();
            t[i] ^= 0x01;
            assertRefused(() -> crypto.decryptFile(1, fileId, t));
        }
        assertRefused(() -> crypto.decryptFile(1, fileId, Arrays.copyOf(sealed, 27)));
        assertRefused(() -> crypto.decryptFile(1, fileId, null));
    }

    @Test
    @DisplayName("AAD = \"MIT-FILE-v1\" ‖ fileId(16) ‖ kid(2) ; entrées invalides refusées")
    void formatEtEntrees() {
        byte[] aad = CryptoService.fileAad(0x0304, fileId);
        assertEquals(11 + 16 + 2, aad.length);
        assertArrayEquals("MIT-FILE-v1".getBytes(StandardCharsets.US_ASCII), Arrays.copyOf(aad, 11));
        assertArrayEquals(fileId, Arrays.copyOfRange(aad, 11, 27));
        assertArrayEquals(new byte[] {0x03, 0x04}, Arrays.copyOfRange(aad, 27, 29));
        assertThrows(IllegalArgumentException.class, () -> crypto.encryptFile(1, new byte[15], data));
        assertThrows(IllegalArgumentException.class, () -> crypto.encryptFile(1, fileId, null));
        assertThrows(IllegalArgumentException.class, () -> crypto.encryptFile(9, fileId, data));
    }
}
