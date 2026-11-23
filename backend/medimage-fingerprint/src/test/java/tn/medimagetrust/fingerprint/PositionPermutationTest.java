package tn.medimagetrust.fingerprint;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PositionPermutationTest {
    private static final int CLASS = 10_000;
    private final byte[] mask = TestKeys.id(5);

    @Test
    void positionsAreDistinctAndInRange() {
        int[] pos = PositionPermutation.select(TestKeys.prf(1), mask, CLASS, FingerprintParams.CODE_BITS);
        assertEquals(FingerprintParams.CODE_BITS, Arrays.stream(pos).distinct().count());
        assertTrue(Arrays.stream(pos).allMatch(p -> p >= 0 && p < CLASS));
    }

    @Test
    void deterministicForSameKeyAndMask() {
        assertArrayEquals(PositionPermutation.select(TestKeys.prf(1), mask, CLASS, 500),
                PositionPermutation.select(TestKeys.prf(1), mask, CLASS, 500));
    }

    @Test
    void secretWithoutKeyAndBoundToMask() {
        int[] a = PositionPermutation.select(TestKeys.prf(1), mask, CLASS, 500);
        assertFalse(Arrays.equals(a, PositionPermutation.select(TestKeys.prf(2), mask, CLASS, 500)));
        assertFalse(Arrays.equals(a, PositionPermutation.select(TestKeys.prf(1), TestKeys.id(6), CLASS, 500)));
    }

    @Test
    void positionsAreSpreadOverTheWholeClass() {
        int[] pos = PositionPermutation.select(TestKeys.prf(1), mask, CLASS, FingerprintParams.CODE_BITS);
        long firstHalf = Arrays.stream(pos).filter(p -> p < CLASS / 2).count();
        assertEquals(FingerprintParams.CODE_BITS / 2.0, firstHalf, 200); // ~ 5 sigma
    }

    @Test
    void tooSmallClassFailsClosed() {
        var ex = assertThrows(FingerprintException.class,
                () -> PositionPermutation.select(TestKeys.prf(1), mask, 100, FingerprintParams.CODE_BITS));
        assertEquals(FingerprintException.Code.CAPACITY, ex.code());
    }
}
