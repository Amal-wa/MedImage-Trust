package tn.medimagetrust.bench;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tn.medimagetrust.dicom.DicomAuditSink;
import tn.medimagetrust.dicom.DicomImage;
import tn.medimagetrust.dicom.DicomReader;
import tn.medimagetrust.dicom.DicomRejectedException;
import tn.medimagetrust.embedding.EmbeddingProfile;
import tn.medimagetrust.fingerprint.FingerprintEmbedder;
import tn.medimagetrust.fingerprint.FingerprintService;
import tn.medimagetrust.fingerprint.TardosCodeGenerator;

/**
 * Section « Corpus réel » du banc (non bloquante) : fichiers de docs/CORPUS.md lus dans {@code $MEDIMAGE_CORPUS_DIR}
 * par {@link DicomReader}, N copies par fichier marquable (N = 5 par défaut, {@code -Dbench.corpus.copies=N}), mêmes
 * scénarios que les fantômes (sans attaque, JPEG Q75, redimensionnement à 75 %), extraction avec le B du registre.
 *
 * <p>La section compte aussi les refus {@code MASK_UNSTABLE} (masque recalculé différent après marquage) : c'est la
 * mesure qui décide s'il faut adapter {@code T_SEARCH} à la profondeur ; le banc ne modifie pas {@code T_SEARCH}.</p>
 */
final class CorpusBench {
    private static final Pattern ROW = Pattern.compile(
            "^\\| ([a-z0-9-]+\\.dcm) \\|[^|]*\\|[^|]*\\|[^|]*\\|[^|]*\\|[^|]*\\| ([0-9a-f]{64}) \\|", Pattern.MULTILINE);
    private static final long TEST_SEED_CORPUS = 0x434F5250L;

    private CorpusBench() {}

    /** Ligne d'un fichier : résultats du banc, ou motif de non-marquabilité. */
    record FileResult(String file, String modality, int bitsStored, AttackBench.PhantomResult result, String reason) {}

    /** Résultats de la section. */
    record Section(int copies, List<FileResult> files, int markingAttempts, int maskUnstable) {}

    /** Section exécutée si {@code MEDIMAGE_CORPUS_DIR} est défini ; vide sinon. */
    static Optional<Section> run(Path manifestPath, int copies, FingerprintService service,
                                 FingerprintEmbedder embedder, TardosCodeGenerator generator, List<String> recipients)
            throws IOException {
        String dirName = System.getenv("MEDIMAGE_CORPUS_DIR");
        if (dirName == null || dirName.isBlank()) {
            return Optional.empty();
        }
        Path dir = Path.of(dirName);
        String manifest = Files.readString(manifestPath, StandardCharsets.UTF_8);
        Matcher m = ROW.matcher(manifest);
        List<FileResult> files = new ArrayList<>();
        int attempts = 0;
        int maskUnstable = 0;
        long index = 0;
        while (m.find()) {
            String file = m.group(1);
            index++;
            Path path = dir.resolve(file);
            if (!Files.isRegularFile(path)) {
                files.add(new FileResult(file, "—", 0, null, "absent du corpus"));
                continue;
            }
            byte[] bytes = Files.readAllBytes(path);
            DicomImage d;
            try {
                d = new DicomReader(DicomAuditSink.NONE).read(new ByteArrayInputStream(bytes), bytes.length);
            } catch (DicomRejectedException e) {
                files.add(new FileResult(file, "—", 0, null, "rejeté à la lecture : " + e.reason()));
                continue;
            }
            if (!d.markability().markable()) {
                files.add(new FileResult(file, d.modality(), d.bitsStored(), null, d.markability().name()));
                continue;
            }
            AttackBench.PhantomResult r = AttackBench.runImages(file, d.bitsStored(), TEST_SEED_CORPUS + (index << 32),
                    seed -> d.image(), copies, service, embedder, generator, recipients, IntUnaryOperator.identity());
            attempts += r.marked + r.refused;
            for (String code : r.refusals) {
                if (code.contains("MASK_UNSTABLE")) maskUnstable++;
            }
            String reason = r.marked == 0 ? "refus de l'embedding : " + String.join(", ", new LinkedHashSet<>(r.refusals))
                    : null;
            files.add(new FileResult(file, d.modality(), d.bitsStored(), r, reason));
        }
        return Optional.of(new Section(copies, files, attempts, maskUnstable));
    }

