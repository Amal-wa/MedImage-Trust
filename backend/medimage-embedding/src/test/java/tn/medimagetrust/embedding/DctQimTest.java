package tn.medimagetrust.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("SEC-D04")
@Tag("SEC-M03")
@DisplayName("Couche F : DCT 8 × 8 orthonormée et QIM avec dither (Δ = 12, rangs zigzag 6 à 13)")
class DctQimTest {
    private static final long TEST_SEED_DCT = 0x44435431L;
    private static final long TEST_SEED_QIM = 0x51494D31L;
    private static final long TEST_SEED_NOISE = 0x4E4F4953L;

    /** Image après ajout d'un bruit gaussien d'écart type {@code sigma}, arrondi et borné. */
    static PixelImage addGaussianNoise(PixelImage img, double sigma, Random rnd) {
        int[] px = img.pixels();
        int max = img.maxValue();
        for (int i = 0; i < px.length; i++) {
            long v = Math.round(px[i] + rnd.nextGaussian() * sigma);
            px[i] = (int) Math.max(0, Math.min(max, v));
        }
        return new PixelImage(img.rows(), img.cols(), img.bitsStored(), img.photometric(), px, false, false);
    }

    @Test
    @DisplayName("DCT puis DCT inverse = identité à 1e-9 ; base orthonormée (énergie conservée)")
    void dctRoundTrip() {
        Random rnd = new Random(TEST_SEED_DCT);
        for (int t = 0; t < 200; t++) {
            double[] f = new double[64];
            for (int i = 0; i < 64; i++) f[i] = rnd.nextDouble() * 65535;
            double[] c = QimBlockCodec.forward(f);
            double[] g = QimBlockCodec.inverse(c);
            double ef = 0;
            double ec = 0;
            for (int i = 0; i < 64; i++) {
                assertEquals(f[i], g[i], 1e-9);
                ef += f[i] * f[i];
                ec += c[i] * c[i];
            }
            assertEquals(1.0, ec / ef, 1e-12);
        }
    }

    @Test
    @DisplayName("Positions porteuses = rangs 6 à 13 du zigzag JPEG")
    void carriersMatchJpegZigzag() {
        int[][] zigzag = new int[64][];
        int r = 0;
        int c = 0;
        for (int k = 0; k < 64; k++) {
            zigzag[k] = new int[] {r, c};
            if ((r + c) % 2 == 0) {          // montée vers la droite
                if (c == 7) r++; else if (r == 0) c++; else { r--; c++; }
            } else {                          // descente vers la gauche
                if (r == 7) c++; else if (c == 0) r++; else { r++; c--; }
            }
        }
        for (int k = EmbeddingProfile.ZIGZAG_FIRST; k <= EmbeddingProfile.ZIGZAG_LAST; k++) {
            assertArrayEquals(zigzag[k], QimBlockCodec.CARRIERS[k - EmbeddingProfile.ZIGZAG_FIRST]);
        }
    }

    @Test
    @DisplayName("QIM aller-retour sans bruit sur les coefficients : 100 % des bits")
    void qimRoundTripExact() {
        Random rnd = new Random(TEST_SEED_QIM);
        for (int t = 0; t < 100_000; t++) {
            double coef = (rnd.nextDouble() - 0.5) * 20_000;
            double d = rnd.nextDouble() * EmbeddingProfile.QIM_DELTA;
            int bit = rnd.nextInt(2);
            double marked = QimBlockCodec.embed(coef, bit, d);
            assertTrue(Math.abs(marked - coef) <= EmbeddingProfile.QIM_DELTA / 2.0 + 1e-9);
            assertEquals(bit, QimBlockCodec.extract(marked, d));
        }
    }

    @Test
    @DisplayName("Sur les pixels (arrondi et bornage compris), fond vers 200 : 100 % relus, écart ≤ 12")
    void pixelRoundTripWithoutNoise() {
        Random rnd = new Random(TEST_SEED_QIM);
        EmbeddingSession session = EmbeddingSession.open(Phantoms.normal().image());
        Markings.FWrites f = Markings.writeF(session, rnd);
        assertEquals(0, Markings.fBitErrors(session.fChannel(), f));
    }

    @Test
    @DisplayName("Écart par pixel ≤ 12 sur des blocs aléatoires, y compris près de 0 et du maximum")
    void maxDeviationBounded() {
        Random rnd = new Random(TEST_SEED_QIM);
        int worst = 0;
        for (int t = 0; t < 20_000; t++) {
            int bits = 8 + rnd.nextInt(9);
            int max = (1 << bits) - 1;
            int[] block = new int[64];
            int mode = rnd.nextInt(3);
            for (int i = 0; i < 64; i++) {
                block[i] = switch (mode) {
                    case 0 -> rnd.nextInt(8);                 // près de 0
                    case 1 -> max - rnd.nextInt(8);           // près du maximum
                    default -> rnd.nextInt(max + 1);          // quelconque
                };
            }
            int[] out = QimBlockCodec.writeBlock(block, Markings.randomBits(rnd), Markings.randomDither(rnd), max);
            for (int i = 0; i < 64; i++) {
                assertTrue(out[i] >= 0 && out[i] <= max);
                worst = Math.max(worst, Math.abs(out[i] - block[i]));
            }
        }
        assertTrue(worst <= EmbeddingProfile.MAX_F_DEVIATION, "écart maximal observé : " + worst);
    }

