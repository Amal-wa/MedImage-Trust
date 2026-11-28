package tn.medimagetrust.embedding;

import java.util.Arrays;

import static tn.medimagetrust.embedding.EmbeddingException.Reason.ILLEGAL_STATE;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;

/**
 * Lecture seule de la classe F sur une copie suspecte, avec la géométrie des partitions de l'émission et la profondeur
 * effective B enregistrée dans le registre.
 *
 * <p><strong>Δ vient du registre, jamais de la copie.</strong> La copie analysée peut avoir des attributs modifiés
 * (un attaquant change {@code BitsStored}), et une copie signée porte {@code BitsStored = 16} : seul
 * {@link EmbeddingResult#bitsStored()}, enregistré à l'émission, donne le bon pas Δ(B). Un mauvais Δ rend les bits
 * illisibles et fait échouer l'attribution.</p>
 *
 * <p>Les blocs sont repérés par leur position (ligne, colonne) dans l'image d'origine. Si la copie est plus petite
 * (recadrage à droite ou en bas) et qu'un bloc en sort, {@link #read(int, double[])} renvoie
 * {@link FBlockChannel#UNREADABLE} pour ses 8 bits. Un recadrage à gauche ou en haut décale la grille : il n'est pas
 * compensé ici. {@link #write(int, boolean[], double[])} est toujours refusé.</p>
 */
public final class FBlockReader implements FBlockChannel {
    private final char[] pixels;
    private final boolean signed;
    private final int rows;
    private final int cols;
    private final Partitions partitions;
    private final double delta;

    private FBlockReader(PixelImage copy, Partitions partitions, double delta) {
        this.pixels = copy.view(); // lecture seule d'une image immuable : pas de copie
        this.signed = copy.signed();
        this.rows = copy.rows();
        this.cols = copy.cols();
        this.partitions = partitions;
        this.delta = delta;
    }

    /**
     * @param copy               copie suspecte (éventuellement recadrée, éventuellement signée)
     * @param partitions         partitions de l'émission d'origine
     * @param emissionBitsStored profondeur effective B de l'émission, lue dans le registre (jamais dans la copie)
     * @throws EmbeddingException {@code INVALID_INPUT} si un argument est nul ou si B est hors de {@code [8, 16]}
     */
    public static FBlockReader of(PixelImage copy, Partitions partitions, int emissionBitsStored) {
        if (copy == null || partitions == null) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        return new FBlockReader(copy, partitions, EmbeddingProfile.qimDelta(emissionBitsStored));
    }

    @Override
    public int classSize() {
        return partitions.fClassSize();
    }

    @Override
    public double qimDelta() {
        return delta;
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
        double[] dither = QimBlockCodec.checkedDither(dither8, delta);
        int block = partitions.fBlock(fIndex);
        int top = partitions.blockTop(block);
        int left = partitions.blockLeft(block);
        if (top + EmbeddingProfile.BLOCK > rows || left + EmbeddingProfile.BLOCK > cols) {
            int[] out = new int[EmbeddingProfile.F_BITS_PER_BLOCK];
            Arrays.fill(out, UNREADABLE);
            return out;
        }
        return QimBlockCodec.readBlock(QimBlockCodec.getBlock(pixels, signed, cols, top, left), dither, delta);
    }
}
