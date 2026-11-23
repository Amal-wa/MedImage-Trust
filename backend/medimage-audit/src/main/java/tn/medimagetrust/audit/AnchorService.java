package tn.medimagetrust.audit;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Ancrage quotidien de la tête de chaîne (SEC09), à lancer une fois par jour par le service d'audit.
 *
 * <ul>
 *   <li>Signe (K_log) {@code (jour, seq de tête, entry_hash de tête)} et écrit l'ancrage sur le volume principal
 *       puis sur la copie séparée.</li>
 *   <li>Idempotent : un second appel le même jour renvoie l'ancrage existant et complète la copie si besoin.</li>
 *   <li>Refuse de signer une tête incohérente avec l'ancrage précédent (journal tronqué ou réécrit) :
 *       sinon l'ancrage « blanchirait » la falsification.</li>
 *   <li>Échec fermé (MT28) : toute erreur lève {@link AuditException} et lève l'alerte ANCHOR_FAILURE.</li>
 * </ul>
 */
public final class AnchorService {

    private final AuditStore store;
    private final AnchorStore primary;
    private final AnchorStore copy;
    private final LogKeyProvider keys;
    private final AlertSink sink;
    private final Clock clock;

    public AnchorService(AuditStore store, AnchorStore primary, AnchorStore copy, LogKeyProvider keys,
                         AlertSink sink, Clock clock) {
        this.store = store;
        this.primary = primary;
        this.copy = copy;
        this.keys = keys;
        this.sink = sink;
        this.clock = clock;
    }

    public Anchor anchorToday() {
        try {
            AuditEntry head = store.head().orElseThrow(() -> new AuditException("Journal vide : rien à ancrer"));
            LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
            checkConsistentWithPrevious(head, today);

            Anchor anchor;
            Optional<byte[]> existing = primary.read(today);
            if (existing.isPresent()) {
                anchor = Anchor.decode(existing.get());
            } else {
                anchor = Anchor.sign(today, head.seq(), head.entryHash(), keys);
                primary.write(today, anchor.encode());
            }
            Optional<byte[]> c = copy.read(today);
            if (c.isEmpty()) {
                copy.write(today, anchor.encode());
            } else if (!Arrays.equals(c.get(), anchor.encode())) {
                throw new AuditException("Copie d'ancrage divergente");
            }
            return anchor;
        } catch (RuntimeException e) {
            sink.raise(new Alert(AlertRule.ANCHOR_FAILURE, "anchor", clock.instant(), 1, -1, "-"));
            if (e instanceof AuditException) {
                throw e;
            }
            throw new AuditException("Ancrage impossible", e);
        }
    }

    private void checkConsistentWithPrevious(AuditEntry head, LocalDate today) {
        List<LocalDate> days = primary.days();
        for (int i = days.size() - 1; i >= 0; i--) {
            LocalDate d = days.get(i);
            if (!d.isBefore(today)) {
                continue;
            }
            Anchor prev = Anchor.decode(primary.read(d).orElseThrow(() -> new AuditException("Ancrage illisible")));
            if (prev.lastSeq() > head.seq()) {
                throw new AuditException("Journal plus court que l'ancrage précédent");
            }
            Optional<AuditEntry> first = store.first();
            if (first.isPresent() && prev.lastSeq() >= first.get().seq()) {
                Optional<AuditEntry> e = store.get(prev.lastSeq());
                if (e.isEmpty() || !java.security.MessageDigest.isEqual(e.get().entryHash(), prev.headHash())) {
                    throw new AuditException("Journal incohérent avec l'ancrage précédent");
                }
            }
            return;
        }
    }
}
