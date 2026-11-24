package tn.medimagetrust.fingerprint;

/** Insertion et extraction du code F dans la partition F, aux positions secrètes. */
public final class FingerprintEmbedder {

    /** Règle de décision par symbole (3 répétitions). */
    public enum VotePolicy {
        /** Vote majoritaire (spec §4.4). Effacement seulement si aucune majorité (pixels illisibles). */
        MAJORITY,
        /** Strict : effacement dès que les 3 répétitions ne sont pas unanimes. */
        UNANIMOUS
    }

    private final Prf prf;

    public FingerprintEmbedder(Prf prf) {
        this.prf = prf;
    }

    /**
     * Écrit les 3 072 bits. Capacité insuffisante : exception, l'émission est annulée (CU07).
     * Les pixels de la classe F non utilisés ne sont pas modifiés.
     */
    public void embed(FPartition f, byte[] maskDigest, boolean[] bits) {
        if (bits.length != FingerprintParams.CODE_BITS) {
            throw new FingerprintException(FingerprintException.Code.INVALID_INPUT, "code F de longueur inattendue");
        }
        requireCapacity(f);
        int[] pos = PositionPermutation.select(prf, maskDigest, f.size(), FingerprintParams.CODE_BITS);
        for (int k = 0; k < pos.length; k++) f.write(pos[k], bits[k]);
    }

    public ExtractedWord extract(FPartition f, byte[] maskDigest, VotePolicy policy) {
        requireCapacity(f);
        int[] pos = PositionPermutation.select(prf, maskDigest, f.size(), FingerprintParams.CODE_BITS);
        byte[] symbols = new byte[FingerprintParams.SYMBOLS];
        int erasures = 0;
        for (int i = 0; i < symbols.length; i++) {
            int ones = 0, zeros = 0;
            for (int r = 0; r < FingerprintParams.REPETITIONS; r++) {
                int v = f.read(pos[r * FingerprintParams.SYMBOLS + i]);
                if (v == 1) ones++;
                else if (v == 0) zeros++;
            }
            byte s = ExtractedWord.ERASURE;
            if (policy == VotePolicy.MAJORITY) {
                if (ones > zeros) s = 1;
                else if (zeros > ones) s = 0;
            } else {
                if (ones == FingerprintParams.REPETITIONS) s = 1;
                else if (zeros == FingerprintParams.REPETITIONS) s = 0;
            }
            if (s == ExtractedWord.ERASURE) erasures++;
            symbols[i] = s;
        }
        return new ExtractedWord(symbols, erasures);
    }

    private static void requireCapacity(FPartition f) {
        if (f.size() < FingerprintParams.MIN_F_CLASS_PIXELS) {
            throw new FingerprintException(FingerprintException.Code.CAPACITY,
                    "classe F : " + f.size() + " pixels, minimum " + FingerprintParams.MIN_F_CLASS_PIXELS);
        }
    }
}
