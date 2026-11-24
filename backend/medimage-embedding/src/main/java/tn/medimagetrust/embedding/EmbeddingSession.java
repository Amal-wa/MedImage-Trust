package tn.medimagetrust.embedding;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.DEVIATION_EXCEEDED;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.F_READBACK_MISMATCH;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.MASK_UNSTABLE;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.PSNR_TOO_LOW;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.ROI_MODIFIED;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.S_READBACK_MISMATCH;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.UNUSED_BLOCK_MODIFIED;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.W_READBACK_MISMATCH;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.ILLEGAL_STATE;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;

/**
 * Orchestration d'un marquage, toujours sur une copie des pixels (échec fermé).
 *
 * <p>Ordre imposé :</p>
 * <ol>
 *   <li>{@link #open(PixelImage)} : détection de la RONI et partitions, ou refus ;</li>
 *   <li>écriture de F par l'appelant via {@link #fChannel()} (au plus 128 blocs, chacun une seule fois) ;</li>
 *   <li>{@link #writeS(byte[])} ;</li>
 *   <li>condensats ({@link #roiDigest()}, {@link #maskDigest()}, {@link #sDigest()}), puis {@link #writeW(byte[])}
 *       en dernier ;</li>
 *   <li>{@link #finish()} : contrôle final, puis renvoi de la copie marquée.</li>
 * </ol>
 *
 * <p>Toute exception levée par une méthode de la session la place dans l'état « en échec » : plus aucune opération
 * n'est acceptée ({@code ILLEGAL_STATE}) et aucune image n'est jamais renvoyée. L'image d'origine n'est jamais
 * écrite. Les méthodes sont synchronisées ; une session sert à un seul marquage.</p>
 */
public final class EmbeddingSession {
    private enum State { F_PHASE, S_WRITTEN, W_WRITTEN, FINISHED, FAILED }

    private final PixelImage original;
    private final int[] working;
    private final RoniProfile profile;
    private final Partitions partitions;
    private final LsbChannel lsb;
    private final boolean[][] fBits;
    private final double[][] fDither;
    private final FBlockChannel fChannel = new SessionFChannel();
    private int fBlocksWritten;
    private byte[] sPayload;
    private byte[] wPayload;
    private State state = State.F_PHASE;

    private EmbeddingSession(PixelImage original, RoniProfile profile, Partitions partitions) {
        this.original = original;
        this.working = original.pixels();
        this.profile = profile;
        this.partitions = partitions;
        this.lsb = new LsbChannel(working, partitions, true);
        this.fBits = new boolean[partitions.fClassSize()][];
        this.fDither = new double[partitions.fClassSize()][];
    }

    /**
     * Ouvre une session : détecte la RONI et découpe les partitions.
     *
     * @throws EmbeddingException un des refus de {@link RoniDetector#detect(PixelImage)}
     */
    public static EmbeddingSession open(PixelImage original) {
        RoniProfile profile = RoniDetector.detect(original);
        return new EmbeddingSession(original, profile, Partitions.of(profile));
    }

    /** Profil RONI de l'original (immuable). */
    public RoniProfile profile() { return profile; }

    /** Partitions W, S et F (immuables). */
    public Partitions partitions() { return partitions; }

    /** Canal F de la session, à donner au module fingerprint. */
    public FBlockChannel fChannel() { return fChannel; }

    /**
     * Écrit les 64 octets de S. Ferme la phase F.
     *
     * @throws EmbeddingException {@code INVALID_INPUT} (taille ≠ 64) ; {@code ILLEGAL_STATE} (S déjà écrit, session
     *                            finie ou en échec)
     */
    public synchronized void writeS(byte[] s) {
        guarded(() -> {
            require(State.F_PHASE);
            if (s == null || s.length != EmbeddingProfile.S_BYTES) {
                throw new EmbeddingException(INVALID_INPUT);
            }
            byte[] copy = s.clone();
            lsb.writeS(copy);
            sPayload = copy;
            state = State.S_WRITTEN;
            return null;
        });
    }

