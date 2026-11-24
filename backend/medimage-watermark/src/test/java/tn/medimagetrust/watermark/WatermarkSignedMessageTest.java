package tn.medimagetrust.watermark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.crypto.CryptoService;
import tn.medimagetrust.crypto.Ed25519;
import tn.medimagetrust.crypto.InMemoryKeyProvider;

/**
 * Dossier v2.4 §8.3 : la signature porte sur {@code "MIT-W-v1" ‖ 0x00 ‖ octets 0 à 143}, avec le préfixe de domaine
 * une seule fois. {@code CryptoService.signBytes} n'ajoute aucun préfixe : c'est watermark qui le pose.
 */
class WatermarkSignedMessageTest {

    private static final int KID = 7;

    private static byte[] expectedMessage(byte[] header144) {
        // Construction indépendante de WatermarkPayload.buildSignedInput.
        byte[] label = "MIT-W-v1".getBytes(StandardCharsets.US_ASCII);
        byte[] msg = new byte[label.length + 1 + 144];
        System.arraycopy(label, 0, msg, 0, label.length);
        msg[label.length] = 0x00;
        System.arraycopy(header144, 0, msg, label.length + 1, 144);
        return msg;
    }

    @Test
    @DisplayName("La signature de W se vérifie par Ed25519 sur exactement \"MIT-W-v1\" ‖ 0x00 ‖ en-tête(144)")
    void signatureCoversDomainLabelOnceThenHeader() {
        InMemoryKeyProvider keys = new InMemoryKeyProvider();
        keys.registerNewKek(KID);
        CryptoService crypto = new CryptoService(keys);
        WatermarkService service = new WatermarkService(crypto, kid -> Optional.empty());

        byte[] w = service.generateWatermark(KID, 100, UUID.randomUUID(), Instant.parse("2026-11-24T10:00:00Z"),
                FlowType.CLINICAL, new byte[32], new byte[32], new byte[32], new byte[16]);
        assertEquals(208, w.length);
        byte[] header = Arrays.copyOfRange(w, 0, 144);
        byte[] signature = Arrays.copyOfRange(w, 144, 208);
        byte[] message = expectedMessage(header);
        assertEquals(8 + 1 + 144, message.length);
        assertArrayEquals(message, WatermarkPayload.buildSignedInput(header));

        // Vérification directe, sans watermark ni CryptoService.verifyBytes : clé publique du kid + Ed25519.
        assertTrue(Ed25519.verify(crypto.publicKey(KID), message, signature));

        // Le préfixe n'est ni absent ni doublé.
        assertFalse(Ed25519.verify(crypto.publicKey(KID), header, signature));
        byte[] doubled = new byte[9 + message.length];
        System.arraycopy(message, 0, doubled, 9, message.length);
        System.arraycopy(message, 0, doubled, 0, 9);
        assertFalse(Ed25519.verify(crypto.publicKey(KID), doubled, signature));
    }

    @Test
    @DisplayName("Seul le kid actif signe une charge W (rotation : l'ancien kid ne sert plus qu'à vérifier)")
    void onlyActiveKidSigns() {
        InMemoryKeyProvider keys = new InMemoryKeyProvider();
        keys.registerNewKek(1);
        keys.registerNewKek(2);
        WatermarkService service = new WatermarkService(new CryptoService(keys), kid -> Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.generateWatermark(1, 100, UUID.randomUUID(),
                Instant.parse("2026-11-24T10:00:00Z"), FlowType.CLINICAL,
                new byte[32], new byte[32], new byte[32], new byte[16]));
    }

    @Test
    @DisplayName("Registre : kid entier sur 16 bits, hors bornes refusé")
    void registryKidIsSixteenBits() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(65_535, new PublicKeyStatusRegistry.KeyMetadata(65_535, 1, t, null, null,
                PublicKeyStatusRegistry.KeyStatus.ACTIVE).kid());
        assertThrows(IllegalArgumentException.class, () -> new PublicKeyStatusRegistry.KeyMetadata(65_536, 1, t,
                null, null, PublicKeyStatusRegistry.KeyStatus.ACTIVE));
        assertThrows(IllegalArgumentException.class, () -> new PublicKeyStatusRegistry.KeyMetadata(-1, 1, t,
                null, null, PublicKeyStatusRegistry.KeyStatus.ACTIVE));
    }
}
