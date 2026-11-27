package tn.medimagetrust.audit;

import java.security.PublicKey;
import java.util.Optional;

/**
 * Clé de signature des ancrages (K_log, Ed25519), versionnée par {@code kid} sur 16 bits (§8.3).
 * La clé privée ne sort pas : seule l'opération {@link #sign(byte[])} est exposée (SEC-K04).
 * En production : keystore du service d'audit (§8.6), passphrase en Docker secret.
 */
public interface LogKeyProvider {

    int activeKid();

    /** Signature Ed25519 avec la clé active. */
    byte[] sign(byte[] message);

    /** Clé publique d'un kid (ancien ou actif) ; vide si inconnu. */
    Optional<PublicKey> publicKey(int kid);
}
