package tn.medimagetrust.fingerprint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** T-SEC-D04 : version rapide (1 000 essais par stratégie). La CI exécute les 3 000 via SimulationMain. */
class CollusionSimulationTest {
    @Test
    void meetsFalseAccusationAndDetectionObjectives() {
        var cfg = new CollusionSimulator.Config(1000, FingerprintParams.MAX_RECIPIENTS, 0.0, 12345L);
        for (var r : new CollusionSimulator().run(cfg)) {
            assertTrue(r.falseAccusationRate() <= FingerprintParams.TARGET_FALSE_ACCUSATION,
                    r.strategy() + " : fausses accusations = " + r.falseAccusationTrials());
            assertTrue(r.detectionRate() >= 0.90, r.strategy() + " : détection = " + r.detectionRate());
        }
    }

    @Test
    void stillDetectsWithThirtyPercentErasures() {
        var cfg = new CollusionSimulator.Config(500, FingerprintParams.MAX_RECIPIENTS, 0.30, 777L);
        for (var r : new CollusionSimulator().run(cfg)) {
            assertEquals(0, r.falseAccusationTrials(), r.strategy().toString());
            assertTrue(r.detectionRate() >= 0.90, r.strategy() + " : détection = " + r.detectionRate());
        }
    }

    @Test
    void ruleOfThreeBoundForZeroFailures() {
        var r = new CollusionSimulator.StrategyResult(CollusionSimulator.Strategy.DRAW, 3000, 0, 3000, 3000);
        assertEquals(0.001, r.falseAccusationUpper95(), 1e-12);
    }

    @Test
    void simulationIsReproducible() {
        var cfg = new CollusionSimulator.Config(50, 16, 0.0, 1L);
        assertEquals(new CollusionSimulator().run(cfg), new CollusionSimulator().run(cfg));
    }
}
