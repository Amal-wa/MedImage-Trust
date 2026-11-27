package tn.medimagetrust.audit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tn.medimagetrust.audit.TestSupport.T0;
import static tn.medimagetrust.audit.TestSupport.event;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AuditLogTest {

    @Test
    void premiereEntreeChaineeAlaGenese() {
        InMemoryAuditStore store = new InMemoryAuditStore();
        AuditLog log = new AuditLog(store, null, null);
        AuditEntry e = log.append(event(T0, EventType.AUTH_SUCCESS, "u1"));
        assertEquals(1, e.seq());
        assertArrayEquals(new byte[32], e.prevHash());
    }

    @Test
    void chaqueEntreeReferenceLeCondensatDeLaPrecedente() {
        InMemoryAuditStore store = new InMemoryAuditStore();
        AuditLog log = new AuditLog(store, null, null);
        AuditEntry a = log.append(event(T0, EventType.AUTH_SUCCESS, "u1"));
        AuditEntry b = log.append(event(T0.plusSeconds(1), EventType.AUTH_SUCCESS, "u1"));
        assertEquals(2, b.seq());
        assertArrayEquals(a.entryHash(), b.prevHash());
        assertFalse(MessageDigest.isEqual(a.entryHash(), b.entryHash()));
    }

    @Test
    void condensatDependDuContenuEtDeLaSequence() {
        AuditEvent e = event(T0, EventType.AUTH_SUCCESS, "u1");
        byte[] h1 = AuditChain.build(1, e, AuditChain.GENESIS).entryHash();
        byte[] h2 = AuditChain.build(2, e, AuditChain.GENESIS).entryHash();
        byte[] h3 = AuditChain.build(1, event(T0, EventType.AUTH_SUCCESS, "u2"), AuditChain.GENESIS).entryHash();
        assertFalse(MessageDigest.isEqual(h1, h2));
        assertFalse(MessageDigest.isEqual(h1, h3));
    }

    @Test
    void vecteurDeReferenceDuCondensat() {
        // SHA-256("MIT-LOG-v1" ‖ 0x00 ‖ 32 zéros ‖ JCS(...)) recalculé indépendamment avec MessageDigest.
        AuditEvent e = event(T0, EventType.AUTH_SUCCESS, "u1");
        byte[] canonical = AuditChain.canonical(1, e);
        byte[] input = new byte[10 + 1 + 32 + canonical.length];
        System.arraycopy("MIT-LOG-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, input, 0, 10);
        System.arraycopy(canonical, 0, input, 43, canonical.length);
        try {
            byte[] expected = MessageDigest.getInstance("SHA-256").digest(input);
            assertArrayEquals(expected, AuditChain.build(1, e, AuditChain.GENESIS).entryHash());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void ecrituresConcurrentesSansTrouNiDoublon() throws Exception {
        InMemoryAuditStore store = new InMemoryAuditStore();
        AuditLog log = new AuditLog(store, null, null);
        int threads = 8;
        int perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            futures.add(pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    log.append(event(T0.plusMillis(i), EventType.AUTH_SUCCESS, "u" + id));
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(threads * perThread, store.head().orElseThrow().seq());
        Set<Long> seqs = new HashSet<>();
        for (AuditEntry e : store.range(1, threads * perThread)) {
            assertTrue(seqs.add(e.seq()));
        }
        assertEquals(threads * perThread, seqs.size());
        // chaîne intacte après écritures concurrentes
        InMemoryAnchorStore a = new InMemoryAnchorStore();
        AuditVerifier v = new AuditVerifier(store, a, new InMemoryAnchorStore(), new InMemoryLogKeyProvider(1),
                new InMemoryAlertSink(), new TestSupport.TestClock(T0));
        assertTrue(v.verify().ok());
    }

    @Test
    void conflitDEcritureReessayeDepuisLaNouvelleTete() {
        // Un autre nœud écrit entre la lecture de la tête et l'insertion : la 1re tentative échoue, la 2e réussit.
        InMemoryAuditStore store = new InMemoryAuditStore() {
            private boolean raced;

            @Override
            public synchronized void append(AuditEntry entry) {
                if (!raced) {
                    raced = true;
                    super.append(AuditChain.build(1, event(T0, EventType.AUTH_SUCCESS, "autre-noeud"),
                            AuditChain.GENESIS));
                }
                super.append(entry);
            }
        };
        AuditLog log = new AuditLog(store, null, null);
        AuditEntry e = log.append(event(T0, EventType.AUTH_SUCCESS, "u1"));
        assertEquals(2, e.seq());
        assertEquals(2, store.head().orElseThrow().seq());
    }

    @Test
    void echecFermeSiLeStockageRefuseToujours() {
        InMemoryAuditStore store = new InMemoryAuditStore() {
            @Override
            public synchronized void append(AuditEntry entry) {
                throw new AuditConflictException();
            }
        };
        AuditLog log = new AuditLog(store, null, null);
        assertThrows(AuditException.class, () -> log.append(event(T0, EventType.AUTH_SUCCESS, "u1")));
    }

    @Test
    void entreeImmuableCopiesDefensives() {
        AuditEntry e = AuditChain.build(1, event(T0, EventType.AUTH_SUCCESS, "u1"), AuditChain.GENESIS);
        byte[] h = e.entryHash();
        h[0] ^= 0x01;
        assertFalse(MessageDigest.isEqual(h, e.entryHash()));
        Optional<AuditEntry> none = Optional.empty();
        assertTrue(none.isEmpty());
    }

    @Test
    void storeRefuseUneEntreeNonChainee() {
        InMemoryAuditStore store = new InMemoryAuditStore();
        AuditLog log = new AuditLog(store, null, null);
        log.append(event(T0, EventType.AUTH_SUCCESS, "u1"));
        AuditEntry bad = AuditChain.build(2, event(T0, EventType.AUTH_SUCCESS, "u1"), new byte[32]);
        assertThrows(AuditConflictException.class, () -> store.append(bad));
        AuditEntry gap = AuditChain.build(5, event(T0, EventType.AUTH_SUCCESS, "u1"),
                store.head().orElseThrow().entryHash());
        assertThrows(AuditConflictException.class, () -> store.append(gap));
    }
}
