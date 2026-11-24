package tn.medimagetrust.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("SEC-M01")
@DisplayName("Érosion de 8 px : marge exacte autour du corps, jamais de débordement")
class ErosionTest {
    private static final long TEST_SEED_EROSION = 0x45524F53L;

    private static boolean[] erode(boolean[] mask, int rows, int cols) {
        boolean[] copy = mask.clone();
        RoniDetector.erodeInPlace(copy, rows, cols, EmbeddingProfile.EROSION_PX);
        return copy;
    }

    /** Référence naïve en O(n·17²) : l'extérieur compte comme du fond. */
    private static boolean[] bruteForce(boolean[] mask, int rows, int cols, int radius) {
        boolean[] out = new boolean[mask.length];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                boolean all = true;
                for (int dr = -radius; dr <= radius && all; dr++) {
                    for (int dc = -radius; dc <= radius && all; dc++) {
                        int rr = r + dr;
                        int cc = c + dc;
                        if (rr >= 0 && rr < rows && cc >= 0 && cc < cols && !mask[rr * cols + cc]) all = false;
                    }
                }
                out[r * cols + c] = all;
            }
        }
        return out;
    }

    @Test
    @DisplayName("Carré de fond isolé de 30 px : il reste le carré central de 14 px (8 px retirés de chaque côté)")
    void isolatedSquareShrinksByEightEachSide() {
        int rows = 60;
        int cols = 60;
        boolean[] mask = new boolean[rows * cols];
        for (int r = 15; r < 45; r++) for (int c = 15; c < 45; c++) mask[r * cols + c] = true;
        boolean[] e = erode(mask, rows, cols);
        int count = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                boolean expected = r >= 23 && r < 37 && c >= 23 && c < 37;
                assertEquals(expected, e[r * cols + c]);
                if (e[r * cols + c]) count++;
            }
        }
        assertEquals(14 * 14, count);
    }

    @Test
    @DisplayName("L'extérieur de l'image compte comme du fond : un masque plein reste plein")
    void outsideCountsAsBackground() {
        boolean[] mask = new boolean[20 * 30];
        java.util.Arrays.fill(mask, true);
        boolean[] e = erode(mask, 20, 30);
        for (boolean b : e) assertTrue(b);
    }

    @Test
    @DisplayName("Masques aléatoires : identique à la référence naïve et toujours inclus dans le masque d'origine")
    void matchesBruteForceAndNeverOverflows() {
        Random rnd = new Random(TEST_SEED_EROSION);
        for (int trial = 0; trial < 30; trial++) {
            int rows = 1 + rnd.nextInt(60);
            int cols = 1 + rnd.nextInt(60);
            boolean[] mask = new boolean[rows * cols];
            double density = 0.90 + rnd.nextDouble() * 0.1;
            for (int i = 0; i < mask.length; i++) mask[i] = rnd.nextDouble() < density;
            boolean[] e = erode(mask, rows, cols);
            assertArrayEquals(bruteForce(mask, rows, cols, EmbeddingProfile.EROSION_PX), e);
            for (int i = 0; i < mask.length; i++) assertTrue(!e[i] || mask[i], "débordement du masque");
        }
    }
}
