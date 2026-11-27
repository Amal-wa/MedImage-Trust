package tn.medimagetrust.embedding;

/**
 * Canal de la classe F exposé au module fingerprint : 8 bits par bloc, par QIM sur les coefficients DCT de rangs
 * zigzag 6 à 13 (pas Δ = 12).
 *
 * <p>Ce module ne connaît aucune clé : les 8 dithers de chaque bloc, dans {@code [0, 12)}, sont dérivés de K_F par
 * l'appelant. L'appelant choisit aussi les 128 blocs utilisés parmi la classe F (positions secrètes) ; les blocs non
 * utilisés ne sont pas modifiés. L'indice {@code fIndex} va de 0 à {@code classSize() - 1}, dans l'ordre raster des
 * blocs de la classe F.</p>
 */
public interface FBlockChannel {
    /** Bit illisible : le bloc sort de l'image (copie recadrée). */
    int UNREADABLE = -1;

    /** Nombre de blocs de la classe F. */
    int classSize();

    /**
     * Écrit 8 bits dans le bloc F {@code fIndex}.
     *
     * @throws EmbeddingException {@code INVALID_INPUT} (indice hors limites, tableau nul ou de longueur ≠ 8, dither
     *                            hors de {@code [0, 12)}) ; {@code F_DEVIATION_EXCEEDED} ; {@code ILLEGAL_STATE}
     */
    void write(int fIndex, boolean[] bits8, double[] dither8);

    /**
     * Lit les 8 bits du bloc F {@code fIndex}.
     *
     * @return 8 valeurs : 0, 1, ou {@link #UNREADABLE} pour les 8 si le bloc sort de l'image
     * @throws EmbeddingException {@code INVALID_INPUT} (indice hors limites, dither invalide) ; {@code ILLEGAL_STATE}
     */
    int[] read(int fIndex, double[] dither8);
}