    /**
     * Écrit les 208 octets de W, en dernier.
     *
     * @throws EmbeddingException {@code INVALID_INPUT} (taille ≠ 208) ; {@code ILLEGAL_STATE} (S pas encore écrit,
     *                            W déjà écrit, session finie ou en échec)
     */
    public synchronized void writeW(byte[] w) {
        guarded(() -> {
            require(State.S_WRITTEN);
            if (w == null || w.length != EmbeddingProfile.W_BYTES) {
                throw new EmbeddingException(INVALID_INPUT);
            }
            byte[] copy = w.clone();
            lsb.writeW(copy);
            wPayload = copy;
            state = State.W_WRITTEN;
            return null;
        });
    }

    /** {@code roiDigest} de l'original (la ROI n'est jamais écrite). */
    public synchronized byte[] roiDigest() {
        return guarded(() -> {
            requireUsable();
            return Digests.roiDigest(original, profile);
        });
    }

    /** {@code maskDigest} du masque de l'original. */
    public synchronized byte[] maskDigest() {
        return guarded(() -> {
            requireUsable();
            return Digests.maskDigest(profile);
        });
    }

    /**
     * {@code sDigest} des 64 octets de S écrits.
     *
     * @throws EmbeddingException {@code ILLEGAL_STATE} si S n'est pas encore écrit
     */
    public synchronized byte[] sDigest() {
        return guarded(() -> {
            requireUsable();
            if (sPayload == null) {
                throw new EmbeddingException(ILLEGAL_STATE);
            }
            return Digests.sDigest(sPayload);
        });
    }

    /**
     * Contrôle final puis renvoi de la copie marquée. Une session ne peut être finie qu'une fois.
     *
     * @throws EmbeddingException {@code ILLEGAL_STATE} (W pas écrit, déjà finie, en échec) ;
     *                            {@code FINAL_CHECK_FAILED} avec son sous-motif
     */
    public synchronized PixelImage finish() {
        return guarded(() -> {
            require(State.W_WRITTEN);
            PixelImage marked = original.withPixels(working);
            finalCheck(marked);
            state = State.FINISHED;
            return marked;
        });
    }

