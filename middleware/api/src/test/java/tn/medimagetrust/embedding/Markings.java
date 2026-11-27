package tn.medimagetrust.embedding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Marquage complet de test : 128 blocs F tirés au hasard, S et W aléatoires (aucune clé réelle). */
final class Markings {
    private Markings() {}

    /** Écritures F d'une session. */
    record FWrites(int[] indices, boolean[][] bits, double[][] dither) {}

    /** Trace d'un marquage complet, pour relire et vérifier. */
    record Marking(PixelImage original, PixelImage marked, RoniProfile profile, Partitions partitions,
                   FWrites f, byte[] s, byte[] w, byte[] roiDigest, byte[] maskDigest, byte[] sDigest) {}

    static int[] pickFIndices(int classSize, int count, Random rnd) {
        List<Integer> all = new ArrayList<>(classSize);
        for (int i = 0; i < classSize; i++) all.add(i);
        Collections.shuffle(all, rnd);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = all.get(i);
        return out;
    }

    static boolean[] randomBits(Random rnd) {
        boolean[] b = new boolean[EmbeddingProfile.F_BITS_PER_BLOCK];
        for (int i = 0; i < b.length; i++) b[i] = rnd.nextBoolean();
        return b;
    }

    static double[] randomDither(Random rnd) {
        double[] d = new double[EmbeddingProfile.F_BITS_PER_BLOCK];
        for (int i = 0; i < d.length; i++) d[i] = rnd.nextDouble() * EmbeddingProfile.QIM_DELTA;
        return d;
    }

    static byte[] randomBytes(int n, Random rnd) {
        byte[] b = new byte[n];
        rnd.nextBytes(b);
        return b;
    }

    /** Écrit F sur 128 blocs de la classe F tirés au hasard. */
    static FWrites writeF(EmbeddingSession session, Random rnd) {
        int[] idx = pickFIndices(session.partitions().fClassSize(), EmbeddingProfile.F_USED_BLOCKS, rnd);
        boolean[][] bits = new boolean[idx.length][];
        double[][] dither = new double[idx.length][];
        for (int k = 0; k < idx.length; k++) {
            bits[k] = randomBits(rnd);
            dither[k] = randomDither(rnd);
            session.fChannel().write(idx[k], bits[k], dither[k]);
        }
        return new FWrites(idx, bits, dither);
    }

    /** Session complète : F, S, condensats, W, contrôle final. */
    static Marking markRandom(PixelImage original, Random rnd) {
        EmbeddingSession session = EmbeddingSession.open(original);
        FWrites f = writeF(session, rnd);
        byte[] s = randomBytes(EmbeddingProfile.S_BYTES, rnd);
        session.writeS(s);
        byte[] roi = session.roiDigest();
        byte[] mask = session.maskDigest();
        byte[] sd = session.sDigest();
        byte[] w = randomBytes(EmbeddingProfile.W_BYTES, rnd);
        session.writeW(w);
        PixelImage marked = session.finish();
        return new Marking(original, marked, session.profile(), session.partitions(), f, s, w, roi, mask, sd);
    }

    /** Nombre de bits F relus différents de ceux écrits. */
    static int fBitErrors(FBlockChannel channel, FWrites f) {
        int errors = 0;
        for (int k = 0; k < f.indices().length; k++) {
            int[] read = channel.read(f.indices()[k], f.dither()[k]);
            for (int j = 0; j < read.length; j++) {
                if (read[j] != (f.bits()[k][j] ? 1 : 0)) errors++;
            }
        }
        return errors;
    }
}
