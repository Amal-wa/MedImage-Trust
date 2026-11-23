package tn.medimagetrust.fingerprint;

/**
 * Génération du code de Tardos par PRF HMAC-SHA-256 : aucun code n'est stocké, il se recalcule
 * à partir de (K_F, imageId, destinataire). Les biais p_i sont communs à tous les destinataires
 * d'une même image.
 */
public final class TardosCodeGenerator {
    private final Prf prf;

    public TardosCodeGenerator(Prf prf) {
        this.prf = prf;
    }

    /** Biais p_i, i = 0..1023, de l'image. */
    public double[] biases(byte[] imageId) {
        double[] p = new double[FingerprintParams.SYMBOLS];
        for (int i = 0; i < p.length; i++) {
            byte[] block = prf.hmac(Messages.build(FingerprintParams.LABEL_BIAS, imageId, Messages.i32(i)));
            p[i] = TardosMath.biasFromUniform(Messages.unitDouble(block), FingerprintParams.CUTOFF);
        }
        return p;
    }

    /** Mot de code du destinataire : 1 024 symboles (0/1), reproductible. */
    public byte[] symbols(byte[] imageId, String recipientId, double[] biases) {
        byte[] x = new byte[FingerprintParams.SYMBOLS];
        byte[] rid = Messages.utf8(recipientId);
        for (int i = 0; i < x.length; i++) {
            byte[] block = prf.hmac(Messages.build(FingerprintParams.LABEL_CODE, imageId, rid, Messages.i32(i)));
            x[i] = (byte) (Messages.unitDouble(block) < biases[i] ? 1 : 0);
        }
        return x;
    }

    public byte[] symbols(byte[] imageId, String recipientId) {
        return symbols(imageId, recipientId, biases(imageId));
    }

    /** Répète chaque symbole 3 fois : bit d'indice r*1024 + i = symbole i, répétition r. */
    public static boolean[] expand(byte[] symbols) {
        boolean[] bits = new boolean[FingerprintParams.CODE_BITS];
        for (int r = 0; r < FingerprintParams.REPETITIONS; r++) {
            for (int i = 0; i < FingerprintParams.SYMBOLS; i++) {
                bits[r * FingerprintParams.SYMBOLS + i] = symbols[i] == 1;
            }
        }
        return bits;
    }

    /** Engagement fCommit : HMAC(K_F, MIT-FC-v1 ‖ emissionId ‖ recipientId) tronqué à 128 bits (via {@link Prf#commit128}). */
    public byte[] commit(byte[] emissionId, String recipientId) {
        return prf.commit128(Messages.build(FingerprintParams.LABEL_COMMIT, emissionId, Messages.utf8(recipientId)));
    }
}
