package tn.medimagetrust.fingerprint;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Construction non ambiguë des messages HMAC : étiquette ‖ 0x00 ‖ (longueur 4 octets ‖ champ)*. */
final class Messages {
    private Messages() {}

    static byte[] build(String label, byte[]... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] l = label.getBytes(StandardCharsets.UTF_8);
        out.write(l, 0, l.length);
        out.write(0x00);
        for (byte[] f : fields) {
            byte[] len = i32(f.length);
            out.write(len, 0, 4);
            out.write(f, 0, f.length);
        }
        return out.toByteArray();
    }

    static byte[] i32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    static byte[] u64(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) { b[i] = (byte) v; v >>>= 8; }
        return b;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Identifiant d'image / d'émission : UUID sur 16 octets. */
    public static byte[] uuidBytes(UUID id) {
        return concat(u64(id.getMostSignificantBits()), u64(id.getLeastSignificantBits()));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** 53 bits de poids fort d'un bloc HMAC -> uniforme dans [0,1). */
    static double unitDouble(byte[] block) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (block[i] & 0xFFL);
        return (v >>> 11) * 0x1.0p-53;
    }
}
