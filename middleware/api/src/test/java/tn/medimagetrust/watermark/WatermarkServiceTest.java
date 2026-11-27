package tn.medimagetrust.watermark;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.crypto.CryptoService;
import tn.medimagetrust.crypto.InMemoryKeyProvider;

class WatermarkServiceTest {

    private CryptoService cryptoService;
    private Map<Integer, PublicKeyStatusRegistry.KeyMetadata> registryMap;
    private WatermarkService watermarkService;

    @BeforeEach
    void setUp() {
        InMemoryKeyProvider keyProvider = new InMemoryKeyProvider();
        keyProvider.registerNewKek(1);
        cryptoService = new CryptoService(keyProvider);
        registryMap = new HashMap<>();
        registryMap.put(1, new PublicKeyStatusRegistry.KeyMetadata(
                1, 100, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-01-01T00:00:00Z"), null,
                PublicKeyStatusRegistry.KeyStatus.ACTIVE));
        watermarkService = new WatermarkService(cryptoService, kid -> Optional.ofNullable(registryMap.get(kid)));
    }

    @Test
    void validWatermarkIsAccepted() {
        UUID emissionId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Instant issuedAt = Instant.parse("2026-11-24T10:00:00Z");

        byte[] roiDigest = watermarkService.computeRoiDigest(512, 512, 16, "roi-pixels".getBytes(StandardCharsets.UTF_8));
        byte[] maskDigest = watermarkService.computeMaskDigest(512, 512, new byte[]{1, 0, 1, 1});
        byte[] sDigest = watermarkService.computePartitionSDigest(new byte[64]);
        byte[] fCommit = watermarkService.computeFCommit(1, emissionId, recipientId);

        byte[] w = watermarkService.generateWatermark(
                1, 100, emissionId, issuedAt, FlowType.CLINICAL, roiDigest, maskDigest, sDigest, fCommit);

        assertEquals(208, w.length);
        VerificationResult res = watermarkService.verifyWatermark(w, roiDigest, maskDigest, sDigest);
        assertTrue(res.valid());
        assertEquals(VerificationReason.CONFORME, res.reason());
        assertEquals(emissionId, res.payload().emissionId());
    }

    @Test
    void detectsModifiedRoiMaskPartitionSAndSignature() {
        UUID emissionId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Instant issuedAt = Instant.parse("2026-11-24T10:00:00Z");

        byte[] roiDigest = watermarkService.computeRoiDigest(512, 512, 16, "roi-original".getBytes(StandardCharsets.UTF_8));
        byte[] maskDigest = watermarkService.computeMaskDigest(512, 512, new byte[]{1, 0, 1, 1});
        byte[] sDigest = watermarkService.computePartitionSDigest(new byte[64]);
        byte[] fCommit = watermarkService.computeFCommit(1, emissionId, recipientId);

        byte[] w = watermarkService.generateWatermark(
                1, 100, emissionId, issuedAt, FlowType.CLINICAL, roiDigest, maskDigest, sDigest, fCommit);

        // 1. Preuve absente / tronquée
        assertEquals(VerificationReason.PREUVE_ABSENTE,
                watermarkService.verifyWatermark(new byte[100], roiDigest, maskDigest, sDigest).reason());

        // 2. Signature altérée d'1 bit
        byte[] tamperedSig = w.clone();
        tamperedSig[200] ^= 0x01;
        assertEquals(VerificationReason.SIGNATURE_INVALIDE,
                watermarkService.verifyWatermark(tamperedSig, roiDigest, maskDigest, sDigest).reason());

        // 3. Masque RONI modifié
        byte[] alteredMaskDigest = watermarkService.computeMaskDigest(512, 512, new byte[]{0, 0, 1, 1});
        assertEquals(VerificationReason.MASQUE_MODIFIE,
                watermarkService.verifyWatermark(w, roiDigest, alteredMaskDigest, sDigest).reason());

        // 4. Pixel de la ROI modifié
        byte[] alteredRoiDigest = watermarkService.computeRoiDigest(512, 512, 16, "roi-altered".getBytes(StandardCharsets.UTF_8));
        assertEquals(VerificationReason.ROI_MODIFIEE,
                watermarkService.verifyWatermark(w, alteredRoiDigest, maskDigest, sDigest).reason());

        // 5. Partition S échangée
        byte[] otherS = new byte[64];
        otherS[0] = 0x42;
        byte[] alteredSDigest = watermarkService.computePartitionSDigest(otherS);
        assertEquals(VerificationReason.PARTITION_S_MODIFIEE,
                watermarkService.verifyWatermark(w, roiDigest, maskDigest, alteredSDigest).reason());

        // 6. Clé révoquée
        registryMap.put(1, new PublicKeyStatusRegistry.KeyMetadata(
                1, 100, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"),
                PublicKeyStatusRegistry.KeyStatus.REVOKED));
        assertEquals(VerificationReason.CLE_EXPIREE_OU_REVOQUEE,
                watermarkService.verifyWatermark(w, roiDigest, maskDigest, sDigest).reason());
    }
}