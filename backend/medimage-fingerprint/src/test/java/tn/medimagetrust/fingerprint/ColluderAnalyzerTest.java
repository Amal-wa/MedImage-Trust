package tn.medimagetrust.fingerprint;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ColluderAnalyzerTest {
    private static final int CLASS = 2 * FingerprintParams.MIN_F_CLASS_PIXELS;
    private final Prf prf = TestKeys.prf(1);
    private final FingerprintService service = new FingerprintService(prf);
    private final TardosCodeGenerator gen = new TardosCodeGenerator(prf);
    private final byte[] image = TestKeys.id(1);
    private final byte[] mask = TestKeys.id(2);
    private final List<String> recipients = TestKeys.recipients(64);

    private InMemoryFPartition issue(String recipient) {
        InMemoryFPartition f = new InMemoryFPartition(CLASS, 99);
        service.embed(f, mask, service.prepare(image, TestKeys.id(7), recipient));
        return f;
    }

    @Test
    void singleLeakIsAttributedToItsRecipientOnly() {
        var report = service.analyze(image, issue("recipient-17"), mask, recipients, Optional.empty());
        assertEquals(AttributionReport.Verdict.ACCUSED, report.verdict());
        assertEquals(1, report.accused().size());
        assertEquals("recipient-17", report.accused().get(0).recipientId());
    }

    @Test
    void unrelatedImageAccusesNobody() {
        InMemoryFPartition noise = new InMemoryFPartition(CLASS, 123); // jamais marquée
        var report = service.analyze(image, noise, mask, recipients, Optional.empty());
        assertEquals(AttributionReport.Verdict.NO_ACCUSATION_BELOW_THRESHOLD, report.verdict());
        assertTrue(report.accused().isEmpty());
    }

    @Test
    void recipientOutsideTheCandidateListIsNeverAccused() {
        var report = service.analyze(image, issue("stranger"), mask, recipients, Optional.empty());
        assertTrue(report.accused().isEmpty());
    }

    @Test
    void twoColludersAreDetectedWithoutInnocentAccused() {
        InMemoryFPartition a = issue("recipient-3"), b = issue("recipient-40");
        InMemoryFPartition pirate = a.copy();
        Random rnd = new Random(5);
        for (int i = 0; i < CLASS; i++) if (a.read(i) != b.read(i) && rnd.nextBoolean()) pirate.write(i, b.read(i) == 1);
        var report = service.analyze(image, pirate, mask, recipients, Optional.empty());
        assertEquals(AttributionReport.Verdict.ACCUSED, report.verdict());
        assertTrue(report.accused().stream().allMatch(
                x -> x.recipientId().equals("recipient-3") || x.recipientId().equals("recipient-40")));
    }

    @Test
    void heavilyCroppedCopyIsNotConclusive() {
        InMemoryFPartition f = issue("recipient-9");
        f.cropFrom(CLASS / 10); // 90 % des pixels perdus : ~73 % des symboles effacés
        var report = service.analyze(image, f, mask, recipients, Optional.empty());
        assertEquals(AttributionReport.Verdict.TOO_MANY_ERASURES, report.verdict());
        assertTrue(report.accused().isEmpty());
    }

    @Test
    void moderatelyCroppedCopyIsStillAttributedThanksToRepetitions() {
        InMemoryFPartition f = issue("recipient-9");
        f.cropFrom(CLASS / 4); // 75 % perdus : ~42 % de symboles effacés, il reste de quoi conclure
        var report = service.analyze(image, f, mask, recipients, Optional.empty());
        assertEquals(AttributionReport.Verdict.ACCUSED, report.verdict());
        assertEquals("recipient-9", report.accused().get(0).recipientId());
    }

    @Test
    void graftedFAgainstValidWIsRejected() {
        // Le fraudeur (recipient-1, W valide à son nom) greffe le F de l'innocent recipient-2.
        var report = service.analyze(image, issue("recipient-2"), mask, recipients, Optional.of("recipient-1"));
        assertEquals(AttributionReport.Verdict.W_F_INCONSISTENT, report.verdict());
        assertTrue(report.accused().isEmpty());
    }

    @Test
    void consistentWAndFAreAccepted() {
        var report = service.analyze(image, issue("recipient-2"), mask, recipients, Optional.of("recipient-2"));
        assertEquals(AttributionReport.Verdict.ACCUSED, report.verdict());
    }

    @Test
    void moreThan64CandidatesIsRefused() {
        assertThrows(FingerprintException.class, () -> service.analyze(image, issue("recipient-1"), mask,
                TestKeys.recipients(65), Optional.empty()));
    }

    @Test
    void scoreOfTheLegitimateRecipientIsFarAboveThreshold() {
        double[] p = gen.biases(image);
        byte[] x = gen.symbols(image, "alice", p);
        double s = TardosScorer.score(x, x, p);
        assertTrue(s > 3 * ThresholdCalibrator.threshold(FingerprintParams.SYMBOLS, 64), "score = " + s);
    }
}
