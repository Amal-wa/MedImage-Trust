package tn.medimagetrust.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tn.medimagetrust.audit.TestSupport.event;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.audit.TestSupport.TamperableStore;
import tn.medimagetrust.audit.TestSupport.TestClock;

/** Rétention de 12 mois par segments mensuels, chaîne toujours vérifiable (§Durées de conservation). */
class RetentionServiceTest {

    private static final Instant START = Instant.parse("2025-09-10T08:00:00Z");

    private TamperableStore store;
    private InMemoryAnchorStore primary;
    private InMemoryAnchorStore copy;
    private InMemoryLogKeyProvider keys;
    private InMemoryAlertSink sink;
    private TestClock clock;
    private AuditLog log;
    private AnchorService anchors;
    private AuditVerifier verifier;
    private RetentionService retention;

    @BeforeEach
    void setUp() {
        store = new TamperableStore();
        primary = new InMemoryAnchorStore();
        copy = new InMemoryAnchorStore();
        keys = new InMemoryLogKeyProvider(1);
        sink = new InMemoryAlertSink();
        clock = new TestClock(START);
        log = new AuditLog(store, null, null);
        anchors = new AnchorService(store, primary, copy, keys, sink, clock);
        verifier = new AuditVerifier(store, primary, copy, keys, sink, clock);
        retention = new RetentionService(store, store, primary, verifier, clock);
    }

    /**
     * Simule {@code days} jours : à chaque pas de {@code stepDays} jours, 2 événements puis un ancrage.
     * (Pas > 1 pour limiter le nombre de signatures Ed25519 en Java pur ; la logique testée est identique.)
     */
    private void simulate(int days, int stepDays) {
        for (int d = 0; d < days; d += stepDays) {
            log.append(event(clock.instant(), EventType.AUTH_SUCCESS, "u1"));
            log.append(event(clock.instant().plusSeconds(5), EventType.COPY_ISSUED, "u2"));
            anchors.anchorToday();
            clock.advance(Duration.ofDays(stepDays));
        }
    }

    private void simulate(int days) {
        simulate(days, 5);
    }

    @Test
    void rienAPurgerAvantDouzeMois() {
        simulate(200);
        assertEquals(0, retention.purgeExpired());
        assertEquals(1, store.first().orElseThrow().seq());
    }

    @Test
    void purgeDesSegmentsAuDela12MoisEtChaineToujoursVerifiable() {
        simulate(500); // ~ 10/09/2025 -> 23/01/2027
        long before = store.head().orElseThrow().seq();
        long purged = retention.purgeExpired();
        assertTrue(purged > 0);
        long firstSeq = store.first().orElseThrow().seq();
        assertEquals(purged + 1, firstSeq);
        assertEquals(before, store.head().orElseThrow().seq(), "la tête n'est jamais purgée");

        VerificationReport r = verifier.verify();
        assertTrue(r.ok(), r.problems().toString());
        assertEquals(before - purged, r.entriesChecked());
    }

    @Test
    void lesEntreesDeMoinsDe12MoisSontConservees() {
        simulate(500);
        retention.purgeExpired();
        Instant firstTs = store.first().orElseThrow().event().timestamp();
        // seuil = 1er du mois courant (janv. 2027) - 12 mois = 1er janv. 2026 ; on conserve tout ce qui suit le dernier ancrage antérieur au seuil
        assertTrue(firstTs.isAfter(Instant.parse("2025-12-25T00:00:00Z")), firstTs.toString());
        assertTrue(firstTs.isBefore(Instant.parse("2026-01-10T00:00:00Z")), firstTs.toString());
    }

    @Test
    void lAncrageDuDernierSegmentPurgeEstConserve() {
        simulate(500);
        int anchorsBefore = primary.days().size();
        retention.purgeExpired();
        assertEquals(anchorsBefore, primary.days().size(), "aucun ancrage supprimé");
        assertEquals(copy.days(), primary.days());
    }

    @Test
    void purgeIdempotente() {
        simulate(500);
        retention.purgeExpired();
        assertEquals(0, retention.purgeExpired());
        assertTrue(verifier.verify().ok());
    }

    @Test
    void purgeRefuseeSiLeJournalEstFalsifie() {
        simulate(500);
        AuditEntry e = store.get(100).orElseThrow();
        store.replace(100, new AuditEntry(100, event(START, EventType.ACCESS_DENIED, "attaquant"),
                e.prevHash(), e.entryHash()));
        assertThrows(AuditException.class, () -> retention.purgeExpired());
        assertEquals(1, store.first().orElseThrow().seq(), "rien n'a été supprimé");
    }

    @Test
    void purgeSansAncrageDeBaseNePurgeRien() {
        simulate(500);
        primary.anchors.clear();
        copy.anchors.clear();
        assertEquals(0, retention.purgeExpired());
    }

    @Test
    void purgeIllegitimeDetecteeCarLaPremiereLigneNEstPasRattachee() {
        simulate(30, 1);
        // 2 événements par ancrage : les ancrages tombent sur les séquences paires. Purger jusqu'à 25 (impaire)
        // = suppression directe, sans ancrage aligné sur la limite.
        store.purgeThrough(25);
        VerificationReport r = verifier.verify();
        assertFalse(r.ok());
        assertTrue(r.has(ProblemKind.BASE_UNTRUSTED));
    }

    @Test
    void purgeAligneeSurUnAncrageSansLeBonCondensatEstRefusee() {
        simulate(30, 1);
        // l'attaquant purge jusqu'à 24 (aligné sur un ancrage) mais la 1re ligne restante ne s'y rattache pas
        store.purgeThrough(24);
        AuditEntry first = store.first().orElseThrow();
        store.replace(first.seq(), new AuditEntry(first.seq(), first.event(), new byte[32], first.entryHash()));
        assertTrue(verifier.verify().has(ProblemKind.BASE_UNTRUSTED));
    }

    @Test
    void ecritureApresPurgeContinueLaChaine() {
        simulate(500);
        retention.purgeExpired();
        long head = store.head().orElseThrow().seq();
        AuditEntry e = log.append(event(clock.instant(), EventType.AUTH_SUCCESS, "u1"));
        assertEquals(head + 1, e.seq());
        assertTrue(verifier.verify().ok());
    }
}
