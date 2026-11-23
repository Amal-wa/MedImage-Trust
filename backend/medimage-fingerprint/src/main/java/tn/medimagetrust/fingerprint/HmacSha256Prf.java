package tn.medimagetrust.fingerprint;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * Implémentation JCA de {@link Prf}. Utilisée pour les tests et en attendant le module crypto.
 * En production la clé vient du coffre de secrets, jamais du code ni d'une variable d'environnement.
 */
public final class HmacSha256Prf implements Prf {
    private final ThreadLocal<Mac> mac;

    public HmacSha256Prf(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length < 32) {
            throw new IllegalArgumentException("K_F doit faire au moins 256 bits");
        }
        SecretKeySpec key = new SecretKeySpec(keyBytes.clone(), "HmacSHA256");
        this.mac = ThreadLocal.withInitial(() -> {
            try {
                Mac m = Mac.getInstance("HmacSHA256");
                m.init(key);
                return m;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("HmacSHA256 indisponible", e);
            }
        });
    }

    @Override
    public byte[] hmac(byte[] message) {
        return mac.get().doFinal(message); // doFinal réinitialise le Mac
    }
}