    /**
     * Point d'injection de défaut, réservé aux tests de mutation du paquet (SEC-M03) : écrit une valeur arbitraire
     * dans la copie de travail, comme le ferait un code d'écriture fautif.
     */
    synchronized void injectFault(int pixelIndex, int value) {
        guarded(() -> {
            requireUsable();
            if (state == State.FINISHED) {
                throw new EmbeddingException(ILLEGAL_STATE);
            }
            working[pixelIndex] = value;
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------ contrôle final

    private void finalCheck(PixelImage marked) {
        int[] orig = original.view();
        int n = orig.length;
        // (a) ROI identique bit à bit.
        for (int i = 0; i < n; i++) {
            if (!profile.inRoniUnchecked(i) && working[i] != orig[i]) {
                throw new EmbeddingException(ROI_MODIFIED);
            }
        }
        // (b) Écarts par partition ; blocs F non utilisés intacts.
        for (int k = 0; k < EmbeddingProfile.W_BLOCKS; k++) {
            checkBlockDeviation(partitions.wBlock(k), EmbeddingProfile.MAX_LSB_DEVIATION, orig);
        }
        for (int k = 0; k < EmbeddingProfile.S_BLOCKS; k++) {
            checkBlockDeviation(partitions.sBlock(k), EmbeddingProfile.MAX_LSB_DEVIATION, orig);
        }
        for (int k = 0; k < partitions.fClassSize(); k++) {
            if (fBits[k] == null) {
                if (maxBlockDeviation(partitions.fBlock(k), orig) != 0) {
                    throw new EmbeddingException(UNUSED_BLOCK_MODIFIED);
                }
            } else {
                checkBlockDeviation(partitions.fBlock(k), EmbeddingProfile.MAX_F_DEVIATION, orig);
            }
        }
        // (c) PSNR de l'image entière.
        if (psnr(orig, working, original.maxValue()) < EmbeddingProfile.MIN_PSNR_DB) {
            throw new EmbeddingException(PSNR_TOO_LOW);
        }
        // (d) Marques relues.
        if (!Arrays.equals(lsb.readS(), sPayload)) {
            throw new EmbeddingException(S_READBACK_MISMATCH);
        }
        if (!Arrays.equals(lsb.readW(), wPayload)) {
            throw new EmbeddingException(W_READBACK_MISMATCH);
        }
        for (int k = 0; k < partitions.fClassSize(); k++) {
            if (fBits[k] != null) {
                int[] read = readF(k, fDither[k]);
                for (int j = 0; j < read.length; j++) {
                    if (read[j] != (fBits[k][j] ? 1 : 0)) {
                        throw new EmbeddingException(F_READBACK_MISMATCH);
                    }
                }
            }
        }
        // (e) Masque recalculé sur la copie : contient le masque d'origine.
        List<RoniProfile> candidates = RoniDetector.candidates(marked);
        boolean found = false;
        for (RoniProfile c : candidates) {
            if (c.sameMask(profile)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new EmbeddingException(MASK_UNSTABLE);
        }
    }

    private void checkBlockDeviation(int block, int bound, int[] orig) {
        if (maxBlockDeviation(block, orig) > bound) {
            throw new EmbeddingException(DEVIATION_EXCEEDED);
        }
    }

    private int maxBlockDeviation(int block, int[] orig) {
        int cols = partitions.cols();
        int top = partitions.blockTop(block);
        int left = partitions.blockLeft(block);
        int max = 0;
        for (int r = 0; r < EmbeddingProfile.BLOCK; r++) {
            int base = (top + r) * cols + left;
            for (int c = 0; c < EmbeddingProfile.BLOCK; c++) {
                max = Math.max(max, Math.abs(working[base + c] - orig[base + c]));
            }
        }
        return max;
    }

    /** PSNR en dB, {@code +∞} si les deux images sont identiques. */
    static double psnr(int[] a, int[] b, int maxValue) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        if (sum == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double mse = sum / a.length;
        return 10.0 * Math.log10((double) maxValue * maxValue / mse);
    }

    private int[] readF(int fIndex, double[] dither) {
        int block = partitions.fBlock(fIndex);
        return QimBlockCodec.readBlock(QimBlockCodec.getBlock(working, partitions.cols(),
                partitions.blockTop(block), partitions.blockLeft(block)), dither);
    }

    // ------------------------------------------------------------------------------------------ états

    private void require(State expected) {
        if (state != expected) {
            throw new EmbeddingException(ILLEGAL_STATE);
        }
    }

    private void requireUsable() {
        if (state == State.FAILED) {
            throw new EmbeddingException(ILLEGAL_STATE);
        }
    }

    /** Exécute une opération ; toute exception place la session en échec (fermé). */
    private <T> T guarded(Supplier<T> op) {
        try {
            return op.get();
        } catch (RuntimeException | Error e) {
            state = State.FAILED;
            throw e;
        }
    }

    /** Canal F lié à la session : écriture seulement en phase F, lecture sur la copie de travail. */
    private final class SessionFChannel implements FBlockChannel {
        @Override
        public int classSize() {
            return partitions.fClassSize();
        }

        @Override
        public void write(int fIndex, boolean[] bits8, double[] dither8) {
            synchronized (EmbeddingSession.this) {
                guarded(() -> {
                    require(State.F_PHASE);
                    if (fIndex < 0 || fIndex >= partitions.fClassSize()) {
                        throw new EmbeddingException(INVALID_INPUT);
                    }
                    boolean[] bits = QimBlockCodec.checkedBits(bits8);
                    double[] dither = QimBlockCodec.checkedDither(dither8);
                    if (fBits[fIndex] != null || fBlocksWritten >= EmbeddingProfile.F_USED_BLOCKS) {
                        throw new EmbeddingException(ILLEGAL_STATE);
                    }
                    int block = partitions.fBlock(fIndex);
                    int top = partitions.blockTop(block);
                    int left = partitions.blockLeft(block);
                    int cols = partitions.cols();
                    int[] marked = QimBlockCodec.writeBlock(QimBlockCodec.getBlock(working, cols, top, left), bits,
                            dither, original.maxValue());
                    QimBlockCodec.putBlock(working, cols, top, left, marked);
                    fBits[fIndex] = bits;
                    fDither[fIndex] = dither;
                    fBlocksWritten++;
                    return null;
                });
            }
        }

        @Override
        public int[] read(int fIndex, double[] dither8) {
            synchronized (EmbeddingSession.this) {
                return guarded(() -> {
                    requireUsable();
                    if (fIndex < 0 || fIndex >= partitions.fClassSize()) {
                        throw new EmbeddingException(INVALID_INPUT);
                    }
                    return readF(fIndex, QimBlockCodec.checkedDither(dither8));
                });
            }
        }
    }
}
