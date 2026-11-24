package tn.medimagetrust.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("SEC-M01")
@Tag("SEC-M03")
@DisplayName("Test de propriétés : 200 images aléatoires, les invariants du marquage tiennent toujours")
class InvariantPropertyTest {
    private static final long TEST_SEED_PROPERTY = 0x50524F50L;
    private static final int IMAGES = 200;

    @Test
    @DisplayName("ROI intacte, écart F ≤ 12, écart S/W ≤ 1, blocs F non utilisés intacts, PSNR ≥ 40 dB, masque stable")
    void invariantsHoldOnRandomImages() {
        Random rnd = new Random(TEST_SEED_PROPERTY);
        Set<Integer> depths = new HashSet<>();
        Set<BackgroundPolarity> polarities = new HashSet<>();
        for (int n = 0; n < IMAGES; n++) {
            PixelImage original = Phantoms.build(Phantoms.randomMarkableSpec(rnd), rnd.nextLong()).image();
            char[] before = original.pixels();
            Markings.Marking m = Markings.markRandom(original, rnd);
            check(m);
            assertArrayEquals(before, original.pixels(), "l'original a été modifié");
            depths.add(original.bitsStored());
            polarities.add(m.profile().polarity());
        }
        // Le générateur couvre bien l'espace annoncé.
        assertEquals(9, depths.size());
        assertEquals(2, polarities.size());
    }

    private static void check(Markings.Marking m) {
        char[] o = m.original().pixels();
        char[] k = m.marked().pixels();
        Partitions p = m.partitions();
        int cols = p.cols();
        byte[] kind = new byte[o.length]; // 0 ROI, 1 S/W, 2 F utilisé, 3 F non utilisé
        for (int b : p.wBlocks()) fill(kind, p, b, cols, (byte) 1);
        for (int b : p.sBlocks()) fill(kind, p, b, cols, (byte) 1);
        Set<Integer> used = new HashSet<>();
        for (int f : m.f().indices()) used.add(f);
        for (int f = 0; f < p.fClassSize(); f++) fill(kind, p, p.fBlock(f), cols, used.contains(f) ? (byte) 2 : 3);
        for (int i = 0; i < o.length; i++) {
            int dev = Math.abs(o[i] - k[i]);
            switch (kind[i]) {
                case 0 -> assertEquals(0, dev, "ROI modifiée");
                case 1 -> assertTrue(dev <= EmbeddingProfile.MAX_LSB_DEVIATION, "écart S/W");
                case 2 -> assertTrue(dev <= EmbeddingProfile.MAX_F_DEVIATION, "écart F");
                default -> assertEquals(0, dev, "bloc F non utilisé modifié");
            }
            assertEquals(kind[i] != 0, m.profile().inRoni(i));
        }
        assertTrue(EmbeddingSession.psnr(o, k, m.original().maxValue()) >= EmbeddingProfile.MIN_PSNR_DB);
        assertTrue(RoniDetector.candidates(m.marked()).stream().anyMatch(c -> c.sameMask(m.profile())));
        LsbChannel lsb = LsbChannel.reader(m.marked(), p);
        assertTrue(Arrays.equals(m.s(), lsb.readS()) && Arrays.equals(m.w(), lsb.readW()));
        assertEquals(0, Markings.fBitErrors(FBlockReader.of(m.marked(), p), m.f()));
    }

    private static void fill(byte[] kind, Partitions p, int block, int cols, byte v) {
        int top = p.blockTop(block);
        int left = p.blockLeft(block);
        for (int r = 0; r < 8; r++) Arrays.fill(kind, (top + r) * cols + left, (top + r) * cols + left + 8, v);
    }
}
