package tn.medimagetrust.audit;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Stockage en mémoire pour le développement et les tests. Thread-safe. */
public class InMemoryAuditStore implements AuditStore, AuditPurgeStore {

    protected final TreeMap<Long, AuditEntry> entries = new TreeMap<>();

    @Override
    public synchronized void append(AuditEntry entry) {
        Map.Entry<Long, AuditEntry> last = entries.lastEntry();
        if (last == null) {
            if (entry.seq() != 1 || !MessageDigest.isEqual(entry.prevHash(), AuditChain.GENESIS)) {
                throw new AuditConflictException();
            }
        } else {
            AuditEntry head = last.getValue();
            if (entry.seq() != head.seq() + 1
                    || !MessageDigest.isEqual(entry.prevHash(), head.entryHash())) {
                throw new AuditConflictException();
            }
        }
        entries.put(entry.seq(), entry);
    }

    @Override
    public synchronized Optional<AuditEntry> head() {
        return Optional.ofNullable(entries.lastEntry()).map(Map.Entry::getValue);
    }

    @Override
    public synchronized Optional<AuditEntry> first() {
        return Optional.ofNullable(entries.firstEntry()).map(Map.Entry::getValue);
    }

    @Override
    public synchronized Optional<AuditEntry> get(long seq) {
        return Optional.ofNullable(entries.get(seq));
    }

    @Override
    public synchronized List<AuditEntry> range(long fromSeq, long toSeq) {
        if (toSeq < fromSeq) {
            return List.of();
        }
        return new ArrayList<>(entries.subMap(fromSeq, true, toSeq, true).values());
    }

    @Override
    public synchronized void purgeThrough(long seq) {
        entries.headMap(seq, true).clear();
    }
}
