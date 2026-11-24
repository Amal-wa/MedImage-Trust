package tn.medimagetrust.crypto;

/**
 * Identifiant de version de clé ({@code kid}) sur 16 bits, comme dans les charges S et W et les ancrages du journal
 * (dossier v2.4, §8.3) : un entier dans {@code [0, 65 535]}, codé sur 2 octets gros-boutiste.
 *
 * <p>Toute valeur hors bornes lève une {@link IllegalArgumentException} dont le message ne contient pas la valeur.</p>
 */
public final class Kid {

    /** Plus petit kid. */
    public static final int MIN = 0;
    /** Plus grand kid. */
    public static final int MAX = 0xFFFF;
    /** Taille de l'encodage, en octets. */
    public static final int BYTES = 2;

    private Kid() {
    }

    /** Vérifie que {@code kid} est dans {@code [0, 65 535]} et le renvoie. */
    public static int check(int kid) {
        if (kid < MIN || kid > MAX) {
            throw new IllegalArgumentException("kid hors limites");
        }
        return kid;
    }

    /** Encode le kid sur 2 octets, gros-boutiste. */
    public static byte[] toBytes(int kid) {
        check(kid);
        return new byte[] {(byte) (kid >>> 8), (byte) kid};
    }

    /** Lit un kid de 2 octets gros-boutiste à la position {@code offset}. */
    public static int fromBytes(byte[] bytes, int offset) {
        if (bytes == null || offset < 0 || offset > bytes.length - BYTES) {
            throw new IllegalArgumentException("kid illisible");
        }
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }
}
