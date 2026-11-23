package tn.medimagetrust.crypto;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;

/**
 * Implémentation en mémoire de KeyProvider pour le développement et les tests.
 * Gère plusieurs versions de clés identifiées par leur kid (rotation sans coupure).
 */
public final class InMemoryKeyProvider implements KeyProvider {

    private final Map<String, KeyPair> signingKeys = new ConcurrentHashMap<>();
    private final Map<String, SecretKey> masterKeys = new ConcurrentHashMap<>();
    private volatile String activeKid;

    /** Génère et enregistre un nouveau jeu de clés pour un kid donné, et le rend actif. */
    public void registerNewKeyVersion(String kid) {
        if (kid == null || kid.isBlank()) {
            throw new IllegalArgumentException("Le kid ne peut pas être vide");
        }
        signingKeys.put(kid, Ed25519.generateKeyPair());
        masterKeys.put(kid, AesGcm.generateKey());
        this.activeKid = kid;
    }

    @Override
    public String activeSigningKeyId() {
        if (activeKid == null) {
            throw new IllegalStateException("Aucune clé active enregistrée");
        }
        return activeKid;
    }

    @Override
    public PrivateKey getSigningPrivateKey(String kid) {
        KeyPair kp = signingKeys.get(kid);
        if (kp == null) {
            throw new IllegalArgumentException("kid inconnu : " + kid);
        }
        return kp.getPrivate();
    }

    @Override
    public PublicKey getSigningPublicKey(String kid) {
        KeyPair kp = signingKeys.get(kid);
        if (kp == null) {
            throw new IllegalArgumentException("kid inconnu : " + kid);
        }
        return kp.getPublic();
    }

    @Override
    public SecretKey getMasterSecretKey(String kid) {
        SecretKey key = masterKeys.get(kid);
        if (key == null) {
            throw new IllegalArgumentException("kid inconnu : " + kid);
        }
        return key;
    }
}
