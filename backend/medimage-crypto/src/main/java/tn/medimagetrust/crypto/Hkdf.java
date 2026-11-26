package tn.medimagetrust.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.KDF;
import javax.crypto.spec.HKDFParameterSpec;

/** Dérivation de clés HKDF-SHA-256 (RFC 5869) via l'API native KDF de Java 25. */
public final class Hkdf {

    private static final String ALGORITHM = "HKDF-SHA256";

    private Hkdf() {
    }

    /**
     * Dérive une clé de longueur {@code lengthBytes} à partir d'une clé maître (ikm),
     * d'un sel optionnel (salt) et d'un contexte applicatif (info).
     */
    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int lengthBytes) {
        if (ikm == null || ikm.length == 0) {
            throw new IllegalArgumentException("La clé maître (ikm) ne peut pas être vide");
        }
        if (lengthBytes <= 0 || lengthBytes > 255 * 32) {
            throw new IllegalArgumentException("Longueur de clé dérivée invalide : " + lengthBytes);
        }
        try {
            KDF kdf = KDF.getInstance(ALGORITHM);
            var builder = HKDFParameterSpec.ofExtract().addIKM(ikm);
            if (salt != null && salt.length > 0) {
                builder.addSalt(salt);
            }
            HKDFParameterSpec params = builder.thenExpand(info, lengthBytes);
            return kdf.deriveData(params);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HKDF-SHA256 indisponible", e);
        }
    }

    /** Raccourci pour dériver une clé de 256 bits (32 octets) avec un label textuel. */
    public static byte[] derive256(byte[] masterKey, byte[] salt, String contextLabel) {
        byte[] info = contextLabel == null ? new byte[0] : contextLabel.getBytes(StandardCharsets.UTF_8);
        return derive(masterKey, salt, info, 32);
    }
}
