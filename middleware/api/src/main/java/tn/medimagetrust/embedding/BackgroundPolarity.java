package tn.medimagetrust.embedding;

/** Polarité du fond, déduite de la majorité des pixels du bord (égalité : fond sombre). */
public enum BackgroundPolarity {
    /** Fond sombre : un pixel est « fond » si {@code q ≤ T}. */
    DARK,
    /** Fond clair : un pixel est « fond » si {@code q > T}. */
    LIGHT;

    /** Le niveau {@code q} appartient-il au fond pour le seuil {@code threshold} ? */
    boolean isBackground(int q, int threshold) {
        return this == DARK ? q <= threshold : q > threshold;
    }

    /** Distance au seuil, comptée positivement du côté du fond. */
    int guardDistance(int q, int threshold) {
        return this == DARK ? threshold - q : q - threshold;
    }
}
