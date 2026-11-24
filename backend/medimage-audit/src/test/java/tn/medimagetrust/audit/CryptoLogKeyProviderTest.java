package tn.medimagetrust.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tn.medimagetrust.audit.TestSupport.T0;
import static tn.medimagetrust.audit.TestSupport.fill;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.audit.TestSupport.TestClock;
import tn.medimagetrust.crypto.CryptoService;
import tn.medimagetrust.crypto.InMemoryKeyProvider;

/** K_log dérivée de la KEK par le module crypto : ancrage signé puis vérifié, y compris après rotation. */
class CryptoLogKeyProviderTest {

    @Test
    void ancrageSigneAvecKlogDeriveeEtVerifie() {
        InMemoryKeyProvider keks = new InMemoryKeyProvider();
        keks.registerNewKek(1);
        CryptoLogKeyProvider keys = new CryptoLogKeyProvider(new CryptoService(keks));

        InMemoryAuditStore store = new InMemoryAuditStore();
        InMemoryAnchorStore primary = new InMemoryAnchorStore();
        InMemoryAnchorStore copy = new InMemoryAnchorStore();
        InMemoryAlertSink sink = new InMemoryAlertSink();
        TestClock clock = new TestClock(T0);
        AuditLog log = new AuditLog(store, null, null);
        AnchorService anchors = new AnchorService(store, primary, copy, keys, sink, clock);
        AuditVerifier verifier = new AuditVerifier(store, primary, copy, keys, sink, clock);

        fill(log, 20);
        Anchor first = anchors.anchorToday();
        assertEquals(1, first.kid());
        assertTrue(first.verifySignature(keys.publicKey(1).orElseThrow()));

        // Rotation de la KEK : le nouvel ancrage est signé avec le kid 2, l'ancien reste vérifiable.
        keks.registerNewKek(2);
        clock.advance(Duration.ofDays(1));
        fill(log, 5);
        Anchor second = anchors.anchorToday();
        assertEquals(2, second.kid());
        assertFalse(second.verifySignature(keys.publicKey(1).orElseThrow()));

        VerificationReport report = verifier.verify();
        assertTrue(report.ok(), report.problems().toString());
        assertEquals(2, report.anchorsChecked());
        assertTrue(sink.alerts().isEmpty());
        assertTrue(keys.publicKey(99).isEmpty());
        assertTrue(keys.publicKey(-1).isEmpty());
    }
}
