package tn.medimagetrust.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("SEC-M01")
@Tag("SEC-D04")
@DisplayName("Partitions : 26 blocs W, 8 blocs S, le reste en classe F, en ordre raster et sans chevauchement")
class PartitionsTest {

    @Test
    @DisplayName("26 / 8 / reste, pris dans l'ordre raster de la RONI")
    void splitInRasterOrder() {
        RoniProfile p = RoniDetector.detect(Phantoms.normal().image());
        Partitions parts = Partitions.of(p);
        int[] all = p.roniBlocks();
        assertEquals(EmbeddingProfile.W_BLOCKS, parts.wBlocks().length);
        assertEquals(EmbeddingProfile.S_BLOCKS, parts.sBlocks().length);
        assertEquals(all.length - 34, parts.fClassSize());
        assertTrue(parts.fClassSize() >= EmbeddingProfile.MIN_F_CLASS_BLOCKS);
        int k = 0;
        for (int b : parts.wBlocks()) assertEquals(all[k++], b);
        for (int b : parts.sBlocks()) assertEquals(all[k++], b);
        for (int b : parts.fBlocks()) assertEquals(all[k++], b);
        assertEquals(all.length, k);
    }

    @Test
    @DisplayName("Aucun chevauchement entre W, S et F ; leur union est exactement la RONI")
    void disjointAndComplete() {
        RoniProfile p = RoniDetector.detect(Phantoms.normal().image());
        Partitions parts = Partitions.of(p);
        Set<Integer> seen = new HashSet<>();
        for (int b : parts.wBlocks()) assertTrue(seen.add(b));
        for (int b : parts.sBlocks()) assertTrue(seen.add(b));
        for (int b : parts.fBlocks()) assertTrue(seen.add(b));
        Set<Integer> roni = new HashSet<>();
        for (int b : p.roniBlocks()) roni.add(b);
        assertEquals(roni, seen);
    }

    @Test
    @DisplayName("Copies défensives : modifier un tableau renvoyé ne change pas les partitions")
    void defensiveCopies() {
        Partitions parts = Partitions.of(RoniDetector.detect(Phantoms.normal().image()));
        int[] w = parts.wBlocks();
        int[] expected = w.clone();
        w[0] = -1;
        assertArrayEquals(expected, parts.wBlocks());
    }

    @Test
    @DisplayName("RONI de moins de 290 blocs : TOO_FEW_RONI_BLOCKS ; profil nul : INVALID_INPUT")
    void refusals() {
        RoniProfile small = new RoniProfile(64, 64, 10, BackgroundPolarity.DARK, new int[] {0, 1, 2});
        assertEquals(EmbeddingException.Reason.TOO_FEW_RONI_BLOCKS,
                assertThrows(EmbeddingException.class, () -> Partitions.of(small)).reason());
        assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                assertThrows(EmbeddingException.class, () -> Partitions.of(null)).reason());
    }
}