    static String markdown(Optional<Section> section) {
        StringBuilder sb = new StringBuilder("\n## Corpus réel (non bloquant)\n\n");
        if (section.isEmpty()) {
            return sb.append("Non exécuté : `MEDIMAGE_CORPUS_DIR` absent (voir docs/CORPUS.md).\n").toString();
        }
        Section s = section.get();
        sb.append(String.format(Locale.ROOT, "Fichiers de docs/CORPUS.md ; %d copies par fichier marquable, chacune pour "
                + "un destinataire parmi %d ; extraction avec le B du registre (Δ(B) = 12 × 2^(B − 8)). Cette section "
                + "n'entre pas dans le code de sortie du banc.%n%n", s.copies(), AttackBench.RECIPIENTS));
        sb.append("| Fichier | Modalité | B | Copies marquées (signées) | Scénario | Bits F relus | ")
                .append("Vrai destinataire accusé | Faux positifs | Image non marquée accusée | PSNR minimal |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (FileResult f : s.files()) {
            AttackBench.PhantomResult r = f.result();
            if (r == null || r.marked == 0) continue;
            for (AttackBench.Scenario sc : r.scenarios.values()) {
                if (sc.name.equals("sans attaque")) continue;
                sb.append(String.format(Locale.ROOT,
                        "| %s | %s | %d | %d (%d) | %s | %s | %d / %d | %s | %d / %d | %.2f dB |%n",
                        f.file(), f.modality(), f.bitsStored(), r.marked, r.signedCopies, sc.name,
                        AttackBench.pct(sc.recovery()), sc.trueAccused, sc.analyses,
                        AttackBench.pct(sc.falsePositiveRate()), r.unmarkedAccusations, r.unmarkedAnalyses,
                        r.psnrMin));
            }
        }
        sb.append("\n### Fichiers non marquables\n\n| Fichier | Modalité | B | Motif |\n|---|---|---|---|\n");
        int nonMarkable = 0;
        for (FileResult f : s.files()) {
            if (f.reason() == null) continue;
            nonMarkable++;
            sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s |%n", f.file(), f.modality(),
                    f.bitsStored() == 0 ? "—" : String.valueOf(f.bitsStored()), f.reason()));
        }
        if (nonMarkable == 0) sb.append("| — | | | |\n");

        sb.append(String.format(Locale.ROOT, "%n### MASK_UNSTABLE et T_SEARCH%n%n"
                + "Refus MASK_UNSTABLE : %d sur %d tentatives de marquage (T_SEARCH = %d, inchangé). ",
                s.maskUnstable(), s.markingAttempts(), EmbeddingProfile.T_SEARCH));
        sb.append(s.maskUnstable() == 0
                ? "Aucun masque instable sur ce corpus : pas de mise à l'échelle de T_SEARCH justifiée par ces mesures.\n"
                : "Des masques instables apparaissent : une mise à l'échelle de T_SEARCH avec la profondeur est à "
                        + "étudier (non appliquée).\n");

        sb.append("\n### Critères proposés pour rendre la section bloquante (non appliqués)\n\n")
                .append("1. Chaque fichier du manifeste est lu sans rejet, et chaque fichier non marquable porte un ")
                .append("motif attendu, figé dans docs/CORPUS.md (statut de lecture ou code de refus de l'embedding) ; ")
                .append("un changement de statut fait échouer le banc.\n")
                .append("2. Pour chaque fichier marquable : vrai destinataire accusé dans toutes les copies, sous JPEG Q75 ")
                .append("et sous redimensionnement à 75 %.\n")
                .append("3. Faux positifs ≤ 1 % par scénario, et aucune accusation sur l'image non marquée.\n")
                .append("4. PSNR minimal ≥ 40 dB.\n")
                .append("5. Zéro refus MASK_UNSTABLE.\n")
                .append("6. Au moins une série marquable par modalité du corpus (CT, MR, MG) : critère qui échoue ")
                .append("aujourd'hui si MR et MG restent non marquables, à décider avant de le rendre bloquant.\n");
        return sb.toString();
    }
}
