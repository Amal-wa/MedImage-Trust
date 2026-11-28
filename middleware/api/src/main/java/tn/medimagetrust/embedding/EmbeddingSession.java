package tn.medimagetrust.embedding;

import java.util.Arrays;
import java.util.function.Supplier;

import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.DEVIATION_EXCEEDED;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.F_READBACK_MISMATCH;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.MASK_UNSTABLE;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.PSNR_TOO_LOW;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.RANGE_OUTSIDE_RONI;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.ROI_MODIFIED;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.S_READBACK_MISMATCH;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.UNUSED_BLOCK_MODIFIED;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck.W_READBACK_MISMATCH;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.EMBEDDING_RANGE_UNSUPPORTED;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.ILLEGAL_STATE;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;

/**
 * Orchestration d'un marquage, toujours sur une copie des pixels (échec fermé).
 *
 * <p>Ordre imposé :</p>
 * <ol>
 *   <li>{@link #open(PixelImage)} : détection de la RONI et partitions, ou refus ;</li>
 *   <li>écriture de F par l'appelant via {@link #fChannel()} (au plus 128 blocs, chacun une seule fois), au pas
 *       Δ(B) de la profondeur effective B de l'original ;</li>
 *   <li>{@link #writeS(byte[])} ;</li>
 *   <li>condensats ({@link #roiDigest()}, {@link #maskDigest()}, {@link #sDigest()}), puis {@link #writeW(byte[])}
 *       en dernier ;</li>
 *   <li>{@link #finish()} : contrôle final, puis renvoi du {@link EmbeddingResult}.</li>
 * </ol>
 *
 * <p><strong>Plage stockable, jamais d'écrêtage.</strong> Chaque bloc F est calculé en entiers signés, sans bornage.
 * Si une valeur sortait de {@code [0, 2^B − 1]} : pour B ≤ 15, la copie passe en mode signé (entier signé 16 bits,
 * valeurs écrites telles quelles, {@link EmbeddingResult#signedOutput()}) ; pour B = 16, ou si une valeur sortait même
 * de {@code [−32 768, 32 767]}, l'émission est refusée ({@code EMBEDDING_RANGE_UNSUPPORTED}). Les blocs F sont dans la
 * RONI : la ROI garde exactement ses valeurs, et le contrôle final le vérifie.</p>
 *
 * <p>Toute exception levée par une méthode de la session la place dans l'état « en échec » : plus aucune opération
 * n'est acceptée ({@code ILLEGAL_STATE}) et aucune image n'est jamais renvoyée. L'image d'origine n'est jamais
 * écrite. Les méthodes sont synchronisées ; une session sert à un seul marquage.</p>
 */
public final class EmbeddingSession {
    private enum State { F_PHASE, S_WRITTEN, W_WRITTEN, FINISHED, FAILED }

    /** L'original (immuable) : seule copie gardée pour le contrôle « ROI bit à bit ». */
    private final PixelImage original;
    /** Unique copie de travail ({@code char[]}) ; elle devient l'image marquée au {@link #finish()}. */
    private final char[] working;
    private final RoniProfile profile;
    private final Partitions partitions;
    private final LsbChannel lsb;
    /** Pas Δ(B) et écart F maximal, calculés une fois depuis la profondeur effective de l'original. */
    private final double delta;
    private final int maxFDeviation;
    /** Le mode signé est-il permis ? Toujours en production ; un test peut forcer le mode non signé. */
    private final boolean signedOutputAllowed;
    /** Blocs F écrits, dans l'ordre d'écriture (au plus 128) : indice dans la classe F, bits et dithers. */
    private final int[] fIndices = new int[EmbeddingProfile.F_USED_BLOCKS];
    private final boolean[][] fBits = new boolean[EmbeddingProfile.F_USED_BLOCKS][];
    private final double[][] fDither = new double[EmbeddingProfile.F_USED_BLOCKS][];
    private final FBlockChannel fChannel = new SessionFChannel();
    private int fBlocksWritten;
    /** La copie de travail est en représentation signée (complément à deux) depuis la première sortie de plage. */
    private boolean signedOutput;
    /** Nombre de valeurs F qui sortaient de la plage stockable de l'original (observation, tests). */
    private long rangeExits;
    private byte[] sPayload;
    private byte[] wPayload;
    private State state = State.F_PHASE;

    private EmbeddingSession(PixelImage original, RoniProfile profile, Partitions partitions,
                             boolean signedOutputAllowed) {
        this.original = original;
        this.working = original.pixels();
        this.profile = profile;
        this.partitions = partitions;
        this.lsb = new LsbChannel(working, partitions, true);
        this.delta = EmbeddingProfile.qimDelta(original.bitsStored());
        this.maxFDeviation = EmbeddingProfile.maxFDeviation(original.bitsStored());
        this.signedOutputAllowed = signedOutputAllowed;
    }

