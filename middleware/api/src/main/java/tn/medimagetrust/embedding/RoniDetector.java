package tn.medimagetrust.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static tn.medimagetrust.embedding.EmbeddingException.Reason.BURNED_IN_ANNOTATION;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.GUARD_VIOLATION;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.OVERLAY_PRESENT;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.ROI_TOO_SMALL;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.TOO_FEW_RONI_BLOCKS;

/**
 * Détection déterministe de la RONI (profil d'incorporation v2, dossier v2.4 §4.1).
 *
 * <ol>
 *   <li>{@code q = pixel >> 2} ;</li>
 *   <li>seuil d'Otsu {@code T} sur l'histogramme de {@code q} ({@link Otsu}) ;</li>
 *   <li>polarité du fond = majorité des pixels du bord (égalité : fond sombre) ;</li>
 *   <li>composantes connexes du fond (4-connexité) qui touchent le bord, par un parcours itératif à file bornée ;</li>
 *   <li>érosion de 8 pixels (carré 17 × 17, l'extérieur de l'image compte comme du fond), séparable en O(n) ;</li>
 *   <li>blocs 8 × 8 alignés sur (0, 0), entièrement dans le masque érodé ; blocs incomplets ignorés ;</li>
 *   <li>garde : chaque pixel de la RONI à au moins 4 niveaux de {@code q} du seuil, du côté du fond.</li>
 * </ol>
 *
 * <p>Mémoire : masques compactés d'un bit par pixel ({@link Bits}), file de parcours de taille fixe, tampons de la
 * taille d'une ligne pour l'érosion ; aucun tableau {@code int} ni {@code boolean} de taille {@code rows × cols}.</p>
 */
public final class RoniDetector {
    private RoniDetector() {}

    /** Capacité de la file du parcours : 2^20 entiers (4 Mio). */
    static final int QUEUE_CAPACITY = 1 << 20;

    /**
     * Détecte la RONI d'une image à marquer et applique les refus du profil.
     *
     * @throws EmbeddingException {@code INVALID_INPUT}, {@code BURNED_IN_ANNOTATION}, {@code OVERLAY_PRESENT},
     *                            {@code TOO_FEW_RONI_BLOCKS}, {@code GUARD_VIOLATION} ou {@code ROI_TOO_SMALL}
     */
    public static RoniProfile detect(PixelImage image) {
        if (image == null) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        if (image.burnedInAnnotation()) {
            throw new EmbeddingException(BURNED_IN_ANNOTATION);
        }
        if (image.overlayPresent()) {
            throw new EmbeddingException(OVERLAY_PRESENT);
        }
        RoniProfile profile = compute(image, otsuThreshold(image));
        if (profile.roniBlockCount() < EmbeddingProfile.MIN_RONI_BLOCKS) {
            throw new EmbeddingException(TOO_FEW_RONI_BLOCKS);
        }
        if (!guardHolds(image, profile)) {
            throw new EmbeddingException(GUARD_VIOLATION);
        }
        if (profile.roiPixelCount() < EmbeddingProfile.MIN_ROI_RATIO * image.pixelCount()) {
            throw new EmbeddingException(ROI_TOO_SMALL);
        }
        return profile;
    }

