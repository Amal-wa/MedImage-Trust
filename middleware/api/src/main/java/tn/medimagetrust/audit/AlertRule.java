package tn.medimagetrust.audit;

/** Règles d'alerte (SEC07 A1–A7, SEC02, SEC-R04, MT28). */
public enum AlertRule {
    /** >= 5 échecs de connexion en 5 min sur un compte. */
    A1,
    /** >= 20 échecs de connexion en 1 h sur un compte (SEC02). */
    A1B,
    /** >= 3 rejets de fichiers suspects par un utilisateur en 1 h. */
    A2,
    /** >= 3 extractions refusées en 1 h. */
    A3,
    /** 4e émission dégradée ou refus de RONI en 24 h. */
    A4,
    /** Tout changement de privilège. */
    A5,
    /** Écart de SHA-256 d'un original. */
    A6,
    /** Rupture de la chaîne du journal ou de ses ancrages. */
    A7,
    /** Ancrage impossible (MT28). */
    ANCHOR_FAILURE,
    /** Dernière vérification de chaîne vieille de plus de 26 h (SEC-R04, MT28). */
    VERIFICATION_STALE
}
