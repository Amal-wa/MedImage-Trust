package tn.medimagetrust.audit;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Arrays;
import tn.medimagetrust.crypto.Ed25519;

/**
 * Ancrage quotidien : signature Ed25519 (K_log) de la tête de chaîne (SEC09).
 *
 * <p>Format binaire fixe, big-endian, version 1 (SEC-K03), 122 octets :
 * <pre>
 *  0  10  étiquette « MIT-LOG-v1 »
 * 10   1  0x00
 * 11   1  version = 0x01
 * 12   2  kid de K_log
 * 14   4  jour UTC (jours depuis 1970-01-01)
 * 18   8  lastSeq : séquence de l'entrée ancrée
 * 26  32  headHash : entry_hash de cette entrée
 * 58  64  signature Ed25519 sur les octets 0 à 57
 * </pre>
 * Toute longueur, version ou étiquette inattendue est rejetée.
 */
public record Anchor(LocalDate day, int kid, long lastSeq, byte[] headHash, byte[] signature) {

    static final int SIGNED_LENGTH = 58;
    static final int ENCODED_LENGTH = 122;
    private static final byte VERSION = 0x01;

    public Anchor {
        if (day == null || kid < 0 || kid > 0xFFFF || lastSeq < 1
                || headHash == null || headHash.length != 32
                || signature == null || signature.length != 64) {
            throw new IllegalArgumentException("Ancrage invalide");
        }
        headHash = headHash.clone();
        signature = signature.clone();
    }

    @Override
    public byte[] headHash() {
        return headHash.clone();
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }

    /** Signe la tête {@code (lastSeq, headHash)} avec la clé active de {@code keys}. */
    public static Anchor sign(LocalDate day, long lastSeq, byte[] headHash, LogKeyProvider keys) {
        int kid = keys.activeKid();
        byte[] signed = signedBytes(day, kid, lastSeq, headHash);
        return new Anchor(day, kid, lastSeq, headHash, keys.sign(signed));
    }

    private static byte[] signedBytes(LocalDate day, int kid, long lastSeq, byte[] headHash) {
        ByteBuffer buf = ByteBuffer.allocate(SIGNED_LENGTH);
        buf.put(AuditChain.LABEL).put((byte) 0x00).put(VERSION)
                .putShort((short) kid).putInt((int) day.toEpochDay()).putLong(lastSeq).put(headHash);
        return buf.array();
    }

    byte[] signedBytes() {
        return signedBytes(day, kid, lastSeq, headHash);
    }

    public boolean verifySignature(PublicKey publicKey) {
        return Ed25519.verify(publicKey, signedBytes(), signature);
    }

    /** Sérialisation de 122 octets, identique sur les deux volumes. */
    public byte[] encode() {
        return ByteBuffer.allocate(ENCODED_LENGTH).put(signedBytes()).put(signature).array();
    }

    /** Décodage strict ; {@link IllegalArgumentException} générique si le format n'est pas exactement celui attendu. */
    public static Anchor decode(byte[] raw) {
        if (raw == null || raw.length != ENCODED_LENGTH) {
            throw new IllegalArgumentException("Ancrage invalide");
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        byte[] label = new byte[AuditChain.LABEL.length];
        buf.get(label);
        if (!Arrays.equals(label, AuditChain.LABEL) || buf.get() != 0x00 || buf.get() != VERSION) {
            throw new IllegalArgumentException("Ancrage invalide");
        }
        int kid = Short.toUnsignedInt(buf.getShort());
        int epochDay = buf.getInt();
        long lastSeq = buf.getLong();
        byte[] head = new byte[32];
        buf.get(head);
        byte[] sig = new byte[64];
        buf.get(sig);
        try {
            return new Anchor(LocalDate.ofEpochDay(epochDay), kid, lastSeq, head, sig);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Ancrage invalide");
        }
    }

    @Override
    public String toString() {
        return "Anchor[" + day + ", kid=" + kid + ", lastSeq=" + lastSeq + "]";
    }

    /** Étiquette ASCII exposée pour les tests de format. */
    static String label() {
        return new String(AuditChain.LABEL, StandardCharsets.US_ASCII);
    }
}
