package tn.medimagetrust.audit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Utilitaires de test : horloge réglable et fabrique d'événements. */
final class TestSupport {

    static final Instant T0 = Instant.parse("2026-11-23T10:00:00Z");

    private TestSupport() {
    }

    static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        void set(Instant t) {
            now = t;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static AuditEvent event(Instant t, EventType type, String actor) {
        return new AuditEvent(t, type, actor, "res-1", Result.SUCCESS, "corr-1");
    }

    /** Magasin de test dont le contenu peut être falsifié (simule un superutilisateur de base de données). */
    static final class TamperableStore extends InMemoryAuditStore {

        synchronized void replace(long seq, AuditEntry entry) {
            entries.put(seq, entry);
        }

        synchronized void delete(long seq) {
            entries.remove(seq);
        }

        synchronized void truncateAfter(long seq) {
            entries.tailMap(seq, false).clear();
        }

        synchronized void clear() {
            entries.clear();
        }
    }

    /** Remplit un journal de {@code n} événements neutres, une minute d'écart. */
    static void fill(AuditLog log, int n) {
        for (int i = 0; i < n; i++) {
            log.append(event(T0.plusSeconds(60L * i), EventType.AUTH_SUCCESS, "user-" + (i % 3)));
        }
    }
}
