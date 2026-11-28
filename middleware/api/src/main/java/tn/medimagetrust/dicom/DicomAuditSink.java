package tn.medimagetrust.dicom;

/**
 * Destinataire des événements de sécurité de la lecture DICOM. Le package {@code dicom} ne dépend d'aucun autre
 * package du projet que {@code embedding} : c'est l'appelant (couche REST) qui relie ce point au journal d'audit.
 *
 * <p>Un événement ne porte qu'un code fixe : jamais de valeur du fichier.</p>
 */
@FunctionalInterface
public interface DicomAuditSink {

    /** Événements émis par la lecture. */
    enum Event {
        /** Préambule d'exécutable détecté (fichier polyglotte) : tentative probable de dépôt de programme. */
        DICOM_SUSPICIOUS_PREAMBLE
    }

    /** Enregistre un événement ; ne doit pas lever d'exception. */
    void record(Event event);

    /** Destinataire qui n'enregistre rien (tests, outils). */
    DicomAuditSink NONE = event -> { };
}
