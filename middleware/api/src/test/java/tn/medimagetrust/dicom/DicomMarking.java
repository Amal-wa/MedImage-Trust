package tn.medimagetrust.dicom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import tn.medimagetrust.embedding.EmbeddingProfile;
import tn.medimagetrust.embedding.EmbeddingResult;
import tn.medimagetrust.embedding.EmbeddingSession;
import tn.medimagetrust.embedding.PixelImage;

/** Marquage complet de test (F, S et W aléatoires, aucune clé réelle), avec la trace nécessaire à la relecture. */
final class DicomMarking {
    private DicomMarking() {}

    record Marked(EmbeddingSession session, EmbeddingResult result, int[] fIndices, boolean[][] bits,
                  double[][] dither, byte[] s, byte[] w) {}

    static Marked mark(PixelImage original, long seed) {
        Random rnd = new Random(seed);
        EmbeddingSession session = EmbeddingSession.open(original);
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < session.partitions().fClassSize(); i++) all.add(i);
        Collections.shuffle(all, rnd);
        int n = EmbeddingProfile.F_USED_BLOCKS;
        int[] idx = new int[n];
        boolean[][] bits = new boolean[n][EmbeddingProfile.F_BITS_PER_BLOCK];
        double[][] dither = new double[n][EmbeddingProfile.F_BITS_PER_BLOCK];
        double delta = session.fChannel().qimDelta();
        for (int k = 0; k < n; k++) {
            idx[k] = all.get(k);
            for (int j = 0; j < EmbeddingProfile.F_BITS_PER_BLOCK; j++) {
                bits[k][j] = rnd.nextBoolean();
                dither[k][j] = rnd.nextDouble() * delta;
            }
            session.fChannel().write(idx[k], bits[k], dither[k]);
        }
        byte[] s = new byte[EmbeddingProfile.S_BYTES];
        byte[] w = new byte[EmbeddingProfile.W_BYTES];
        rnd.nextBytes(s);
        rnd.nextBytes(w);
        session.writeS(s);
        session.writeW(w);
        return new Marked(session, session.finish(), idx, bits, dither, s, w);
    }

    static double psnr(PixelImage a, PixelImage b) {
        double sum = 0;
        int n = a.pixelCount();
        for (int i = 0; i < n; i++) {
            double d = a.pixel(i) - b.pixel(i);
            sum += d * d;
        }
        if (sum == 0) return Double.POSITIVE_INFINITY;
        double max = a.maxValue();
        return 10 * Math.log10(max * max / (sum / n));
    }
}
