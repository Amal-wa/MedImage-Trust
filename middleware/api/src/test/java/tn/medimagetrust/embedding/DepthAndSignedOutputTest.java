package tn.medimagetrust.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.EMBEDDING_RANGE_UNSUPPORTED;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.ILLEGAL_STATE;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.PIXEL_PADDING_IN_RONI;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Profil 3 : pas QIM proportionnel à la profondeur, Δ(B) = 12 × 2^(B − 8), et copie en entier signé 16 bits quand la
 * couche F sortirait de la plage stockable (fond nul, fond saturé). Fantômes de {@link Phantoms}.
 */
@Tag("SEC-M03")
@Tag("SEC-D04")
@DisplayName("Profondeur : Δ(B), mode signé sans écrêtage, refus en 16 bits, PixelPaddingValue")
class DepthAndSignedOutputTest {
    private static final long TEST_SEED_DEPTH = 0x4445_5054_4833L;

    // ------------------------------------------------------------------------------------------ Δ(B)

    @Test
    @DisplayName("Δ(B) = 12 × 2^(B − 8) : B = 8 → 12, 10 → 48, 12 → 192, 16 → 3 072 ; écart F et garde suivent Δ")
    void qimDeltaScalesWithDepth() {
        assertEquals(12, EmbeddingProfile.qimDelta(8));
        assertEquals(48, EmbeddingProfile.qimDelta(10));
        assertEquals(192, EmbeddingProfile.qimDelta(12));
        assertEquals(3072, EmbeddingProfile.qimDelta(16));
        for (int b = 8; b <= 16; b++) {
            int scale = 1 << (b - 8);
            assertEquals(12 * scale, EmbeddingProfile.maxFDeviation(b));
            assertEquals(4 * scale, EmbeddingProfile.guardQ(b));
            // La garde (en q) reste plus large que l'écart F maximal exprimé en q : le masque ne bouge pas.
            assertTrue(EmbeddingProfile.guardQ(b) > EmbeddingProfile.maxFDeviation(b) >> EmbeddingProfile.Q_SHIFT);
        }
        for (int bad : new int[] {7, 17}) {
            assertEquals(INVALID_INPUT,
                    assertThrows(EmbeddingException.class, () -> EmbeddingProfile.qimDelta(bad)).reason());
        }
    }

    @Test
    @DisplayName("EmbeddingResult : B de l'original, Δ(B), sortie non signée sans attribut à appliquer (8 bits)")
    void resultCarriesDepthForTheRegistry() {
        Markings.Marking m = Markings.markRandom(Phantoms.normal8().image(), new Random(TEST_SEED_DEPTH));
        EmbeddingResult r = m.result();
        assertEquals(8, r.bitsStored());
        assertEquals(12, r.qimDelta());
        assertFalse(r.signedOutput());
        assertTrue(r.pixelAttributes().isEmpty());
        assertEquals(12.0, FBlockReader.of(m.marked(), m.partitions(), r.bitsStored()).qimDelta());
    }

    // ------------------------------------------------------------------------------------------ mode signé

