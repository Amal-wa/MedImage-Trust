package tn.medimagetrust.fingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Analyse d'une copie suspecte (CU08) : scores de Tardos, seuil, contrôle de cohérence W/F. */
public final class ColluderAnalyzer {
    private final TardosCodeGenerator generator;

    public ColluderAnalyzer(TardosCodeGenerator generator) {
        this.generator = generator;
    }

    /**
     * @param imageId    image d'origine (identifie les biais)
     * @param word       mot extrait de la copie suspecte
     * @param candidates destinataires enregistrés pour cette image (au plus 64)
     * @param wRecipient destinataire désigné par l'engagement fCommit de W, si W est valide
     *                   (vide si W est détruit, ce qui est le cas d'une copie fusionnée)
     */
    public AttributionReport analyze(byte[] imageId, ExtractedWord word, List<String> candidates,
                                     Optional<String> wRecipient) {
        if (candidates.isEmpty() || candidates.size() > FingerprintParams.MAX_RECIPIENTS) {
            throw new FingerprintException(FingerprintException.Code.INVALID_INPUT,
                    "1 à " + FingerprintParams.MAX_RECIPIENTS + " destinataires attendus");
        }
        int readable = word.readable();
        if (readable < FingerprintParams.MIN_READABLE_RATIO * FingerprintParams.SYMBOLS) {
            return new AttributionReport(AttributionReport.Verdict.TOO_MANY_ERASURES, List.of(), 0, 0,
                    readable, word.erasures(), candidates.size());
        }
        double threshold = ThresholdCalibrator.threshold(readable, candidates.size());
        double[] biases = generator.biases(imageId);

        List<AttributionReport.Accusation> accused = new ArrayList<>();
        double top = Double.NEGATIVE_INFINITY;
        for (String recipient : candidates) {
            byte[] x = generator.symbols(imageId, recipient, biases);
            double s = TardosScorer.score(x, word.symbols(), biases);
            top = Math.max(top, s);
            if (s > threshold) accused.add(new AttributionReport.Accusation(recipient, s));
        }
        accused.sort(Comparator.comparingDouble(AttributionReport.Accusation::score).reversed());

        // Contrôle W/F : si W désigne R, R doit faire partie des accusés ; sinon F a pu être greffé.
        if (wRecipient.isPresent() && !accused.isEmpty()
                && accused.stream().noneMatch(a -> a.recipientId().equals(wRecipient.get()))) {
            return new AttributionReport(AttributionReport.Verdict.W_F_INCONSISTENT, List.of(), threshold, top,
                    readable, word.erasures(), candidates.size());
        }
        AttributionReport.Verdict v = accused.isEmpty()
                ? AttributionReport.Verdict.NO_ACCUSATION_BELOW_THRESHOLD
                : AttributionReport.Verdict.ACCUSED;
        return new AttributionReport(v, List.copyOf(accused), threshold, top, readable, word.erasures(),
                candidates.size());
    }
}