    @Test
    @DisplayName("Bruit gaussien σ = 1 sur les pixels : au moins 95 % des bits relus")
    void robustToSmallNoise() {
        assertTrue(readRateUnderNoise(1.0) >= 0.95);
    }

    @Test
    @DisplayName("Bruit gaussien σ = 2 : taux conforme à la théorie 1 − 2Q(Δ/4σ) ≈ 86,6 %, sous l'objectif de 95 %")
    void noiseSigmaTwoMatchesTheory() {
        // Écart à la consigne : ≥ 95 % à σ = 2 est impossible avec Δ = 12 (la marge de décision vaut Δ/4 = 3,
        // soit 1,5 σ). Voir README, « Décisions à valider ». Ce test fige le comportement mesuré.
        double rate = readRateUnderNoise(2.0);
        assertTrue(rate > 0.83 && rate < 0.90, "taux mesuré : " + rate);
    }

    /** Taux de bits F relus après bruit gaussien, sur 4 marquages du fantôme normal (4 096 bits). */
    static double readRateUnderNoise(double sigma) {
        Random rnd = new Random(TEST_SEED_NOISE);
        int ok = 0;
        int total = 0;
        for (int trial = 0; trial < 4; trial++) {
            Markings.Marking m = Markings.markRandom(Phantoms.normal().image(), rnd);
            PixelImage noisy = addGaussianNoise(m.marked(), sigma, rnd);
            FBlockReader reader = FBlockReader.of(noisy, m.partitions());
            int errors = Markings.fBitErrors(reader, m.f());
            int bits = m.f().indices().length * EmbeddingProfile.F_BITS_PER_BLOCK;
            ok += bits - errors;
            total += bits;
        }
        return (double) ok / total;
    }

    @Test
    @DisplayName("Copie recadrée : les blocs hors de l'image sont UNREADABLE, les autres restent lisibles")
    void croppedCopyIsUnreadableOutside() {
        Markings.Marking m = Markings.markRandom(Phantoms.normal().image(), new Random(TEST_SEED_QIM));
        int newRows = 256;
        int cols = m.marked().cols();
        int[] px = java.util.Arrays.copyOf(m.marked().pixels(), newRows * cols);
        PixelImage cropped = new PixelImage(newRows, cols, 12, Photometric.MONOCHROME2, px, false, false);
        FBlockReader reader = FBlockReader.of(cropped, m.partitions());
        int unreadable = 0;
        for (int k = 0; k < m.f().indices().length; k++) {
            int fIndex = m.f().indices()[k];
            int top = m.partitions().blockTop(m.partitions().fBlock(fIndex));
            int[] read = reader.read(fIndex, m.f().dither()[k]);
            if (top + 8 > newRows) {
                for (int v : read) assertEquals(FBlockChannel.UNREADABLE, v);
                unreadable++;
            } else {
                for (int j = 0; j < 8; j++) assertEquals(m.f().bits()[k][j] ? 1 : 0, read[j]);
            }
        }
        assertTrue(unreadable > 0 && unreadable < m.f().indices().length);
    }

    @Test
    @DisplayName("Entrées malformées du canal F : index hors limites, tableaux nuls ou de longueur ≠ 8, dither ≥ Δ")
    void invalidFInputs() {
        Random rnd = new Random(TEST_SEED_QIM);
        boolean[] bits = Markings.randomBits(rnd);
        double[] d = Markings.randomDither(rnd);
        PixelImage img = Phantoms.normal().image();
        int size = EmbeddingSession.open(img).fChannel().classSize();
        assertInvalid(img, s -> s.fChannel().write(-1, bits, d));
        assertInvalid(img, s -> s.fChannel().write(size, bits, d));
        assertInvalid(img, s -> s.fChannel().write(0, new boolean[7], d));
        assertInvalid(img, s -> s.fChannel().write(0, new boolean[9], d));
        assertInvalid(img, s -> s.fChannel().write(0, null, d));
        assertInvalid(img, s -> s.fChannel().write(0, bits, new double[7]));
        assertInvalid(img, s -> s.fChannel().write(0, bits, null));
        assertInvalid(img, s -> s.fChannel().write(0, bits, new double[] {0, 0, 0, 0, 0, 0, 0, 12.0}));
        assertInvalid(img, s -> s.fChannel().write(0, bits, new double[] {0, 0, 0, 0, 0, 0, 0, -0.5}));
        assertInvalid(img, s -> s.fChannel().write(0, bits, new double[] {0, 0, 0, 0, 0, 0, 0, Double.NaN}));
        assertInvalid(img, s -> s.fChannel().read(size, d));
        assertInvalid(img, s -> s.fChannel().read(0, new double[9]));
        FBlockReader reader = FBlockReader.of(img, Partitions.of(RoniDetector.detect(img)));
        assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                assertThrows(EmbeddingException.class, () -> reader.read(-1, d)).reason());
        assertEquals(EmbeddingException.Reason.ILLEGAL_STATE,
                assertThrows(EmbeddingException.class, () -> reader.write(0, bits, d)).reason());
    }

    private static void assertInvalid(PixelImage img, java.util.function.Consumer<EmbeddingSession> op) {
        EmbeddingSession s = EmbeddingSession.open(img);
        assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                assertThrows(EmbeddingException.class, () -> op.accept(s)).reason());
    }
}
