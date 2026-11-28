package tn.medimagetrust.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mesures reportées dans le README (section « Mesures »). Les chiffres sont imprimés sur la sortie des tests ;
 * les seules assertions portent sur des faits stables (aucune assertion sur le temps).
 */
@Tag("mesure")
@Tag("SEC-D04")
@DisplayName("Mesures : bits F cassés par le bornage, PSNR moyen, temps sur 512 × 512")
class MeasurementsTest {
    private static final long TEST_SEED_MEASURE = 0x4D455355L;
    private static final int SESSIONS = 20;

    private static void print(String format, Object... args) {
        System.out.println("[MESURE] " + String.format(Locale.ROOT, format, args));
    }

    /** Taux de bits F mal relus (avant tout bruit), et part des sessions annulées au contrôle final. */
    private static double[] clampEffect(PixelImage img, long seed) {
        Random rnd = new Random(seed);
        long errors = 0;
        long bits = 0;
        int failed = 0;
        for (int t = 0; t < SESSIONS; t++) {
            EmbeddingSession s = EmbeddingSession.open(img);
            Markings.FWrites f = Markings.writeF(s, rnd);
            errors += Markings.fBitErrors(s.fChannel(), f);
            bits += (long) f.indices().length * EmbeddingProfile.F_BITS_PER_BLOCK;
            s.writeS(Markings.randomBytes(64, rnd));
            s.writeW(Markings.randomBytes(208, rnd));
            try {
                s.finish();
            } catch (EmbeddingException e) {
                assertEquals(EmbeddingException.Reason.FINAL_CHECK_FAILED, e.reason());
                failed++;
            }
        }
        return new double[] {(double) errors / bits, (double) failed / SESSIONS};
    }

    @Test
    @DisplayName("Bornage à 0 : taux de bits F mal relus, fond à 0 contre fond vers 200")
    void clampingBreaksBitsOnZeroBackground() {
        double[] zero = clampEffect(Phantoms.zeroBackground().image(), TEST_SEED_MEASURE);
        double[] normal = clampEffect(Phantoms.normal().image(), TEST_SEED_MEASURE);
        print("fond 0   : bits F mal relus = %.4f %% ; sessions annulées = %.0f %%", 100 * zero[0], 100 * zero[1]);
        print("fond 200 : bits F mal relus = %.4f %% ; sessions annulées = %.0f %%", 100 * normal[0],
                100 * normal[1]);
        assertEquals(0.0, normal[0]);
        assertEquals(0.0, normal[1]);
    }

    @Test
    @DisplayName("PSNR moyen et minimal sur 50 marquages du fantôme normal (12 bits, 512 × 512)")
    void meanPsnr() {
        Random rnd = new Random(TEST_SEED_MEASURE);
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        int n = 50;
        for (int i = 0; i < n; i++) {
            PixelImage img = Phantoms.normal(rnd.nextLong()).image();
            Markings.Marking m = Markings.markRandom(img, rnd);
            double psnr = EmbeddingSession.psnr(img.pixels(), m.marked().pixels(), img.maxValue());
            sum += psnr;
            min = Math.min(min, psnr);
        }
        print("PSNR 12 bits : moyen = %.2f dB ; minimal = %.2f dB (%d marquages)", sum / n, min, n);
        assertTrue(min >= EmbeddingProfile.MIN_PSNR_DB);
    }

    @Test
    @DisplayName("PSNR minimal en 8 bits sur 256 × 256 (cas le plus défavorable du profil)")
    void worstCasePsnr8Bits() {
        Random rnd = new Random(TEST_SEED_MEASURE);
        Phantoms.Spec spec = new Phantoms.Spec(256, 256, 8, Photometric.MONOCHROME2, 30, 3, 153, 12, 128, 128, 60, 60);
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 20; i++) {
            PixelImage img = Phantoms.build(spec, rnd.nextLong()).image();
            Markings.Marking m = Markings.markRandom(img, rnd);
            min = Math.min(min, EmbeddingSession.psnr(img.pixels(), m.marked().pixels(), img.maxValue()));
        }
        print("PSNR 8 bits 256 x 256 : minimal = %.2f dB", min);
        assertTrue(min >= EmbeddingProfile.MIN_PSNR_DB);
    }

    @Test
    @DisplayName("Robustesse F au bruit gaussien : σ = 1 ; 1,5 ; 2 (× 2^(B − 8), bruit relatif au pas)")
    void noiseRates() {
        for (double sigma : new double[] {1.0, 1.5, 2.0}) {
            print("bruit gaussien sigma = %.1f x 2^(B - 8) : bits F relus = %.2f %%", sigma,
                    100 * DctQimTest.readRateUnderNoise(sigma));
        }
    }

    @Test
    @DisplayName("Temps sur 512 × 512 : détection seule et session complète (indicatif, aucune assertion)")
    void timings() {
        PixelImage img = Phantoms.normal().image();
        Random rnd = new Random(TEST_SEED_MEASURE);
        for (int i = 0; i < 3; i++) Markings.markRandom(img, rnd); // préchauffage du JIT
        int n = 10;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) RoniDetector.detect(img);
        long t1 = System.nanoTime();
        for (int i = 0; i < n; i++) Markings.markRandom(img, rnd);
        long t2 = System.nanoTime();
        print("512 x 512 : détection = %.1f ms ; session complète (F 128 blocs, S, W, contrôle final) = %.1f ms",
                (t1 - t0) / 1e6 / n, (t2 - t1) / 1e6 / n);
    }
}
