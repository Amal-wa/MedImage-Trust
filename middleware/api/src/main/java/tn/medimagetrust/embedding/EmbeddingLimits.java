package tn.medimagetrust.embedding;

import static tn.medimagetrust.embedding.EmbeddingException.Reason.IMAGE_TOO_LARGE;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;

/**
 * Contrôle des dimensions <strong>avant toute allocation</strong> (protection contre les bombes de décodage).
 *
 * <p>Le module {@code medimage-dicom} appelle {@link #checkDimensions(int, int, int)} juste après avoir lu les
 * dimensions dans l'en-tête, avant de décoder les pixels. Le constructeur de {@link PixelImage} l'appelle aussi en
 * premier. Le contrôle est en temps constant et n'alloue rien.</p>
 */
public final class EmbeddingLimits {
    private EmbeddingLimits() {}

    /**
     * Vérifie les dimensions et la profondeur annoncées.
     *
     * @param rows       nombre de lignes annoncé
     * @param cols       nombre de colonnes annoncé
     * @param bitsStored profondeur annoncée ({@code BitsStored})
     * @throws EmbeddingException {@code INVALID_INPUT} si {@code rows} ou {@code cols} ≤ 0 ou si {@code bitsStored}
     *                            est hors de [8, 16] ; {@code IMAGE_TOO_LARGE} si une dimension dépasse 8 192 ou si
     *                            {@code rows × cols} dépasse 36 000 000 (calcul en {@code long}, sans débordement)
     */
    public static void checkDimensions(int rows, int cols, int bitsStored) {
        if (rows <= 0 || cols <= 0) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        if (rows > EmbeddingProfile.MAX_DIMENSION || cols > EmbeddingProfile.MAX_DIMENSION) {
            throw new EmbeddingException(IMAGE_TOO_LARGE);
        }
        if ((long) rows * (long) cols > EmbeddingProfile.MAX_PIXELS) {
            throw new EmbeddingException(IMAGE_TOO_LARGE);
        }
        if (bitsStored < EmbeddingProfile.MIN_BITS_STORED || bitsStored > EmbeddingProfile.MAX_BITS_STORED) {
            throw new EmbeddingException(INVALID_INPUT);
        }
    }
}
