package tn.medimagetrust.bench;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import tn.medimagetrust.embedding.EmbeddingException;
import tn.medimagetrust.embedding.EmbeddingSession;
import tn.medimagetrust.embedding.FBlockReader;
import tn.medimagetrust.embedding.Partitions;
import tn.medimagetrust.embedding.Photometric;
import tn.medimagetrust.embedding.Phantoms;
import tn.medimagetrust.embedding.PixelImage;
import tn.medimagetrust.fingerprint.AttributionReport;
import tn.medimagetrust.fingerprint.ExtractedWord;
import tn.medimagetrust.fingerprint.FingerprintEmbedder;
import tn.medimagetrust.fingerprint.FingerprintService;
import tn.medimagetrust.fingerprint.HmacSha256Prf;
import tn.medimagetrust.fingerprint.Prf;
import tn.medimagetrust.fingerprint.TardosCodeGenerator;

/**
 * Banc d'attaques (consigne 4.6) sur la vraie chaîne : {@code EmbeddingSession} (RONI, F par QIM, S, W, contrôle
 * final) et {@code FingerprintService} (code de Tardos, analyse). Les fantômes viennent de
 * {@link Phantoms} (tests d'embedding), réutilisés sans copie.
 *
 * <p>Pour chaque fantôme (8 bits et 12 bits), N copies sont marquées (N = 20 par défaut, propriété
 * {@code -Dbench.copies=N}), chacune pour un destinataire parmi 64. Scénarios : sans attaque ; JPEG qualité 0,75
 * (passage en 8 bits par une fenêtre fixe couvrant toute la plage stockée, compression, décompression, retour à
 * l'échelle d'origine) ; redimensionnement à 75 % puis retour à la taille d'origine, en bilinéaire (l'analyste connaît
 * les dimensions de l'émission). Tout passe par ImageIO et Java 2D, sans dépendance externe.</p>
 *
 * <p>Seuils de l'enseignant : récupération ≥ 95 %, faux positifs ≤ 1 %, PSNR ≥ 40 dB. Le programme sort avec le code 1
 * si le PSNR minimal est sous 40 dB ou si les faux positifs dépassent 1 % ; une récupération sous 95 % produit un
 * avertissement GitHub ({@code ::warning::}) sans échec.</p>
 *
 * <p>Lancement : {@code mvn -B -f middleware/pom.xml -pl api test-compile exec:java@attack-bench}
 * (après {@code -pl core -am install}) ; rapport dans {@code middleware/api/target/bench/report.md} et
 * {@code report.json}. Clé K_F de test fixe, jamais utilisée en production.</p>
 */
public final class AttackBench {

    static final long TEST_SEED_BENCH = 0x42454E43L;
    static final int RECIPIENTS = 64;
    static final double MIN_PSNR_DB = 40.0;
    static final double MAX_FALSE_POSITIVE_RATE = 0.01;
    static final double MIN_RECOVERY = 0.95;
    static final float JPEG_QUALITY = 0.75f;
    static final double RESIZE_FACTOR = 0.75;

    private AttackBench() {}

    /** Résultats d'un scénario (sans attaque, JPEG, redimensionnement) sur un fantôme. */
    static final class Scenario {
        final String name;
        long bitsOk;
        long bitsTotal;
        int analyses;
        int trueAccused;
        int innocentAccusedCopies;

        Scenario(String name) {
            this.name = name;
        }

        double recovery() { return bitsTotal == 0 ? 0 : (double) bitsOk / bitsTotal; }
        double attribution() { return analyses == 0 ? 0 : (double) trueAccused / analyses; }
        double falsePositiveRate() { return analyses == 0 ? 0 : (double) innocentAccusedCopies / analyses; }
    }

    /** Résultats d'un fantôme. */
    static final class PhantomResult {
        final String name;
        int marked;
        int refused;
        final List<String> refusals = new ArrayList<>();
        double psnrSum;
        double psnrMin = Double.POSITIVE_INFINITY;
        int unmarkedAnalyses;
        int unmarkedAccusations;
        final Map<String, Scenario> scenarios = new LinkedHashMap<>();

        PhantomResult(String name) {
            this.name = name;
            for (String s : List.of("sans attaque", "JPEG Q75", "redimensionnement 75 %")) {
                scenarios.put(s, new Scenario(s));
            }
        }

        double psnrMean() { return marked == 0 ? 0 : psnrSum / marked; }
        double unmarkedFalsePositiveRate() {
            return unmarkedAnalyses == 0 ? 0 : (double) unmarkedAccusations / unmarkedAnalyses;
        }
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "target/bench");
        int copies = Integer.getInteger("bench.copies", 20);
        if (copies < 1) throw new IllegalArgumentException("bench.copies doit être >= 1");

