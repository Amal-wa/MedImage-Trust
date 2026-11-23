package tn.medimagetrust.fingerprint;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Simulation Monte-Carlo de collusions de 2 destinataires (SEC-D04, T-SEC-D04).
 * Travaille au niveau des symboles avec les mêmes lois que {@link TardosCodeGenerator}, mais avec
 * un générateur pseudo-aléatoire germé (reproductible) : la voie PRF coûterait des centaines de millions de HMAC.
 * La voie PRF complète est couverte par {@code EndToEndFingerprintTest}.
 */
public final class CollusionSimulator {

    public enum Strategy {
        /** Tirage : à chaque symbole où ils diffèrent, les complices en choisissent un au hasard. */
        DRAW,
        /** Min/max : sur les symboles qui diffèrent, sortie du minimum (0) ou du maximum (1), choisi par essai. */
        MIN_MAX,
        /** Entrelacement : blocs de 64 symboles pris alternativement chez chaque complice. */
        INTERLEAVE
    }

    public record Config(int trialsPerStrategy, int recipients, double erasureRate, long seed) {
        public static Config defaults() { return new Config(3000, FingerprintParams.MAX_RECIPIENTS, 0.0, 20260101L); }
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
            for (int i = 0; i < m; i++) {
                if (cfg.erasureRate() > 0 && rng.nextDouble() < cfg.erasureRate()) {
                    pirate[i] = ExtractedWord.ERASURE;
                    erasures++;
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
        switch (s) {
            case DRAW -> { for (int i = 0; i < m; i++) out[i] = rng.nextBoolean() ? x[i] : y[i]; }
            case MIN_MAX -> {
                boolean max = rng.nextBoolean();
                for (int i = 0; i < m; i++) out[i] = max ? (byte) Math.max(x[i], y[i]) : (byte) Math.min(x[i], y[i]);
            }
            case INTERLEAVE -> {
                boolean first = rng.nextBoolean();
                for (int i = 0; i < m; i++) out[i] = (((i / 64) % 2 == 0) == first) ? x[i] : y[i];
            }
        }
    }
}
