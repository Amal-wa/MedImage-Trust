package tn.medimagetrust.fingerprint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FingerprintEmbedderTest {
    private static final int CLASS = 2 * FingerprintParams.MIN_F_CLASS_PIXELS; // 12 288
    private final Prf prf = TestKeys.prf(1);
    private final FingerprintEmbedder embedder = new FingerprintEmbedder(prf);
    private final TardosCodeGenerator gen = new TardosCodeGenerator(prf);
    private final byte[] mask = TestKeys.id(3);

    @Test
    void embedThenExtractRestoresTheCode() {
        byte[] x = gen.symbols(TestKeys.id(1), "alice");
        InMemoryFPartition f = new InMemoryFPartition(CLASS, 42);
        embedder.embed(f, mask, TardosCodeGenerator.expand(x));
        ExtractedWord w = embedder.extract(f, mask, FingerprintEmbedder.VotePolicy.MAJORITY);
        assertArrayEquals(x, w.symbols());
        assertEquals(0, w.erasures());
    }

    @Test
    void embedOnlyTouchesTheSecretPositions() {
        InMemoryFPartition before = new InMemoryFPartition(CLASS, 42);
        InMemoryFPartition after = before.copy();
        embedder.embed(after, mask, TardosCodeGenerator.expand(gen.symbols(TestKeys.id(1), "alice")));
        int changed = 0;
        for (int i = 0; i < CLASS; i++) if (before.read(i) != after.read(i)) changed++;
        assertTrue(changed <= FingerprintParams.CODE_BITS, "changements : " + changed);
        assertTrue(changed > 0);
    }

    @Test
    void wrongMaskGivesGarbage() {
        byte[] x = gen.symbols(TestKeys.id(1), "alice");
        InMemoryFPartition f = new InMemoryFPartition(CLASS, 42);
        embedder.embed(f, mask, TardosCodeGenerator.expand(x));
        ExtractedWord w = embedder.extract(f, TestKeys.id(4), FingerprintEmbedder.VotePolicy.MAJORITY);
        int equal = 0;
        for (int i = 0; i < x.length; i++) if (x[i] == w.symbols()[i]) equal++;
        assertTrue(equal < 0.7 * x.length, "positions devinées sans la bonne graine ?");
    }

    @Test
    void croppedCopyProducesErasures() {
        byte[] x = gen.symbols(TestKeys.id(1), "alice");
        InMemoryFPartition f = new InMemoryFPartition(CLASS, 42);
        embedder.embed(f, mask, TardosCodeGenerator.expand(x));
        f.cropFrom(CLASS / 2);
        ExtractedWord w = embedder.extract(f, mask, FingerprintEmbedder.VotePolicy.MAJORITY);
        assertTrue(w.erasures() > 0);
        for (int i = 0; i < x.length; i++) {
            if (w.symbols()[i] != ExtractedWord.ERASURE) assertEquals(x[i], w.symbols()[i]);
        }
    }

    @Test
    void unanimousPolicyErasesSplitVotes() {
        byte[] x = gen.symbols(TestKeys.id(1), "alice");
        InMemoryFPartition f = new InMemoryFPartition(CLASS, 42);
        embedder.embed(f, mask, TardosCodeGenerator.expand(x));
        int[] pos = PositionPermutation.select(prf, mask, CLASS, FingerprintParams.CODE_BITS);
        f.write(pos[0], f.read(pos[0]) == 1 ? false : true); // un vote sur 3 inversé pour le symbole 0
        assertEquals(x[0], embedder.extract(f, mask, FingerprintEmbedder.VotePolicy.MAJORITY).symbols()[0]);
        assertEquals(ExtractedWord.ERASURE, embedder.extract(f, mask, FingerprintEmbedder.VotePolicy.UNANIMOUS).symbols()[0]);
    }

    @Test
    void notMarkableImageFailsClosed() {
        InMemoryFPartition small = new InMemoryFPartition(FingerprintParams.MIN_F_CLASS_PIXELS - 1, 1);
        boolean[] bits = TardosCodeGenerator.expand(gen.symbols(TestKeys.id(1), "alice"));
        var ex = assertThrows(FingerprintException.class, () -> embedder.embed(small, mask, bits));
        assertEquals(FingerprintException.Code.CAPACITY, ex.code());
    }
}
