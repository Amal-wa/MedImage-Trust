package tn.medimagetrust.audit;

/** Un autre écrivain a ajouté une entrée entre la lecture de la tête et l'insertion : réessayer. */
public final class AuditConflictException extends AuditException {

    private static final long serialVersionUID = 1L;

    public AuditConflictException() {
        super("Conflit d'écriture dans le journal");
    }
}
