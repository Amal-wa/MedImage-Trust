package tn.medimagetrust.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Empreinte SHA-256 d'un contenu (document avant ou après marquage). */
public final class Sha256 {

    private Sha256() {
    }

    public static byte[] hash(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    public static String hashHex(byte[] data) {
        return HexFormat.of().formatHex(hash(data));
    }
}
