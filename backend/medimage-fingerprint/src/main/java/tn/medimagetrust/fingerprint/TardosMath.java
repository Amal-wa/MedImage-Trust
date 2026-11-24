package tn.medimagetrust.fingerprint;

/** Mathématiques du code de Tardos symétrique (Škorić et al.), alphabet binaire. */
public final class TardosMath {
    private TardosMath() {}

    /**
     * Biais p = sin²(r), r uniforme dans [t', π/2 − t'] avec sin²(t') = t (loi à coupure de Tardos).
     * Donc p appartient à [t, 1 − t].
     *
     * @param u uniforme dans [0,1)
     * @param t coupure (1 / (300 c))
     */
    public static double biasFromUniform(double u, double t) {
        double tp = Math.asin(Math.sqrt(t));
        double r = tp + u * (Math.PI / 2 - 2 * tp);
        double s = Math.sin(r);
        return s * s;
    }

    /**
     * Contribution d'un symbole au score d'accusation (version symétrique).
     *
     * @param y symbole de la copie pirate : 0, 1 ou -1 (effacement)
     * @param x symbole du destinataire : 0 ou 1
     * @param p biais du symbole
     */
    public static double scoreTerm(int y, int x, double p) {
        if (y < 0) return 0.0; // effacement : aucune information
        double a = Math.sqrt((1 - p) / p);
        double b = Math.sqrt(p / (1 - p));
        if (y == 1) return x == 1 ? a : -b;
        return x == 0 ? b : -a;
    }
}