    private boolean fWritten(int fIndex) {
        for (int k = 0; k < fBlocksWritten; k++) {
            if (fIndices[k] == fIndex) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ouvre une session : détecte la RONI et découpe les partitions.
     *
     * @throws EmbeddingException {@code INVALID_INPUT} si l'image est déjà une copie signée ; un des refus de
     *                            {@link RoniDetector#detect(PixelImage)}
     */
    public static EmbeddingSession open(PixelImage original) {
        return open(original, true);
    }

    /**
     * Ouverture avec choix du mode : {@code signedOutputAllowed = false} force une sortie non signée, et toute sortie
     * de plage est alors refusée ({@code EMBEDDING_RANGE_UNSUPPORTED}). Réservé aux tests (comportement « sans la
     * correction »).
     */
    static EmbeddingSession open(PixelImage original, boolean signedOutputAllowed) {
        if (original != null && original.signed()) {
            throw new EmbeddingException(INVALID_INPUT); // on ne remarque pas une copie déjà marquée en mode signé
        }
        RoniProfile profile = RoniDetector.detect(original);
        return new EmbeddingSession(original, profile, Partitions.of(profile), signedOutputAllowed);
    }

    /** Profil RONI de l'original (immuable). */
    public RoniProfile profile() { return profile; }

    /** Partitions W, S et F (immuables). */
    public Partitions partitions() { return partitions; }

    /** Canal F de la session, à donner au module fingerprint. */
    public FBlockChannel fChannel() { return fChannel; }

    /** Nombre de valeurs F qui sortaient de la plage stockable de l'original (0 si aucun passage en mode signé). */
    synchronized long rangeExitCount() { return rangeExits; }

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
     * Contrôle final puis renvoi de la copie marquée, avec la profondeur B à enregistrer et le mode de sortie. Une
     * session ne peut être finie qu'une fois.
     *
     * @throws EmbeddingException {@code ILLEGAL_STATE} (W pas écrit, déjà finie, en échec) ;
     *                            {@code FINAL_CHECK_FAILED} avec son sous-motif
     */
    public synchronized EmbeddingResult finish() {
        return guarded(() -> {
            require(State.W_WRITTEN);
            // L'image marquée prend possession de la copie de travail (pas de troisième copie). Plus aucune écriture
            // n'est possible ensuite : la session passe à FINISHED ou FAILED, et injectFault est refusé.
            PixelImage marked = signedOutput ? PixelImage.adoptSigned(original, working)
                    : PixelImage.adopt(original, working);
            finalCheck(marked);
            state = State.FINISHED;
            return new EmbeddingResult(marked, original.bitsStored(), signedOutput);
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
            working[pixelIndex] = PixelImage.encode(value, signedOutput);
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------ contrôle final

    private void finalCheck(PixelImage marked) {
        char[] orig = original.view();
        int n = orig.length;
        int max = original.maxValue();
        // (a) ROI identique bit à bit ; valeurs hors de [0, 2^B − 1] seulement dans la RONI d'une copie signée.
        for (int i = 0; i < n; i++) {
            boolean inRoni = profile.inRoniUnchecked(i);
            if (!inRoni && working[i] != orig[i]) {
                throw new EmbeddingException(ROI_MODIFIED);
            }
            int v = PixelImage.decode(working[i], signedOutput);
            if ((v < 0 || v > max) && (!inRoni || !signedOutput)) {
                throw new EmbeddingException(RANGE_OUTSIDE_RONI);
            }
        }
        // (b) Écarts par partition ; blocs F non utilisés intacts.
        for (int k = 0; k < EmbeddingProfile.W_BLOCKS; k++) {
            checkBlockDeviation(partitions.wBlock(k), EmbeddingProfile.MAX_LSB_DEVIATION, orig);
        }
        for (int k = 0; k < EmbeddingProfile.S_BLOCKS; k++) {
            checkBlockDeviation(partitions.sBlock(k), EmbeddingProfile.MAX_LSB_DEVIATION, orig);
        }
        long[] used = Bits.alloc(partitions.fClassSize());
        for (int k = 0; k < fBlocksWritten; k++) {
            Bits.set(used, fIndices[k]);
        }
        for (int k = 0; k < partitions.fClassSize(); k++) {
            if (!Bits.get(used, k)) {
                if (maxBlockDeviation(partitions.fBlock(k), orig) != 0) {
                    throw new EmbeddingException(UNUSED_BLOCK_MODIFIED);
                }
            } else {
                checkBlockDeviation(partitions.fBlock(k), maxFDeviation, orig);
            }
        }
        used = null;
        // (c) PSNR de l'image entière, avec MAX = 2^B − 1 (plage de l'original).
        if (psnr(orig, working, signedOutput, max) < EmbeddingProfile.MIN_PSNR_DB) {
            throw new EmbeddingException(PSNR_TOO_LOW);
        }
        // (d) Marques relues.
        if (!Arrays.equals(lsb.readS(), sPayload)) {
            throw new EmbeddingException(S_READBACK_MISMATCH);
        }
        if (!Arrays.equals(lsb.readW(), wPayload)) {
            throw new EmbeddingException(W_READBACK_MISMATCH);
        }
        for (int k = 0; k < fBlocksWritten; k++) {
            int[] read = readF(fIndices[k], fDither[k]);
            for (int j = 0; j < read.length; j++) {
                if (read[j] != (fBits[k][j] ? 1 : 0)) {
                    throw new EmbeddingException(F_READBACK_MISMATCH);
                }
            }
        }
        // (e) Masque recalculé sur la copie : l'un des candidats est le masque d'origine (un candidat à la fois).
        if (!RoniDetector.candidatesContain(marked, profile)) {
            throw new EmbeddingException(MASK_UNSTABLE);
        }
    }

    private void checkBlockDeviation(int block, int bound, char[] orig) {
        if (maxBlockDeviation(block, orig) > bound) {
            throw new EmbeddingException(DEVIATION_EXCEEDED);
        }
    }

    private int maxBlockDeviation(int block, char[] orig) {
        int cols = partitions.cols();
        int top = partitions.blockTop(block);
        int left = partitions.blockLeft(block);
        int max = 0;
        for (int r = 0; r < EmbeddingProfile.BLOCK; r++) {
            int base = (top + r) * cols + left;
            for (int c = 0; c < EmbeddingProfile.BLOCK; c++) {
                max = Math.max(max, Math.abs(PixelImage.decode(working[base + c], signedOutput) - orig[base + c]));
            }
        }
        return max;
    }

    /** PSNR en dB entre deux images non signées, {@code +∞} si elles sont identiques. */
    static double psnr(char[] a, char[] b, int maxValue) {
        return psnr(a, b, false, maxValue);
    }

    /**
     * PSNR en dB entre l'original {@code a} (non signé) et la copie {@code b} (représentation {@code bSigned}), avec
     * {@code MAX = maxValue} (plage de l'original) ; {@code +∞} si identiques.
     */
    static double psnr(char[] a, char[] b, boolean bSigned, int maxValue) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - PixelImage.decode(b[i], bSigned);
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
        return QimBlockCodec.readBlock(QimBlockCodec.getBlock(working, signedOutput, partitions.cols(),
                partitions.blockTop(block), partitions.blockLeft(block)), dither, delta);
    }

    /**
     * Contrôle de plage d'un bloc F calculé : rien à faire s'il tient dans {@code [0, 2^B − 1]} ; sinon passage en
     * mode signé si B ≤ 15 et si le mode est permis, ou refus. Ne modifie pas la copie de travail.
     */
    private void admitRange(int[] marked) {
        int max = original.maxValue();
        int exits = 0;
        boolean fitsSigned = true;
        for (int v : marked) {
            if (v < 0 || v > max) {
                exits++;
            }
            if (v < PixelImage.SIGNED_MIN || v > PixelImage.SIGNED_MAX) {
                fitsSigned = false;
            }
        }
        if (exits == 0) {
            return;
        }
        rangeExits += exits;
        if (!signedOutputAllowed || original.bitsStored() > EmbeddingProfile.MAX_BITS_SIGNED_OUTPUT || !fitsSigned) {
            throw new EmbeddingException(EMBEDDING_RANGE_UNSUPPORTED);
        }
        // B ≤ 15 : les valeurs déjà écrites sont dans [0, 32 767], identiques dans les deux représentations.
        signedOutput = true;
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
        public double qimDelta() {
            return delta;
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
                    double[] dither = QimBlockCodec.checkedDither(dither8, delta);
                    if (fWritten(fIndex) || fBlocksWritten >= EmbeddingProfile.F_USED_BLOCKS) {
                        throw new EmbeddingException(ILLEGAL_STATE);
                    }
                    int block = partitions.fBlock(fIndex);
                    int top = partitions.blockTop(block);
                    int left = partitions.blockLeft(block);
                    int cols = partitions.cols();
                    int[] marked = QimBlockCodec.writeBlock(
                            QimBlockCodec.getBlock(working, signedOutput, cols, top, left), bits, dither, delta,
                            maxFDeviation);
                    admitRange(marked);
                    QimBlockCodec.putBlock(working, signedOutput, cols, top, left, marked);
                    fIndices[fBlocksWritten] = fIndex;
                    fBits[fBlocksWritten] = bits;
                    fDither[fBlocksWritten] = dither;
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
                    return readF(fIndex, QimBlockCodec.checkedDither(dither8, delta));
                });
            }
        }
    }
}
