package tn.medimagetrust.fingerprint;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PositionPermutationTest {
    private static final int CLASS = 10_000;
    private final byte[] mask = TestKeys.id(5);

    @Test
    void positionsAreDistinctAndInRange() {
        int[] pos = PositionPermutation.select(TestKeys.prf(1), mask, CLASS, FingerprintParams.USED_BLOCKS);
        assertEquals(FingerprintParams.USED_BLOCKS, Arrays.stream(pos).distinct().count());
        assertTrue(Arrays.stream(pos).allMatch(p -> p >= 0 && p < CLASS));
    }

    @Test
    void deterministicForSameKeyAndMask() {
        assertArrayEquals(PositionPermutation.select(TestKeys.prf(1), mask, CLASS, FingerprintParams.USED_BLOCKS),
                PositionPermutation.select(TestKeys.prf(1), mask, CLASS, FingerprintParams.USED_BLOCKS));
    }

    @Test
    void secretWithoutKeyAndBoundToMask() {
        int n = FingerprintParams.USED_BLOCKS;
        int[] a = PositionPermutation.select(TestKeys.prf(1), mask, CLASS, n);
        assertFalse(Arrays.equals(a, PositionPermutation.select(TestKeys.prf(2), mask, CLASS, n)));
        assertFalse(Arrays.equals(a, PositionPermutation.select(TestKeys.prf(1), TestKeys.id(6), CLASS, n)));
    }

    @Test
    void positionsAreSpreadOverTheWholeClass() {
        int[] pos = PositionPermutation.select(TestKeys.prf(1), mask, CLASS, FingerprintParams.USED_BLOCKS);
        long firstHalf = Arrays.stream(pos).filter(p -> p < CLASS / 2).count();
        assertEquals(FingerprintParams.USED_BLOCKS / 2.0, firstHalf, 28); // ~ 5 sigma (sigma ~ 5,7)
    }

    @Test
    void tooSmallClassFailsClosed() {
        var ex = assertThrows(FingerprintException.class,
                () -> PositionPermutation.select(TestKeys.prf(1), mask, 100, FingerprintParams.USED_BLOCKS));
        assertEquals(FingerprintException.Code.CAPACITY, ex.code());
    }
}
