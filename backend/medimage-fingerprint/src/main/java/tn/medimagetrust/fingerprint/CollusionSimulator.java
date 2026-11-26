package tn.medimagetrust.fingerprint;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Simulation Monte-Carlo de collusions de 2 destinataires (SEC-D04, T-SEC-D04), modèle v2.4.
 *
 * <p>Travaille au niveau des symboles avec les mêmes lois que {@link TardosCodeGenerator}, mais avec un générateur
 * pseudo-aléatoire germé (reproductible) : la voie PRF coûterait des centaines de millions de HMAC. Les 1 024
 * symboles sont regroupés comme dans F : le symbole {@code i} appartient au bloc {@code i / 8} (128 blocs). La voie
 * PRF complète, avec la vraie QIM, est couverte par {@code EndToEndFingerprintTest} et
 * {@code EmbeddingIntegrationTest}.</p>
 *
 * <p>Dégradations simulées après la collusion : effacement de blocs entiers (recadrage, {@code blockErasureRate})
 * et erreurs de lecture QIM indépendantes par symbole lisible (bruit, recompression, {@code bitErrorRate}).</p>
 */
public final class CollusionSimulator {

    /**
     * Stratégies de collusion.
     *
     * <p>Supprimée par rapport à la v2.3 : {@code INTERLEAVE} (séries de 64 symboles prises alternativement chez
     * chaque complice). En v2.4 l'ordre des symboles est caché derrière des blocs tirés secrètement : les complices
     * ne peuvent pas découper le code en séries de symboles. Le découpage qu'ils peuvent réellement faire, par zones
     * de l'image, revient à choisir chaque bloc chez l'un ou l'autre : c'est {@link #BLOCK_DRAW}.</p>
     */
    public enum Strategy {
        /** Mélange symbole par symbole : à chaque symbole, les complices en prennent un au hasard. */
        DRAW,
        /**
         * Min/max : sortie du minimum (0) ou du maximum (1) de chaque symbole, choisi par essai. Suppose que les
         * complices lisent les bits, ce qui est impossible sans K_F (dither secret) : gardée comme cas pessimiste.
         */
        MIN_MAX,
        /** Bloc entier : chacun des 128 blocs (8 symboles) est pris tel quel chez l'un ou l'autre complice. */
        BLOCK_DRAW
    }

    /**
     * @param blockErasureRate probabilité qu'un bloc utilisé soit illisible (ses 8 symboles effacés)
     * @param bitErrorRate     probabilité qu'un symbole lisible soit relu inversé (erreur QIM)
     */
    public record Config(int trialsPerStrategy, int recipients, double blockErasureRate, double bitErrorRate,
                         long seed) {
        public static Config defaults() {
            return new Config(3000, FingerprintParams.MAX_RECIPIENTS, 0.0, 0.0, 20260101L);
        }
    }

    public record StrategyResult(Strategy strategy, int trials, int falseAccusationTrials,
                                 int detectedAtLeastOne, int detectedBoth) {
        public double falseAccusationRate() { return (double) falseAccusationTrials / trials; }

        /** Borne haute à 95 % : règle des trois si 0 échec (3/n), sinon borne de Wilson. */
        public double falseAccusationUpper95() {
            if (falseAccusationTrials == 0) return 3.0 / trials;
            double z = 1.96, n = trials, ph = falseAccusationRate();
            double den = 1 + z * z / n;
            double centre = ph + z * z / (2 * n);
            double marge = z * Math.sqrt(ph * (1 - ph) / n + z * z / (4 * n * n));
            return (centre + marge) / den;
        }

        public double detectionRate() { return (double) detectedAtLeastOne / trials; }
        public double bothDetectedRate() { return (double) detectedBoth / trials; }
    }

    public List<StrategyResult> run(Config cfg) {
        List<StrategyResult> out = new ArrayList<>();
        for (Strategy s : Strategy.values()) out.add(run(cfg, s));
        return out;
    }

    public StrategyResult run(Config cfg, Strategy strategy) {
        RandomGenerator rng = RandomGeneratorFactory.of("L64X128MixRandom").create(cfg.seed() + strategy.ordinal());
        final int m = FingerprintParams.SYMBOLS;
        final int n = FingerprintParams.BITS_PER_BLOCK;
        int fa = 0, detOne = 0, detBoth = 0;
        double[] p = new double[m];
        byte[][] codes = new byte[cfg.recipients()][m];
        byte[] pirate = new byte[m];

        for (int trial = 0; trial < cfg.trialsPerStrategy(); trial++) {
            for (int i = 0; i < m; i++) p[i] = TardosMath.biasFromUniform(rng.nextDouble(), FingerprintParams.CUTOFF);
            for (byte[] c : codes) for (int i = 0; i < m; i++) c[i] = (byte) (rng.nextDouble() < p[i] ? 1 : 0);

            int a = rng.nextInt(cfg.recipients());
            int b;
            do { b = rng.nextInt(cfg.recipients()); } while (b == a);
            forge(strategy, codes[a], codes[b], pirate, rng);

            int erasures = 0;
            for (int k = 0; k < FingerprintParams.USED_BLOCKS; k++) {
                boolean erased = cfg.blockErasureRate() > 0 && rng.nextDouble() < cfg.blockErasureRate();
                for (int j = 0; j < n; j++) {
                    int i = k * n + j;
                    if (erased) {
                        pirate[i] = ExtractedWord.ERASURE;
                        erasures++;
                    } else if (cfg.bitErrorRate() > 0 && rng.nextDouble() < cfg.bitErrorRate()) {
                        pirate[i] = (byte) (1 - pirate[i]);
                    }
                }
            }
            double thr = ThresholdCalibrator.threshold(m - erasures, cfg.recipients());
            boolean accA = false, accB = false, innocent = false;
            for (int j = 0; j < cfg.recipients(); j++) {
                if (TardosScorer.score(codes[j], pirate, p) > thr) {
                    if (j == a) accA = true; else if (j == b) accB = true; else innocent = true;
                }
            }
            if (innocent) fa++;
            if (accA || accB) detOne++;
            if (accA && accB) detBoth++;
        }
        return new StrategyResult(strategy, cfg.trialsPerStrategy(), fa, detOne, detBoth);
    }

    private static void forge(Strategy s, byte[] x, byte[] y, byte[] out, RandomGenerator rng) {
        int m = out.length;
        int n = FingerprintParams.BITS_PER_BLOCK;
        switch (s) {
            case DRAW -> { for (int i = 0; i < m; i++) out[i] = rng.nextBoolean() ? x[i] : y[i]; }
            case MIN_MAX -> {
                boolean max = rng.nextBoolean();
                for (int i = 0; i < m; i++) out[i] = max ? (byte) Math.max(x[i], y[i]) : (byte) Math.min(x[i], y[i]);
            }
            case BLOCK_DRAW -> {
                for (int k = 0; k < m / n; k++) {
                    byte[] src = rng.nextBoolean() ? x : y;
                    System.arraycopy(src, k * n, out, k * n, n);
                }
            }
        }
    }
}
