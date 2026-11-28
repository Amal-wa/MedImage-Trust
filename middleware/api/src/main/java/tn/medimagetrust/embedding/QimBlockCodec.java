package tn.medimagetrust.embedding;

import static tn.medimagetrust.embedding.EmbeddingException.Reason.F_DEVIATION_EXCEEDED;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;

/**
 * Couche robuste F : DCT 8 × 8 orthonormée (type II) et QIM avec dither sur les rangs zigzag 6 à 13.
 *
 * <p>Le pas Δ est celui de la profondeur effective de l'original, Δ(B) = 12 × 2^(B − 8)
 * ({@link EmbeddingProfile#qimDelta(int)}), toujours reçu en paramètre : ce codec ne le déduit d'aucune image.
 * Insertion d'un bit {@code b} avec un dither {@code d ∈ [0, Δ)} :
 * {@code c' = Δ·round((c − d − bΔ/2) / Δ) + d + bΔ/2} ; extraction : le {@code b} dont le réseau est le plus proche
 * de {@code c} (égalité : 0), ce qui fixe la marge de décision à Δ/4. Après DCT inverse, chaque pixel est arrondi à
 * l'entier ({@code floor(x + 0,5)}), <strong>sans aucun bornage</strong> : une valeur qui sortirait de la plage
 * stockable est rendue telle quelle, et c'est la session qui décide (mode signé ou refus), jamais un écrêtage.</p>
 *
 * <p>Borne théorique : chaque coefficient bouge d'au plus Δ/2 ; la somme des valeurs absolues des 8 fonctions de base
 * en un pixel vaut au plus {@code 3·√2/8 + 5/4 ≈ 1,78} ; l'écart avant arrondi est donc ≤ 0,89 Δ (10,7 pour Δ = 12,
 * soit ≤ 11 après arrondi). Le contrôle {@code ≤ maxFDeviation(B) = Δ} est une ceinture de sécurité.</p>
 */
final class QimBlockCodec {
    private QimBlockCodec() {}

    static final int N = EmbeddingProfile.BLOCK;

    /** Positions (ligne, colonne) des rangs zigzag 6 à 13, ordre JPEG. */
    static final int[][] CARRIERS = {
            {0, 3}, {1, 2}, {2, 1}, {3, 0}, {4, 0}, {3, 1}, {2, 2}, {1, 3}
    };

    /** Matrice de la DCT-II orthonormée : {@code M[u][x] = α(u) cos((2x + 1) u π / 16)}. */
    private static final double[][] M = new double[N][N];

    static {
        for (int u = 0; u < N; u++) {
            double alpha = u == 0 ? Math.sqrt(1.0 / N) : Math.sqrt(2.0 / N);
            for (int x = 0; x < N; x++) {
                M[u][x] = alpha * Math.cos((2 * x + 1) * u * Math.PI / (2.0 * N));
            }
        }
    }

    /** DCT 2D : {@code C = M · F · Mᵀ} ; tableaux de 64 valeurs en ordre raster. */
    static double[] forward(double[] block) {
        double[] tmp = new double[N * N];
        double[] out = new double[N * N];
        for (int u = 0; u < N; u++) {          // tmp = M · F
            for (int y = 0; y < N; y++) {
                double s = 0;
                for (int x = 0; x < N; x++) s += M[u][x] * block[x * N + y];
                tmp[u * N + y] = s;
            }
        }
        for (int u = 0; u < N; u++) {          // out = tmp · Mᵀ
            for (int v = 0; v < N; v++) {
                double s = 0;
                for (int y = 0; y < N; y++) s += tmp[u * N + y] * M[v][y];
                out[u * N + v] = s;
            }
        }
        return out;
    }

    /** DCT 2D inverse : {@code F = Mᵀ · C · M}. */
    static double[] inverse(double[] coef) {
        double[] tmp = new double[N * N];
        double[] out = new double[N * N];
        for (int x = 0; x < N; x++) {          // tmp = Mᵀ · C
            for (int v = 0; v < N; v++) {
                double s = 0;
                for (int u = 0; u < N; u++) s += M[u][x] * coef[u * N + v];
                tmp[x * N + v] = s;
            }
        }
        for (int x = 0; x < N; x++) {          // out = tmp · M
            for (int y = 0; y < N; y++) {
                double s = 0;
                for (int v = 0; v < N; v++) s += tmp[x * N + v] * M[v][y];
                out[x * N + y] = s;
            }
        }
        return out;
    }

