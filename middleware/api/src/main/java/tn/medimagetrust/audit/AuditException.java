package tn.medimagetrust.audit;

/**
 * Échec du journal ou de l'ancrage. Non vérifiée : l'appelant métier doit refuser l'opération
 * (échec fermé, cahier des charges §Principes : « jamais dégradée en silence »).
 * Les messages ne contiennent jamais de valeur issue de l'utilisateur.
 */
public class AuditException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuditException(String message) {
        super(message);
    }

    public AuditException(String message, Throwable cause) {
        super(message, cause);
    }
}
