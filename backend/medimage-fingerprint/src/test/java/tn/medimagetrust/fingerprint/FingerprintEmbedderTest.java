package tn.medimagetrust.fingerprint;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Couche d'écriture/lecture v2.4 : 1 024 symboles dans 128 blocs secrets, 8 coefficients QIM par bloc. */
class FingerprintEmbedderTest {
    private static final int CLASS = 2 * FingerprintParams.MIN_F_CLASS_BLOCKS; // 512 blocs
    private final Prf prf = TestKeys.prf(1);
    private final FingerprintEmbedder embedder = new FingerprintEmbedder(prf);
    private final TardosCodeGenerator gen = new TardosCodeGenerator(prf);
    private final byte[] mask = TestKeys.id(3);

    private boolean[] aliceBits() {
        return TardosCodeGenerator.expand(gen.symbols(TestKeys.id(1), "alice"));
    }

    @Test
    void embedThenExtractRestoresTheCode() {
        byte[] x = gen.symbols(TestKeys.id(1), "alice");
        InMemoryFBlockChannel f = new InMemoryFBlockChannel(CLASS, 42);
        embedder.embed(f, mask, TardosCodeGenerator.expand(x));
        ExtractedWord w = embedder.extract(f, mask);
        assertArrayEquals(x, w.symbols()); // 100 % des 1 024 symboles
        assertEquals(0, w.erasures());
    }

    @Test
    void embedWritesExactly128BlocksOnceAndNeverTheOthers() {
        InMemoryFBlockChannel before = new InMemoryFBlockChannel(CLASS, 42);
        InMemoryFBlockChannel after = before.copy();
        embedder.embed(after, mask, aliceBits());
        List<InMemoryFBlockChannel.Write> writes = after.writes();
        assertEquals(FingerprintParams.USED_BLOCKS, writes.size());
        Set<Integer> chosen = new HashSet<>();
        for (var w : writes) assertTrue(chosen.add(w.fIndex()), "bloc écrit deux fois");
        for (int i = 0; i < CLASS; i++) {
            if (!chosen.contains(i)) assertArrayEquals(before.pixels(i), after.pixels(i), "bloc non choisi modifié");
        }
    }

    @Test
    void symbolGoesToBlockPosIDiv8AtCoefficientIMod8() {
        boolean[] bits = aliceBits();
        InMemoryFBlockChannel f = new InMemoryFBlockChannel(CLASS, 42);
        embedder.embed(f, mask, bits);
        int[] pos = PositionPermutation.select(prf, mask, CLASS, FingerprintParams.USED_BLOCKS);
        List<InMemoryFBlockChannel.Write> writes = f.writes();
        for (int k = 0; k < pos.length; k++) {
            assertEquals(pos[k], writes.get(k).fIndex());
            for (int j = 0; j < 8; j++) assertEquals(bits[8 * k + j], writes.get(k).bits()[j]);
        }
    }

    @Test
    void sameMaskAndKeyGiveSameBlocksAndDithersOtherKeyGivesOtherBlocks() {
        InMemoryFBlockChannel a = new InMemoryFBlockChannel(CLASS, 42);
        InMemoryFBlockChannel b = new InMemoryFBlockChannel(CLASS, 7); // contenu différent, mêmes clé et masque
        embedder.embed(a, mask, aliceBits());
        new FingerprintEmbedder(TestKeys.prf(1)).embed(b, mask, aliceBits());
        for (int k = 0; k < FingerprintParams.USED_BLOCKS; k++) {
            assertEquals(a.writes().get(k).fIndex(), b.writes().get(k).fIndex());
            assertArrayEquals(a.writes().get(k).dither(), b.writes().get(k).dither());
        }

        InMemoryFBlockChannel c = new InMemoryFBlockChannel(CLASS, 42);
        new FingerprintEmbedder(TestKeys.prf(2)).embed(c, mask, aliceBits());
        int[] blocksA = a.writes().stream().mapToInt(InMemoryFBlockChannel.Write::fIndex).toArray();
        int[] blocksC = c.writes().stream().mapToInt(InMemoryFBlockChannel.Write::fIndex).toArray();
        assertFalse(Arrays.equals(blocksA, blocksC));
        assertFalse(Arrays.equals(a.writes().get(0).dither(), c.writes().get(0).dither()));
    }

    @Test
    void dithersAreInRangeAndDependOnTheBlock() {
        double[] d0 = embedder.dithers(mask, 0);
        double[] d1 = embedder.dithers(mask, 1);
        assertEquals(8, d0.length);
        for (double v : d0) assertTrue(v >= 0 && v < FingerprintParams.QIM_DELTA);
        assertFalse(Arrays.equals(d0, d1));
        assertFalse(Arrays.equals(d0, embedder.dithers(TestKeys.id(4), 0)));
        assertArrayEquals(d0, new FingerprintEmbedder(TestKeys.prf(1)).dithers(mask, 0));
    }

    @Test
    void wrongMaskGivesGarbage() {
        byte[] x = gen.symbols(TestKeys.id(1), "alice");
        InMemoryFBlockChannel f = new InMemoryFBlockChannel(CLASS, 42);
        embedder.embed(f, mask, TardosCodeGenerator.expand(x));
        ExtractedWord w = embedder.extract(f, TestKeys.id(4));
        int equal = 0;
        for (int i = 0; i < x.length; i++) if (x[i] == w.symbols()[i]) equal++;
        assertTrue(equal < 0.7 * x.length, "blocs devinés sans la bonne graine ?");
    }

    @Test
    void croppedCopyProducesErasuresByWholeBlocks() {
        byte[] x = gen.symbols(TestKeys.id(1), "alice");
        InMemoryFBlockChannel f = new InMemoryFBlockChannel(CLASS, 42);
        embedder.embed(f, mask, TardosCodeGenerator.expand(x));
        f.cropFrom(CLASS / 2);
        ExtractedWord w = embedder.extract(f, mask);
        assertTrue(w.erasures() > 0);
        assertEquals(0, w.erasures() % FingerprintParams.BITS_PER_BLOCK);
        for (int i = 0; i < x.length; i++) {
            if (w.symbols()[i] != ExtractedWord.ERASURE) assertEquals(x[i], w.symbols()[i]);
        }
    }

    @Test
    void classOf255BlocksFailsClosedWithCapacity() {
        InMemoryFBlockChannel small = new InMemoryFBlockChannel(FingerprintParams.MIN_F_CLASS_BLOCKS - 1, 1);
        var ex = assertThrows(FingerprintException.class, () -> embedder.embed(small, mask, aliceBits()));
        assertEquals(FingerprintException.Code.CAPACITY, ex.code());
        assertTrue(small.writes().isEmpty());
        assertEquals(FingerprintException.Code.CAPACITY,
                assertThrows(FingerprintException.class, () -> embedder.extract(small, mask)).code());
        assertFalse(ex.getMessage().matches(".*\\d.*"), "le message ne doit révéler aucune taille");
    }

    @Test
    void wrongCodeLengthIsRefused() {
        InMemoryFBlockChannel f = new InMemoryFBlockChannel(CLASS, 42);
        var ex = assertThrows(FingerprintException.class, () -> embedder.embed(f, mask, new boolean[3072]));
        assertEquals(FingerprintException.Code.INVALID_INPUT, ex.code());
        assertTrue(f.writes().isEmpty());
    }
}
