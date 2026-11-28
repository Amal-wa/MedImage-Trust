package tn.medimagetrust.fingerprint;

import tn.medimagetrust.embedding.EmbeddingException;
import tn.medimagetrust.embedding.FBlockChannel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Canal F de test : classe F de {@code classSize} blocs 8 × 8 synthétiques (12 bits), avec la vraie QIM.
 *
 * <p>Reproduit le contrat de {@link FBlockChannel} et les règles de {@code EmbeddingSession.fChannel()} :
 * index hors limites, tableaux de longueur ≠ 8 ou dither hors de {@code [0, 12)} → {@code INVALID_INPUT} ;
 * bloc écrit deux fois ou plus de 128 blocs → {@code ILLEGAL_STATE} ; écart par pixel > 12 →
 * {@code F_DEVIATION_EXCEEDED} ; bloc illisible (recadrage simulé) → 8 × {@link FBlockChannel#UNREADABLE}.</p>
 *
 * <p>La DCT/QIM est une <strong>copie de test</strong> de {@code tn.medimagetrust.embedding.QimBlockCodec}, qui n'est
 * pas publique : DCT-II orthonormée 8 × 8, rangs zigzag 6 à 13, Δ = 12, arrondi {@code floor(x + 0,5)}, bornage à
 * {@code [0, 4095]}. Le vrai codec est couvert par {@code EmbeddingIntegrationTest}.</p>
 */
final class InMemoryFBlockChannel implements FBlockChannel {
    static final int MAX_VALUE = 4095;

    /** Une écriture reçue par le canal (pour vérifier les blocs et les dithers utilisés). */
    record Write(int fIndex, boolean[] bits, double[] dither) {}

    private final int[][] blocks;
    private final boolean[] unreadable;
    private final boolean[] written;
    private final List<Write> writes;

    /** Classe F de {@code classSize} blocs texturés (valeurs entre 1 000 et 3 000 : aucun bornage). */
    InMemoryFBlockChannel(int classSize, long seed) {
        Random rnd = new Random(seed); // test uniquement : simule le contenu naturel des blocs
        blocks = new int[classSize][64];
        for (int[] b : blocks) {
            int base = 1000 + rnd.nextInt(1500);
            int gr = rnd.nextInt(21) - 10, gc = rnd.nextInt(21) - 10;
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) b[r * 8 + c] = base + gr * r + gc * c + rnd.nextInt(41) - 20;
            }
        }
        unreadable = new boolean[classSize];
        written = new boolean[classSize];
        writes = new ArrayList<>();
    }

    private InMemoryFBlockChannel(InMemoryFBlockChannel o) {
        blocks = new int[o.blocks.length][];
        for (int i = 0; i < blocks.length; i++) blocks[i] = o.blocks[i].clone();
        unreadable = o.unreadable.clone();
        written = o.written.clone();
        writes = new ArrayList<>(o.writes);
    }

    InMemoryFBlockChannel copy() {
        return new InMemoryFBlockChannel(this);
    }

    /** Simule un recadrage : les blocs d'indice >= from deviennent illisibles. */
    void cropFrom(int from) {
        for (int i = from; i < unreadable.length; i++) unreadable[i] = true;
    }

    /** Collusion au bloc près : remplace le bloc {@code fIndex} par celui de {@code other}. */
    void copyBlockFrom(InMemoryFBlockChannel other, int fIndex) {
        blocks[fIndex] = other.blocks[fIndex].clone();
    }

    boolean sameBlock(InMemoryFBlockChannel other, int fIndex) {
        return Arrays.equals(blocks[fIndex], other.blocks[fIndex]);
    }

    int[] pixels(int fIndex) {
        return blocks[fIndex].clone();
    }

    List<Write> writes() {
        return List.copyOf(writes);
    }

    @Override
    public int classSize() {
        return blocks.length;
    }

    /** Pas de ce canal de test : Δ(8), celui de la copie de la QIM ci-dessous. */
    @Override
    public double qimDelta() {
        return Qim.DELTA;
    }

    @Override
    public void write(int fIndex, boolean[] bits8, double[] dither8) {
        checkIndex(fIndex);
        if (bits8 == null || bits8.length != 8) throw new EmbeddingException(EmbeddingException.Reason.INVALID_INPUT);
        double[] d = checkedDither(dither8);
        if (written[fIndex] || writes.size() >= FingerprintParams.USED_BLOCKS) {
            throw new EmbeddingException(EmbeddingException.Reason.ILLEGAL_STATE);
        }
        blocks[fIndex] = Qim.writeBlock(blocks[fIndex], bits8, d);
        written[fIndex] = true;
        writes.add(new Write(fIndex, bits8.clone(), d));
    }

    @Override
    public int[] read(int fIndex, double[] dither8) {
        checkIndex(fIndex);
        double[] d = checkedDither(dither8);
        if (unreadable[fIndex]) {
            int[] out = new int[8];
            Arrays.fill(out, UNREADABLE);
            return out;
        }
        return Qim.readBlock(blocks[fIndex], d);
    }

    private void checkIndex(int fIndex) {
        if (fIndex < 0 || fIndex >= blocks.length) throw new EmbeddingException(EmbeddingException.Reason.INVALID_INPUT);
    }

    private static double[] checkedDither(double[] dither8) {
        if (dither8 == null || dither8.length != 8) throw new EmbeddingException(EmbeddingException.Reason.INVALID_INPUT);
        for (double v : dither8) {
            if (!(v >= 0.0 && v < Qim.DELTA)) throw new EmbeddingException(EmbeddingException.Reason.INVALID_INPUT);
        }
        return dither8.clone();
    }

    /** Copie de test de la DCT/QIM du module embedding (voir la Javadoc de la classe). */
    static final class Qim {
        static final double DELTA = tn.medimagetrust.embedding.EmbeddingProfile.qimDelta(8);
        static final int[][] CARRIERS = {{0, 3}, {1, 2}, {2, 1}, {3, 0}, {4, 0}, {3, 1}, {2, 2}, {1, 3}};
        private static final double[][] M = new double[8][8];

        static {
            for (int u = 0; u < 8; u++) {
                double a = u == 0 ? Math.sqrt(1.0 / 8) : Math.sqrt(2.0 / 8);
                for (int x = 0; x < 8; x++) M[u][x] = a * Math.cos((2 * x + 1) * u * Math.PI / 16.0);
            }
        }

        private Qim() {}

        static double[] forward(double[] f) {
            double[] t = new double[64], out = new double[64];
            for (int u = 0; u < 8; u++)
                for (int y = 0; y < 8; y++) {
                    double s = 0;
                    for (int x = 0; x < 8; x++) s += M[u][x] * f[x * 8 + y];
                    t[u * 8 + y] = s;
                }
            for (int u = 0; u < 8; u++)
                for (int v = 0; v < 8; v++) {
                    double s = 0;
                    for (int y = 0; y < 8; y++) s += t[u * 8 + y] * M[v][y];
                    out[u * 8 + v] = s;
                }
            return out;
        }

        static double[] inverse(double[] c) {
            double[] t = new double[64], out = new double[64];
            for (int x = 0; x < 8; x++)
                for (int v = 0; v < 8; v++) {
                    double s = 0;
                    for (int u = 0; u < 8; u++) s += M[u][x] * c[u * 8 + v];
                    t[x * 8 + v] = s;
                }
            for (int x = 0; x < 8; x++)
                for (int y = 0; y < 8; y++) {
                    double s = 0;
                    for (int v = 0; v < 8; v++) s += t[x * 8 + v] * M[v][y];
                    out[x * 8 + y] = s;
                }
            return out;
        }

        static double embed(double c, int bit, double d) {
            double off = d + bit * DELTA / 2;
            return DELTA * Math.floor((c - off) / DELTA + 0.5) + off;
        }

        static int extract(double c, double d) {
            return Math.abs(c - embed(c, 1, d)) < Math.abs(c - embed(c, 0, d)) ? 1 : 0;
        }

        static int[] writeBlock(int[] block, boolean[] bits, double[] dither) {
            double[] f = new double[64];
            for (int i = 0; i < 64; i++) f[i] = block[i];
            double[] c = forward(f);
            for (int k = 0; k < 8; k++) {
                int idx = CARRIERS[k][0] * 8 + CARRIERS[k][1];
                c[idx] = embed(c[idx], bits[k] ? 1 : 0, dither[k]);
            }
            double[] g = inverse(c);
            int[] out = new int[64];
            for (int i = 0; i < 64; i++) {
                long v = (long) Math.floor(g[i] + 0.5);
                out[i] = (int) Math.max(0, Math.min(MAX_VALUE, v));
                if (Math.abs(out[i] - block[i]) > 12) {
                    throw new EmbeddingException(EmbeddingException.Reason.F_DEVIATION_EXCEEDED);
                }
            }
            return out;
        }

        static int[] readBlock(int[] block, double[] dither) {
            double[] f = new double[64];
            for (int i = 0; i < 64; i++) f[i] = block[i];
            double[] c = forward(f);
            int[] bits = new int[8];
            for (int k = 0; k < 8; k++) bits[k] = extract(c[CARRIERS[k][0] * 8 + CARRIERS[k][1]], dither[k]);
            return bits;
        }
    }
}
