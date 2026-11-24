package tn.medimagetrust.audit;

import java.util.List;
import java.util.Optional;

/**
 * Stockage du journal : INSERT seul (SEC09). Aucune méthode de modification ni de suppression ici ;
 * la purge de rétention est dans {@link AuditPurgeStore}, réservée au service d'audit.
 *
 * <p>Implémentation PostgreSQL attendue : rôle applicatif avec le seul droit INSERT/SELECT,
 * {@code seq} en clé primaire (l'unicité fait échouer les écritures concurrentes -> {@link AuditConflictException}).
 */
public interface AuditStore {

    /**
     * Ajoute une entrée de façon atomique. Doit refuser (AuditConflictException) si
     * {@code entry.seq() != tête.seq() + 1} ou si {@code entry.prevHash()} n'est pas le condensat de la tête.
     */
    void append(AuditEntry entry);

    Optional<AuditEntry> head();

    /** Plus ancienne entrée conservée (supérieure à 1 après une purge). */
    Optional<AuditEntry> first();

    Optional<AuditEntry> get(long seq);

    /** Entrées présentes dont la séquence est dans [fromSeq, toSeq], par ordre croissant. */
    List<AuditEntry> range(long fromSeq, long toSeq);
}
