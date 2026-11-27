package tn.medimagetrust.crypto;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * KEK en mémoire, pour le développement et les tests : 32 octets aléatoires par kid. Toutes les autres clés en sont
 * dérivées ({@code KeyDerivation}). En production : {@code ElytronKeyProvider} (module web).
 */
public final class InMemoryKeyProvider implements KeyProvider {

    private static final int KEK_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<Integer, SecretKey> keks = new ConcurrentHashMap<>();
    private volatile int activeKid = -1;

    /**
     * Tire une nouvelle KEK pour {@code kid} et rend ce kid actif. Les KEK des kid précédents restent disponibles
     * (vérification des anciennes copies).
     *
     * @throws IllegalArgumentException si le kid est hors de {@code [0, 65 535]} ou déjà enregistré : une KEK n'est
     *                                  jamais remplacée, sinon les copies déjà émises deviendraient illisibles
     */
    public synchronized void registerNewKek(int kid) {
        Kid.check(kid);
        if (keks.containsKey(kid)) {
            throw new IllegalArgumentException("kid déjà enregistré");
        }
        byte[] kek = new byte[KEK_BYTES];
        RANDOM.nextBytes(kek);
        keks.put(kid, new SecretKeySpec(kek, "HKDF-SHA256"));
        Arrays.fill(kek, (byte) 0);
        activeKid = kid;
    }

    @Override
    public int activeKid() {
        int kid = activeKid;
        if (kid < 0) {
            throw new IllegalStateException("Aucune KEK enregistrée");
        }
        return kid;
    }

    @Override
    public SecretKey kek(int kid) {
        SecretKey key = keks.get(kid);
        if (key == null) {
            throw new IllegalArgumentException("kid inconnu");
        }
        return key;
    }

    /** N'imprime ni kid ni octet de clé. */
    @Override
    public String toString() {
        return "InMemoryKeyProvider[contenu masqué]";
    }
}
