package tn.medimagetrust.audit;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** Volume d'ancrages en mémoire (tests). */
public class InMemoryAnchorStore implements AnchorStore {

    protected final TreeMap<LocalDate, byte[]> anchors = new TreeMap<>();
    private boolean failWrites;

    /** Simule un volume plein ou indisponible (MT28). */
    public synchronized void setFailWrites(boolean fail) {
        this.failWrites = fail;
    }

    @Override
    public synchronized void write(LocalDate day, byte[] encoded) {
        if (failWrites) {
            throw new AuditException("Volume d'ancrage indisponible");
        }
        if (anchors.containsKey(day)) {
            throw new AuditException("Ancrage déjà présent pour ce jour");
        }
        anchors.put(day, encoded.clone());
    }

    @Override
    public synchronized Optional<byte[]> read(LocalDate day) {
        byte[] raw = anchors.get(day);
        return Optional.ofNullable(raw == null ? null : raw.clone());
    }

    @Override
    public synchronized List<LocalDate> days() {
        return new ArrayList<>(anchors.keySet());
    }
}
