package tn.medimagetrust.fingerprint;

import java.util.Arrays;

/**
 * Port vers la fonction pseudo-aléatoire HMAC-SHA-256 sous la clé K_F.
 * En production : {@link CryptoPrf}, branché sur le module medimage-crypto (K_F dérivée par HKDF,
 * versionnée par kid, jamais codée en dur). Pour les tests unitaires : {@link HmacSha256Prf}.
 */
public interface Prf {
    /** @return HMAC-SHA-256(K_F, message) : 32 octets. */
    byte[] hmac(byte[] message);

    /**
     * Engagement 128 bits (16 premiers octets du HMAC) : c'est l'engagement fCommit signé dans la preuve W.
     * {@link CryptoPrf} le délègue à CryptoService.computeFCommit : une seule implémentation fait foi.
     */
    default byte[] commit128(byte[] message) {
        return Arrays.copyOf(hmac(message), 16);
    }
}
