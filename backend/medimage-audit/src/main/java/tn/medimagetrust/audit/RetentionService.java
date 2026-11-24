package tn.medimagetrust.audit;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Rétention du journal : 12 mois, purge par segment (cahier des charges §Durées de conservation).
 *
 * <p>Seuil : début du mois courant moins 12 mois. On purge jusqu'au dernier ancrage quotidien daté avant ce seuil
 * (donc des mois complets, au jour d'ancrage près) ; cet ancrage <b>est conservé</b> : il sert de base de confiance
 * à la première ligne restante, pour que la chaîne reste vérifiable ({@link AuditVerifier}).
 *
 * <p>Garde-fous : la purge est refusée si la vérification complète échoue (on ne détruit jamais la preuve
 * d'une falsification), et la tête n'est jamais purgée.
 */
public final class RetentionService {

    private static final int RETENTION_MONTHS = 12;

    private final AuditStore store;
    private final AuditPurgeStore purgeStore;
    private final AnchorStore primary;
    private final AuditVerifier verifier;
    private final Clock clock;

    public RetentionService(AuditStore store, AuditPurgeStore purgeStore, AnchorStore primary,
                            AuditVerifier verifier, Clock clock) {
        this.store = store;
        this.purgeStore = purgeStore;
        this.primary = primary;
        this.verifier = verifier;
        this.clock = clock;
    }

    /** Purge les segments expirés ; renvoie le nombre de lignes supprimées. */
    public long purgeExpired() {
        if (!verifier.verify().ok()) {
            throw new AuditException("Purge refusée : la vérification du journal a échoué");
        }
        Optional<AuditEntry> first = store.first();
        Optional<AuditEntry> head = store.head();
        if (first.isEmpty() || head.isEmpty()) {
            return 0;
        }
        LocalDate cutoff = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC))
                .minusMonths(RETENTION_MONTHS).atDay(1);

        Anchor boundary = null;
        List<LocalDate> days = primary.days();
        for (int i = days.size() - 1; i >= 0; i--) {
            if (days.get(i).isBefore(cutoff)) {
                boundary = Anchor.decode(primary.read(days.get(i))
                        .orElseThrow(() -> new AuditException("Ancrage illisible")));
                break;
            }
        }
        if (boundary == null || boundary.lastSeq() >= head.get().seq() || boundary.lastSeq() < first.get().seq()) {
            return 0;
        }
        long purged = boundary.lastSeq() - first.get().seq() + 1;
        purgeStore.purgeThrough(boundary.lastSeq());
        return purged;
    }
}
