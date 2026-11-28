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
@DisplayName("Couche F : DCT 8 × 8 orthonormée et QIM avec dither (Δ(B) = 12 × 2^(B − 8), rangs zigzag 6 à 13)")
class DctQimTest {
    private static final long TEST_SEED_DCT = 0x44435431L;
    private static final long TEST_SEED_QIM = 0x51494D31L;
    private static final long TEST_SEED_NOISE = 0x4E4F4953L;

    /**
     * Image après ajout d'un bruit gaussien d'écart type {@code sigma}, arrondi et borné à la plage représentable
     * (celle de l'original pour une copie non signée, l'entier signé 16 bits pour une copie signée).
     */
    static PixelImage addGaussianNoise(PixelImage img, double sigma, Random rnd) {
        int n = img.pixelCount();
        if (!img.signed()) {
            char[] px = new char[n];
            int max = img.maxValue();
            for (int i = 0; i < n; i++) {
                long v = Math.round(img.pixel(i) + rnd.nextGaussian() * sigma);
                px[i] = (char) Math.max(0, Math.min(max, v));
            }
            return new PixelImage(img.rows(), img.cols(), img.bitsStored(), img.photometric(), px, false, false);
        }
        short[] sv = new short[n];
        for (int i = 0; i < n; i++) {
            long v = Math.round(img.pixel(i) + rnd.nextGaussian() * sigma);
            sv[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
        }
        return PixelImage.signedFromShorts(img.rows(), img.cols(), img.bitsStored(), img.photometric(), sv, false,
                false);
    }

    /** Copie recadrée aux {@code newRows} premières lignes, dans la représentation de l'image. */
    static PixelImage cropRows(PixelImage img, int newRows) {
        int n = newRows * img.cols();
        if (!img.signed()) {
            char[] px = new char[n];
            for (int i = 0; i < n; i++) px[i] = (char) img.pixel(i);
            return new PixelImage(newRows, img.cols(), img.bitsStored(), img.photometric(), px, false, false);
        }
        short[] sv = new short[n];
        for (int i = 0; i < n; i++) sv[i] = (short) img.pixel(i);
        return PixelImage.signedFromShorts(newRows, img.cols(), img.bitsStored(), img.photometric(), sv, false, false);
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
    @DisplayName("QIM aller-retour sans bruit sur les coefficients, pour B = 8, 12 et 16 : 100 % des bits, écart ≤ Δ/2")
    void qimRoundTripExact() {
        for (int b : new int[] {8, 12, 16}) {
            double delta = EmbeddingProfile.qimDelta(b);
            Random rnd = new Random(TEST_SEED_QIM);
            for (int t = 0; t < 100_000; t++) {
                double coef = (rnd.nextDouble() - 0.5) * 20_000 * (delta / EmbeddingProfile.qimDelta(8));
                double d = rnd.nextDouble() * delta;
                int bit = rnd.nextInt(2);
                double marked = QimBlockCodec.embed(coef, bit, d, delta);
                assertTrue(Math.abs(marked - coef) <= delta / 2.0 + 1e-9);
                assertEquals(bit, QimBlockCodec.extract(marked, d, delta));
            }
        }
    }

    @Test
    @DisplayName("Sur les pixels (arrondi compris), fantôme normal 12 bits : 100 % relus, écart ≤ Δ(12)")
    void pixelRoundTripWithoutNoise() {
        Random rnd = new Random(TEST_SEED_QIM);
        EmbeddingSession session = EmbeddingSession.open(Phantoms.normal().image());
        Markings.FWrites f = Markings.writeF(session, rnd);
        assertEquals(0, Markings.fBitErrors(session.fChannel(), f));
    }

    @Test
    @DisplayName("Écart par pixel ≤ Δ(B) sur des blocs aléatoires de 8 à 16 bits ; aucun bornage près de 0 et du maximum")
    void maxDeviationBounded() {
        Random rnd = new Random(TEST_SEED_QIM);
        double worstRatio = 0;
        int rangeExits = 0;
        for (int t = 0; t < 20_000; t++) {
            int bits = 8 + rnd.nextInt(9);
            int max = (1 << bits) - 1;
            double delta = EmbeddingProfile.qimDelta(bits);
            int bound = EmbeddingProfile.maxFDeviation(bits);
            int[] block = new int[64];
            int mode = rnd.nextInt(3);
            for (int i = 0; i < 64; i++) {
                block[i] = switch (mode) {
                    case 0 -> rnd.nextInt(8);                 // près de 0
                    case 1 -> max - rnd.nextInt(8);           // près du maximum
                    default -> rnd.nextInt(max + 1);          // quelconque
                };
            }
            int[] out = QimBlockCodec.writeBlock(block, Markings.randomBits(rnd), Markings.randomDither(rnd, delta),
                    delta, bound);
            for (int i = 0; i < 64; i++) {
                int dev = Math.abs(out[i] - block[i]);
                assertTrue(dev <= bound, "écart " + dev + " > Δ(" + bits + ")");
                worstRatio = Math.max(worstRatio, dev / delta);
                if (out[i] < 0 || out[i] > max) rangeExits++;
            }
        }
        // Borne théorique 0,89 Δ avant arrondi (voir QimBlockCodec) ; pour Δ = 12 : 11 après arrondi.
        assertTrue(worstRatio <= 11.0 / 12.0, "écart maximal observé : " + worstRatio + " Δ");
        // Près de 0 et du maximum, le codec rend des valeurs hors plage telles quelles (règle : jamais d'écrêtage) ;
        // la session décide ensuite du mode signé ou du refus.
        assertTrue(rangeExits > 0, "aucune sortie de plage observée : le bornage serait silencieux");
    }

    @Test
    @DisplayName("Bruit gaussien σ = 1 × 2^(B − 8) sur les pixels : au moins 95 % des bits relus")
    void robustToSmallNoise() {
        assertTrue(readRateUnderNoise(1.0) >= 0.95);
    }

    @Test
    @DisplayName("Bruit gaussien σ = 2 × 2^(B − 8) : taux conforme à la théorie 1 − 2Q(Δ/4σ) ≈ 86,6 %, sous 95 %")
    void noiseSigmaTwoMatchesTheory() {
        // À bruit relatif égal (σ/Δ), le taux ne dépend pas de B : ≥ 95 % à σ = 2 × 2^(B − 8) reste impossible (la
        // marge de décision vaut Δ/4, soit 1,5 σ). Voir api/docs/embedding.md, « Limites connues ».
        double rate = readRateUnderNoise(2.0);
        assertTrue(rate > 0.83 && rate < 0.90, "taux mesuré : " + rate);
    }

    /**
     * Taux de bits F relus après bruit gaussien d'écart type {@code sigma8 × Δ(B)/Δ(8)} (bruit exprimé en unités du pas
     * de 8 bits), sur 4 marquages du fantôme normal (4 096 bits), lus avec la profondeur B de l'émission.
     */
    static double readRateUnderNoise(double sigma8) {
        Random rnd = new Random(TEST_SEED_NOISE);
        int ok = 0;
        int total = 0;
        for (int trial = 0; trial < 4; trial++) {
            Markings.Marking m = Markings.markRandom(Phantoms.normal().image(), rnd);
            int b = m.original().bitsStored();
            double sigma = sigma8 * EmbeddingProfile.qimDelta(b) / EmbeddingProfile.qimDelta(8);
            PixelImage noisy = addGaussianNoise(m.marked(), sigma, rnd);
            FBlockReader reader = FBlockReader.of(noisy, m.partitions(), b);
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
        PixelImage cropped = cropRows(m.marked(), newRows);
        FBlockReader reader = FBlockReader.of(cropped, m.partitions(), m.original().bitsStored());
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
        assertInvalid(img, s -> s.fChannel().write(0, bits,
                new double[] {0, 0, 0, 0, 0, 0, 0, s.fChannel().qimDelta()}));
        assertInvalid(img, s -> s.fChannel().write(0, bits, new double[] {0, 0, 0, 0, 0, 0, 0, -0.5}));
        assertInvalid(img, s -> s.fChannel().write(0, bits, new double[] {0, 0, 0, 0, 0, 0, 0, Double.NaN}));
        assertInvalid(img, s -> s.fChannel().read(size, d));
        assertInvalid(img, s -> s.fChannel().read(0, new double[9]));
        FBlockReader reader = FBlockReader.of(img, Partitions.of(RoniDetector.detect(img)), img.bitsStored());
        assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                assertThrows(EmbeddingException.class, () -> reader.read(-1, d)).reason());
        assertEquals(EmbeddingException.Reason.INVALID_INPUT, assertThrows(EmbeddingException.class,
                () -> reader.read(0, new double[] {0, 0, 0, 0, 0, 0, 0, reader.qimDelta()})).reason());
        assertEquals(EmbeddingException.Reason.ILLEGAL_STATE,
                assertThrows(EmbeddingException.class, () -> reader.write(0, bits, d)).reason());
    }

    private static void assertInvalid(PixelImage img, java.util.function.Consumer<EmbeddingSession> op) {
        EmbeddingSession s = EmbeddingSession.open(img);
        assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                assertThrows(EmbeddingException.class, () -> op.accept(s)).reason());
    }
}
