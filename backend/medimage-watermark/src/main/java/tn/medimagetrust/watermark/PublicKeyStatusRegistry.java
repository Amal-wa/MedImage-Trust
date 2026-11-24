package tn.medimagetrust.watermark;

import java.time.Instant;
import java.util.Optional;
import tn.medimagetrust.crypto.Kid;

/** Contrat d'interrogation du registre des clés publiques (key_registry) pour vérifier kid et validité. */
public interface PublicKeyStatusRegistry {

    enum KeyStatus {
        ACTIVE,
        EXPIRED,
        REVOKED
    }

    /**
     * Métadonnées d'une clé publique. Le {@code kid} est un entier sur 16 bits ({@code 0} à {@code 65 535}), comme
     * dans la charge W et partout ailleurs ({@link Kid}).
     */
    record KeyMetadata(int kid, int institutionId, Instant validFrom, Instant validTo, Instant revokedAt, KeyStatus status) {
        public KeyMetadata {
            Kid.check(kid);
        }

        public boolean isValidAt(Instant issuedAt) {
            if (issuedAt.isBefore(validFrom)) {
                return false;
            }
            if (validTo != null && issuedAt.isAfter(validTo)) {
                return false;
            }
            if (status == KeyStatus.REVOKED && revokedAt != null && !issuedAt.isBefore(revokedAt)) {
                return false;
            }
            return status != KeyStatus.EXPIRED;
        }
    }

    /** Métadonnées du kid (entier sur 16 bits) ; vide si le kid est inconnu du registre. */
    Optional<KeyMetadata> findByKid(int kid);
}
