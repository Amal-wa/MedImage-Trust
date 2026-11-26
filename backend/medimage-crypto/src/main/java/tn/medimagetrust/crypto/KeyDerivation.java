package tn.medimagetrust.crypto;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Dérivation de toutes les clés de l'établissement à partir de la KEK (dossier v2.4, §8.2, figure 8.1).
 *
 * <p>HKDF-SHA-256 ({@link Hkdf}, API {@code javax.crypto.KDF}), <strong>sans sel</strong>, 32 octets de sortie, avec
 * {@code info = étiquette ASCII ‖ 0x00 ‖ kid (2 octets gros-boutiste)} :</p>
 *
 * <table>
 *   <caption>Clés dérivées de la KEK</caption>
 *   <tr><th>Clé</th><th>Étiquette</th><th>Usage</th></tr>
 *   <tr><td>K_tok</td><td>{@code MIT-KTOK-v1}</td><td>jeton S</td></tr>
 *   <tr><td>K_F</td><td>{@code MIT-KF-v1}</td><td>fingerprint (HMAC)</td></tr>
 *   <tr><td>K_store</td><td>{@code MIT-KSTORE-v1}</td><td>fichiers au repos</td></tr>
 *   <tr><td>graine SK</td><td>{@code MIT-SIG-v1}</td><td>clé de signature Ed25519 (W)</td></tr>
 *   <tr><td>graine K_log</td><td>{@code MIT-LOG-v1}</td><td>signature Ed25519 des ancrages du journal</td></tr>
 * </table>
 *
 * <p>Sous-clés par objet : {@code HKDF(clé parente, info = étiquette ASCII ‖ 0x00 ‖ identifiant de 16 octets)}.
 * Aucune clé dérivée n'est conservée : chaque appel la recalcule.</p>
 */
final class KeyDerivation {

    static final String K_TOK = "MIT-KTOK-v1";
    static final String K_F = "MIT-KF-v1";
    static final String K_STORE = "MIT-KSTORE-v1";
    static final String SIG_SEED = "MIT-SIG-v1";
    static final String LOG_SEED = "MIT-LOG-v1";
    /** Sous-clé du jeton S, par émission. */
    static final String S_SUBKEY = "MIT-S-v1";
    /** Sous-clé d'un fichier au repos. */
    static final String FILE_SUBKEY = "MIT-FILE-v1";

    static final int KEY_BYTES = 32;
    static final int ID_BYTES = 16;

    private KeyDerivation() {
    }

    /** {@code étiquette ASCII ‖ 0x00 ‖ kid (2 octets gros-boutiste)}. */
    static byte[] kidInfo(String label, int kid) {
        byte[] l = label.getBytes(StandardCharsets.US_ASCII);
        byte[] k = Kid.toBytes(kid);
        byte[] info = new byte[l.length + 1 + Kid.BYTES];
        System.arraycopy(l, 0, info, 0, l.length);
        info[l.length] = 0x00;
        System.arraycopy(k, 0, info, l.length + 1, Kid.BYTES);
        return info;
    }

    /** {@code étiquette ASCII ‖ 0x00 ‖ identifiant (16 octets)}. */
    static byte[] idInfo(String label, byte[] id16) {
        if (id16 == null || id16.length != ID_BYTES) {
            throw new IllegalArgumentException("identifiant de 16 octets attendu");
        }
        byte[] l = label.getBytes(StandardCharsets.US_ASCII);
        byte[] info = new byte[l.length + 1 + ID_BYTES];
        System.arraycopy(l, 0, info, 0, l.length);
        info[l.length] = 0x00;
        System.arraycopy(id16, 0, info, l.length + 1, ID_BYTES);
        return info;
    }

    /** 32 octets dérivés de la KEK du kid pour l'étiquette donnée. */
    static byte[] fromKek(KeyProvider keys, String label, int kid) {
        Kid.check(kid);
        byte[] kek = keys.kek(kid).getEncoded();
        try {
            return Hkdf.derive(kek, null, kidInfo(label, kid), KEY_BYTES);
        } finally {
            java.util.Arrays.fill(kek, (byte) 0);
        }
    }

    static SecretKey kTok(KeyProvider keys, int kid) {
        return aesKey(fromKek(keys, K_TOK, kid));
    }

    static byte[] kF(KeyProvider keys, int kid) {
        return fromKek(keys, K_F, kid);
    }

    static SecretKey kStore(KeyProvider keys, int kid) {
        return aesKey(fromKek(keys, K_STORE, kid));
    }

    static byte[] signingSeed(KeyProvider keys, int kid) {
        return fromKek(keys, SIG_SEED, kid);
    }

    static byte[] logSeed(KeyProvider keys, int kid) {
        return fromKek(keys, LOG_SEED, kid);
    }

    /** Sous-clé AES-256 d'un objet : {@code HKDF(parent, info = étiquette ‖ 0x00 ‖ id16)}. */
    static SecretKey subKey(SecretKey parent, String label, byte[] id16) {
        byte[] p = parent.getEncoded();
        try {
            return aesKey(Hkdf.derive(p, null, idInfo(label, id16), KEY_BYTES));
        } finally {
            java.util.Arrays.fill(p, (byte) 0);
        }
    }

    private static SecretKey aesKey(byte[] bytes) {
        try {
            return new SecretKeySpec(bytes, "AES");
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }
}
