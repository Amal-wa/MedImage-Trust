package tn.medimagetrust.audit;

/** Types d'événements journalisés (SEC07). Liste fermée : aucun type libre. */
public enum EventType {
    AUTH_SUCCESS,
    AUTH_FAILURE,
    ACCESS_DENIED,
    FILE_IMPORTED,
    /** Fichier rejeté par les contrôles d'entrée (SEC-D02) : compté par la règle A2. */
    FILE_REJECTED,
    ROI_VALIDATED,
    /** Refus de RONI par le clinicien : compté par la règle A4. */
    ROI_REFUSED,
    COPY_ISSUED,
    /** Émission dégradée d'une image non marquable : comptée par la règle A4. */
    COPY_ISSUED_DEGRADED,
    EXTRACTION_PERFORMED,
    /** Extraction du lien patient refusée : comptée par la règle A3. */
    EXTRACTION_DENIED,
    VERIFICATION_PERFORMED,
    COPY_ANALYZED,
    RESEARCH_EXPORT,
    RESEARCH_EXPORT_REFUSED,
    /** Changement de rôle ou de privilège : déclenche A5. */
    ROLE_CHANGE,
    /** Modification du registre de clés (SEC-K05). */
    KEY_REGISTRY_CHANGE,
    /** Écart de SHA-256 d'un original (SEC05) : déclenche A6. */
    ORIGINAL_HASH_MISMATCH,
    /** Alerte levée par le moteur de règles, inscrite dans la chaîne pour qu'on ne puisse pas la masquer (CA15). */
    ALERT_RAISED
}
