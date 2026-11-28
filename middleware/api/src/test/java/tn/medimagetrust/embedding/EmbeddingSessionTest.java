package tn.medimagetrust.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.ILLEGAL_STATE;

@Tag("SEC-M03")
@Tag("SEC-D03")
@Tag("SEC-D04")
@DisplayName("Session complète F, S, W : ROI intacte, PSNR ≥ 40 dB, marques relues, ordre imposé")
class EmbeddingSessionTest {
    private static final long TEST_SEED_SESSION = 0x53455353L;

    @Test
    @DisplayName("Marquage complet : ROI identique bit à bit, PSNR ≥ 40 dB, S, W et F relus sur la copie")
    void fullSession() {
        PixelImage original = Phantoms.normal().image();
        Markings.Marking m = Markings.markRandom(original, new Random(TEST_SEED_SESSION));
        PixelImage marked = m.marked();
        assertEquals(original.rows(), marked.rows());
        assertEquals(original.photometric(), marked.photometric());
        for (int i = 0; i < original.pixelCount(); i++) {
            if (!m.profile().inRoni(i)) assertEquals(original.pixel(i), marked.pixel(i));
        }
        double psnr = EmbeddingSession.psnr(original.pixels(), marked.pixels(), original.maxValue());
        assertTrue(psnr >= EmbeddingProfile.MIN_PSNR_DB, "PSNR : " + psnr);
        LsbChannel lsb = LsbChannel.reader(marked, m.partitions());
        assertArrayEquals(m.s(), lsb.readS());
        assertArrayEquals(m.w(), lsb.readW());
        assertEquals(0, Markings.fBitErrors(FBlockReader.of(marked, m.partitions(), m.original().bitsStored()), m.f()));
        // Les condensats recalculés sur la copie sont ceux que W signe.
        assertArrayEquals(m.roiDigest(), Digests.roiDigest(marked, m.profile()));
        assertArrayEquals(m.sDigest(), Digests.sDigest(LsbChannel.reader(marked, m.partitions()).readS()));
        boolean found = false;
        for (RoniProfile c : RoniDetector.candidates(marked)) {
            if (c.sameMask(m.profile())) {
                assertArrayEquals(m.maskDigest(), Digests.maskDigest(c));
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    @DisplayName("Fond clair (MONOCHROME1) : marquage complet accepté")
    void lightBackgroundSession() {
        Markings.Marking m = Markings.markRandom(Phantoms.lightBackground().image(), new Random(TEST_SEED_SESSION));
        assertEquals(BackgroundPolarity.LIGHT, m.profile().polarity());
    }

    @Test
    @DisplayName("Sans F (0 bloc écrit) : la session aboutit, la classe F reste intacte")
    void sessionWithoutF() {
        PixelImage original = Phantoms.normal().image();
        EmbeddingSession s = EmbeddingSession.open(original);
        s.writeS(new byte[64]);
        s.writeW(new byte[208]);
        PixelImage marked = s.finish().image();
        for (int b : s.partitions().fBlocks()) {
            int top = s.partitions().blockTop(b);
            int left = s.partitions().blockLeft(b);
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    assertEquals(original.pixel(top + r, left + c), marked.pixel(top + r, left + c));
                }
            }
        }
    }

    @Test
    @DisplayName("Ordre imposé : W avant S, F après S, S deux fois, finish sans W : ILLEGAL_STATE")
    void orderIsEnforced() {
        PixelImage img = Phantoms.normal().image();
        Random rnd = new Random(TEST_SEED_SESSION);

        EmbeddingSession a = EmbeddingSession.open(img);
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class, () -> a.writeW(new byte[208])).reason());

        EmbeddingSession b = EmbeddingSession.open(img);
        b.writeS(new byte[64]);
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class,
                () -> b.fChannel().write(0, Markings.randomBits(rnd), Markings.randomDither(rnd))).reason());

        EmbeddingSession c = EmbeddingSession.open(img);
        c.writeS(new byte[64]);
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class, () -> c.writeS(new byte[64])).reason());

        EmbeddingSession d = EmbeddingSession.open(img);
        d.writeS(new byte[64]);
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class, d::finish).reason());

        EmbeddingSession e = EmbeddingSession.open(img);
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class, e::sDigest).reason());
    }

    @Test
    @DisplayName("Classe F : un bloc écrit deux fois ou un 129e bloc : ILLEGAL_STATE")
    void fBlockLimits() {
        PixelImage img = Phantoms.normal().image();
        Random rnd = new Random(TEST_SEED_SESSION);
        EmbeddingSession a = EmbeddingSession.open(img);
        a.fChannel().write(5, Markings.randomBits(rnd), Markings.randomDither(rnd));
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class,
                () -> a.fChannel().write(5, Markings.randomBits(rnd), Markings.randomDither(rnd))).reason());

        EmbeddingSession b = EmbeddingSession.open(img);
        for (int k = 0; k < EmbeddingProfile.F_USED_BLOCKS; k++) {
            b.fChannel().write(k, Markings.randomBits(rnd), Markings.randomDither(rnd));
        }
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class,
                () -> b.fChannel().write(EmbeddingProfile.F_USED_BLOCKS, Markings.randomBits(rnd),
                        Markings.randomDither(rnd))).reason());
    }

    @Test
    @DisplayName("Les condensats de la session sont ceux de Digests sur l'original")
    void digestsMatch() {
        PixelImage img = Phantoms.normal().image();
        EmbeddingSession s = EmbeddingSession.open(img);
        byte[] payload = Markings.randomBytes(64, new Random(TEST_SEED_SESSION));
        s.writeS(payload);
        assertArrayEquals(Digests.roiDigest(img, s.profile()), s.roiDigest());
        assertArrayEquals(Digests.maskDigest(s.profile()), s.maskDigest());
        assertArrayEquals(Digests.sDigest(payload), s.sDigest());
    }
}
