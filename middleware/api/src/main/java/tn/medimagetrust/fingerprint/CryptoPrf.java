package tn.medimagetrust.fingerprint;

import tn.medimagetrust.crypto.CryptoService;

import java.util.function.UnaryOperator;

/**
 * Adaptateur entre le module fingerprint et le module medimage-crypto (Nour).
 * K_F est dérivée par CryptoService (HKDF, label MIT-KF-v1, selon le kid sur 16 bits) et reste encapsulée :
 * ce module ne voit jamais les octets de la clé.
 */
public final class CryptoPrf implements Prf {
    private final CryptoService crypto;
    private final int kid;
    private final UnaryOperator<byte[]> kfHmac;

    public CryptoPrf(CryptoService crypto, int kid) {
        this.crypto = crypto;
        this.kid = kid;
        this.kfHmac = crypto.kfHmac(kid); // dérivée une seule fois
    }

    public int kid() { return kid; }

    @Override
    public byte[] hmac(byte[] message) {
        return kfHmac.apply(message);
    }

    /** Délégation : l'engagement fCommit est calculé par le module crypto, source unique. */
    @Override
    public byte[] commit128(byte[] message) {
        return crypto.computeFCommit(kid, message);
    }
}
