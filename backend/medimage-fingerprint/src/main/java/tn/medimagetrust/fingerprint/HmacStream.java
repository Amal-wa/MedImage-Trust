package tn.medimagetrust.fingerprint;

/** Flux d'octets déterministe : HMAC en mode compteur (graine ‖ compteur). */
final class HmacStream {
    private final Prf prf;
    private final String label;
    private final byte[] seed;
    private long counter = 0;
    private byte[] block = new byte[0];
    private int pos = 0;

    HmacStream(Prf prf, String label, byte[] seed) {
        this.prf = prf;
        this.label = label;
        this.seed = seed.clone();
    }

    private void refill() {
        block = prf.hmac(Messages.build(label, seed, Messages.u64(counter++)));
        pos = 0;
    }

    long nextLong() {
        if (pos + 8 > block.length) refill();
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (block[pos++] & 0xFFL);
        return v;
    }

    /** Entier uniforme dans [0, bound) sans biais de modulo (échantillonnage par rejet). */
    int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound <= 0");
        long rem = (Long.MAX_VALUE % bound + 1) % bound; // 2^63 mod bound
        long r;
        do {
            r = nextLong() >>> 1;
        } while (r > Long.MAX_VALUE - rem);
        return (int) (r % bound);
    }
}
