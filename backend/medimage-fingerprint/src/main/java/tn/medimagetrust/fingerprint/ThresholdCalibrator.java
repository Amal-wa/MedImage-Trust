package tn.medimagetrust.fingerprint;

/**
 * Seuil d'accusation.
 * Pour un innocent, chaque symbole lisible contribue avec une espérance 0 et une variance 1 :
 * le score est donc d'écart-type sqrt(n) (n = symboles lisibles). On approxime la queue par une loi normale
 * et on corrige par un coefficient de sécurité {@link #SAFETY_MARGIN} validé par la simulation Monte-Carlo
 * ({@link CollusionSimulator}) : la longueur 1 024 est plus courte que la longueur théorique de Tardos,
 * donc le seuil est empirique et à documenter dans le rapport.
 */
public final class ThresholdCalibrator {
    private ThresholdCalibrator() {}

    /** Coefficient de sécurité sur l'écart-type (queues plus lourdes que la loi normale). */
    public static final double SAFETY_MARGIN = 1.15; // mesuré : 0 dépassement sur 3 millions d innocents (marge 1,0 : 6e-6 par candidat)

    /**
     * @param readable   symboles non effacés
     * @param candidates nombre de destinataires comparés (<= 64)
     * @param epsilon    probabilité maximale d'accuser au moins un innocent
     */
    public static double threshold(int readable, int candidates, double epsilon) {
        double perCandidate = epsilon / Math.max(1, candidates); // borne de l'union
        return SAFETY_MARGIN * inverseNormalTail(perCandidate) * Math.sqrt(readable);
    }

    public static double threshold(int readable, int candidates) {
        return threshold(readable, candidates, FingerprintParams.TARGET_FALSE_ACCUSATION);
    }

    /** z tel que P(N(0,1) > z) = q (approximation d'Acklam, erreur relative ~1e-9). */
    public static double inverseNormalTail(double q) {
        if (q <= 0 || q >= 1) throw new IllegalArgumentException("q hors de ]0,1[");
        return -inverseNormalCdf(q);
    }

    private static double inverseNormalCdf(double p) {
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
                3.754408661907416e+00};
        double plow = 0.02425, phigh = 1 - plow;
        if (p < plow) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > phigh) {
            double q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        double q = p - 0.5, r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }
}
