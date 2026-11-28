package tn.medimagetrust.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("SEC-M01")
@DisplayName("SEC-M01 : le masque recalculé sur la copie marquée contient le masque d'origine")
class MaskStabilityTest {
    private static final long TEST_SEED_MASK = 0x4D41534BL;
    private static final int MARKINGS = 50;

    @Test
    @DisplayName("50 marquages aléatoires : candidates(copie marquée) contient le masque d'origine dans 100 % des cas")
    void maskIsStableOverFiftyMarkings() {
        Random rnd = new Random(TEST_SEED_MASK);
        int stable = 0;
        int sameThreshold = 0;
        for (int i = 0; i < MARKINGS; i++) {
            PixelImage original = Phantoms.normal(rnd.nextLong()).image();
            Markings.Marking m = Markings.markRandom(original, rnd);
            List<RoniProfile> cands = RoniDetector.candidates(m.marked());
            if (cands.stream().anyMatch(c -> c.sameMask(m.profile()))) stable++;
            if (cands.get(0).sameMask(m.profile())) sameThreshold++;
        }
        assertEquals(MARKINGS, stable);
        assertTrue(sameThreshold > 0);
    }

    @Test
    @DisplayName("Fond clair et fond à 0 : après F, S et W écrits à la main, le masque est toujours retrouvé")
    void maskStableOnOtherBackgrounds() {
        Random rnd = new Random(TEST_SEED_MASK);
        for (PixelImage img : new PixelImage[] {Phantoms.lightBackground().image(),
                Phantoms.zeroBackground().image()}) {
            RoniProfile profile = RoniDetector.detect(img);
            Partitions parts = Partitions.of(profile);
            char[] work = img.pixels();
            double delta = EmbeddingProfile.qimDelta(img.bitsStored());
            int bound = EmbeddingProfile.maxFDeviation(img.bitsStored());
            // Même règle que la session : aucun bornage ; représentation signée dès qu'une valeur sort de la plage.
            boolean signed = false;
            for (int k : Markings.pickFIndices(parts.fClassSize(), EmbeddingProfile.F_USED_BLOCKS, rnd)) {
                int b = parts.fBlock(k);
                int top = parts.blockTop(b);
                int left = parts.blockLeft(b);
                int[] block = QimBlockCodec.writeBlock(QimBlockCodec.getBlock(work, signed, img.cols(), top, left),
                        Markings.randomBits(rnd), Markings.randomDither(rnd, delta), delta, bound);
                for (int v : block) {
                    if (v < 0 || v > img.maxValue()) signed = true;
                }
                QimBlockCodec.putBlock(work, signed, img.cols(), top, left, block);
            }
            LsbChannel lsb = new LsbChannel(work, parts, true);
            lsb.writeS(Markings.randomBytes(64, rnd));
            lsb.writeW(Markings.randomBytes(208, rnd));
            PixelImage marked = img.withPixels(work, signed);
            assertTrue(RoniDetector.candidates(marked).stream().anyMatch(c -> c.sameMask(profile)));
        }
    }
}
