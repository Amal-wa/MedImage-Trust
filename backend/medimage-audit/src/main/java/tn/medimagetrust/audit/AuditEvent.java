package tn.medimagetrust.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Événement à schéma fixe (SEC07) : horodatage UTC, type, acteur, ressource, résultat,
 * identifiant de corrélation, plus un condensat SHA-256 optionnel (SEC05, SEC06).
 *
 * <p>Liste blanche stricte (MT11, SEC-P01) : acteur, ressource et corrélation sont des
 * identifiants opaques de 1 à 64 caractères dans {@code [A-Za-z0-9._:@-]}. Un retour chariot, un guillemet,
 * un nom de fichier ou un attribut DICOM est donc rejeté à la construction, jamais journalisé.
 * L'appelant passe l'identifiant opaque du compte (jamais le nom saisi à la connexion).
 * Les messages d'erreur ne reproduisent pas la valeur refusée.
 */
public record AuditEvent(
        Instant timestamp,
        EventType type,
        String actor,
        String resource,
        Result result,
        String correlationId,
        String digestHex) {

    private static final Pattern OPAQUE_ID = Pattern.compile("[A-Za-z0-9._:@-]{1,64}");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public AuditEvent {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(result, "result");
        // Précision milliseconde : la forme canonique stocke des millisecondes entières.
        timestamp = Instant.ofEpochMilli(timestamp.toEpochMilli());
        if (timestamp.toEpochMilli() < 0) {
            throw new IllegalArgumentException("Horodatage invalide");
        }
        requireOpaqueId(actor, "acteur");
        requireOpaqueId(resource, "ressource");
        requireOpaqueId(correlationId, "identifiant de corrélation");
        digestHex = digestHex == null ? "" : digestHex;
        if (!digestHex.isEmpty() && !SHA256_HEX.matcher(digestHex).matches()) {
            throw new IllegalArgumentException("Condensat invalide (64 caractères hexadécimaux minuscules attendus)");
        }
    }

    /** Événement sans condensat. */
    public AuditEvent(Instant timestamp, EventType type, String actor, String resource,
                      Result result, String correlationId) {
        this(timestamp, type, actor, resource, result, correlationId, "");
    }

    private static void requireOpaqueId(String value, String field) {
        if (value == null || !OPAQUE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Champ invalide : " + field);
        }
    }
}
