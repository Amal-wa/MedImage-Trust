package tn.medimagetrust.audit;

/** Destination des alertes (console du responsable sécurité, notification). Ne doit pas lever d'exception. */
public interface AlertSink {

    void raise(Alert alert);
}
