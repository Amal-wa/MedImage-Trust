package tn.medimagetrust.audit;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import tn.medimagetrust.audit.VerificationReport.Problem;

/**
 * Vérification de l'intégrité du journal (SEC09) : chaîne de condensats, ancrages signés, et comparaison
 * entre le volume principal et sa copie. À lancer chaque jour et à la demande du responsable sécurité.
 *
 * <p>Toute anomalie lève l'alerte A7. Limite connue : la chaîne seule ne détecte pas la suppression des
 * dernières lignes ; seul le dernier ancrage borne cette fenêtre (au plus un jour).
 */
public final class AuditVerifier {

    /** SEC-R04 : alerter si la dernière vérification date de plus de 26 h. */
    public static final Duration MAX_VERIFICATION_AGE = Duration.ofHours(26);
    private static final int PAGE = 1000;

    private final AuditStore store;
    private final AnchorStore primary;
    private final AnchorStore copy;
    private final LogKeyProvider keys;
    private final AlertSink sink;
    private final Clock clock;
    private volatile Instant lastRun;

    public AuditVerifier(AuditStore store, AnchorStore primary, AnchorStore copy, LogKeyProvider keys,
                         AlertSink sink, Clock clock) {
        this.store = store;
        this.primary = primary;
        this.copy = copy;
        this.keys = keys;
        this.sink = sink;
        this.clock = clock;
    }

    public VerificationReport verify() {
        List<Problem> problems = new ArrayList<>();
        List<Anchor> anchors = loadAnchors(problems);
        long checked = verifyChain(anchors, problems);
        verifyAnchors(anchors, problems);

        lastRun = clock.instant();
        boolean ok = problems.isEmpty();
        if (!ok) {
            long firstSeq = problems.stream().mapToLong(Problem::seq).filter(s -> s >= 0).findFirst().orElse(-1);
            sink.raise(new Alert(AlertRule.A7, "chain", lastRun, problems.size(), firstSeq, "-"));
        }
        return new VerificationReport(ok, checked, anchors.size(), problems);
    }

    /** Lève VERIFICATION_STALE si aucune vérification depuis plus de 26 h (ou jamais). */
    public Optional<Alert> checkFreshness() {
        Instant now = clock.instant();
        Instant last = lastRun;
        if (last == null || Duration.between(last, now).compareTo(MAX_VERIFICATION_AGE) > 0) {
            Alert alert = new Alert(AlertRule.VERIFICATION_STALE, "chain", now, 1, -1, "-");
            sink.raise(alert);
            return Optional.of(alert);
        }
        return Optional.empty();
    }

    public Optional<Instant> lastVerification() {
        return Optional.ofNullable(lastRun);
    }

    // ---------------------------------------------------------------- chaîne

    private long verifyChain(List<Anchor> anchors, List<Problem> problems) {
        Optional<AuditEntry> firstOpt = store.first();
        Optional<AuditEntry> headOpt = store.head();
        if (firstOpt.isEmpty() || headOpt.isEmpty()) {
            return 0; // journal vide : les ancrages éventuels sont signalés par verifyAnchors
        }
        AuditEntry first = firstOpt.get();
        long headSeq = headOpt.get().seq();

        if (first.seq() == 1) {
            if (!MessageDigest.isEqual(first.prevHash(), AuditChain.GENESIS)) {
                problems.add(new Problem(ProblemKind.PREV_MISMATCH, 1, "genèse"));
                return 0;
            }
        } else if (!baseTrusted(first, anchors)) {
            problems.add(new Problem(ProblemKind.BASE_UNTRUSTED, first.seq(), "aucun ancrage valide avant la première ligne"));
            return 0;
        }

        long expected = first.seq();
        byte[] prev = first.prevHash();
        long checked = 0;
        while (expected <= headSeq) {
            List<AuditEntry> page = store.range(expected, Math.min(headSeq, expected + PAGE - 1));
            if (page.isEmpty()) {
                problems.add(new Problem(ProblemKind.SEQ_GAP, expected, "lignes manquantes"));
                return checked;
            }
            for (AuditEntry e : page) {
                if (e.seq() != expected) {
                    problems.add(new Problem(ProblemKind.SEQ_GAP, expected, "séquence attendue absente"));
                    return checked;
                }
                if (!MessageDigest.isEqual(e.prevHash(), prev)) {
                    problems.add(new Problem(ProblemKind.PREV_MISMATCH, e.seq(), "chaînage rompu"));
                    return checked;
                }
                byte[] recomputed = AuditChain.hash(e.prevHash(), AuditChain.canonical(e.seq(), e.event()));
                if (!MessageDigest.isEqual(recomputed, e.entryHash())) {
                    problems.add(new Problem(ProblemKind.HASH_MISMATCH, e.seq(), "contenu modifié"));
                    return checked;
                }
                prev = e.entryHash();
                expected++;
                checked++;
            }
        }
        return checked;
    }

