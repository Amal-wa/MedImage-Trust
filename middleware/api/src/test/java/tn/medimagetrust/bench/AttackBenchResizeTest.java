package tn.medimagetrust.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.embedding.EmbeddingProfile;
import tn.medimagetrust.embedding.EmbeddingResult;
import tn.medimagetrust.embedding.EmbeddingSession;
import tn.medimagetrust.embedding.FBlockReader;
import tn.medimagetrust.embedding.Partitions;
import tn.medimagetrust.embedding.Phantoms;
import tn.medimagetrust.embedding.Photometric;
import tn.medimagetrust.embedding.PixelImage;

/**
 * Attaque par redimensionnement du banc sur une copie signée : avec l'ancien décalage fixe de 32 768, les valeurs d'une
 * copie signée à fond nul passaient de part et d'autre de 32 768 dans le raster 16 bits de Java 2D, et l'interpolation
 * bilinéaire les détruisait (environ 50 % des bits F relus, au hasard) : l'échec venait du banc, pas du marquage.
 */
class AttackBenchResizeTest {
    private static final long SEED = 0x52455349L;

    private static int bitsRead(PixelImage img, Partitions p, boolean[][] bits, double[][] dither, int b) {
        FBlockReader r = FBlockReader.of(img, p, b);
        int ok = 0;
        for (int k = 0; k < bits.length; k++) {
            int[] got = r.read(k, dither[k]);
            for (int j = 0; j < got.length; j++) if ((got[j] == 1) == bits[k][j]) ok++;
        }
        return ok;
    }

    @Test
    void signedZeroBackgroundCopySurvivesResize() {
        Random rnd = new Random(SEED);
        PixelImage original = Phantoms.build(new Phantoms.Spec(512, 512, 12, Photometric.MONOCHROME2, 0, 0, 2000, 200,
                256, 256, 150, 190), SEED).image();
        EmbeddingSession session = EmbeddingSession.open(original);
        int n = EmbeddingProfile.F_USED_BLOCKS;
        boolean[][] bits = new boolean[n][EmbeddingProfile.F_BITS_PER_BLOCK];
        double[][] dither = new double[n][EmbeddingProfile.F_BITS_PER_BLOCK];
        for (int k = 0; k < n; k++) {
            for (int j = 0; j < bits[k].length; j++) {
                bits[k][j] = rnd.nextBoolean();
                dither[k][j] = rnd.nextDouble() * session.fChannel().qimDelta();
            }
            session.fChannel().write(k, bits[k], dither[k]);
        }
        session.writeS(new byte[EmbeddingProfile.S_BYTES]);
        session.writeW(new byte[EmbeddingProfile.W_BYTES]);
        EmbeddingResult result = session.finish();
        assertTrue(result.signedOutput(), "copie signée attendue (fond à 0)");

        PixelImage attacked = AttackBench.image(result.image(), AttackBench.resize(result.image()));
        int total = n * EmbeddingProfile.F_BITS_PER_BLOCK;
        int ok = bitsRead(attacked, session.partitions(), bits, dither, 12);
        assertTrue(ok >= 0.95 * total, "bits F relus après redimensionnement : " + ok + " / " + total);
    }

    @Test
    void unsignedImagesKeepTheZeroOffset() {
        PixelImage img = Phantoms.build(Phantoms.normalSpec(), SEED).image();
        assertEquals(0, AttackBench.resizeOffset(img));
    }
}
