package tn.medimagetrust.fingerprint;

import java.util.Arrays;
import java.util.Random;

/** Implémentation de test (bits en mémoire) en attendant l'adaptateur vers le module embedding. */
public final class InMemoryFPartition implements FPartition {
    private final byte[] bits; // 0, 1 ou UNREADABLE

    public InMemoryFPartition(int size, long seed) {
        this.bits = new byte[size];
        Random r = new Random(seed); // test uniquement : simule les LSB naturels de l'image
        for (int i = 0; i < size; i++) bits[i] = (byte) r.nextInt(2);
    }

    private InMemoryFPartition(byte[] bits) {
        this.bits = bits;
    }

    public InMemoryFPartition copy() {
        return new InMemoryFPartition(Arrays.copyOf(bits, bits.length));
    }

    /** Simule un recadrage : les pixels de rang >= from deviennent illisibles. */
    public void cropFrom(int from) {
        for (int i = from; i < bits.length; i++) bits[i] = (byte) UNREADABLE;
    }

    @Override public int size() { return bits.length; }

    @Override public void write(int rank, boolean bit) { bits[rank] = (byte) (bit ? 1 : 0); }

    @Override public int read(int rank) { return bits[rank]; }
}