    /** Après purge : la première ligne conservée doit se rattacher à un ancrage signé valide (lastSeq = seq - 1). */
    private boolean baseTrusted(AuditEntry first, List<Anchor> anchors) {
        for (Anchor a : anchors) {
            if (a.lastSeq() == first.seq() - 1
                    && MessageDigest.isEqual(a.headHash(), first.prevHash())
                    && signatureValid(a)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------- ancrages

    /** Charge les ancrages des deux volumes (union des jours) ; signale les formats invalides. */
    private List<Anchor> loadAnchors(List<Problem> problems) {
        TreeSet<LocalDate> days = new TreeSet<>(primary.days());
        days.addAll(copy.days());
        List<Anchor> out = new ArrayList<>();
        for (LocalDate day : days) {
            Optional<byte[]> p = primary.read(day);
            Optional<byte[]> c = copy.read(day);
            if (p.isEmpty()) {
                problems.add(new Problem(ProblemKind.ANCHOR_COPY_MISSING, -1, "absent du volume principal : " + day));
            } else if (c.isEmpty()) {
                problems.add(new Problem(ProblemKind.ANCHOR_COPY_MISSING, -1, "absent de la copie : " + day));
            } else if (!Arrays.equals(p.get(), c.get())) {
                problems.add(new Problem(ProblemKind.ANCHOR_COPY_MISMATCH, -1, "volumes divergents : " + day));
            }
            Optional<byte[]> raw = p.isPresent() ? p : c;
            try {
                out.add(Anchor.decode(raw.orElseThrow()));
            } catch (IllegalArgumentException e) {
                problems.add(new Problem(ProblemKind.ANCHOR_MALFORMED, -1, "format invalide : " + day));
            }
        }
        return out;
    }

    private void verifyAnchors(List<Anchor> anchors, List<Problem> problems) {
        Optional<AuditEntry> first = store.first();
        for (Anchor a : anchors) {
            Optional<PublicKey> key = keys.publicKey(a.kid());
            if (key.isEmpty()) {
                problems.add(new Problem(ProblemKind.ANCHOR_UNKNOWN_KEY, a.lastSeq(), "kid inconnu : " + a.day()));
                continue;
            }
            if (!a.verifySignature(key.get())) {
                problems.add(new Problem(ProblemKind.ANCHOR_SIGNATURE_INVALID, a.lastSeq(), "signature invalide : " + a.day()));
                continue;
            }
            if (first.isPresent() && a.lastSeq() < first.get().seq()) {
                continue; // segment purgé : l'ancrage est conservé comme preuve, sa ligne n'existe plus
            }
            Optional<AuditEntry> e = store.get(a.lastSeq());
            if (e.isEmpty()) {
                problems.add(new Problem(ProblemKind.ANCHOR_ENTRY_MISSING, a.lastSeq(), "ligne ancrée absente : " + a.day()));
            } else if (!MessageDigest.isEqual(e.get().entryHash(), a.headHash())) {
                problems.add(new Problem(ProblemKind.ANCHOR_HEAD_MISMATCH, a.lastSeq(), "ligne ancrée réécrite : " + a.day()));
            }
        }
    }

    private boolean signatureValid(Anchor a) {
        return keys.publicKey(a.kid()).map(a::verifySignature).orElse(false);
    }
}
