package tn.medimagetrust.audit;

import java.util.Objects;

/** Ligne du journal : événement + numéro de séquence + chaînage. Immuable (copies défensives). */
public record AuditEntry(long seq, AuditEvent event, byte[] prevHash, byte[] entryHash) {

    public AuditEntry {
        Objects.requireNonNull(event, "event");
        if (seq < 1) {
            throw new IllegalArgumentException("seq >= 1 requis");
        }
        if (prevHash == null || prevHash.length != 32 || entryHash == null || entryHash.length != 32) {
            throw new IllegalArgumentException("Condensats de 32 octets requis");
        }
        prevHash = prevHash.clone();
        entryHash = entryHash.clone();
    }

    @Override
    public byte[] prevHash() {
        return prevHash.clone();
    }

    @Override
    public byte[] entryHash() {
        return entryHash.clone();
    }
}
