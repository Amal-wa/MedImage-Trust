package tn.medimagetrust.fingerprint;

/** Score d'accusation d'un destinataire face à un mot pirate. */
public final class TardosScorer {
    private TardosScorer() {}

    public static double score(byte[] recipientSymbols, byte[] pirate, double[] biases) {
        double s = 0.0;
        for (int i = 0; i < pirate.length; i++) {
            s += TardosMath.scoreTerm(pirate[i], recipientSymbols[i], biases[i]);
        }
        return s;
    }
}
