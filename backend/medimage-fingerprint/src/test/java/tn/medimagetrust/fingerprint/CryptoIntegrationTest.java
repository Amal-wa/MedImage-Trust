package tn.medimagetrust.fingerprint;

import org.junit.jupiter.api.Test;
import tn.medimagetrust.crypto.CryptoService;
import tn.medimagetrust.crypto.InMemoryKeyProvider;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Intégration avec le vrai module medimage-crypto : K_F dérivée par HKDF, versionnée par kid. */
class CryptoIntegrationTest {
    private static final int CLASS = 2 * FingerprintParams.MIN_F_CLASS_BLOCKS;
    private final InMemoryKeyProvider keys = new InMemoryKeyProvider();
    private final CryptoService crypto = new CryptoService(keys);
    private final byte[] image = TestKeys.id(1), mask = TestKeys.id(2), emission = TestKeys.id(3);
    private final List<String> recipients = TestKeys.recipients(64);

    private InMemoryFBlockChannel issueWith(FingerprintService svc, String recipient) {
        InMemoryFBlockChannel f = new InMemoryFBlockChannel(CLASS, 99);
        svc.embed(f, mask, svc.prepare(image, emission, recipient));
        return f;
    }

    @Test
    void emissionThenAttributionWithRealCryptoModule() {
        keys.registerNewKek(1);
        var svc = FingerprintService.forKid(crypto, 1);
        var report = svc.analyze(image, issueWith(svc, "recipient-5"), mask, recipients, Optional.empty());
        assertEquals(AttributionReport.Verdict.ACCUSED, report.verdict());
        assertEquals("recipient-5", report.accused().get(0).recipientId());
    }

    @Test
    void fCommitComesFromCryptoServiceAndMatchesWhatWatermarkWillVerify() {
        keys.registerNewKek(1);
        var svc = FingerprintService.forKid(crypto, 1);
        byte[] fCommit = svc.prepare(image, emission, "recipient-5").fCommit();
        byte[] message = Messages.build(FingerprintParams.LABEL_COMMIT, emission, Messages.utf8("recipient-5"));
        assertArrayEquals(crypto.computeFCommit(1, message), fCommit);
        assertEquals(16, fCommit.length);
    }

    @Test
    void oldCopyStaysAnalysableAfterKeyRotationWithTheRecordedKid() {
        keys.registerNewKek(1);
        var emissionService = FingerprintService.forKid(crypto, 1);
        InMemoryFBlockChannel leaked = issueWith(emissionService, "recipient-9");

        keys.registerNewKek(2); // rotation : le kid 2 devient actif

        var analysisWithRecordedKid = FingerprintService.forKid(crypto, 1);
        assertEquals("recipient-9", analysisWithRecordedKid
                .analyze(image, leaked, mask, recipients, Optional.empty()).accused().get(0).recipientId());

        var wrongKid = FingerprintService.forKid(crypto, 2); // mauvaise clé : aucune accusation
        assertTrue(wrongKid.analyze(image, leaked, mask, recipients, Optional.empty()).accused().isEmpty());
    }

    @Test
    void differentEstablishmentKeysCannotAttributeEachOthersCopies() {
        keys.registerNewKek(1);
        InMemoryFBlockChannel leaked = issueWith(FingerprintService.forKid(crypto, 1), "recipient-1");

        var other = new CryptoService(otherProvider());
        assertTrue(FingerprintService.forKid(other, 1)
                .analyze(image, leaked, mask, recipients, Optional.empty()).accused().isEmpty());
    }

    @Test
    void emissionKidMismatchIsRefused() {
        keys.registerNewKek(1);
        keys.registerNewKek(2);
        var fp = FingerprintService.forKid(crypto, 1).prepare(image, emission, "recipient-1");
        var svc2 = FingerprintService.forKid(crypto, 2);
        assertThrows(FingerprintException.class, () -> svc2.embed(new InMemoryFBlockChannel(CLASS, 1), mask, fp));
    }

    @Test
    void unknownKidFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> FingerprintService.forKid(crypto, 99));
    }

    private static InMemoryKeyProvider otherProvider() {
        InMemoryKeyProvider p = new InMemoryKeyProvider();
        p.registerNewKek(1);
        return p;
    }
}
