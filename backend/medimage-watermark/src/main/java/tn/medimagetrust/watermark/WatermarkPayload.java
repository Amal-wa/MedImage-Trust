package tn.medimagetrust.watermark;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Représentation binaire de la partition W (208 octets, Big-Endian) conforme à la section 8.3.
 */
public final class WatermarkPayload {

    public static final byte VERSION_1 = 0x01;
    public static final int HEADER_SIZE = 144;
    public static final int SIGNATURE_SIZE = 64;
    public static final int TOTAL_SIZE = 208;
    public static final byte[] DOMAIN_LABEL = "MIT-W-v1".getBytes(StandardCharsets.UTF_8);

    private final byte version;
    private final int kid; // unsigned 16-bit (0..65535)
    private final int issuerId;
    private final UUID emissionId;
    private final Instant issuedAt;
    private final FlowType flow;
    private final byte[] roiDigest;   // 32 octets
    private final byte[] maskDigest;  // 32 octets
    private final byte[] sDigest;     // 32 octets
    private final byte[] fCommit;     // 16 octets
    private final byte[] signature;   // 64 octets

    public WatermarkPayload(
            byte version,
            int kid,
            int issuerId,
            UUID emissionId,
            Instant issuedAt,
            FlowType flow,
            byte[] roiDigest,
            byte[] maskDigest,
            byte[] sDigest,
            byte[] fCommit,
            byte[] signature) {
        if (version != VERSION_1) {
            throw new IllegalArgumentException("Version de watermark non supportée: " + version);
        }
        if (kid < 0 || kid > 0xFFFF) {
            throw new IllegalArgumentException("kid hors plage uint16: " + kid);
        }
        this.version = version;
        this.kid = kid;
        this.issuerId = issuerId;
        this.emissionId = Objects.requireNonNull(emissionId, "emissionId requis");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt requis");
        this.flow = Objects.requireNonNull(flow, "flow requis");
        this.roiDigest = requireLength(roiDigest, 32, "roiDigest");
        this.maskDigest = requireLength(maskDigest, 32, "maskDigest");
        this.sDigest = requireLength(sDigest, 32, "sDigest");
        this.fCommit = requireLength(fCommit, 16, "fCommit");
        this.signature = requireLength(signature, SIGNATURE_SIZE, "signature");
    }

    /** Sérialise les 144 premiers octets (offsets 0 à 143) en Big-Endian. */
    public static byte[] serializeUnsignedHeader(
            int kid,
            int issuerId,
            UUID emissionId,
            Instant issuedAt,
            FlowType flow,
            byte[] roiDigest,
            byte[] maskDigest,
            byte[] sDigest,
            byte[] fCommit) {
        requireLength(roiDigest, 32, "roiDigest");
        requireLength(maskDigest, 32, "maskDigest");
        requireLength(sDigest, 32, "sDigest");
        requireLength(fCommit, 16, "fCommit");

        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.put(VERSION_1);                                      // offset 0 (1 o)
        buf.putShort((short) (kid & 0xFFFF));                    // offset 1 (2 o)
        buf.putInt(issuerId);                                    // offset 3 (4 o)
        buf.putLong(emissionId.getMostSignificantBits());        // offset 7 (8 o)
        buf.putLong(emissionId.getLeastSignificantBits());       // offset 15 (8 o) -> 16 o total
        buf.putLong(issuedAt.getEpochSecond());                  // offset 23 (8 o)
        buf.put(flow.code());                                    // offset 31 (1 o)
        buf.put(roiDigest);                                      // offset 32 (32 o)
        buf.put(maskDigest);                                     // offset 64 (32 o)
        buf.put(sDigest);                                        // offset 96 (32 o)
        buf.put(fCommit);                                        // offset 128 (16 o)
        return buf.array();
    }

    /** Construit le message exact à signer par Ed25519 : "MIT-W-v1" || 0x00 || header144. */
    public static byte[] buildSignedInput(byte[] header144) {
        requireLength(header144, HEADER_SIZE, "header144");
        ByteBuffer msg = ByteBuffer.allocate(DOMAIN_LABEL.length + 1 + HEADER_SIZE);
        msg.put(DOMAIN_LABEL);
        msg.put((byte) 0x00);
        msg.put(header144);
        return msg.array();
    }

    /** Sérialise la partition W complète (208 octets). */
    public byte[] toBytes() {
        byte[] header = serializeUnsignedHeader(
                kid, issuerId, emissionId, issuedAt, flow, roiDigest, maskDigest, sDigest, fCommit);
        ByteBuffer full = ByteBuffer.allocate(TOTAL_SIZE).order(ByteOrder.BIG_ENDIAN);
        full.put(header);
        full.put(signature);
        return full.array();
    }

    /** Désérialise 208 octets bruts en WatermarkPayload. */
    public static WatermarkPayload fromBytes(byte[] raw) {
        if (raw == null || raw.length != TOTAL_SIZE) {
            throw new IllegalArgumentException("Longueur de partition W invalide (attendu 208 octets)");
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        byte version = buf.get();
        if (version != VERSION_1) {
            throw new IllegalArgumentException("Version de watermark inconnue: " + version);
        }
        int kid = Short.toUnsignedInt(buf.getShort());
        int issuerId = buf.getInt();
        long msb = buf.getLong();
        long lsb = buf.getLong();
        UUID emissionId = new UUID(msb, lsb);
        Instant issuedAt = Instant.ofEpochSecond(buf.getLong());
        FlowType flow = FlowType.fromCode(buf.get());

        byte[] roiDigest = new byte[32];
        buf.get(roiDigest);
        byte[] maskDigest = new byte[32];
        buf.get(maskDigest);
        byte[] sDigest = new byte[32];
        buf.get(sDigest);
        byte[] fCommit = new byte[16];
        buf.get(fCommit);
        byte[] signature = new byte[SIGNATURE_SIZE];
        buf.get(signature);

        return new WatermarkPayload(
                version, kid, issuerId, emissionId, issuedAt, flow,
                roiDigest, maskDigest, sDigest, fCommit, signature);
    }

    private static byte[] requireLength(byte[] arr, int expected, String name) {
        if (arr == null || arr.length != expected) {
            throw new IllegalArgumentException(name + " doit faire exactement " + expected + " octets");
        }
        return Arrays.copyOf(arr, arr.length);
    }

    public byte version() { return version; }
    public int kid() { return kid; }
    public String kidAsString() { return String.valueOf(kid); }
    public int issuerId() { return issuerId; }
    public UUID emissionId() { return emissionId; }
    public Instant issuedAt() { return issuedAt; }
    public FlowType flow() { return flow; }
    public byte[] roiDigest() { return Arrays.copyOf(roiDigest, roiDigest.length); }
    public byte[] maskDigest() { return Arrays.copyOf(maskDigest, maskDigest.length); }
    public byte[] sDigest() { return Arrays.copyOf(sDigest, sDigest.length); }
    public byte[] fCommit() { return Arrays.copyOf(fCommit, fCommit.length); }
    public byte[] signature() { return Arrays.copyOf(signature, signature.length); }
}