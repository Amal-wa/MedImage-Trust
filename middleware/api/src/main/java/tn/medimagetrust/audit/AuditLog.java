package tn.medimagetrust.audit;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Écriture du journal chaîné (SEC07, SEC09). Point d'entrée unique pour journaliser.
 *
 * <ul>
 *   <li>Échec fermé : si l'entrée ne peut pas être écrite, {@link AuditException} remonte et l'appelant
 *       doit refuser l'opération métier.</li>
 *   <li>Chaque alerte levée est elle-même inscrite dans la chaîne (type ALERT_RAISED) : on ne peut pas
 *       la masquer sans casser la chaîne (CA15).</li>
 *   <li>Un échec de livraison au {@link AlertSink} ne perd pas l'alerte (déjà dans la chaîne) et n'annule pas
 *       l'opération ; il est compté dans {@link #sinkFailures()}.</li>
 * </ul>
 */
public final class AuditLog {

    private static final int MAX_ATTEMPTS = 3;

    private final AuditStore store;
    private final AlertEngine engine;
    private final AlertSink sink;
    private final AtomicLong sinkFailures = new AtomicLong();

    /** @param engine et {@code sink} peuvent être null pour un journal sans alertes. */
    public AuditLog(AuditStore store, AlertEngine engine, AlertSink sink) {
        if (store == null) {
            throw new IllegalArgumentException("store requis");
        }
        if ((engine == null) != (sink == null)) {
            throw new IllegalArgumentException("engine et sink vont ensemble");
        }
        this.store = store;
        this.engine = engine;
        this.sink = sink;
    }

    /** Ajoute l'événement à la chaîne, évalue les règles d'alerte, et renvoie l'entrée écrite. */
    public AuditEntry append(AuditEvent event) {
        AuditEntry entry = write(event);
        if (engine != null && event.type() != EventType.ALERT_RAISED) {
            for (Alert alert : engine.evaluate(entry)) {
                write(new AuditEvent(event.timestamp(), EventType.ALERT_RAISED, "system",
                        alert.rule().name(), Result.SUCCESS, event.correlationId()));
                deliver(alert);
            }
        }
        return entry;
    }

    public long sinkFailures() {
        return sinkFailures.get();
    }

    private synchronized AuditEntry write(AuditEvent event) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<AuditEntry> head = store.head();
            long seq = head.map(h -> h.seq() + 1).orElse(1L);
            byte[] prev = head.map(AuditEntry::entryHash).orElse(AuditChain.GENESIS);
            AuditEntry entry = AuditChain.build(seq, event, prev);
            try {
                store.append(entry);
                return entry;
            } catch (AuditConflictException conflict) {
                // un autre nœud a écrit entre-temps : relire la tête et recommencer
            }
        }
        throw new AuditException("Journal indisponible : conflits d'écriture répétés");
    }

    private void deliver(Alert alert) {
        try {
            sink.raise(alert);
        } catch (RuntimeException e) {
            sinkFailures.incrementAndGet();
        }
    }

    /** Pour les tests et le service de vérification : instantané en lecture seule de la tête. */
    public Optional<AuditEntry> head() {
        return store.head();
    }

    /** Entrées [from, to] (lecture seule). */
    public List<AuditEntry> range(long from, long to) {
        return store.range(from, to);
    }
}