    /**
     * Masques candidats pour le vérificateur : seuils {@code T − 4 … T + 4} où {@code T} est l'Otsu de la copie,
     * essayés dans l'ordre {@code T, T−1, T+1, T−2, T+2, …} ; seuls les masques distincts sont gardés (premier
     * seuil rencontré). Aucun refus n'est appliqué, sauf {@code INVALID_INPUT}.
     *
     * <p>Coût mémoire : jusqu'à 9 profils gardés (à 36 Mpx, 4,3 Mio de masque + 2,1 Mio d'indices chacun).</p>
     */
    public static List<RoniProfile> candidates(PixelImage marked) {
        if (marked == null) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        int t0 = otsuThreshold(marked);
        int qMax = marked.maxValue() >> EmbeddingProfile.Q_SHIFT;
        List<RoniProfile> out = new ArrayList<>();
        for (int k = 0; k <= 2 * EmbeddingProfile.T_SEARCH; k++) {
            int t = t0 + offsetOf(k);
            if (t < 0 || t > qMax) {
                continue;
            }
            RoniProfile p = compute(marked, t);
            boolean seen = false;
            for (RoniProfile c : out) {
                if (c.sameMask(p)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    /**
     * {@code true} si l'un des masques de {@link #candidates(PixelImage)} est celui de {@code original}. Même parcours
     * des seuils, mais chaque masque est abandonné dès qu'il est comparé : un seul masque candidat en mémoire à la
     * fois (contrôle final de la session).
     */
    static boolean candidatesContain(PixelImage marked, RoniProfile original) {
        int t0 = otsuThreshold(marked);
        int qMax = marked.maxValue() >> EmbeddingProfile.Q_SHIFT;
        for (int k = 0; k <= 2 * EmbeddingProfile.T_SEARCH; k++) {
            int t = t0 + offsetOf(k);
            if (t >= 0 && t <= qMax && compute(marked, t).sameMask(original)) {
                return true;
            }
        }
        return false;
    }

    /** Ordre des seuils essayés : 0, −1, +1, −2, +2, … */
    private static int offsetOf(int k) {
        return (k % 2 == 1) ? -((k + 1) / 2) : k / 2;
    }

    /** Seuil d'Otsu sur l'histogramme de {@code q} (taille {@code (maxValue >> 2) + 1}). */
    static int otsuThreshold(PixelImage image) {
        char[] px = image.view();
        long[] hist = new long[(image.maxValue() >> EmbeddingProfile.Q_SHIFT) + 1];
        for (char v : px) {
            hist[v >> EmbeddingProfile.Q_SHIFT]++;
        }
        return Otsu.threshold(hist);
    }

    /** Étapes 2 à 6 pour un seuil donné, sans aucun refus. */
    static RoniProfile compute(PixelImage image, int threshold) {
        int rows = image.rows();
        int cols = image.cols();
        char[] px = image.view();
        BackgroundPolarity polarity = polarity(px, rows, cols, threshold);
        long[] mask = borderConnectedBackground(px, rows, cols, threshold, polarity);
        erodeInPlace(mask, rows, cols, EmbeddingProfile.EROSION_PX);
        int[] blocks = fullBlocks(mask, rows, cols);
        mask = null; // libéré avant que RoniProfile n'alloue son propre masque
        return new RoniProfile(rows, cols, threshold, polarity, blocks);
    }

    /** Majorité des pixels du bord (chaque pixel compté une fois) ; égalité : fond sombre. */
    static BackgroundPolarity polarity(char[] px, int rows, int cols, int threshold) {
        long dark = 0;
        long light = 0;
        for (int i : borderIndices(rows, cols)) {
            if ((px[i] >> EmbeddingProfile.Q_SHIFT) <= threshold) {
                dark++;
            } else {
                light++;
            }
        }
        return dark >= light ? BackgroundPolarity.DARK : BackgroundPolarity.LIGHT;
    }

    /** Indices des pixels du bord, chacun une fois (2 × (rows + cols) au plus). */
    private static int[] borderIndices(int rows, int cols) {
        int count = (rows == 1 || cols == 1) ? rows * cols : 2 * cols + 2 * (rows - 2);
        int[] out = new int[count];
        int k = 0;
        if (rows == 1 || cols == 1) {
            for (int i = 0; i < rows * cols; i++) {
                out[k++] = i;
            }
            return out;
        }
        for (int c = 0; c < cols; c++) {
            out[k++] = c;
            out[k++] = (rows - 1) * cols + c;
        }
        for (int r = 1; r < rows - 1; r++) {
            out[k++] = r * cols;
            out[k++] = r * cols + cols - 1;
        }
        return out;
    }

    /**
     * Fond connexe au bord (4-connexité), parcours itératif (aucune récursion), résultat en masque compacté.
     *
     * <p>La file est un anneau d'entiers de taille fixe ({@link #QUEUE_CAPACITY}) : ni {@code ArrayDeque<Integer>}
     * ni tableau de taille {@code rows × cols}. Si la frontière dépasse la file (images pathologiques), les pixels
     * en trop sont notés dans un masque « en attente » d'un bit par pixel, relu quand la file se vide. Le résultat
     * (l'ensemble connexe) ne dépend pas de l'ordre de visite.</p>
     */
    static long[] borderConnectedBackground(char[] px, int rows, int cols, int threshold,
                                            BackgroundPolarity polarity) {
        return borderConnectedBackground(px, rows, cols, threshold, polarity, QUEUE_CAPACITY);
    }

    /** Variante à capacité de file choisie (tests du débordement). */
    static long[] borderConnectedBackground(char[] px, int rows, int cols, int threshold,
                                            BackgroundPolarity polarity, int queueCapacity) {
        Flood flood = new Flood(px, rows, cols, threshold, polarity,
                Math.max(1, Math.min(queueCapacity, rows * cols)));
        for (int i : borderIndices(rows, cols)) {
            flood.offer(i);
        }
        flood.run();
        return flood.seen;
    }

    /** Remplissage par diffusion, file bornée, débordement dans un masque compacté. */
    private static final class Flood {
        private final char[] px;
        private final int rows;
        private final int cols;
        private final int threshold;
        private final BackgroundPolarity polarity;
        final long[] seen;
        private final int[] ring;
        private int head;
        private int size;
        private long[] pending;
        private long pendingCount;

        Flood(char[] px, int rows, int cols, int threshold, BackgroundPolarity polarity, int capacity) {
            this.px = px;
            this.rows = rows;
            this.cols = cols;
            this.threshold = threshold;
            this.polarity = polarity;
            this.seen = Bits.alloc(rows * cols);
            this.ring = new int[capacity];
        }

        void offer(int j) {
            if (!Bits.get(seen, j) && polarity.isBackground(px[j] >> EmbeddingProfile.Q_SHIFT, threshold)) {
                Bits.set(seen, j);
                push(j);
            }
        }

        private void push(int j) {
            if (size < ring.length) {
                enqueue(j);
            } else {
                if (pending == null) {
                    pending = Bits.alloc(rows * cols);
                }
                Bits.set(pending, j);
                pendingCount++;
            }
        }

        private void enqueue(int j) {
            int slot = head + size;
            ring[slot >= ring.length ? slot - ring.length : slot] = j;
            size++;
        }

        void run() {
            while (true) {
                while (size > 0) {
                    int i = ring[head];
                    head = head + 1 == ring.length ? 0 : head + 1;
                    size--;
                    int r = i / cols;
                    int c = i - r * cols;
                    if (r > 0) offer(i - cols);
                    if (r < rows - 1) offer(i + cols);
                    if (c > 0) offer(i - 1);
                    if (c < cols - 1) offer(i + 1);
                }
                if (pendingCount == 0) {
                    pending = null;
                    return;
                }
                refill();
            }
        }

        /** Recharge la file depuis le masque « en attente », jusqu'à la remplir. */
        private void refill() {
            for (int w = 0; w < pending.length && size < ring.length; w++) {
                long word = pending[w];
                while (word != 0 && size < ring.length) {
                    int b = Long.numberOfTrailingZeros(word);
                    word &= word - 1;
                    pending[w] &= ~(1L << b);
                    pendingCount--;
                    enqueue((w << 6) + b);
                }
            }
        }
    }

    /**
     * Érosion binaire par un carré de côté {@code 2 × radius + 1} ; l'extérieur de l'image compte comme du fond
     * (bit à 1). Séparable : minimum glissant en ligne puis en colonne, O(n) quel que soit le rayon. Mémoire : un
     * masque compacté temporaire et un compteur par colonne.
     */
    static void erodeInPlace(long[] mask, int rows, int cols, int radius) {
        long[] tmp = Bits.alloc(rows * cols);
        // Passe en ligne : tmp[r][c] = aucun « non-fond » dans [c - radius, c + radius].
        for (int r = 0; r < rows; r++) {
            int base = r * cols;
            int zeros = 0;
            for (int c = 0; c <= Math.min(radius, cols - 1); c++) {
                if (!Bits.get(mask, base + c)) zeros++;
            }
            for (int c = 0; c < cols; c++) {
                if (zeros == 0) Bits.set(tmp, base + c);
                int out = c - radius;
                if (out >= 0 && !Bits.get(mask, base + out)) zeros--;
                int in = c + radius + 1;
                if (in < cols && !Bits.get(mask, base + in)) zeros++;
            }
        }
        // Passe en colonne, ligne par ligne (accès mémoire séquentiels) : un compteur par colonne.
        int[] zeros = new int[cols];
        for (int r = 0; r <= Math.min(radius, rows - 1); r++) {
            int base = r * cols;
            for (int c = 0; c < cols; c++) {
                if (!Bits.get(tmp, base + c)) zeros[c]++;
            }
        }
        for (int r = 0; r < rows; r++) {
            int base = r * cols;
            for (int c = 0; c < cols; c++) {
                Bits.put(mask, base + c, zeros[c] == 0);
            }
            int out = r - radius;
            if (out >= 0) {
                int ob = out * cols;
                for (int c = 0; c < cols; c++) {
                    if (!Bits.get(tmp, ob + c)) zeros[c]--;
                }
            }
            int in = r + radius + 1;
            if (in < rows) {
                int ib = in * cols;
                for (int c = 0; c < cols; c++) {
                    if (!Bits.get(tmp, ib + c)) zeros[c]++;
                }
            }
        }
    }

    /** Adaptateur de test : même érosion sur un {@code boolean[]} (compacté, érodé, puis décompacté). */
    static void erodeInPlace(boolean[] mask, int rows, int cols, int radius) {
        long[] bits = Bits.alloc(mask.length);
        for (int i = 0; i < mask.length; i++) {
            if (mask[i]) Bits.set(bits, i);
        }
        erodeInPlace(bits, rows, cols, radius);
        for (int i = 0; i < mask.length; i++) {
            mask[i] = Bits.get(bits, i);
        }
    }

    /** Indices raster des blocs 8 × 8 complets dont les 64 pixels sont dans le masque. */
    static int[] fullBlocks(long[] mask, int rows, int cols) {
        int b = EmbeddingProfile.BLOCK;
        int blockRows = rows / b;
        int blockCols = cols / b;
        int[] out = new int[blockRows * blockCols];
        int n = 0;
        for (int br = 0; br < blockRows; br++) {
            for (int bc = 0; bc < blockCols; bc++) {
                if (blockInside(mask, cols, br * b, bc * b)) {
                    out[n++] = br * blockCols + bc;
                }
            }
        }
        return Arrays.copyOf(out, n);
    }

    private static boolean blockInside(long[] mask, int cols, int r0, int c0) {
        for (int dr = 0; dr < EmbeddingProfile.BLOCK; dr++) {
            int base = (r0 + dr) * cols + c0;
            for (int dc = 0; dc < EmbeddingProfile.BLOCK; dc++) {
                if (!Bits.get(mask, base + dc)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Garde : chaque pixel de la RONI est à au moins {@code GUARD_Q} niveaux de q du seuil, du côté du fond. */
    static boolean guardHolds(PixelImage image, RoniProfile profile) {
        char[] px = image.view();
        int n = image.pixelCount();
        for (int i = 0; i < n; i++) {
            if (profile.inRoniUnchecked(i)
                    && profile.polarity().guardDistance(px[i] >> EmbeddingProfile.Q_SHIFT, profile.threshold())
                    < EmbeddingProfile.GUARD_Q) {
                return false;
            }
        }
        return true;
    }
}
