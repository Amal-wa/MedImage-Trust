package tn.medimagetrust.audit;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import tn.medimagetrust.crypto.Ed25519;

/** K_log en mémoire (développement, tests). Rotation : les anciens kid restent vérifiables. */
public final class InMemoryLogKeyProvider implements LogKeyProvider {

    private final Map<Integer, KeyPair> keys = new ConcurrentHashMap<>();
    private volatile int active;

    public InMemoryLogKeyProvider(int firstKid) {
        rotate(firstKid);
    }

    /** Nouvelle clé de signature, devenue active ; les ancrages signés avec les précédentes restent vérifiables. */
    public void rotate(int kid) {
        if (kid < 0 || kid > 0xFFFF) {
            throw new IllegalArgumentException("kid sur 16 bits attendu");
        }
        if (keys.containsKey(kid)) {
            throw new IllegalArgumentException("kid déjà utilisé");
        }
        keys.put(kid, Ed25519.generateKeyPair());
        active = kid;
    }

    @Override
    public int activeKid() {
        return active;
    }

    @Override
    public byte[] sign(byte[] message) {
        return Ed25519.sign(keys.get(active).getPrivate(), message);
    }

    @Override
    public Optional<PublicKey> publicKey(int kid) {
        return Optional.ofNullable(keys.get(kid)).map(KeyPair::getPublic);
    }
}
