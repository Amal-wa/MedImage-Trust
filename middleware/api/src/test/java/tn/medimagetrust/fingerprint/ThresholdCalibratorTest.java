package tn.medimagetrust.fingerprint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThresholdCalibratorTest {
    @Test
    void inverseNormalTailKnownValues() {
        assertEquals(1.959964, ThresholdCalibrator.inverseNormalTail(0.025), 1e-4);
        assertEquals(3.090232, ThresholdCalibrator.inverseNormalTail(1e-3), 1e-4);
        assertEquals(4.753424, ThresholdCalibrator.inverseNormalTail(1e-6), 1e-4);
    }

    @Test
    void thresholdGrowsWithCandidatesAndReadableSymbols() {
        assertTrue(ThresholdCalibrator.threshold(1024, 64) > ThresholdCalibrator.threshold(1024, 2));
        assertTrue(ThresholdCalibrator.threshold(1024, 64) > ThresholdCalibrator.threshold(512, 64));
    }

    @Test
    void innocentScoreHasVarianceOneBySymbol() {
        // E[g²] = 1 pour tout p : vérification exacte sur quelques biais
        for (double p : new double[]{FingerprintParams.CUTOFF, 0.1, 0.5, 0.9}) {
            double v = 0;
            for (int y = 0; y <= 1; y++)
                for (int x = 0; x <= 1; x++) {
                    double py = y == 1 ? p : 1 - p, px = x == 1 ? p : 1 - p;
                    double g = TardosMath.scoreTerm(y, x, p);
                    v += py * px * g * g;
                }
            assertEquals(1.0, v, 1e-9);
        }
    }
}
