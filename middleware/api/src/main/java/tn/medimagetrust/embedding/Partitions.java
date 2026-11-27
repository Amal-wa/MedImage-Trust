package tn.medimagetrust.embedding;

import java.util.Arrays;

import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.TOO_FEW_RONI_BLOCKS;

/**
 * Découpage de la RONI en partitions disjointes, blocs pris en ordre raster : les 26 premiers pour W, les 8 suivants
 * pour S, tous les autres pour la classe F (au moins 256 blocs).
 *
 * <p>Ordre des bits dans W et S : les blocs se suivent dans l'ordre de la partition ; dans un bloc, les pixels vont
 * en raster (ligne par ligne) ; dans un octet, le bit de poids fort est écrit en premier.</p>
 */
public final class Partitions {
    private final int rows;
    private final int cols;
    private final int[] w;
    private final int[] s;
    private final int[] f;

    private Partitions(int rows, int cols, int[] w, int[] s, int[] f) {
        this.rows = rows;
        this.cols = cols;
        this.w = w;
        this.s = s;
        this.f = f;
    }

    /**
     * @throws EmbeddingException {@code INVALID_INPUT} si le profil est nul ; {@code TOO_FEW_RONI_BLOCKS} si la RONI
     *                            compte moins de 290 blocs
     */
    public static Partitions of(RoniProfile profile) {
        if (profile == null) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        int[] all = profile.roniBlocks();
        if (all.length < EmbeddingProfile.MIN_RONI_BLOCKS) {
            throw new EmbeddingException(TOO_FEW_RONI_BLOCKS);
        }
        int w = EmbeddingProfile.W_BLOCKS;
        int s = EmbeddingProfile.S_BLOCKS;
        return new Partitions(profile.rows(), profile.cols(), Arrays.copyOfRange(all, 0, w),
                Arrays.copyOfRange(all, w, w + s), Arrays.copyOfRange(all, w + s, all.length));
    }

    /** Lignes de l'image d'origine. */
    public int rows() { return rows; }

    /** Colonnes de l'image d'origine. */
    public int cols() { return cols; }

    /** Colonnes de blocs complets de l'image d'origine. */
    public int blockCols() { return cols / EmbeddingProfile.BLOCK; }

    /** Blocs W, en ordre raster (copie). */
    public int[] wBlocks() { return w.clone(); }

    /** Blocs S, en ordre raster (copie). */
    public int[] sBlocks() { return s.clone(); }

    /** Blocs de la classe F, en ordre raster (copie) ; l'indice F {@code k} désigne {@code fBlocks()[k]}. */
    public int[] fBlocks() { return f.clone(); }

    /** Nombre de blocs de la classe F. */
    public int fClassSize() { return f.length; }

    int wBlock(int i) { return w[i]; }

    int sBlock(int i) { return s[i]; }

    int fBlock(int i) { return f[i]; }

    /** Ligne du premier pixel du bloc d'indice {@code blockIndex}. */
    int blockTop(int blockIndex) { return (blockIndex / blockCols()) * EmbeddingProfile.BLOCK; }

    /** Colonne du premier pixel du bloc d'indice {@code blockIndex}. */
    int blockLeft(int blockIndex) { return (blockIndex % blockCols()) * EmbeddingProfile.BLOCK; }

    @Override
    public String toString() {
        return "Partitions[contenu masqué]";
    }
}