    /** Point du réseau {@code ΔZ + d + bΔ/2} le plus proche de {@code c}. */
    static double embed(double c, int bit, double dither, double delta) {
        double offset = dither + bit * delta / 2.0;
        return delta * Math.floor((c - offset) / delta + 0.5) + offset;
    }

    /** Bit dont le réseau est le plus proche de {@code c} (égalité : 0) : seuil de décision à Δ/4. */
    static int extract(double c, double dither, double delta) {
        double d0 = Math.abs(c - embed(c, 0, dither, delta));
        double d1 = Math.abs(c - embed(c, 1, dither, delta));
        return d1 < d0 ? 1 : 0;
    }

    /** Contrôle d'un vecteur de 8 dithers : non nul, longueur 8, chaque valeur finie dans {@code [0, Δ)}. */
    static double[] checkedDither(double[] dither8, double delta) {
        if (dither8 == null || dither8.length != EmbeddingProfile.F_BITS_PER_BLOCK) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        double[] d = dither8.clone();
        for (double v : d) {
            if (!(v >= 0.0 && v < delta)) { // rejette aussi NaN
                throw new EmbeddingException(INVALID_INPUT);
            }
        }
        return d;
    }

    /** Contrôle d'un vecteur de 8 bits : non nul, longueur 8. */
    static boolean[] checkedBits(boolean[] bits8) {
        if (bits8 == null || bits8.length != EmbeddingProfile.F_BITS_PER_BLOCK) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        return bits8.clone();
    }

    /**
     * Insère 8 bits dans un bloc de 64 valeurs. Ne modifie pas {@code block} : renvoie le nouveau bloc, arrondi mais
     * non borné (les valeurs peuvent sortir de la plage stockable : c'est à l'appelant d'en décider).
     *
     * @param delta        pas Δ(B)
     * @param maxDeviation écart maximal autorisé par pixel, {@link EmbeddingProfile#maxFDeviation(int)}
     * @throws EmbeddingException {@code F_DEVIATION_EXCEEDED} si un pixel s'écarterait de plus de {@code maxDeviation}
     */
    static int[] writeBlock(int[] block, boolean[] bits, double[] dither, double delta, int maxDeviation) {
        double[] f = new double[N * N];
        for (int i = 0; i < f.length; i++) f[i] = block[i];
        double[] c = forward(f);
        for (int k = 0; k < CARRIERS.length; k++) {
            int idx = CARRIERS[k][0] * N + CARRIERS[k][1];
            c[idx] = embed(c[idx], bits[k] ? 1 : 0, dither[k], delta);
        }
        double[] g = inverse(c);
        int[] out = new int[N * N];
        for (int i = 0; i < out.length; i++) {
            long v = (long) Math.floor(g[i] + 0.5);
            if (Math.abs(v - block[i]) > maxDeviation) {
                throw new EmbeddingException(F_DEVIATION_EXCEEDED);
            }
            out[i] = (int) v;
        }
        return out;
    }

    /** Lit les 8 bits d'un bloc de 64 valeurs, avec le pas Δ(B) de l'émission. */
    static int[] readBlock(int[] block, double[] dither, double delta) {
        double[] f = new double[N * N];
        for (int i = 0; i < f.length; i++) f[i] = block[i];
        double[] c = forward(f);
        int[] bits = new int[CARRIERS.length];
        for (int k = 0; k < CARRIERS.length; k++) {
            bits[k] = extract(c[CARRIERS[k][0] * N + CARRIERS[k][1]], dither[k], delta);
        }
        return bits;
    }

    /**
     * Valeurs du bloc 8 × 8 de coin ({@code top}, {@code left}) d'une image de {@code cols} colonnes, décodées selon la
     * représentation ({@link PixelImage#decode(char, boolean)}).
     */
    static int[] getBlock(char[] pixels, boolean signed, int cols, int top, int left) {
        int[] out = new int[N * N];
        for (int r = 0; r < N; r++) {
            int base = (top + r) * cols + left;
            for (int c = 0; c < N; c++) {
                out[r * N + c] = PixelImage.decode(pixels[base + c], signed);
            }
        }
        return out;
    }

    /**
     * Écrit le bloc 8 × 8 de coin ({@code top}, {@code left}), encodé selon la représentation
     * ({@link PixelImage#encode(int, boolean)}) ; l'appelant a vérifié que chaque valeur y est représentable.
     */
    static void putBlock(char[] pixels, boolean signed, int cols, int top, int left, int[] block) {
        for (int r = 0; r < N; r++) {
            int base = (top + r) * cols + left;
            for (int c = 0; c < N; c++) {
                pixels[base + c] = PixelImage.encode(block[r * N + c], signed);
            }
        }
    }
}
