package tn.medimagetrust.audit;

import java.util.List;

/**
 * Résultat d'une vérification. {@code seq} vaut -1 pour une anomalie d'ancrage sans ligne associée.
 * Contient uniquement des séquences, dates et motifs : aucune donnée de l'événement.
 */
public record VerificationReport(boolean ok, long entriesChecked, int anchorsChecked, List<Problem> problems) {

    public record Problem(ProblemKind kind, long seq, String detail) {
    }

    public VerificationReport {
        problems = List.copyOf(problems);
    }

    public boolean has(ProblemKind kind) {
        return problems.stream().anyMatch(p -> p.kind() == kind);
    }
}
