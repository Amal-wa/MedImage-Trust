package tn.medimagetrust.audit;

/** Droit de purge de rétention, séparé de {@link AuditStore} : jamais donné au rôle applicatif. */
public interface AuditPurgeStore {

    /** Supprime les entrées de séquence <= {@code seq}. */
    void purgeThrough(long seq);
}
