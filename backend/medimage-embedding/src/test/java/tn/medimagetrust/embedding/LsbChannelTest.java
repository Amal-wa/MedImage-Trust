package tn.medimagetrust.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("SEC-M03")
@Tag("SEC-D03")
@DisplayName("Scellement LSB de S et W : aller-retour exact, écart ≤ 1, sceau fragile")
class LsbChannelTest {
    private static final long TEST_SEED_LSB = 0x4C534231L;

    private final PixelImage image = Phantoms.normal().image();
    private final Partitions parts = Partitions.of(RoniDetector.detect(image));

    @Test
    @DisplayName("Aller-retour S (64 octets) et W (208 octets), écart ≤ 1, aucun pixel hors S et W touché")
    void roundTripAndDeviation() {
        Random rnd = new Random(TEST_SEED_LSB);
        char[] work = image.pixels();
        LsbChannel ch = new LsbChannel(work, parts, true);
        byte[] s = Markings.randomBytes(64, rnd);
        byte[] w = Markings.randomBytes(208, rnd);
        ch.writeS(s);
        ch.writeW(w);
        assertArrayEquals(s, ch.readS());
        assertArrayEquals(w, ch.readW());
        char[] orig = image.pixels();
        boolean[] sw = new boolean[orig.length];
        for (int b : parts.sBlocks()) mark(sw, b);
        for (int b : parts.wBlocks()) mark(sw, b);
        for (int i = 0; i < orig.length; i++) {
            int dev = Math.abs(work[i] - orig[i]);
            assertTrue(dev <= EmbeddingProfile.MAX_LSB_DEVIATION);
            if (!sw[i]) assertEquals(0, dev);
        }
        LsbChannel reader = LsbChannel.reader(image.withPixels(work), parts);
        assertArrayEquals(s, reader.readS());
        assertArrayEquals(w, reader.readW());
    }

    @Test
    @DisplayName("Ordre des bits : premier pixel du premier bloc W = bit de poids fort du premier octet")
    void bitOrder() {
        char[] work = image.pixels();
        LsbChannel ch = new LsbChannel(work, parts, true);
        byte[] w = new byte[208];
        w[0] = (byte) 0x80;
        ch.writeW(w);
        int b0 = parts.wBlock(0);
        int first = parts.blockTop(b0) * image.cols() + parts.blockLeft(b0);
        assertEquals(1, work[first] & 1);
        assertEquals(0, work[first + 1] & 1);
        assertEquals(0, work[first + image.cols()] & 1);
    }

    @Test
    @DisplayName("Un seul bit modifié dans un bloc S ou W de la copie : la relecture diffère (sceau fragile)")
    void singleBitFlipIsDetected() {
        Random rnd = new Random(TEST_SEED_LSB);
        char[] work = image.pixels();
        LsbChannel ch = new LsbChannel(work, parts, true);
        byte[] s = Markings.randomBytes(64, rnd);
        byte[] w = Markings.randomBytes(208, rnd);
        ch.writeS(s);
        ch.writeW(w);
        int sb = parts.sBlock(3);
        int ps = (parts.blockTop(sb) + 5) * image.cols() + parts.blockLeft(sb) + 2;
        work[ps] ^= 1;
        assertFalse(java.util.Arrays.equals(s, ch.readS()));
        assertArrayEquals(w, ch.readW());
        work[ps] ^= 1;
        int wb = parts.wBlock(25);
        int pw = (parts.blockTop(wb) + 7) * image.cols() + parts.blockLeft(wb) + 7;
        work[pw] ^= 1;
        assertFalse(java.util.Arrays.equals(w, ch.readW()));
        assertArrayEquals(s, ch.readS());
    }

    @Test
    @DisplayName("Mauvaise taille ou tableau nul : INVALID_INPUT")
    void wrongSizes() {
        LsbChannel ch = new LsbChannel(image.pixels(), parts, true);
        for (int n : new int[] {0, 63, 65, 208}) {
            assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                    assertThrows(EmbeddingException.class, () -> ch.writeS(new byte[n])).reason());
        }
        for (int n : new int[] {0, 64, 207, 209}) {
            assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                    assertThrows(EmbeddingException.class, () -> ch.writeW(new byte[n])).reason());
        }
        assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                assertThrows(EmbeddingException.class, () -> ch.writeS(null)).reason());
        assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                assertThrows(EmbeddingException.class, () -> ch.writeW(null)).reason());
    }

    @Test
    @DisplayName("Lecteur public : lecture seule (écriture refusée), dimensions vérifiées")
    void readerIsReadOnly() {
        LsbChannel reader = LsbChannel.reader(image, parts);
        assertEquals(EmbeddingException.Reason.ILLEGAL_STATE,
                assertThrows(EmbeddingException.class, () -> reader.writeS(new byte[64])).reason());
        assertEquals(EmbeddingException.Reason.ILLEGAL_STATE,
                assertThrows(EmbeddingException.class, () -> reader.writeW(new byte[208])).reason());
        assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                assertThrows(EmbeddingException.class,
                        () -> LsbChannel.reader(Phantoms.small64().image(), parts)).reason());
        assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                assertThrows(EmbeddingException.class, () -> LsbChannel.reader(null, parts)).reason());
    }

    private void mark(boolean[] m, int block) {
        int top = parts.blockTop(block);
        int left = parts.blockLeft(block);
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) m[(top + r) * image.cols() + left + c] = true;
    }
}
