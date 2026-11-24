package tn.medimagetrust.fingerprint;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T-SEC-D04 : simulation Monte-Carlo de collusions de 2 destinataires parmi 64, modèle v2.4 (128 blocs × 8 symboles,
 * 1 répétition). Objectif : 0 fausse accusation et au moins un complice détecté dans ≥ 99 % des cas.
 */
class CollusionSimulationTest {

    private static void print(String title, List<CollusionSimulator.StrategyResult> results) {
        for (var r : results) {
            System.out.println(String.format(Locale.ROOT,
                    "[SIMULATION] %s | %-10s | FA %d/%d | détection >= 1 : %.2f %% | les 2 : %.2f %%",
                    title, r.strategy(), r.falseAccusationTrials(), r.trials(), 100 * r.detectionRate(),
                    100 * r.bothDetectedRate()));
        }
    }

    @Test
    void threeThousandCollusionsPerStrategyMeetTheObjectives() {
        var cfg = new CollusionSimulator.Config(3000, FingerprintParams.MAX_RECIPIENTS, 0.0, 0.0, 20260101L);
        var results = new CollusionSimulator().run(cfg);
        print("sans dégradation", results);
        for (var r : results) {
            assertEquals(0, r.falseAccusationTrials(), r.strategy() + " : fausses accusations");
            assertTrue(r.detectionRate() >= 0.99, r.strategy() + " : détection = " + r.detectionRate());
        }
    }

    @Test
    void stillDetectsWithThirtyPercentOfBlocksErased() {
        var cfg = new CollusionSimulator.Config(500, FingerprintParams.MAX_RECIPIENTS, 0.30, 0.0, 777L);
        var results = new CollusionSimulator().run(cfg);
        print("30 % de blocs effacés", results);
        for (var r : results) {
            assertEquals(0, r.falseAccusationTrials(), r.strategy().toString());
            assertTrue(r.detectionRate() >= 0.90, r.strategy() + " : détection = " + r.detectionRate());
        }
    }

    @Test
    void qimNoiseNeverCausesFalseAccusations() {
        // Taux d'erreur QIM mesurés par le module embedding : σ = 1 → 0,6 % ; σ = 1,5 → 5,6 % ; σ = 2 → 13,6 %.
        for (double ber : new double[] {0.006, 0.056, 0.136}) {
            var cfg = new CollusionSimulator.Config(500, FingerprintParams.MAX_RECIPIENTS, 0.0, ber, 4242L);
            var results = new CollusionSimulator().run(cfg);
            print(String.format(Locale.ROOT, "erreurs QIM %.1f %%", ber * 100), results);
            for (var r : results) assertEquals(0, r.falseAccusationTrials(), r.strategy() + " / " + ber);
        }
    }

    @Test
    void ruleOfThreeBoundForZeroFailures() {
        var r = new CollusionSimulator.StrategyResult(CollusionSimulator.Strategy.DRAW, 3000, 0, 3000, 3000);
        assertEquals(0.001, r.falseAccusationUpper95(), 1e-12);
    }

    @Test
    void simulationIsReproducible() {
        var cfg = new CollusionSimulator.Config(50, 16, 0.1, 0.01, 1L);
        assertEquals(new CollusionSimulator().run(cfg), new CollusionSimulator().run(cfg));
    }
}
