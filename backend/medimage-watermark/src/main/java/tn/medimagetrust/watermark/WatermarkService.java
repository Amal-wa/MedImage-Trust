package tn.medimagetrust.watermark;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tn.medimagetrust.crypto.CryptoService;

/**
 * Service métier de génération et vérification de la preuve signée W (208 octets).
 * Conforme aux exigences SEC-D03, SEC-K03, SEC-K05 et SEC-A04.
 */
public final class WatermarkService {

    private static final byte[] ROI_LABEL = "MIT-ROI-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MASK_LABEL = "MIT-MASK-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FC_LABEL = "MIT-FC-v1".getBytes(StandardCharsets.UTF_8);

    private final CryptoService cryptoService;
    private final PublicKeyStatusRegistry keyRegistry;

    public WatermarkService(CryptoService cryptoService, PublicKeyStatusRegistry keyRegistry) {
        this.cryptoService = Objects.requireNonNull(cryptoService, "cryptoService requis");
        this.keyRegistry = Objects.requireNonNull(keyRegistry, "keyRegistry requis");
    }

    /** Calcule roiDigest = SHA-256("MIT-ROI-v1" || rows || cols || bits || roiPixelsRaster). */
    public byte[] computeRoiDigest(int rows, int cols, int bitsAllocated, byte[] roiPixelsRaster) {
        Objects.requireNonNull(roiPixelsRaster, "roiPixelsRaster requis");
        ByteBuffer buf = ByteBuffer.allocate(ROI_LABEL.length + 12 + roiPixelsRaster.length)
                .order(ByteOrder.BIG_ENDIAN);
        buf.put(ROI_LABEL);
        buf.putInt(rows);
        buf.putInt(cols);
        buf.putInt(bitsAllocated);
        buf.put(roiPixelsRaster);
        return cryptoService.digest(buf.array());
    }

    /** Calcule maskDigest = SHA-256("MIT-MASK-v1" || rows || cols || binaryRoniMask). */
    public byte[] computeMaskDigest(int rows, int cols, byte[] binaryRoniMask) {
        Objects.requireNonNull(binaryRoniMask, "binaryRoniMask requis");
        ByteBuffer buf = ByteBuffer.allocate(MASK_LABEL.length + 8 + binaryRoniMask.length)
                .order(ByteOrder.BIG_ENDIAN);
        buf.put(MASK_LABEL);
        buf.putInt(rows);
        buf.putInt(cols);
        buf.put(binaryRoniMask);
        return cryptoService.digest(buf.array());
    }

    /** Calcule sDigest = SHA-256(64 octets de la partition S). */
    public byte[] computePartitionSDigest(byte[] partitionS64) {
        if (partitionS64 == null || partitionS64.length != 64) {
            throw new IllegalArgumentException("La partition S doit faire exactement 64 octets");
        }
        return cryptoService.digest(partitionS64);
    }

    /** Calcule l'engagement fCommit sur 128 bits : HMAC(K_F, "MIT-FC-v1" || emissionId || recipientId). */
    public byte[] computeFCommit(int kid, UUID emissionId, UUID recipientId) {
        Objects.requireNonNull(emissionId, "emissionId requis");
        Objects.requireNonNull(recipientId, "recipientId requis");
        ByteBuffer msg = ByteBuffer.allocate(FC_LABEL.length + 32).order(ByteOrder.BIG_ENDIAN);
        msg.put(FC_LABEL);
        msg.putLong(emissionId.getMostSignificantBits());
        msg.putLong(emissionId.getLeastSignificantBits());
        msg.putLong(recipientId.getMostSignificantBits());
        msg.putLong(recipientId.getLeastSignificantBits());
        return cryptoService.computeFCommit(String.valueOf(kid), msg.array());
    }

    /** Génère et signe la partition W complète (208 octets). */
    public byte[] generateWatermark(
            int kid,
            int issuerId,
            UUID emissionId,
            Instant issuedAt,
            FlowType flow,
            byte[] roiDigest,
            byte[] maskDigest,
            byte[] sDigest,
            byte[] fCommit) {
        byte[] header144 = WatermarkPayload.serializeUnsignedHeader(
                kid, issuerId, emissionId, issuedAt, flow, roiDigest, maskDigest, sDigest, fCommit);
        byte[] toSign = WatermarkPayload.buildSignedInput(header144);
        byte[] signature = cryptoService.signBinary(String.valueOf(kid), toSign);

        WatermarkPayload payload = new WatermarkPayload(
                WatermarkPayload.VERSION_1, kid, issuerId, emissionId, issuedAt, flow,
                roiDigest, maskDigest, sDigest, fCommit, signature);
        return payload.toBytes();
    }

    /**
     * Vérifie la partition W extraite (208 octets) en mode fail-closed (CU06).
     */
    public VerificationResult verifyWatermark(
            byte[] extractedW,
            byte[] expectedRoiDigest,
            byte[] expectedMaskDigest,
            byte[] expectedSDigest) {
        // V3. Longueur, version, format
        final WatermarkPayload payload;
        try {
            payload = WatermarkPayload.fromBytes(extractedW);
        } catch (IllegalArgumentException e) {
            return VerificationResult.rejected(VerificationReason.PREUVE_ABSENTE);
        }

        // V4. Résolution du kid dans le registre des clés publiques
        String kidStr = payload.kidAsString();
        Optional<PublicKeyStatusRegistry.KeyMetadata> keyMetaOpt = keyRegistry.findByKid(kidStr);
        if (keyMetaOpt.isEmpty()) {
            return VerificationResult.rejected(VerificationReason.CLE_INCONNUE);
        }
        PublicKeyStatusRegistry.KeyMetadata keyMeta = keyMetaOpt.get();
        if (keyMeta.institutionId() != payload.issuerId() || !keyMeta.isValidAt(payload.issuedAt())) {
            return VerificationResult.rejected(VerificationReason.CLE_EXPIREE_OU_REVOQUEE);
        }

        // V5. Vérification de la signature Ed25519
        byte[] raw = payload.toBytes();
        byte[] header144 = java.util.Arrays.copyOfRange(raw, 0, WatermarkPayload.HEADER_SIZE);
        byte[] signedInput = WatermarkPayload.buildSignedInput(header144);
        boolean sigOk;
        try {
            sigOk = cryptoService.verifyBinary(kidStr, signedInput, payload.signature());
        } catch (RuntimeException e) {
            return VerificationResult.rejected(VerificationReason.SIGNATURE_INVALIDE);
        }
        if (!sigOk) {
            return VerificationResult.rejected(VerificationReason.SIGNATURE_INVALIDE);
        }

        // V6. Comparaison en temps constant des condensats (SEC-A04)
        if (expectedMaskDigest == null || !MessageDigest.isEqual(payload.maskDigest(), expectedMaskDigest)) {
            return VerificationResult.rejected(VerificationReason.MASQUE_MODIFIE);
        }
        if (expectedRoiDigest == null || !MessageDigest.isEqual(payload.roiDigest(), expectedRoiDigest)) {
            return VerificationResult.rejected(VerificationReason.ROI_MODIFIEE);
        }
        if (expectedSDigest == null || !MessageDigest.isEqual(payload.sDigest(), expectedSDigest)) {
            return VerificationResult.rejected(VerificationReason.PARTITION_S_MODIFIEE);
        }

        return VerificationResult.accepted(payload);
    }
}