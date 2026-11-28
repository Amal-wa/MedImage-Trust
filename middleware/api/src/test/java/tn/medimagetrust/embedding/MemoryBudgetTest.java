package tn.medimagetrust.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Budget mémoire du dossier v2.4 (§ « Mémoire ») : une session complète sur 36 Mpx (6 000 × 6 000, 16 bits) doit tenir
 * dans ≤ 216 Mo, plafond absolu 250 Mio.
 *
 * <p>Exclu de {@code mvn -B test} par défaut (tag {@code memory}). À lancer avec :</p>
 * <pre>mvn -B -pl medimage-embedding test -Dgroups=memory -DargLine=-Xmx300m</pre>
 *
 * <p>Mesures imprimées : tas vivant après un GC à chaque étape (ce que la session retient) et pic des pools du tas
 * entre deux étapes (borne haute : il inclut les déchets pas encore collectés).</p>
 */
@Tag("memory")
@DisplayName("Mémoire : session complète sur 36 Mpx dans le budget du dossier")
class MemoryBudgetTest {
    private static final long TEST_SEED_MEMORY = 0x4D454D4FL;
    private static final int SIDE = 6_000;
    private static final double MIB = 1024.0 * 1024.0;
    private static final double LIMIT_MIB = 250.0;

    private static long liveHeapAfterGc() {
        for (int i = 0; i < 3; i++) System.gc();
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static void resetPeaks() {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) p.resetPeakUsage();
        }
    }

    private static long peakSum() {
        long sum = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP && p.getPeakUsage() != null) sum += p.getPeakUsage().getUsed();
        }
        return sum;
    }

    private static void print(String step, long live, long peak, long nanos) {
        System.out.println(String.format(Locale.ROOT, "[MEMOIRE] %-34s vivant %7.1f Mio | pic pools %7.1f Mio | %6.0f ms",
                step, live / MIB, peak / MIB, nanos / 1e6));
    }

    @Test
    @DisplayName("6 000 × 6 000, 16 bits, fond bruité : open, F (128 blocs), S, W, finish, sans OutOfMemoryError")
    void fullSessionOn36Megapixels() {
        System.out.println(String.format(Locale.ROOT, "[MEMOIRE] tas maximal de la JVM : %.0f Mio",
                Runtime.getRuntime().maxMemory() / MIB));
        long base = liveHeapAfterGc();

        // Image : fond bruité vers 2 000 (q 400..600), ellipse bruitée vers 30 000 (q 7 000..8 000).
        long t0 = System.nanoTime();
        PixelImage original;
        {
            Random rnd = new Random(TEST_SEED_MEMORY);
            char[] raw = new char[SIDE * SIDE];
            for (int r = 0; r < SIDE; r++) {
                double dr = (r - 3000) / 1800.0;
                for (int c = 0; c < SIDE; c++) {
                    double dc = (c - 3000) / 2200.0;
                    boolean body = dr * dr + dc * dc <= 1.0;
                    raw[r * SIDE + c] = (char) (body ? 30_000 + rnd.nextInt(4001) - 2000 : 2000 + rnd.nextInt(801) - 400);
                }
            }
            original = new PixelImage(SIDE, SIDE, 16, Photometric.MONOCHROME2, raw, false, false);
            raw = null; // la copie de l'appelant n'est plus utile (le module DICOM la libère de même)
        }
        long live = liveHeapAfterGc() - base;
        print("image construite", live, peakSum() - base, System.nanoTime() - t0);
        long maxLive = live;

        resetPeaks();
        t0 = System.nanoTime();
        EmbeddingSession session = EmbeddingSession.open(original);
        long tOpen = System.nanoTime() - t0;
        long peakOpen = peakSum() - base;
        live = liveHeapAfterGc() - base;
        maxLive = Math.max(maxLive, live);
        print("open (détection, partitions)", live, peakOpen, tOpen);

        resetPeaks();
        t0 = System.nanoTime();
        Random rnd = new Random(TEST_SEED_MEMORY + 1);
        Markings.FWrites f = Markings.writeF(session, rnd);
        session.writeS(Markings.randomBytes(EmbeddingProfile.S_BYTES, rnd));
        byte[] roi = session.roiDigest();
        byte[] mask = session.maskDigest();
        byte[] sd = session.sDigest();
        session.writeW(Markings.randomBytes(EmbeddingProfile.W_BYTES, rnd));
        long tWrite = System.nanoTime() - t0;
        long peakWrite = peakSum() - base;
        live = liveHeapAfterGc() - base;
        maxLive = Math.max(maxLive, live);
        print("F, S, condensats, W", live, peakWrite, tWrite);

        resetPeaks();
        t0 = System.nanoTime();
        PixelImage marked = session.finish().image();
        long tFinish = System.nanoTime() - t0;
        long peakFinish = peakSum() - base;
        live = liveHeapAfterGc() - base;
        maxLive = Math.max(maxLive, live);
        print("finish (contrôle final)", live, peakFinish, tFinish);

        long peak = Math.max(peakOpen, Math.max(peakWrite, peakFinish));
        System.out.println(String.format(Locale.ROOT,
                "[MEMOIRE] maximum vivant pendant la session : %.1f Mio ; pic des pools (borne haute) : %.1f Mio",
                maxLive / MIB, peak / MIB));

        // Vérifications fonctionnelles sans copier les 36 Mpx : bits F relus, ROI échantillonnée.
        assertEquals(0, Markings.fBitErrors(session.fChannel(), f));
        assertEquals(32, roi.length);
        assertEquals(32, mask.length);
        assertEquals(32, sd.length);
        RoniProfile profile = session.profile();
        for (int i = 0; i < SIDE * SIDE; i += 9973) {
            if (!profile.inRoni(i)) assertEquals(original.pixel(i), marked.pixel(i));
        }
        assertTrue(maxLive / MIB <= LIMIT_MIB, "mémoire vivante au-delà du plafond de 250 Mio");
    }
}
