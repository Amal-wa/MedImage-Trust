package tn.medimagetrust.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.IMAGE_TOO_LARGE;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;

@Tag("SEC-D02")
@Tag("SEC-F02")
@DisplayName("Cas d'abus : bombe de décodage et entrées malformées rejetées avant toute allocation")
class EmbeddingLimitsTest {

    private static EmbeddingException.Reason check(int rows, int cols, int bits) {
        return assertThrows(EmbeddingException.class, () -> EmbeddingLimits.checkDimensions(rows, cols, bits))
                .reason();
    }

    @Test
    @DisplayName("Image géante 20 000 × 20 000 × 16 bits : IMAGE_TOO_LARGE en moins de 2 s, sans allocation")
    void decompressionBombRejectedFast() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertEquals(IMAGE_TOO_LARGE, check(20_000, 20_000, 16));
            // Le constructeur contrôle les dimensions avant même de regarder le tableau (ici nul) :
            // aucune copie ni allocation de pixels n'a lieu.
            assertEquals(IMAGE_TOO_LARGE, assertThrows(EmbeddingException.class,
                    () -> new PixelImage(20_000, 20_000, 16, Photometric.MONOCHROME2, null, false, false)).reason());
            assertEquals(IMAGE_TOO_LARGE, assertThrows(EmbeddingException.class,
                    () -> new PixelImage(20_000, 20_000, 16, Photometric.MONOCHROME2, new int[4], false, false))
                    .reason());
        });
    }

    @Test
    @DisplayName("Produit rows × cols qui déborde d'un int : IMAGE_TOO_LARGE (calcul en long)")
    void intOverflowRejected() {
        assertEquals(IMAGE_TOO_LARGE, check(65_536, 65_536, 16));          // 2^32 → 0 en int
        assertEquals(IMAGE_TOO_LARGE, check(Integer.MAX_VALUE, Integer.MAX_VALUE, 12));
        assertEquals(IMAGE_TOO_LARGE, check(46_341, 46_341, 12));          // > 2^31 − 1
    }

    @Test
    @DisplayName("rows ou cols ≤ 0 : INVALID_INPUT")
    void nonPositiveDimensions() {
        assertEquals(INVALID_INPUT, check(0, 512, 12));
        assertEquals(INVALID_INPUT, check(512, 0, 12));
        assertEquals(INVALID_INPUT, check(-1, 512, 12));
        assertEquals(INVALID_INPUT, check(512, Integer.MIN_VALUE, 12));
    }

    @Test
    @DisplayName("Limites exactes : 36 000 000 px acceptés, un de plus refusé ; 8 192 par côté accepté, 8 193 refusé")
    void exactLimits() {
        assertDoesNotThrow(() -> EmbeddingLimits.checkDimensions(6_000, 6_000, 16));
        assertEquals(IMAGE_TOO_LARGE, check(6_000, 6_001, 16));
        assertDoesNotThrow(() -> EmbeddingLimits.checkDimensions(8_192, 4_394, 16));
        assertDoesNotThrow(() -> EmbeddingLimits.checkDimensions(1, 8_192, 8));
        assertEquals(IMAGE_TOO_LARGE, check(8_193, 1, 8));
        assertEquals(IMAGE_TOO_LARGE, check(1, 8_193, 8));
    }

    @Test
    @DisplayName("bitsStored hors de [8, 16] : INVALID_INPUT")
    void bitsStoredOutOfRange() {
        assertEquals(INVALID_INPUT, check(512, 512, 7));
        assertEquals(INVALID_INPUT, check(512, 512, 17));
        assertEquals(INVALID_INPUT, check(512, 512, 0));
        assertDoesNotThrow(() -> EmbeddingLimits.checkDimensions(512, 512, 8));
        assertDoesNotThrow(() -> EmbeddingLimits.checkDimensions(512, 512, 16));
    }

    @Test
    @DisplayName("PixelImage malformée : longueur ≠ rows × cols, pixel > maxValue ou < 0, tableau ou photométrie nuls")
    void malformedPixelImage() {
        assertEquals(INVALID_INPUT, assertThrows(EmbeddingException.class,
                () -> new PixelImage(4, 4, 8, Photometric.MONOCHROME2, new int[15], false, false)).reason());
        assertEquals(INVALID_INPUT, assertThrows(EmbeddingException.class,
                () -> new PixelImage(4, 4, 8, Photometric.MONOCHROME2, new int[17], false, false)).reason());
        int[] tooBright = new int[16];
        tooBright[7] = 256;
        assertEquals(INVALID_INPUT, assertThrows(EmbeddingException.class,
                () -> new PixelImage(4, 4, 8, Photometric.MONOCHROME2, tooBright, false, false)).reason());
        int[] negative = new int[16];
        negative[3] = -1;
        assertEquals(INVALID_INPUT, assertThrows(EmbeddingException.class,
                () -> new PixelImage(4, 4, 12, Photometric.MONOCHROME2, negative, false, false)).reason());
        assertEquals(INVALID_INPUT, assertThrows(EmbeddingException.class,
                () -> new PixelImage(4, 4, 8, Photometric.MONOCHROME2, null, false, false)).reason());
        assertEquals(INVALID_INPUT, assertThrows(EmbeddingException.class,
                () -> new PixelImage(4, 4, 8, null, new int[16], false, false)).reason());
        assertEquals(INVALID_INPUT, assertThrows(EmbeddingException.class,
                () -> new PixelImage(4, 4, 7, Photometric.MONOCHROME2, new int[16], false, false)).reason());
        int[] max16 = new int[16];
        java.util.Arrays.fill(max16, 65_535);
        assertDoesNotThrow(() -> new PixelImage(4, 4, 16, Photometric.MONOCHROME1, max16, false, false));
    }

    @Test
    @DisplayName("Accès hors limites à un pixel : INVALID_INPUT (jamais d'exception non fermée)")
    void outOfBoundsAccess() {
        PixelImage img = new PixelImage(4, 4, 8, Photometric.MONOCHROME2, new int[16], false, false);
        assertEquals(INVALID_INPUT, assertThrows(EmbeddingException.class, () -> img.pixel(16)).reason());
        assertEquals(INVALID_INPUT, assertThrows(EmbeddingException.class, () -> img.pixel(-1)).reason());
        assertEquals(INVALID_INPUT, assertThrows(EmbeddingException.class, () -> img.pixel(4, 0)).reason());
    }
}
