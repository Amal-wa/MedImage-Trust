package tn.medimagetrust.embedding;

import java.util.Arrays;

import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;

/**
 * Résultat immuable de la détection de la RONI (profil d'incorporation v2).
 *
 * <p>Contient le seuil {@code T}, la polarité du fond, la liste ordonnée (raster) des indices de blocs 8 × 8 de la
 * RONI et le masque binaire par pixel (pixels des blocs de la RONI). L'indice d'un bloc est
 * {@code blockRow × blockCols + blockCol}, avec {@code blockCols = cols / 8} (blocs complets seulement). La ROI est le
 * complément exact de ce masque.</p>
 */
public final class RoniProfile {
    private final int rows;
    private final int cols;
    private final int threshold;
    private final BackgroundPolarity polarity;
    private final int[] roniBlocks;
    private final long[] maskBits;
    private final int roniPixelCount;

    RoniProfile(int rows, int cols, int threshold, BackgroundPolarity polarity, int[] roniBlocks) {
        this.rows = rows;
        this.cols = cols;
        this.threshold = threshold;
        this.polarity = polarity;
        this.roniBlocks = roniBlocks.clone();
        int blockCols = cols / EmbeddingProfile.BLOCK;
        long[] bits = new long[(int) (((long) rows * cols + 63) / 64)];
        for (int b : this.roniBlocks) {
            int r0 = (b / blockCols) * EmbeddingProfile.BLOCK;
            int c0 = (b % blockCols) * EmbeddingProfile.BLOCK;
            for (int dr = 0; dr < EmbeddingProfile.BLOCK; dr++) {
                int base = (r0 + dr) * cols + c0;
                for (int dc = 0; dc < EmbeddingProfile.BLOCK; dc++) {
                    int i = base + dc;
                    bits[i >>> 6] |= 1L << (i & 63);
                }
            }
        }
        this.maskBits = bits;
        this.roniPixelCount = this.roniBlocks.length * EmbeddingProfile.BLOCK_PIXELS;
    }

    /** Version du profil qui a produit ce masque. */
    public int profileVersion() { return EmbeddingProfile.PROFILE_VERSION; }

    /** Lignes de l'image source. */
    public int rows() { return rows; }

    /** Colonnes de l'image source. */
    public int cols() { return cols; }

    /** Seuil d'Otsu {@code T} sur {@code q = pixel >> 2}. */
    public int threshold() { return threshold; }

    /** Polarité du fond. */
    public BackgroundPolarity polarity() { return polarity; }

    /** Nombre de lignes de blocs complets. */
    public int blockRows() { return rows / EmbeddingProfile.BLOCK; }

    /** Nombre de colonnes de blocs complets. */
    public int blockCols() { return cols / EmbeddingProfile.BLOCK; }

    /** Nombre de blocs de la RONI. */
    public int roniBlockCount() { return roniBlocks.length; }

    /** Indices des blocs de la RONI, en ordre raster (copie). */
    public int[] roniBlocks() { return roniBlocks.clone(); }

    int roniBlock(int i) { return roniBlocks[i]; }

    /** Nombre de pixels de la RONI. */
    public int roniPixelCount() { return roniPixelCount; }

    /** Nombre de pixels de la ROI (tout ce qui n'est pas RONI). */
    public int roiPixelCount() { return rows * cols - roniPixelCount; }

    /** Le pixel d'indice raster {@code index} est-il dans la RONI ? */
    public boolean inRoni(int index) {
        if (index < 0 || index >= rows * cols) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        return inRoniUnchecked(index);
    }

    /** Le pixel ({@code row}, {@code col}) est-il dans la RONI ? */
    public boolean inRoni(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        return inRoniUnchecked(row * cols + col);
    }

    boolean inRoniUnchecked(int index) {
        return (maskBits[index >>> 6] & (1L << (index & 63))) != 0;
    }

    /**
     * Masque binaire par pixel, compacté 8 pixels par octet en ordre raster, bit de poids fort d'abord, dernier octet
     * complété par des zéros (encodage de {@code maskDigest}). Copie.
     */
    public byte[] packedMask() {
        int n = rows * cols;
        byte[] out = new byte[(n + 7) / 8];
        for (int i = 0; i < n; i++) {
            if (inRoniUnchecked(i)) {
                out[i >>> 3] |= (byte) (0x80 >>> (i & 7));
            }
        }
        return out;
    }

    /** Même géométrie et même masque par pixel (le seuil et la polarité peuvent différer). */
    public boolean sameMask(RoniProfile other) {
        return other != null && rows == other.rows && cols == other.cols && Arrays.equals(maskBits, other.maskBits);
    }

    /** Aucune valeur de l'image n'est imprimée. */
    @Override
    public String toString() {
        return "RoniProfile[contenu masqué]";
    }
}
