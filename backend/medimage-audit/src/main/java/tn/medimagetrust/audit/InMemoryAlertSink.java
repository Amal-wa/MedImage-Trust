package tn.medimagetrust.audit;

import java.util.ArrayList;
import java.util.List;

/** Collecte les alertes en mémoire (tests, mode démo). */
public final class InMemoryAlertSink implements AlertSink {

    private final List<Alert> alerts = new ArrayList<>();

    @Override
    public synchronized void raise(Alert alert) {
        alerts.add(alert);
    }

    public synchronized List<Alert> alerts() {
        return List.copyOf(alerts);
    }

    public synchronized List<Alert> alerts(AlertRule rule) {
        return alerts.stream().filter(a -> a.rule() == rule).toList();
    }
}
