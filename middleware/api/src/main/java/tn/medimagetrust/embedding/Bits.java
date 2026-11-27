package tn.medimagetrust.embedding;

/**
 * Masques binaires compactés : 1 bit par pixel dans un {@code long[]} (4,5 Mo pour 36 Mpx, contre 36 Mo en
 * {@code boolean[]}). Le pixel d'indice raster {@code i} est le bit {@code i & 63} du mot {@code i >>> 6}.
 */
final class Bits {
    private Bits() {}

    /** Masque de {@code n} bits, tous à 0. */
    static long[] alloc(int n) {
        return new long[(int) (((long) n + 63) >>> 6)];
    }

    static boolean get(long[] bits, int i) {
        return (bits[i >>> 6] & (1L << (i & 63))) != 0;
    }

    static void set(long[] bits, int i) {
        bits[i >>> 6] |= 1L << (i & 63);
    }

    static void clear(long[] bits, int i) {
        bits[i >>> 6] &= ~(1L << (i & 63));
    }

    static void put(long[] bits, int i, boolean value) {
        if (value) {
            set(bits, i);
        } else {
            clear(bits, i);
        }
    }
}
