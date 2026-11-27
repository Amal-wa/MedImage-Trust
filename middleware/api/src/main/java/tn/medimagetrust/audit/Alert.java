package tn.medimagetrust.audit;

import java.time.Instant;

/**
 * Alerte levée par une règle.
 *
 * @param subject  acteur concerné (ressource pour A6, « chain » pour A7) ; identifiant opaque
 * @param count    nombre d'occurrences ayant déclenché la règle
 * @param seq      séquence de l'entrée déclencheuse, -1 si l'alerte ne vient pas d'une entrée
 */
public record Alert(AlertRule rule, String subject, Instant at, int count, long seq, String correlationId) {
}
