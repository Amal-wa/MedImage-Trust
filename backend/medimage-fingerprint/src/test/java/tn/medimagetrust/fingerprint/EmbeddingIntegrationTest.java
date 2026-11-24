package tn.medimagetrust.fingerprint;

import org.junit.jupiter.api.Test;
import tn.medimagetrust.crypto.CryptoService;
import tn.medimagetrust.crypto.KeyProvider;
import tn.medimagetrust.embedding.EmbeddingSession;
import tn.medimagetrust.embedding.FBlockReader;
import tn.medimagetrust.embedding.Partitions;
import tn.medimagetrust.embedding.Photometric;
import tn.medimagetrust.embedding.PixelImage;
import tn.medimagetrust.embedding.RoniDetector;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Intégration avec le vrai module embedding (QIM réelle sur DCT 8 × 8, contrôle final) et le vrai module crypto
 * (K_F dérivée de la KEK) : session complète, puis analyse de la copie marquée.
 */
class EmbeddingIntegrationTest {
    private static final long TEST_SEED_PHANTOM = 0x4D49_5450_4831L;
    private static final int KID = 1;

    /** KEK de test fixe (jamais en production) : rend les blocs secrets, donc le test, reproductibles. */
    private static final byte[] TEST_KEK = new byte[32];

    static {
        for (int i = 0; i < TEST_KEK.length; i++) TEST_KEK[i] = (byte) (0x5A ^ (i * 13));
    }

    private final CryptoService crypto = new CryptoService(new KeyProvider() {
        @Override
        public int activeKid() {
            return KID;
        }

        @Override
        public SecretKey kek(int kid) {
            if (kid != KID) throw new IllegalArgumentException("kid inconnu");
            return new SecretKeySpec(TEST_KEK, "HKDF-SHA256");
        }
    });
    private final byte[] imageId = TestKeys.id(77);
    private final List<String> recipients = TestKeys.recipients(64);

    /**
     * Fantôme synthétique recopié du générateur de test du module embedding : 512 × 512, 12 bits, fond sombre vers
     * 200 (bruit ± 40), ellipse claire vers 2 000 (bruit ± 200) au centre.
     */
    static PixelImage phantom(long seed) {
        Random rnd = new Random(seed);
        int rows = 512, cols = 512;
        int[] px = new int[rows * cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double dr = (r - 256) / 150.0, dc = (c - 256) / 190.0;
                boolean body = dr * dr + dc * dc <= 1.0;
                int v = body ? 2000 + rnd.nextInt(401) - 200 : 200 + rnd.nextInt(81) - 40;
                px[r * cols + c] = Math.max(0, Math.min(4095, v));
            }
        }
        return new PixelImage(rows, cols, 12, Photometric.MONOCHROME2, px, false, false);
    }

    /** Émission complète : F par fingerprint, S et W aléatoires, contrôle final. */
    private PixelImage issue(PixelImage original, FingerprintService svc, String recipient, Random rnd) {
        EmbeddingSession session = EmbeddingSession.open(original);
        byte[] maskDigest = session.maskDigest();
        svc.embed(session.fChannel(), maskDigest, svc.prepare(imageId, TestKeys.id(rnd.nextLong()), recipient));
        byte[] s = new byte[64], w = new byte[208];
        rnd.nextBytes(s);
        rnd.nextBytes(w);
        session.writeS(s);
        session.writeW(w);
        return session.finish(); // lève une exception si un invariant est violé
    }

    @Test
    void fullSessionThenAnalysisAccusesTheRightRecipientOnly() {
        PixelImage original = phantom(TEST_SEED_PHANTOM);
        FingerprintService svc = FingerprintService.forKid(crypto, KID);
        PixelImage copy = issue(original, svc, "recipient-21", new Random(1));

        EmbeddingSession reference = EmbeddingSession.open(original); // partitions et masque de l'émission
        Partitions partitions = Partitions.of(RoniDetector.detect(original));
        var report = svc.analyze(imageId, FBlockReader.of(copy, partitions), reference.maskDigest(), recipients,
                Optional.empty());
        assertEquals(AttributionReport.Verdict.ACCUSED, report.verdict());
        assertEquals(1, report.accused().size());
        assertEquals("recipient-21", report.accused().get(0).recipientId());
        assertEquals(0, report.erasures());
    }

    @Test
    void twoRealCopiesMixedBlockByBlockAccuseNoInnocent() {
        PixelImage original = phantom(TEST_SEED_PHANTOM);
        FingerprintService svc = FingerprintService.forKid(crypto, KID);
        Random rnd = new Random(2);
        PixelImage a = issue(original, svc, "recipient-3", rnd);
        PixelImage b = issue(original, svc, "recipient-40", rnd);

        // Les complices comparent leurs copies et prennent chaque bloc F chez l'un ou l'autre.
        Partitions partitions = Partitions.of(RoniDetector.detect(original));
        int[] pa = a.pixels(), pb = b.pixels();
        int[] pirate = pa.clone();
        for (int block : partitions.fBlocks()) {
            if (!rnd.nextBoolean()) continue;
            int top = (block / partitions.blockCols()) * 8, left = (block % partitions.blockCols()) * 8;
            for (int r = 0; r < 8; r++) {
                System.arraycopy(pb, (top + r) * 512 + left, pirate, (top + r) * 512 + left, 8);
            }
        }
        PixelImage forged = new PixelImage(512, 512, 12, Photometric.MONOCHROME2, pirate, false, false);
        var report = svc.analyze(imageId, FBlockReader.of(forged, partitions),
                EmbeddingSession.open(original).maskDigest(), recipients, Optional.empty());
        assertFalse(report.accused().isEmpty(), "aucun complice détecté");
        assertTrue(report.accused().stream().allMatch(
                x -> x.recipientId().equals("recipient-3") || x.recipientId().equals("recipient-40")));
    }

    @Test
    void bottomCropOfThreeQuartersGivesTooManyErasures() {
        // Sur l'image réelle, perdre 60 % des lignes laisse ~45-50 % des blocs F lisibles (le fond est plus présent en
        // haut et en bas) : trop près du seuil de 50 % pour un test. Les 60 % exacts de blocs illisibles sont testés
        // sur le canal de test (ColluderAnalyzerTest). Ici, 75 % des lignes perdues.
        PixelImage original = phantom(TEST_SEED_PHANTOM);
        FingerprintService svc = FingerprintService.forKid(crypto, KID);
        PixelImage copy = issue(original, svc, "recipient-5", new Random(3));
        int keptRows = 512 / 4;
        int[] px = java.util.Arrays.copyOf(copy.pixels(), keptRows * 512);
        PixelImage cropped = new PixelImage(keptRows, 512, 12, Photometric.MONOCHROME2, px, false, false);
        var report = svc.analyze(imageId, FBlockReader.of(cropped, Partitions.of(RoniDetector.detect(original))),
                EmbeddingSession.open(original).maskDigest(), recipients, Optional.empty());
        assertEquals(AttributionReport.Verdict.TOO_MANY_ERASURES, report.verdict());
        assertTrue(report.accused().isEmpty());
    }
}
