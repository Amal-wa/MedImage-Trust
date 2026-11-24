package tn.medimagetrust.embedding;

import static tn.medimagetrust.embedding.EmbeddingException.Reason.ILLEGAL_STATE;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;

/**
 * Scellement fragile S et W : substitution du bit 0, {@code p = (p & ~1) | bit} (écart d'au plus 1).
 *
 * <p>S occupe les 8 blocs S (64 octets), W les 26 blocs W (208 octets). Ordre des bits : blocs dans l'ordre de la
 * partition, pixels en raster dans le bloc, bit de poids fort de chaque octet d'abord.</p>
 *
 * <p>Une instance publique ({@link #reader(PixelImage, Partitions)}) est en lecture seule. L'écriture n'est possible
 * que depuis une {@link EmbeddingSession}, qui impose l'ordre F, puis S, puis W.</p>
 */
public final class LsbChannel {
    private final int[] pixels;
    private final Partitions partitions;
    private final boolean writable;

    /** Canal sur un tableau de travail ; {@code writable} seulement pour la session. */
    LsbChannel(int[] pixels, Partitions partitions, boolean writable) {
        this.pixels = pixels;
        this.partitions = partitions;
        this.writable = writable;
    }

    /**
     * Canal en lecture seule sur une image marquée (copie des pixels).
     *
     * @throws EmbeddingException {@code INVALID_INPUT} si l'image ou les partitions sont nulles, ou si les dimensions
     *                            ne correspondent pas
     */
    public static LsbChannel reader(PixelImage image, Partitions partitions) {
        if (image == null || partitions == null || image.rows() != partitions.rows()
                || image.cols() != partitions.cols()) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        return new LsbChannel(image.pixels(), partitions, false);
    }

    /** Écrit les 64 octets de S. */
    public void writeS(byte[] s) {
        write(s, EmbeddingProfile.S_BYTES, true);
    }

    /** Lit les 64 octets de S. */
    public byte[] readS() {
        return read(EmbeddingProfile.S_BYTES, true);
    }

    /** Écrit les 208 octets de W. */
    public void writeW(byte[] w) {
        write(w, EmbeddingProfile.W_BYTES, false);
    }

    /** Lit les 208 octets de W. */
    public byte[] readW() {
        return read(EmbeddingProfile.W_BYTES, false);
    }

    private void write(byte[] payload, int expected, boolean s) {
        if (!writable) {
            throw new EmbeddingException(ILLEGAL_STATE);
        }
        if (payload == null || payload.length != expected) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        byte[] copy = payload.clone();
        int cols = partitions.cols();
        for (int k = 0; k < expected * 8; k++) {
            int bit = (copy[k >>> 3] >>> (7 - (k & 7))) & 1;
            int i = pixelIndex(k, s, cols);
            pixels[i] = (pixels[i] & ~1) | bit;
        }
    }

    private byte[] read(int length, boolean s) {
        byte[] out = new byte[length];
        int cols = partitions.cols();
        for (int k = 0; k < length * 8; k++) {
            int bit = pixels[pixelIndex(k, s, cols)] & 1;
            out[k >>> 3] |= (byte) (bit << (7 - (k & 7)));
        }
        return out;
    }

    /** Indice raster du pixel portant le bit {@code k} de la partition. */
    private int pixelIndex(int k, boolean s, int cols) {
        int blockOrdinal = k / EmbeddingProfile.BLOCK_PIXELS;
        int inBlock = k % EmbeddingProfile.BLOCK_PIXELS;
        int block = s ? partitions.sBlock(blockOrdinal) : partitions.wBlock(blockOrdinal);
        int r = partitions.blockTop(block) + inBlock / EmbeddingProfile.BLOCK;
        int c = partitions.blockLeft(block) + inBlock % EmbeddingProfile.BLOCK;
        return r * cols + c;
    }
}
