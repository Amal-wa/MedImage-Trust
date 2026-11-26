package tn.medimagetrust.audit;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import tn.medimagetrust.crypto.Jcs;
import tn.medimagetrust.crypto.Sha256;

/**
 * Chaînage SHA-256 du journal (SEC09).
 *
 * <pre>
 * canonique(seq, e) = JCS (RFC 8785) du JSON à schéma fixe {actor, corr, digest, resource, result, seq, ts, type}
 * entry_hash        = SHA-256( "MIT-LOG-v1" ‖ 0x00 ‖ prev_hash(32) ‖ canonique )
 * prev_hash(1)      = 32 octets à zéro (genèse)
 * </pre>
 *
 * L'étiquette de domaine MIT-LOG-v1 (§8.2, SEC-K03) sépare ce condensat de tout autre usage de SHA-256.
 * Le numéro de séquence est dans la partie hachée : réordonner ou supprimer une ligne casse la chaîne.
 */
public final class AuditChain {

    /** Étiquette de domaine du journal (§8.2). */
    static final byte[] LABEL = "MIT-LOG-v1".getBytes(StandardCharsets.US_ASCII);
    /** prev_hash de la première entrée. */
    public static final byte[] GENESIS = new byte[32];

    private AuditChain() {
    }

    /** Forme canonique d'une entrée. Les valeurs sont déjà validées (liste blanche) : aucun échappement spécial à gérer. */
    public static byte[] canonical(long seq, AuditEvent e) {
        String json = "{\"actor\":\"" + e.actor() + "\""
                + ",\"corr\":\"" + e.correlationId() + "\""
                + ",\"digest\":\"" + e.digestHex() + "\""
                + ",\"resource\":\"" + e.resource() + "\""
                + ",\"result\":\"" + e.result().name() + "\""
                + ",\"seq\":" + seq
                + ",\"ts\":" + e.timestamp().toEpochMilli()
                + ",\"type\":\"" + e.type().name() + "\"}";
        return Jcs.canonicalize(json);
    }

    /** entry_hash = SHA-256(MIT-LOG-v1 ‖ 0x00 ‖ prev ‖ canonique). */
    public static byte[] hash(byte[] prevHash, byte[] canonical) {
        if (prevHash == null || prevHash.length != 32) {
            throw new IllegalArgumentException("prev_hash de 32 octets requis");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(LABEL.length + 1 + 32 + canonical.length);
        out.writeBytes(LABEL);
        out.write(0x00);
        out.writeBytes(prevHash);
        out.writeBytes(canonical);
        return Sha256.hash(out.toByteArray());
    }

    /** Construit l'entrée de séquence {@code seq} chaînée à {@code prevHash}. */
    public static AuditEntry build(long seq, AuditEvent event, byte[] prevHash) {
        return new AuditEntry(seq, event, prevHash, hash(prevHash, canonical(seq, event)));
    }
}
