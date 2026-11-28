package tn.medimagetrust.dicom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.embedding.Photometric;
import tn.medimagetrust.embedding.PixelImage;

/**
 * Fuzzing à graine fixe : 200 mutations d'un fichier valide (octets inversés, troncature, insertion, longueurs
 * écrasées, blocs dupliqués, taille annoncée fausse). Attendu : 0 plantage non maîtrisé (toute issue est une image
 * lue ou un {@link DicomRejectedException} à message fixe), 0 dépassement du délai de 30 s.
 */
class DicomFuzzTest {
    private static final long TEST_SEED_FUZZ = 0x46555A5AL;
    private static final int MUTATIONS = 200;

    private static byte[] base() {
        int[] v = new int[64 * 64];
        for (int i = 0; i < v.length; i++) v[i] = (i * 31) % 4096;
        PixelImage img = PixelImage.fromInts(64, 64, 12, Photometric.MONOCHROME2, v, false, false);
        DicomFixture f = DicomFixture.of(img, 16, 0);
        f.str(0x00080080, "LO", "HOPITAL DE TEST");
        f.str(0x00081030, "LO", "ETUDE FANTOME");
        f.us(0x00280120, 0);
        f.encoded(0x00081110, f.sequence(0x00081110,
                DicomFixture.element(0x00081150, "UI", "2.25.420".getBytes(StandardCharsets.US_ASCII), 8, true),
                f.nested(3)));
        return f.bytes();
    }

    static byte[] mutate(byte[] base, Random rnd) {
        byte[] b = base.clone();
        switch (rnd.nextInt(5)) {
            case 0 -> { // octets inversés, surtout dans l'en-tête
                int n = 1 + rnd.nextInt(16);
                for (int k = 0; k < n; k++) {
                    int pos = rnd.nextInt(10) == 0 ? rnd.nextInt(b.length) : 128 + rnd.nextInt(Math.min(900, b.length - 128));
                    b[pos] ^= (byte) (1 + rnd.nextInt(255));
                }
            }
            case 1 -> b = Arrays.copyOf(b, rnd.nextInt(b.length));
            case 2 -> {
                int pos = rnd.nextInt(b.length);
                byte[] ins = new byte[1 + rnd.nextInt(64)];
                rnd.nextBytes(ins);
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                o.write(b, 0, pos);
                o.writeBytes(ins);
                o.write(b, pos, b.length - pos);
                b = o.toByteArray();
            }
            case 3 -> { // longueur écrasée
                int pos = 132 + rnd.nextInt(Math.min(900, b.length - 136));
                int v = rnd.nextInt(4) == 0 ? -1 : rnd.nextInt();
                b[pos] = (byte) v;
                b[pos + 1] = (byte) (v >> 8);
                b[pos + 2] = (byte) (v >> 16);
                b[pos + 3] = (byte) (v >> 24);
            }
            default -> { // bloc d'en-tête dupliqué
                int from = 132 + rnd.nextInt(Math.min(800, b.length - 232));
                int len = 1 + rnd.nextInt(100);
                int to = 132 + rnd.nextInt(Math.min(800, b.length - 232));
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                o.write(b, 0, to);
                o.write(b, from, len);
                o.write(b, to, b.length - to);
                b = o.toByteArray();
            }
        }
        return b;
    }

    private static DicomReader reader0() {
        return new DicomReader(DicomAuditSink.NONE);
    }

    @Test
    void seededMutationsNeverCrashNorOverrun() {
        byte[] base = base();
        try {
            reader0().read(new ByteArrayInputStream(base), base.length);
        } catch (DicomRejectedException e) {
            fail("le fichier de base doit être accepté");
        }
        Random rnd = new Random(TEST_SEED_FUZZ);
        DicomReader reader = new DicomReader(DicomAuditSink.NONE);
        Map<DicomRejectedException.Reason, Integer> reasons = new EnumMap<>(DicomRejectedException.Reason.class);
        int accepted = 0;
        long worstMs = 0;
        for (int i = 0; i < MUTATIONS; i++) {
            byte[] file = mutate(base, rnd);
            long declared = rnd.nextInt(8) == 0 ? Math.max(0, file.length - rnd.nextInt(500)) : file.length;
            long start = System.nanoTime();
            try {
                reader.read(new ByteArrayInputStream(file), declared);
                accepted++;
            } catch (DicomRejectedException e) {
                DicomTrapTest.assertMessageIsFixed(e);
                reasons.merge(e.reason(), 1, Integer::sum);
            } catch (Throwable t) {
                fail("plantage non maîtrisé à la mutation " + i + " : " + t.getClass().getName());
            }
            worstMs = Math.max(worstMs, (System.nanoTime() - start) / 1_000_000);
        }
        System.out.println("Fuzzing : " + MUTATIONS + " mutations, " + accepted + " acceptées, rejets " + reasons
                + ", pire durée " + worstMs + " ms");
        assertEquals(0, reasons.getOrDefault(DicomRejectedException.Reason.TIMEOUT, 0), "dépassement du délai");
        assertTrue(worstMs < 30_000);
        assertTrue(accepted < MUTATIONS, "les mutations doivent produire des rejets");
    }
}
