package tn.medimagetrust.dicom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tn.medimagetrust.embedding.EmbeddingException;
import tn.medimagetrust.embedding.PixelImage;
import tn.medimagetrust.embedding.RoniProfile;

/**
 * Corpus d'images réelles (docs/CORPUS.md), lu dans {@code $MEDIMAGE_CORPUS_DIR} : exclu par défaut
 * ({@code -Dgroups=corpus} pour le lancer), ignoré si la variable est absente.
 *
 * <p>Pour chaque fichier du manifeste : SHA-256 vérifiée, lecture, impression des attributs utiles ; s'il est
 * marquable, marquage, écriture et relecture de la copie : ROI identique, PSNR ≥ 40 dB, nouveau SOPInstanceUID,
 * préambule nul, aucun élément privé, attributs d'interprétation identiques (ou les attributs signés). Un fichier non
 * marquable doit porter un motif (statut de lecture ou code de refus de l'embedding).</p>
 */
@org.junit.jupiter.api.Tag("corpus")
class DicomCorpusTest {
    private static final Pattern ROW = Pattern.compile(
            "^\\| ([a-z0-9-]+\\.dcm) \\|[^|]*\\|[^|]*\\|[^|]*\\|[^|]*\\|[^|]*\\| ([0-9a-f]{64}) \\|", Pattern.MULTILINE);
    private static final List<String> REPORT = new ArrayList<>();

    record Entry(String file, String sha256) {}

    static Path corpusDir() {
        String dir = System.getenv("MEDIMAGE_CORPUS_DIR");
        Assumptions.assumeTrue(dir != null && !dir.isBlank(), "MEDIMAGE_CORPUS_DIR absent : corpus ignoré");
        return Path.of(dir);
    }

    static List<Entry> manifest() throws IOException {
        Path md = Path.of("..", "..", "docs", "CORPUS.md");
        String text = Files.readString(md, StandardCharsets.UTF_8);
        List<Entry> entries = new ArrayList<>();
        Matcher m = ROW.matcher(text);
        while (m.find()) entries.add(new Entry(m.group(1), m.group(2)));
        assertTrue(entries.size() >= 26, "manifeste incomplet");
        return entries;
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestFactory
    Stream<DynamicTest> corpus() throws IOException {
        Path dir = corpusDir();
        REPORT.add("| Fichier | Syntaxe | Modalité | BitsAllocated | B | PixelRepresentation | Photométrie | "
                + "RescaleIntercept | PixelPaddingValue | InstanceNumber | Statut |");
        REPORT.add("|---|---|---|---|---|---|---|---|---|---|---|");
        return manifest().stream().map(e -> DynamicTest.dynamicTest(e.file(), () -> check(dir, e)));
    }

    private static void check(Path dir, Entry e) throws Exception {
        Path path = dir.resolve(e.file());
        assertTrue(Files.isRegularFile(path), "fichier absent du corpus : lancer scripts/corpus/fetch-corpus.sh");
        byte[] bytes = Files.readAllBytes(path);
        assertEquals(e.sha256(), sha256(bytes), "SHA-256 différente du manifeste");

        DicomImage d;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            d = new DicomReader(DicomAuditSink.NONE).read(in, bytes.length);
        } catch (DicomRejectedException r) {
            row(e.file(), "rejeté : " + r.reason(), null);
            fail("fichier du corpus rejeté : " + r.reason());
            return;
        }

        String status;
        if (!d.markability().markable()) {
            status = "non marquable : " + d.markability(); // écriture refusée : voir DicomWriterTest
        } else {
            DicomMarking.Marked m;
            try {
                m = DicomMarking.mark(d.image(), sha256(bytes).hashCode());
            } catch (EmbeddingException refused) {
                // Refus de l'embedding (profil RONI, contrôle final) : statut justifié par le code de refus.
                row(e.file(), "non marquable : " + refused.auditCode(), d);
                return;
            }
            status = checkCopy(d, m);
        }
        row(e.file(), status, d);
    }

    /** Écrit et relit la copie ; renvoie le statut imprimé. */
    private static String checkCopy(DicomImage d, DicomMarking.Marked m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String uid = new DicomWriter().writeCopy(d, m.result(), out);
        byte[] copyBytes = out.toByteArray();
        DicomImage c = new DicomReader(DicomAuditSink.NONE).read(new ByteArrayInputStream(copyBytes), copyBytes.length);
        boolean signed = m.result().signedOutput();

        for (int i = 0; i < 128; i++) assertEquals(0, copyBytes[i], "préambule non nul");
        assertNotEquals(d.sopInstanceUid(), c.sopInstanceUid());
        assertEquals(uid, c.sopInstanceUid());
        assertTrue(uid.startsWith("2.25."));
        for (int tag : c.dataset().tags()) assertEquals(0, (tag >>> 16) & 1, "élément privé dans la copie");

        Attributes expected = DicomWriter.sanitizedCopy(d.dataset());
        expected.setString(Tag.SOPInstanceUID, VR.UI, uid);
        if (signed) {
            expected.setInt(Tag.PixelRepresentation, VR.US, 1);
            expected.setInt(Tag.BitsAllocated, VR.US, 16);
            expected.setInt(Tag.BitsStored, VR.US, 16);
            expected.setInt(Tag.HighBit, VR.US, 15);
            d.pixelPaddingValue().ifPresent(v -> expected.setInt(Tag.PixelPaddingValue, VR.SS, v));
        }
        assertEquals(expected, c.dataset(), "attributs d'interprétation modifiés");

        PixelImage verify = signed ? c.signedCopyImage(d.bitsStored(), d.pixelRepresentation()) : c.image();
        PixelImage original = d.image();
        RoniProfile profile = m.session().profile();
        for (int i = 0; i < original.pixelCount(); i++) {
            if (!profile.inRoni(i) && original.pixel(i) != verify.pixel(i)) fail("pixel de ROI modifié");
            if (verify.pixel(i) != m.result().image().pixel(i)) fail("copie relue différente de la copie émise");
        }
        double psnr = DicomMarking.psnr(original, m.result().image());
        assertTrue(psnr >= 40.0, "PSNR < 40 dB");
        return String.format(Locale.ROOT, "marquable, copie %s, PSNR %.1f dB, RONI %d blocs",
                signed ? "signée" : "non signée", psnr, profile.roniBlockCount());
    }

    private static void row(String file, String status, DicomImage d) {
        String line;
        if (d == null) {
            line = "| " + file + " | | | | | | | | | | " + status + " |";
        } else {
            line = "| " + file + " | " + d.transferSyntaxUid() + " | " + d.modality() + " | " + d.bitsAllocated()
                    + " | " + d.bitsStored() + " | " + d.pixelRepresentation() + " | " + d.photometric() + " | "
                    + d.rescaleIntercept().orElse("—") + " | "
                    + (d.pixelPaddingValue().isPresent() ? String.valueOf(d.pixelPaddingValue().getAsInt()) : "—")
                    + " | " + d.dataset().getString(Tag.InstanceNumber, "—") + " | " + status + " |";
        }
        synchronized (REPORT) {
            REPORT.add(line);
        }
        System.out.println(line);
    }

    @AfterAll
    static void writeReport() throws IOException {
        if (REPORT.size() <= 2) return;
        Path out = Path.of("target", "corpus");
        Files.createDirectories(out);
        Files.writeString(out.resolve("corpus-report.md"), String.join("\n", REPORT) + "\n", StandardCharsets.UTF_8);
    }
}
