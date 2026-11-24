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
 *   <li>composantes connexes du fond (4-connexité) qui touchent le bord, par un parcours en largeur itératif ;</li>
 *   <li>érosion de 8 pixels (carré 17 × 17, l'extérieur de l'image compte comme du fond), séparable en O(n) ;</li>
 *   <li>blocs 8 × 8 alignés sur (0, 0), entièrement dans le masque érodé ; blocs incomplets ignorés ;</li>
 *   <li>garde : chaque pixel de la RONI à au moins 4 niveaux de {@code q} du seuil, du côté du fond.</li>
 * </ol>
 */
public final class RoniDetector {
    private RoniDetector() {}

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
     */
    public static List<RoniProfile> candidates(PixelImage marked) {
        if (marked == null) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        int t0 = otsuThreshold(marked);
        int qMax = marked.maxValue() >> EmbeddingProfile.Q_SHIFT;
        List<RoniProfile> out = new ArrayList<>();
        for (int k = 0; k <= 2 * EmbeddingProfile.T_SEARCH; k++) {
            int offset = (k % 2 == 1) ? -((k + 1) / 2) : k / 2;
            int t = t0 + offset;
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

    /** Seuil d'Otsu sur l'histogramme de {@code q} (taille {@code (maxValue >> 2) + 1}). */
    static int otsuThreshold(PixelImage image) {
        int[] px = image.view();
        long[] hist = new long[(image.maxValue() >> EmbeddingProfile.Q_SHIFT) + 1];
        for (int v : px) {
            hist[v >> EmbeddingProfile.Q_SHIFT]++;
        }
        return Otsu.threshold(hist);
    }

    /** Étapes 2 à 6 pour un seuil donné, sans aucun refus. */
    static RoniProfile compute(PixelImage image, int threshold) {
        int rows = image.rows();
        int cols = image.cols();
        int[] px = image.view();
        BackgroundPolarity polarity = polarity(px, rows, cols, threshold);
        boolean[] mask = borderConnectedBackground(px, rows, cols, threshold, polarity);
        erodeInPlace(mask, rows, cols, EmbeddingProfile.EROSION_PX);
        return new RoniProfile(rows, cols, threshold, polarity, fullBlocks(mask, rows, cols));
    }

    /** Majorité des pixels du bord (chaque pixel compté une fois) ; égalité : fond sombre. */
    static BackgroundPolarity polarity(int[] px, int rows, int cols, int threshold) {
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

    /** Fond connexe au bord (4-connexité), parcours en largeur itératif (aucune récursion). */
    static boolean[] borderConnectedBackground(int[] px, int rows, int cols, int threshold,
                                               BackgroundPolarity polarity) {
        boolean[] seen = new boolean[rows * cols];
        IntQueue queue = new IntQueue(4 * (rows + cols));
        for (int i : borderIndices(rows, cols)) {
            if (!seen[i] && polarity.isBackground(px[i] >> EmbeddingProfile.Q_SHIFT, threshold)) {
                seen[i] = true;
                queue.add(i);
            }
        }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int r = i / cols;
            int c = i - r * cols;
            if (r > 0) visit(i - cols, px, seen, queue, threshold, polarity);
            if (r < rows - 1) visit(i + cols, px, seen, queue, threshold, polarity);
            if (c > 0) visit(i - 1, px, seen, queue, threshold, polarity);
            if (c < cols - 1) visit(i + 1, px, seen, queue, threshold, polarity);
        }
        return seen;
    }

    private static void visit(int j, int[] px, boolean[] seen, IntQueue queue, int threshold,
                              BackgroundPolarity polarity) {
        if (!seen[j] && polarity.isBackground(px[j] >> EmbeddingProfile.Q_SHIFT, threshold)) {
            seen[j] = true;
            queue.add(j);
        }
    }

    /**
     * Érosion binaire par un carré de côté {@code 2 × radius + 1} ; l'extérieur de l'image compte comme du fond
     * ({@code true}). Séparable : minimum glissant en ligne puis en colonne, O(n) quel que soit le rayon.
     */
    static void erodeInPlace(boolean[] mask, int rows, int cols, int radius) {
        boolean[] tmp = new boolean[mask.length];
        // Passe en ligne : tmp[r][c] = aucun « non-fond » dans [c - radius, c + radius].
        for (int r = 0; r < rows; r++) {
            int base = r * cols;
            int zeros = 0;
            for (int c = 0; c <= Math.min(radius, cols - 1); c++) {
                if (!mask[base + c]) zeros++;
            }
            for (int c = 0; c < cols; c++) {
                tmp[base + c] = zeros == 0;
                int out = c - radius;
                if (out >= 0 && !mask[base + out]) zeros--;
                int in = c + radius + 1;
                if (in < cols && !mask[base + in]) zeros++;
            }
        }
        // Passe en colonne, ligne par ligne (accès mémoire séquentiels) : un compteur par colonne.
        int[] zeros = new int[cols];
        for (int r = 0; r <= Math.min(radius, rows - 1); r++) {
            int base = r * cols;
            for (int c = 0; c < cols; c++) {
                if (!tmp[base + c]) zeros[c]++;
            }
        }
        for (int r = 0; r < rows; r++) {
            int base = r * cols;
            for (int c = 0; c < cols; c++) {
                mask[base + c] = zeros[c] == 0;
            }
            int out = r - radius;
            if (out >= 0) {
                int ob = out * cols;
                for (int c = 0; c < cols; c++) {
                    if (!tmp[ob + c]) zeros[c]--;
                }
            }
            int in = r + radius + 1;
            if (in < rows) {
                int ib = in * cols;
                for (int c = 0; c < cols; c++) {
                    if (!tmp[ib + c]) zeros[c]++;
                }
            }
        }
    }

    /** Indices raster des blocs 8 × 8 complets dont les 64 pixels sont dans le masque. */
    static int[] fullBlocks(boolean[] mask, int rows, int cols) {
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

    private static boolean blockInside(boolean[] mask, int cols, int r0, int c0) {
        for (int dr = 0; dr < EmbeddingProfile.BLOCK; dr++) {
            int base = (r0 + dr) * cols + c0;
            for (int dc = 0; dc < EmbeddingProfile.BLOCK; dc++) {
                if (!mask[base + dc]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Garde : chaque pixel de la RONI est à au moins {@code GUARD_Q} niveaux de q du seuil, du côté du fond. */
    static boolean guardHolds(PixelImage image, RoniProfile profile) {
        int[] px = image.view();
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

    /** File d'entiers circulaire qui grandit à la demande (la frontière d'un parcours en largeur reste petite). */
    private static final class IntQueue {
        private int[] data;
        private int head;
        private int size;

        IntQueue(int initialCapacity) {
            data = new int[Math.max(16, initialCapacity)];
        }

        boolean isEmpty() { return size == 0; }

        void add(int v) {
            if (size == data.length) {
                int[] bigger = new int[data.length * 2];
                for (int k = 0; k < size; k++) {
                    bigger[k] = data[(head + k) % data.length];
                }
                data = bigger;
                head = 0;
            }
            data[(head + size) % data.length] = v;
            size++;
        }

        int poll() {
            int v = data[head];
            head = (head + 1) % data.length;
            size--;
            return v;
        }
    }
}