    /** Vérifications communes d'une copie produite en mode signé à partir d'un fond hors plage. */
    private static void assertSignedCopyIsCorrect(Markings.Marking m, boolean expectNegative) {
        PixelImage orig = m.original();
        PixelImage marked = m.marked();
        EmbeddingResult r = m.result();
        int max = orig.maxValue();
        assertTrue(r.signedOutput(), "copie attendue en mode signé");
        assertTrue(marked.signed());
        assertEquals(orig.bitsStored(), r.bitsStored());
        assertEquals(orig.bitsStored(), marked.bitsStored(), "bitsStored reste B (condensat de la ROI)");
        assertEquals(new EmbeddingResult.PixelAttributes(1, 16, 16, 15), r.pixelAttributes().orElseThrow());

        int below = 0;
        int above = 0;
        for (int i = 0; i < orig.pixelCount(); i++) {
            int v = marked.pixel(i);
            boolean inRoni = m.profile().inRoni(i);
            if (!inRoni) {
                assertEquals(orig.pixel(i), v, "ROI modifiée");
            }
            if (v < 0 || v > max) {
                assertTrue(inRoni, "valeur hors plage en dehors de la RONI");
                if (v < 0) below++; else above++;
            }
        }
        // Aucun écrêtage : les valeurs que la QIM fait sortir de la plage sont écrites telles quelles.
        if (expectNegative) {
            assertTrue(below > 0, "aucune valeur négative : un écrêtage aurait eu lieu");
            assertEquals(0, above);
        } else {
            assertTrue(above > 0, "aucune valeur > 2^B − 1 : un écrêtage aurait eu lieu");
            assertEquals(0, below);
        }
        double psnr = EmbeddingSession.psnr(orig.pixels(), marked.pixels(), true, max);
        assertTrue(psnr >= EmbeddingProfile.MIN_PSNR_DB, "PSNR : " + psnr);
        // 100 % des bits F relus sans attaque, avec la profondeur B du registre.
        assertEquals(0, Markings.fBitErrors(FBlockReader.of(marked, m.partitions(), r.bitsStored()), m.f()));
        // S et W relus, condensat de la ROI inchangé.
        LsbChannel lsb = LsbChannel.reader(marked, m.partitions());
        assertArrayEquals(m.s(), lsb.readS());
        assertArrayEquals(m.w(), lsb.readW());
        assertArrayEquals(m.roiDigest(), Digests.roiDigest(marked, m.profile()));
    }

    @Test
    @DisplayName("Fond nul 12 bits : mode signé, aucun écrêtage, ROI identique, négatifs dans la RONI seulement, PSNR ≥ 40 dB, 100 % de F")
    void zeroBackgroundTwelveBitsGivesSignedCopy() {
        assertSignedCopyIsCorrect(Markings.markRandom(Phantoms.zeroBackground().image(), new Random(TEST_SEED_DEPTH)),
                true);
    }

    @Test
    @DisplayName("Fond nul 8 bits (stocké sur 8 bits) : mode signé, attributs BitsAllocated 16, BitsStored 16, HighBit 15, PixelRepresentation 1")
    void zeroBackgroundEightBitsNeedsSixteenBitsAllocated() {
        Markings.Marking m = Markings.markRandom(Phantoms.zeroBackground8().image(), new Random(TEST_SEED_DEPTH));
        assertSignedCopyIsCorrect(m, true);
        EmbeddingResult.PixelAttributes a = m.result().pixelAttributes().orElseThrow();
        assertEquals(1, a.pixelRepresentation());
        // Un échantillon de 8 bits ne peut pas contenir un entier signé 16 bits : BitsAllocated passe à 16.
        assertEquals(16, a.bitsAllocated());
        assertEquals(16, a.bitsStored());
        assertEquals(15, a.highBit());
        assertEquals(8, m.result().bitsStored(), "B de l'original, à enregistrer dans le registre");
        assertEquals(12, m.result().qimDelta());
    }

    @Test
    @DisplayName("Fond nul 12 bits, sortie non signée forcée : au moins un écrêtage détecté, émission refusée")
    void zeroBackgroundWithoutCorrectionDetectsClipping() {
        EmbeddingSession s = EmbeddingSession.open(Phantoms.zeroBackground().image(), false);
        EmbeddingException e = assertThrows(EmbeddingException.class,
                () -> Markings.writeF(s, new Random(TEST_SEED_DEPTH)));
        assertEquals(EMBEDDING_RANGE_UNSUPPORTED, e.reason());
        assertTrue(s.rangeExitCount() >= 1);
    }

