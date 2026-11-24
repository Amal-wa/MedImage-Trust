package tn.medimagetrust.watermark;

import java.time.Instant;
import java.util.Optional;

/** Contrat d'interrogation du registre des clés publiques (key_registry) pour vérifier kid et validité. */
public interface PublicKeyStatusRegistry {

    enum KeyStatus {
        ACTIVE,
        EXPIRED,
        REVOKED
    }

    record KeyMetadata(String kid, int institutionId, Instant validFrom, Instant validTo, Instant revokedAt, KeyStatus status) {
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

    Optional<KeyMetadata> findByKid(String kid);
}