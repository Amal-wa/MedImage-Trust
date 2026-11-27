package tn.medimagetrust.audit;

import java.security.PublicKey;
import java.util.Objects;
import java.util.Optional;
import tn.medimagetrust.crypto.CryptoService;
import tn.medimagetrust.crypto.Ed25519;

/**
 * K_log de production : paire Ed25519 dérivée de la KEK par le module crypto (graine {@code MIT-LOG-v1}, dossier
 * v2.4 §8.2). Aucune clé n'est conservée : chaque signature redérive la clé privée du kid actif, et la vérification
 * redérive la clé publique du kid de l'ancrage, ce qui garde vérifiables les ancrages signés avant une rotation.
 */
public final class CryptoLogKeyProvider implements LogKeyProvider {

    private final CryptoService crypto;

    public CryptoLogKeyProvider(CryptoService crypto) {
        this.crypto = Objects.requireNonNull(crypto, "service crypto");
    }

    @Override
    public int activeKid() {
        return crypto.activeKid();
    }

    @Override
    public byte[] sign(byte[] message) {
        return Ed25519.sign(crypto.logKeyPair(crypto.activeKid()).getPrivate(), message);
    }

    /** Clé publique du kid ; vide si le kid est inconnu ou hors de {@code [0, 65 535]}. */
    @Override
    public Optional<PublicKey> publicKey(int kid) {
        try {
            return Optional.of(crypto.logKeyPair(kid).getPublic());
        } catch (IllegalArgumentException unknownKid) {
            return Optional.empty();
        }
    }
}