    @Test
    @DisplayName("Fond saturé 12 bits (2^B − 1, polarité claire) : mode signé, valeurs > 2^B − 1 dans la RONI seulement")
    void saturatedBackgroundGivesSignedCopy() {
        Phantoms.Phantom ph = Phantoms.saturatedBackground();
        assertEquals(BackgroundPolarity.LIGHT, RoniDetector.detect(ph.image()).polarity());
        assertSignedCopyIsCorrect(Markings.markRandom(ph.image(), new Random(TEST_SEED_DEPTH)), false);
    }

    @Test
    @DisplayName("B = 16 avec sortie de plage : refus EMBEDDING_RANGE_UNSUPPORTED, aucune copie produite")
    void sixteenBitsOutOfRangeIsRefused() {
        EmbeddingSession s = EmbeddingSession.open(Phantoms.zeroBackground16().image());
        EmbeddingException e = assertThrows(EmbeddingException.class,
                () -> Markings.writeF(s, new Random(TEST_SEED_DEPTH)));
        assertEquals(EMBEDDING_RANGE_UNSUPPORTED, e.reason());
        assertTrue(s.rangeExitCount() >= 1);
        // Session en échec : plus aucune opération, aucune image.
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class, () -> s.writeS(new byte[64])).reason());
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class, s::finish).reason());
    }

    @Test
    @DisplayName("Extraction avec Δ(8) sur une copie 12 bits : les bits F sont perdus (Δ doit venir du registre)")
    void wrongDeltaLosesTheBits() {
        Markings.Marking m = Markings.markRandom(Phantoms.normal().image(), new Random(TEST_SEED_DEPTH));
        assertEquals(0, Markings.fBitErrors(FBlockReader.of(m.marked(), m.partitions(), 12), m.f()));
        // Dithers dans [0, Δ(8)) pour le lecteur à Δ(8) : on relit les mêmes tirages, à la mauvaise échelle.
        Markings.FWrites scaled = rescaledDither(m.f(), 1.0 / 16);
        int errors = Markings.fBitErrors(FBlockReader.of(m.marked(), m.partitions(), 8), scaled);
        int total = m.f().indices().length * EmbeddingProfile.F_BITS_PER_BLOCK;
        double rate = 1.0 - (double) errors / total;
        assertTrue(rate > 0.40 && rate < 0.60, "taux de bits relus avec un mauvais Δ : " + rate);
    }

    private static Markings.FWrites rescaledDither(Markings.FWrites f, double factor) {
        double[][] d = new double[f.dither().length][];
        for (int k = 0; k < d.length; k++) {
            d[k] = f.dither()[k].clone();
            for (int j = 0; j < d[k].length; j++) d[k][j] *= factor;
        }
        return new Markings.FWrites(f.indices(), f.bits(), d);
    }

    // ------------------------------------------------------------------------------------------ copie signée relue

    @Test
    @DisplayName("Copie signée relue (BitsStored = 16 ou B) : candidats → mêmes blocs W, S et F ; pixels négatifs dans le fond")
    void roniDetectionOnSignedCopyReadBack() {
        Markings.Marking m = Markings.markRandom(Phantoms.zeroBackground().image(), new Random(TEST_SEED_DEPTH));
        assertTrue(m.result().signedOutput());
        PixelImage marked = m.marked();
        short[] values = new short[marked.pixelCount()];
        for (int i = 0; i < values.length; i++) values[i] = (short) marked.pixel(i);
        for (int readBackBits : new int[] {16, marked.bitsStored()}) {
            // Le module dicom relit la copie avec PixelRepresentation = 1 (BitsStored = 16, ou B depuis le registre).
            PixelImage readBack = PixelImage.signedFromShorts(marked.rows(), marked.cols(), readBackBits,
                    marked.photometric(), values, false, false);
            List<RoniProfile> cands = RoniDetector.candidates(readBack);
            RoniProfile same = cands.stream().filter(c -> c.sameMask(m.profile())).findFirst().orElseThrow();
            Partitions p = Partitions.of(same);
            assertArrayEquals(m.partitions().fBlocks(), p.fBlocks());
            for (int k = 0; k < EmbeddingProfile.W_BLOCKS; k++) assertEquals(m.partitions().wBlock(k), p.wBlock(k));
            for (int k = 0; k < EmbeddingProfile.S_BLOCKS; k++) assertEquals(m.partitions().sBlock(k), p.sBlock(k));

            // Un pixel négatif est classé comme fond (q négatif, sous le seuil, polarité sombre).
            int t = same.threshold();
            assertEquals(BackgroundPolarity.DARK, same.polarity());
            long[] background = RoniDetector.borderConnectedBackground(readBack.view(), true, readBack.rows(),
                    readBack.cols(), t, same.polarity(), RoniDetector.QUEUE_CAPACITY);
            int negatives = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i] < 0) {
                    negatives++;
                    assertTrue(Bits.get(background, i), "pixel négatif hors du fond");
                }
            }
            assertTrue(negatives > 0);
        }
    }

    @Test
    @DisplayName("Représentation signée : encode puis decode = identité sur [−32 768, 32 767] ; non signé inchangé")
    void signedRepresentationRoundTrip() {
        for (int v : new int[] {-32768, -1, 0, 1, 4095, 32767}) {
            assertEquals(v, PixelImage.decode(PixelImage.encode(v, true), true));
        }
        for (int v : new int[] {0, 255, 4095, 65535}) {
            assertEquals(v, PixelImage.decode(PixelImage.encode(v, false), false));
        }
        PixelImage s = PixelImage.signedFromShorts(1, 3, 12, Photometric.MONOCHROME2, new short[] {-5, 0, 4100},
                false, false);
        assertEquals(-5, s.pixel(0));
        assertEquals(4100, s.pixel(2));
        assertEquals(INVALID_INPUT, assertThrows(EmbeddingException.class, () -> EmbeddingSession.open(s)).reason());
    }

    // ------------------------------------------------------------------------------------------ PixelPaddingValue

    @Test
    @DisplayName("PixelPaddingValue égal au fond nul : blocs de remplissage nécessaires à la capacité → PIXEL_PADDING_IN_RONI")
    void paddingEqualToBackgroundIsRefused() {
        PixelImage img = Phantoms.zeroBackground().image().withPixelPaddingValue(0);
        assertEquals(PIXEL_PADDING_IN_RONI,
                assertThrows(EmbeddingException.class, () -> EmbeddingSession.open(img)).reason());
    }

    @Test
    @DisplayName("PixelPaddingValue dans un coin du fond : blocs exclus de la RONI, pixels de remplissage jamais modifiés")
    void paddingPixelsAreNeverModified() {
        PixelImage base = Phantoms.normal().image();
        int cols = base.cols();
        int[] px = new int[base.pixelCount()];
        for (int i = 0; i < px.length; i++) px[i] = base.pixel(i);
        int padding = 0; // absent du fantôme normal (fond ≥ 160)
        for (int r = 0; r < 64; r++) {
            for (int c = 0; c < 64; c++) px[r * cols + c] = padding;
        }
        PixelImage img = PixelImage.fromInts(base.rows(), cols, base.bitsStored(), base.photometric(), px, false,
                false).withPixelPaddingValue(padding);
        Markings.Marking m = Markings.markRandom(img, new Random(TEST_SEED_DEPTH));
        for (int i = 0; i < px.length; i++) {
            if (px[i] == padding) {
                assertFalse(m.profile().inRoni(i), "pixel de remplissage dans la RONI");
                assertEquals(padding, m.marked().pixel(i), "pixel de remplissage modifié");
            }
        }
        // Sans la déclaration, ces blocs de coin font partie de la RONI.
        RoniProfile undeclared = RoniDetector.detect(PixelImage.fromInts(base.rows(), cols, base.bitsStored(),
                base.photometric(), px, false, false));
        assertTrue(undeclared.inRoni(8 * cols + 8));
        assertFalse(m.profile().inRoni(8 * cols + 8));
    }
}
