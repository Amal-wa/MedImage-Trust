package tn.medimagetrust.fingerprint;

import tn.medimagetrust.embedding.FBlockChannel;

/**
 * Insertion et extraction du code F dans la classe F (v2.4) : 1 024 symboles, 1 bit par coefficient, 8 coefficients
 * par bloc, 128 blocs tirés secrètement ({@link PositionPermutation}).
 *
 * <p>Correspondance : symbole {@code i} → bloc {@code pos[i / 8]} → coefficient {@code i % 8} (rang zigzag
 * {@code 6 + i % 8}). Chaque bloc utilisé reçoit 8 dithers dérivés de K_F ({@link #dithers(byte[], int, double)}),
 * dans {@code [0, Δ)} où Δ est le pas du canal ({@link FBlockChannel#qimDelta()}) : Δ(B) de l'émission, jamais lu dans
 * une copie analysée.</p>
 */
public final class FingerprintEmbedder {

    private final Prf prf;

    public FingerprintEmbedder(Prf prf) {
        this.prf = prf;
    }

    /**
     * Écrit les 1 024 bits dans 128 blocs, chacun une seule fois. Capacité insuffisante : exception, l'émission est
     * annulée (CU07). Les blocs de la classe F non choisis ne sont pas écrits.
     */
    public void embed(FBlockChannel f, byte[] maskDigest, boolean[] bits) {
        if (bits == null || bits.length != FingerprintParams.CODE_BITS) {
            throw new FingerprintException(FingerprintException.Code.INVALID_INPUT, "code F de longueur inattendue");
        }
        int[] pos = blocks(f, maskDigest);
        final int n = FingerprintParams.BITS_PER_BLOCK;
        for (int k = 0; k < pos.length; k++) {
            boolean[] b = new boolean[n];
            System.arraycopy(bits, k * n, b, 0, n);
            f.write(pos[k], b, dithers(maskDigest, pos[k], f.qimDelta()));
        }
    }

    /** Lit les 128 blocs ; un bloc illisible (copie recadrée) donne 8 effacements. */
    public ExtractedWord extract(FBlockChannel f, byte[] maskDigest) {
        int[] pos = blocks(f, maskDigest);
        final int n = FingerprintParams.BITS_PER_BLOCK;
        byte[] symbols = new byte[FingerprintParams.SYMBOLS];
        int erasures = 0;
        for (int k = 0; k < pos.length; k++) {
            int[] read = f.read(pos[k], dithers(maskDigest, pos[k], f.qimDelta()));
            if (read == null || read.length != n) {
                throw new FingerprintException(FingerprintException.Code.INVALID_INPUT, "bloc F illisible");
            }
            for (int j = 0; j < n; j++) {
                byte s = read[j] == 1 ? 1 : read[j] == 0 ? 0 : ExtractedWord.ERASURE;
                if (s == ExtractedWord.ERASURE) erasures++;
                symbols[k * n + j] = s;
            }
        }
        return new ExtractedWord(symbols, erasures);
    }

    /**
     * 8 dithers du bloc d'indice {@code blockIndex} de la classe F, uniformes dans {@code [0, Δ)} :
     * flux HMAC sous K_F, étiquette {@code MIT-FD-v1}, graine {@code maskDigest ‖ blockIndex (4 octets,
     * gros-boutiste)} ; 53 bits par valeur. Les tirages ne dépendent pas de Δ : seule l'échelle change.
     *
     * @param delta pas Δ(B) du canal F
     */
    double[] dithers(byte[] maskDigest, int blockIndex, double delta) {
        byte[] seed = new byte[maskDigest.length + 4];
        System.arraycopy(maskDigest, 0, seed, 0, maskDigest.length);
        System.arraycopy(Messages.i32(blockIndex), 0, seed, maskDigest.length, 4);
        HmacStream stream = new HmacStream(prf, FingerprintParams.LABEL_DITHER, seed);
        double[] d = new double[FingerprintParams.BITS_PER_BLOCK];
        for (int j = 0; j < d.length; j++) {
            double v = stream.nextUnitDouble() * delta;
            d[j] = v < delta ? v : Math.nextDown(delta);
        }
        return d;
    }

    /** Les 128 blocs secrets, après contrôle de la capacité. */
    int[] blocks(FBlockChannel f, byte[] maskDigest) {
        if (f == null || maskDigest == null) {
            throw new FingerprintException(FingerprintException.Code.INVALID_INPUT, "entrée absente");
        }
        if (f.classSize() < FingerprintParams.MIN_F_CLASS_BLOCKS) {
            throw new FingerprintException(FingerprintException.Code.CAPACITY, "classe F trop petite");
        }
        return PositionPermutation.select(prf, maskDigest, f.classSize(), FingerprintParams.USED_BLOCKS);
    }
}
