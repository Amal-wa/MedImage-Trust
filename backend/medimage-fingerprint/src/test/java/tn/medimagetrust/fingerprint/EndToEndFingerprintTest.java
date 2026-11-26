package tn.medimagetrust.fingerprint;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T-SEC-D04 (partie « essais de bout en bout ») : émission, collusion au niveau des blocs F, extraction QIM, analyse,
 * avec la vraie PRF HMAC. 100 essais, 16 destinataires par image pour garder un temps de test court.
 */
class EndToEndFingerprintTest {
    private static final int CLASS = 2 * FingerprintParams.MIN_F_CLASS_BLOCKS;

    @Test
    void hundredEndToEndTrials() {
        FingerprintService service = new FingerprintService(TestKeys.prf(3));
        List<String> recipients = TestKeys.recipients(16);
        Random rnd = new Random(2026);
        int detected = 0, falseAccusations = 0;

        for (int t = 0; t < 100; t++) {
            byte[] image = TestKeys.id(1000 + t), mask = TestKeys.id(5000 + t);
            int ia = rnd.nextInt(16), ib;
            do { ib = rnd.nextInt(16); } while (ib == ia);

            InMemoryFBlockChannel base = new InMemoryFBlockChannel(CLASS, 31L * t);
            InMemoryFBlockChannel a = base.copy(), b = base.copy();
            service.embed(a, mask, service.prepare(image, TestKeys.id(t), recipients.get(ia)));
            service.embed(b, mask, service.prepare(image, TestKeys.id(t + 9000), recipients.get(ib)));

            // Collusion au bloc près : sur chaque bloc qui diffère, choix au hasard.
            InMemoryFBlockChannel pirate = a.copy();
            for (int i = 0; i < CLASS; i++) if (!a.sameBlock(b, i) && rnd.nextBoolean()) pirate.copyBlockFrom(b, i);

            var report = service.analyze(image, pirate, mask, recipients, Optional.empty());
            for (var acc : report.accused()) {
                if (!acc.recipientId().equals(recipients.get(ia)) && !acc.recipientId().equals(recipients.get(ib))) falseAccusations++;
            }
            if (!report.accused().isEmpty()) detected++;
        }
        assertEquals(0, falseAccusations);
        assertTrue(detected >= 90, "détection de bout en bout : " + detected + "/100");
    }
}