        byte[] key = new byte[32];
        new Random(TEST_SEED_BENCH).nextBytes(key); // clé K_F de test, déterministe
        Prf prf = new HmacSha256Prf(key);
        FingerprintService service = new FingerprintService(prf);
        FingerprintEmbedder embedder = new FingerprintEmbedder(prf);
        TardosCodeGenerator generator = new TardosCodeGenerator(prf);
        List<String> recipients = new ArrayList<>();
        for (int i = 0; i < RECIPIENTS; i++) recipients.add("recipient-" + i);

        List<PhantomResult> results = new ArrayList<>();
        results.add(run("12 bits (512 × 512)", Phantoms.normalSpec(), copies, service, embedder, generator, recipients));
        results.add(run("8 bits (512 × 512)",
                new Phantoms.Spec(512, 512, 8, Photometric.MONOCHROME2, 30, 3, 153, 12, 256, 256, 150, 190),
                copies, service, embedder, generator, recipients));

        Files.createDirectories(out);
        String md = markdown(results, copies);
        Files.writeString(out.resolve("report.md"), md, StandardCharsets.UTF_8);
        Files.writeString(out.resolve("report.json"), json(results, copies), StandardCharsets.UTF_8);
        System.out.println(md);

        boolean failed = false;
        for (PhantomResult r : results) {
            if (r.marked == 0) {
                System.out.println("::error::" + r.name + " : aucune copie marquée");
                failed = true;
                continue;
            }
            if (r.psnrMin < MIN_PSNR_DB) {
                System.out.printf(Locale.ROOT, "::error::%s : PSNR minimal %.2f dB < 40 dB%n", r.name, r.psnrMin);
                failed = true;
            }
            if (r.unmarkedFalsePositiveRate() > MAX_FALSE_POSITIVE_RATE) {
                System.out.printf(Locale.ROOT, "::error::%s : faux positifs sur images non marquées %.2f %% > 1 %%%n",
                        r.name, 100 * r.unmarkedFalsePositiveRate());
                failed = true;
            }
            for (Scenario s : r.scenarios.values()) {
                if (s.falsePositiveRate() > MAX_FALSE_POSITIVE_RATE) {
                    System.out.printf(Locale.ROOT, "::error::%s, %s : faux positifs %.2f %% > 1 %%%n",
                            r.name, s.name, 100 * s.falsePositiveRate());
                    failed = true;
                }
                if (s.recovery() < MIN_RECOVERY) {
                    System.out.printf(Locale.ROOT,
                            "::warning::%s, %s : récupération des bits F %.2f %% < 95 %% (question posée à l'enseignant)%n",
                            r.name, s.name, 100 * s.recovery());
                }
            }
        }
        System.out.println("Rapport : " + out.resolve("report.md").toAbsolutePath());
        if (failed) System.exit(1);
    }

    static PhantomResult run(String name, Phantoms.Spec spec, int copies, FingerprintService service,
                             FingerprintEmbedder embedder, TardosCodeGenerator generator, List<String> recipients) {
        PhantomResult result = new PhantomResult(name);
        for (int i = 0; i < copies; i++) {
            long seed = TEST_SEED_BENCH + 1000L * i + spec.bitsStored();
            Random rnd = new Random(seed);
            PixelImage original = Phantoms.build(spec, seed).image();
            byte[] imageId = id(seed);
            byte[] emissionId = id(seed ^ 0x5A5A5A5AL);
            String recipient = recipients.get(i % recipients.size());

            EmbeddingSession session;
            PixelImage marked;
            try {
                session = EmbeddingSession.open(original);
                service.embed(session.fChannel(), session.maskDigest(),
                        service.prepare(imageId, emissionId, recipient));
                byte[] s = new byte[64];
                byte[] w = new byte[208];
                rnd.nextBytes(s);
                rnd.nextBytes(w);
                session.writeS(s);
                session.writeW(w);
                marked = session.finish();
            } catch (EmbeddingException e) {
                result.refused++;
                result.refusals.add(e.auditCode());
                continue;
            }
            result.marked++;
            double psnr = psnr(original.pixels(), marked.pixels(), original.maxValue());
            result.psnrSum += psnr;
            result.psnrMin = Math.min(result.psnrMin, psnr);

            Partitions partitions = session.partitions();
            byte[] maskDigest = session.maskDigest();
            byte[] expected = generator.symbols(imageId, recipient);

            Map<String, PixelImage> attacked = new LinkedHashMap<>();
            attacked.put("sans attaque", marked);
            attacked.put("JPEG Q75", withPixels(marked, jpeg(marked)));
            attacked.put("redimensionnement 75 %", withPixels(marked, resize(marked)));
            for (Map.Entry<String, PixelImage> e : attacked.entrySet()) {
                Scenario sc = result.scenarios.get(e.getKey());
                FBlockReader reader = FBlockReader.of(e.getValue(), partitions);
                ExtractedWord word = embedder.extract(reader, maskDigest);
                byte[] got = word.symbols();
                for (int j = 0; j < expected.length; j++) {
                    if (got[j] == expected[j]) sc.bitsOk++;
                }
                sc.bitsTotal += expected.length;
                AttributionReport report = service.analyze(imageId, reader, maskDigest, recipients, Optional.empty());
                sc.analyses++;
                boolean trueAccused = false;
                boolean innocent = false;
                for (AttributionReport.Accusation a : report.accused()) {
                    if (a.recipientId().equals(recipient)) trueAccused = true; else innocent = true;
                }
                if (trueAccused) sc.trueAccused++;
                if (innocent) sc.innocentAccusedCopies++;
            }

            // Copie d'innocents : l'image non marquée n'a été remise à personne ; toute accusation est un faux positif.
            AttributionReport clean = service.analyze(imageId, FBlockReader.of(original, partitions), maskDigest,
                    recipients, Optional.empty());
            result.unmarkedAnalyses++;
            if (!clean.accused().isEmpty()) result.unmarkedAccusations++;
        }
        return result;
    }

    // --- Attaques ------------------------------------------------------------------------------------------------

    /** JPEG qualité 0,75 : fenêtre fixe [0, maxValue] → 8 bits, compression, décompression, retour à l'échelle. */
    static char[] jpeg(PixelImage img) {
        int rows = img.rows();
        int cols = img.cols();
        int max = img.maxValue();
        char[] px = img.pixels();
        BufferedImage gray = new BufferedImage(cols, rows, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = gray.getRaster();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                raster.setSample(c, r, 0, (int) Math.round(px[r * cols + c] * 255.0 / max));
            }
        }
        try {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(gray, null, null), param);
            } finally {
                writer.dispose();
            }
            BufferedImage back = ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
            char[] out = new char[rows * cols];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int g = back.getRaster().getSample(c, r, 0);
                    out[r * cols + c] = (char) Math.min(max, Math.round(g * (double) max / 255.0));
                }
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("JPEG indisponible", e);
        }
    }

    /** Redimensionnement à 75 % puis retour à la taille d'origine, en bilinéaire, sur toute la profondeur. */
    static char[] resize(PixelImage img) {
        int rows = img.rows();
        int cols = img.cols();
        char[] px = img.pixels();
        BufferedImage src = new BufferedImage(cols, rows, BufferedImage.TYPE_USHORT_GRAY);
        WritableRaster sr = src.getRaster();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) sr.setSample(c, r, 0, px[r * cols + c]);
        }
        int smallCols = (int) Math.round(cols * RESIZE_FACTOR);
        int smallRows = (int) Math.round(rows * RESIZE_FACTOR);
        WritableRaster small = sr.createCompatibleWritableRaster(smallCols, smallRows);
        new AffineTransformOp(AffineTransform.getScaleInstance((double) smallCols / cols, (double) smallRows / rows),
                AffineTransformOp.TYPE_BILINEAR).filter(sr, small);
        WritableRaster back = sr.createCompatibleWritableRaster(cols, rows);
        new AffineTransformOp(AffineTransform.getScaleInstance((double) cols / smallCols, (double) rows / smallRows),
                AffineTransformOp.TYPE_BILINEAR).filter(small, back);
        int max = img.maxValue();
        char[] out = new char[rows * cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) out[r * cols + c] = (char) Math.min(max, back.getSample(c, r, 0));
        }
        return out;
    }

    // --- Outils --------------------------------------------------------------------------------------------------

    static PixelImage withPixels(PixelImage like, char[] px) {
        return new PixelImage(like.rows(), like.cols(), like.bitsStored(), like.photometric(), px,
                like.burnedInAnnotation(), like.overlayPresent());
    }

    static double psnr(char[] a, char[] b, int max) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return sum == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10((double) max * max / (sum / a.length));
    }

    static byte[] id(long seed) {
        return ByteBuffer.allocate(16).putLong(seed).putLong(~seed).array();
    }

    // --- Rapports ------------------------------------------------------------------------------------------------

    static String pct(double v) {
        return String.format(Locale.ROOT, "%.2f %%", 100 * v);
    }

    static String markdown(List<PhantomResult> results, int copies) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Banc d'attaques (consigne 4.6)\n\n");
        sb.append(String.format(Locale.ROOT, "Copies demandées par fantôme : %d ; destinataires candidats : %d ; "
                + "JPEG qualité %.2f ; redimensionnement %.0f %% puis retour, bilinéaire.%n%n",
                copies, RECIPIENTS, JPEG_QUALITY, RESIZE_FACTOR * 100));
        sb.append("Seuils de l'enseignant : récupération ≥ 95 % (avertissement), faux positifs ≤ 1 % (bloquant), ")
                .append("PSNR ≥ 40 dB (bloquant).\n\n");
        sb.append("## Marquage\n\n| Fantôme | Copies marquées | Refusées | Motifs de refus | PSNR moyen | PSNR minimal |\n")
                .append("|---|---|---|---|---|---|\n");
        for (PhantomResult r : results) {
            sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %s | %.2f dB | %.2f dB |%n", r.name, r.marked,
                    r.refused, r.refusals.isEmpty() ? "—" : String.join(", ", r.refusals), r.psnrMean(),
                    r.marked == 0 ? 0.0 : r.psnrMin));
        }
        sb.append("\n## Attaques\n\n| Fantôme | Scénario | Bits F relus | Vrai destinataire accusé | ")
                .append("Faux positifs (innocent accusé) | Statut |\n|---|---|---|---|---|---|\n");
        for (PhantomResult r : results) {
            for (Scenario s : r.scenarios.values()) {
                String status = s.falsePositiveRate() > MAX_FALSE_POSITIVE_RATE ? "ÉCHEC (faux positifs)"
                        : s.recovery() < MIN_RECOVERY ? "avertissement (récupération < 95 %)" : "OK";
                sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %d / %d (%s) | %s | %s |%n", r.name, s.name,
                        pct(s.recovery()), s.trueAccused, s.analyses, pct(s.attribution()),
                        pct(s.falsePositiveRate()), status));
            }
        }
        sb.append("\n## Copies d'innocents (images non marquées)\n\n| Fantôme | Analyses | Accusations | Taux |\n")
                .append("|---|---|---|---|\n");
        for (PhantomResult r : results) {
            sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %s |%n", r.name, r.unmarkedAnalyses,
                    r.unmarkedAccusations, pct(r.unmarkedFalsePositiveRate())));
        }
        sb.append("\n## Question posée à l'enseignant\n\n")
                .append("Le seuil « récupération ≥ 95 % » porte-t-il sur les **bits** F relus ou sur l'**attribution** ")
                .append("(le vrai destinataire accusé) ? Et le JPEG Q75 s'entend-il sur une image **8 bits** ou ")
                .append("**12 bits** ? Le banc rapporte les deux mesures et les deux profondeurs ; en attendant la ")
                .append("réponse, une récupération des bits sous 95 % n'est qu'un avertissement.\n")
                .append("\n## Cause de l'échec en 12 bits\n\n")
                .append("**Pas QIM fixe Δ = 12 quelle que soit la profondeur** (`EmbeddingProfile.QIM_DELTA`, en valeurs ")
                .append("stockées). En 12 bits (4096 niveaux), ce pas est trop petit pour la dynamique de l'image : le ")
                .append("passage en 8 bits imposé par JPEG le ramène à moins d'un niveau de gris, et l'interpolation du ")
                .append("redimensionnement déplace les coefficients de bien plus que Δ/4. F est effacé (≈ 50 % des bits, ")
                .append("le hasard), sans fausse accusation.\n\n")
                .append("**Correction prévue au prompt 09 : Δ proportionnel à la profondeur.** D'ici là, une ")
                .append("récupération sous 95 % reste un avertissement.\n");
        return sb.toString();
    }

    static String json(List<PhantomResult> results, int copies) {
        StringBuilder sb = new StringBuilder("{\"copies\":").append(copies).append(",\"phantoms\":[");
        for (int i = 0; i < results.size(); i++) {
            PhantomResult r = results.get(i);
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT,
                    "{\"name\":\"%s\",\"marked\":%d,\"refused\":%d,\"psnrMean\":%.4f,\"psnrMin\":%.4f,"
                            + "\"unmarkedFalsePositiveRate\":%.6f,\"scenarios\":[",
                    r.name, r.marked, r.refused, r.psnrMean(), r.marked == 0 ? 0.0 : r.psnrMin,
                    r.unmarkedFalsePositiveRate()));
            int k = 0;
            for (Scenario s : r.scenarios.values()) {
                if (k++ > 0) sb.append(',');
                sb.append(String.format(Locale.ROOT,
                        "{\"name\":\"%s\",\"bitRecovery\":%.6f,\"attribution\":%.6f,\"falsePositiveRate\":%.6f}",
                        s.name, s.recovery(), s.attribution(), s.falsePositiveRate()));
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }
}
