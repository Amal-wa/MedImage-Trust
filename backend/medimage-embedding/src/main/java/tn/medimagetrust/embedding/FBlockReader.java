package tn.medimagetrust.embedding;

import java.util.Arrays;

import static tn.medimagetrust.embedding.EmbeddingException.Reason.ILLEGAL_STATE;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;

/**
 * Lecture seule de la classe F sur une copie suspecte, avec la géométrie des partitions de l'émission.
 *
 * <p>Les blocs sont repérés par leur position (ligne, colonne) dans l'image d'origine. Si la copie est plus petite
 * (recadrage à droite ou en bas) et qu'un bloc en sort, {@link #read(int, double[])} renvoie
 * {@link FBlockChannel#UNREADABLE} pour ses 8 bits. Un recadrage à gauche ou en haut décale la grille : il n'est pas
 * compensé ici. {@link #write(int, boolean[], double[])} est toujours refusé.</p>
 */
public final class FBlockReader implements FBlockChannel {
    private final char[] pixels;
    private final int rows;
    private final int cols;
    private final Partitions partitions;

    private FBlockReader(PixelImage copy, Partitions partitions) {
        this.pixels = copy.view(); // lecture seule d'une image immuable : pas de copie

        this.rows = copy.rows();
        this.cols = copy.cols();
        this.partitions = partitions;
    }

    /**
     * @param copy       copie suspecte (éventuellement recadrée)
     * @param partitions partitions de l'émission d'origine
     * @throws EmbeddingException {@code INVALID_INPUT} si un argument est nul
     */
    public static FBlockReader of(PixelImage copy, Partitions partitions) {
        if (copy == null || partitions == null) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        return new FBlockReader(copy, partitions);
    }

    @Override
    public int classSize() {
        return partitions.fClassSize();
    }

    /** Toujours refusé : ce canal est en lecture seule. */
    @Override
    public void write(int fIndex, boolean[] bits8, double[] dither8) {
        throw new EmbeddingException(ILLEGAL_STATE);
    }

    @Override
    public int[] read(int fIndex, double[] dither8) {
        if (fIndex < 0 || fIndex >= partitions.fClassSize()) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        double[] dither = QimBlockCodec.checkedDither(dither8);
        int block = partitions.fBlock(fIndex);
        int top = partitions.blockTop(block);
        int left = partitions.blockLeft(block);
        if (top + EmbeddingProfile.BLOCK > rows || left + EmbeddingProfile.BLOCK > cols) {
            int[] out = new int[EmbeddingProfile.F_BITS_PER_BLOCK];
            Arrays.fill(out, UNREADABLE);
            return out;
        }
        return QimBlockCodec.readBlock(QimBlockCodec.getBlock(pixels, cols, top, left), dither);
    }
}
