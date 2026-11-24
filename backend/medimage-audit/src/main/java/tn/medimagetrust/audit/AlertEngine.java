package tn.medimagetrust.audit;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Règles d'alerte pilotées par les événements : A1, A1B, A2, A3, A4, A5, A6 (SEC07, SEC02).
 * A7 (rupture de chaîne) vient de {@link AuditVerifier}.
 *
 * <p>Évaluation synchrone à chaque entrée : l'alerte existe dès l'écriture, bien en deçà des 60 s exigées.
 * Les fenêtres glissantes se calculent sur l'horodatage de l'événement (déterministe, testable), par acteur.
 * Une règle à seuil se déclenche une fois par « épisode » : tant que le compteur reste au-dessus du seuil,
 * pas de nouvelle alerte ; elle se réarme quand la fenêtre redescend sous le seuil.
 * A5 et A6 (seuil 1) alertent à chaque événement.
 */
public final class AlertEngine {

    private record Spec(AlertRule rule, Set<EventType> types, int threshold, Duration window) {
    }

    private static final List<Spec> SPECS = List.of(
            new Spec(AlertRule.A1, EnumSet.of(EventType.AUTH_FAILURE), 5, Duration.ofMinutes(5)),
            new Spec(AlertRule.A1B, EnumSet.of(EventType.AUTH_FAILURE), 20, Duration.ofHours(1)),
            new Spec(AlertRule.A2, EnumSet.of(EventType.FILE_REJECTED), 3, Duration.ofHours(1)),
            new Spec(AlertRule.A3, EnumSet.of(EventType.EXTRACTION_DENIED), 3, Duration.ofHours(1)),
            new Spec(AlertRule.A4, EnumSet.of(EventType.COPY_ISSUED_DEGRADED, EventType.ROI_REFUSED), 4,
                    Duration.ofHours(24)),
            new Spec(AlertRule.A5, EnumSet.of(EventType.ROLE_CHANGE), 1, Duration.ZERO),
            new Spec(AlertRule.A6, EnumSet.of(EventType.ORIGINAL_HASH_MISMATCH), 1, Duration.ZERO));

    private static final Duration LONGEST_WINDOW = Duration.ofHours(24);
    private static final int SWEEP_THRESHOLD = 10_000;

    private static final class Window {
        final ArrayDeque<Long> times = new ArrayDeque<>();
        boolean alerted;
        long lastMillis;
    }

    private final Map<String, Window> windows = new HashMap<>();

    /** Évalue une entrée déjà écrite ; renvoie les alertes à lever (souvent vide). */
    public synchronized List<Alert> evaluate(AuditEntry entry) {
        AuditEvent e = entry.event();
        long now = e.timestamp().toEpochMilli();
        List<Alert> out = new ArrayList<>();
        for (Spec spec : SPECS) {
            if (!spec.types().contains(e.type())) {
                continue;
            }
            String subject = spec.rule() == AlertRule.A6 ? e.resource() : e.actor();
            if (spec.threshold() == 1) {
                out.add(new Alert(spec.rule(), subject, e.timestamp(), 1, entry.seq(), e.correlationId()));
                continue;
            }
            Window w = windows.computeIfAbsent(spec.rule() + "|" + e.actor(), k -> new Window());
            long limit = now - spec.window().toMillis();
            // Les événements arrivent dans l'ordre d'écriture ; un léger désordre d'horloge est toléré.
            while (!w.times.isEmpty() && w.times.peekFirst() <= limit) {
                w.times.pollFirst();
            }
            w.times.addLast(now);
            w.lastMillis = Math.max(w.lastMillis, now);
            if (w.times.size() >= spec.threshold()) {
                if (!w.alerted) {
                    w.alerted = true;
                    out.add(new Alert(spec.rule(), subject, e.timestamp(), w.times.size(), entry.seq(),
                            e.correlationId()));
                }
            } else {
                w.alerted = false;
            }
        }
        if (windows.size() > SWEEP_THRESHOLD) {
            long stale = now - LONGEST_WINDOW.toMillis();
            windows.values().removeIf(w -> w.lastMillis < stale);
        }
        return out;
    }
}
