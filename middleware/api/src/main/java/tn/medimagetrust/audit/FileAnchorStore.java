package tn.medimagetrust.audit;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Volume d'ancrages sur disque : un fichier {@code anchor-AAAA-MM-JJ.bin} par jour.
 * Écriture temporaire + fsync + renommage atomique : un fichier est soit absent, soit complet.
 * Création seule : un ancrage existant n'est jamais écrasé.
 */
public final class FileAnchorStore implements AnchorStore {

    private static final Pattern NAME = Pattern.compile("anchor-(\\d{4}-\\d{2}-\\d{2})\\.bin");

    private final Path dir;

    public FileAnchorStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new AuditException("Volume d'ancrage inaccessible", e);
        }
    }

    private Path fileFor(LocalDate day) {
        return dir.resolve("anchor-" + day + ".bin");
    }

    @Override
    public void write(LocalDate day, byte[] encoded) {
        Path target = fileFor(day);
        Path tmp = dir.resolve("anchor-" + day + ".tmp");
        try {
            if (Files.exists(target)) {
                throw new AuditException("Ancrage déjà présent pour ce jour");
            }
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ch.write(ByteBuffer.wrap(encoded));
                ch.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new AuditException("Écriture de l'ancrage impossible", e);
        }
    }

    @Override
    public Optional<byte[]> read(LocalDate day) {
        try {
            Path p = fileFor(day);
            return Files.exists(p) ? Optional.of(Files.readAllBytes(p)) : Optional.empty();
        } catch (IOException e) {
            throw new AuditException("Lecture de l'ancrage impossible", e);
        }
    }

    @Override
    public List<LocalDate> days() {
        List<LocalDate> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(p -> {
                Matcher m = NAME.matcher(p.getFileName().toString());
                if (m.matches()) {
                    out.add(LocalDate.parse(m.group(1)));
                }
            });
        } catch (IOException e) {
            throw new AuditException("Lecture du volume d'ancrage impossible", e);
        }
        Collections.sort(out);
        return out;
    }
}
