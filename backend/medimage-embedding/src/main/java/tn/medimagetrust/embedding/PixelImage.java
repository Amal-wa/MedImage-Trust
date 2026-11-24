package tn.medimagetrust.embedding;

import java.util.Arrays;

import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;

/**
 * Image monochrome immuable : valeurs stockées non signées sur 16 bits ({@code char}), en ordre raster.
 *
 * <p>Stockage en {@code char[]} (2 octets par pixel, couvre {@code bitsStored} de 8 à 16) : 72 Mo pour 36 Mpx au lieu
 * de 144 Mo en {@code int[]} (budget mémoire du dossier v2.4, voir README, « Bilan mémoire »).</p>
 *
 * <p>Le constructeur appelle d'abord {@link EmbeddingLimits#checkDimensions(int, int, int)} (aucune allocation avant),
 * puis copie le tableau et valide la copie (pas de modification possible entre le contrôle et l'usage). L'accesseur
 * {@link #pixels()} renvoie une copie : aucun tableau interne n'est exposé.</p>
 *
 * <p>Classe finale plutôt que {@code record} : un record recopie toujours son tableau dans le constructeur canonique,
 * alors que la session doit pouvoir confier sa copie de travail à l'image marquée sans troisième copie
 * ({@link #adopt(PixelImage, char[])}, réservé au paquet).</p>
 */
public final class PixelImage {
    private final int rows;
    private final int cols;
    private final int bitsStored;
    private final Photometric photometric;
    private final char[] pixels;
    private final boolean burnedInAnnotation;
    private final boolean overlayPresent;

    /**
     * @param rows               nombre de lignes (&gt; 0)
     * @param cols               nombre de colonnes (&gt; 0)
     * @param bitsStored         profondeur, de 8 à 16
     * @param photometric        {@code MONOCHROME1} ou {@code MONOCHROME2}
     * @param pixels             valeurs stockées, longueur {@code rows × cols}, chacune dans {@code [0, 2^bitsStored - 1]}
     *                           ; le tableau est copié
     * @param burnedInAnnotation {@code BurnedInAnnotation = YES}
     * @param overlayPresent     un overlay (groupes 60xx) est présent
     * @throws EmbeddingException {@code IMAGE_TOO_LARGE} ou {@code INVALID_INPUT}
     */
    public PixelImage(int rows, int cols, int bitsStored, Photometric photometric, char[] pixels,
                      boolean burnedInAnnotation, boolean overlayPresent) {
        this(rows, cols, bitsStored, photometric, checkedCopy(rows, cols, bitsStored, photometric, pixels),
                burnedInAnnotation, overlayPresent, true);
    }

    /** Constructeur interne : {@code owned} est déjà validé et n'appartient qu'à cette image. */
    private PixelImage(int rows, int cols, int bitsStored, Photometric photometric, char[] owned,
                       boolean burnedInAnnotation, boolean overlayPresent, boolean validated) {
        this.rows = rows;
        this.cols = cols;
        this.bitsStored = bitsStored;
        this.photometric = photometric;
        this.pixels = owned;
        this.burnedInAnnotation = burnedInAnnotation;
        this.overlayPresent = overlayPresent;
    }

    /**
     * Fabrique à partir de valeurs {@code int} (tests, petits appels) : contrôle des dimensions d'abord, puis chaque
     * valeur doit être dans {@code [0, maxValue]}, sinon {@code INVALID_INPUT}.
     */
    public static PixelImage fromInts(int rows, int cols, int bitsStored, Photometric photometric, int[] pixels,
                                      boolean burnedInAnnotation, boolean overlayPresent) {
        EmbeddingLimits.checkDimensions(rows, cols, bitsStored);
        if (photometric == null || pixels == null || pixels.length != rows * cols) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        int max = (1 << bitsStored) - 1;
        char[] owned = new char[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int v = pixels[i];
            if (v < 0 || v > max) {
                throw new EmbeddingException(INVALID_INPUT);
            }
            owned[i] = (char) v;
        }
        return new PixelImage(rows, cols, bitsStored, photometric, owned, burnedInAnnotation, overlayPresent, true);
    }

    /**
     * Image de mêmes attributs que {@code like}, qui <strong>prend possession</strong> de {@code owned} sans le
     * copier (réservé à la session : sa copie de travail devient l'image marquée). L'appelant ne doit plus jamais
     * écrire dans {@code owned}. Les valeurs sont revalidées.
     */
    static PixelImage adopt(PixelImage like, char[] owned) {
        if (owned == null || owned.length != like.pixels.length) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        checkValues(owned, like.bitsStored);
        return new PixelImage(like.rows, like.cols, like.bitsStored, like.photometric, owned,
                like.burnedInAnnotation, like.overlayPresent, true);
    }

    private static char[] checkedCopy(int rows, int cols, int bitsStored, Photometric photometric, char[] pixels) {
        EmbeddingLimits.checkDimensions(rows, cols, bitsStored);
        if (photometric == null || pixels == null) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        char[] copy = pixels.clone();
        if (copy.length != rows * cols) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        checkValues(copy, bitsStored);
        return copy;
    }

    private static void checkValues(char[] values, int bitsStored) {
        if (bitsStored >= 16) {
            return; // un char couvre exactement [0, 65 535]
        }
        int max = (1 << bitsStored) - 1;
        for (char v : values) {
            if (v > max) {
                throw new EmbeddingException(INVALID_INPUT);
            }
        }
    }

    /** Nombre de lignes. */
    public int rows() { return rows; }

    /** Nombre de colonnes. */
    public int cols() { return cols; }

    /** Profondeur ({@code BitsStored}). */
    public int bitsStored() { return bitsStored; }

    /** Interprétation photométrique. */
    public Photometric photometric() { return photometric; }

    /** {@code BurnedInAnnotation = YES}. */
    public boolean burnedInAnnotation() { return burnedInAnnotation; }

    /** Overlay présent. */
    public boolean overlayPresent() { return overlayPresent; }

    /** Copie des valeurs stockées. */
    public char[] pixels() {
        return pixels.clone();
    }

    /** {@code 2^bitsStored - 1}. */
    public int maxValue() {
        return (1 << bitsStored) - 1;
    }

    /** Nombre de pixels. */
    public int pixelCount() {
        return rows * cols;
    }

    /** Valeur stockée du pixel d'indice raster {@code index}. */
    public int pixel(int index) {
        if (index < 0 || index >= pixels.length) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        return pixels[index];
    }

    /** Valeur stockée du pixel ({@code row}, {@code col}). */
    public int pixel(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        return pixels[row * cols + col];
    }

    /**
     * Vue directe, sans copie, réservée au paquet. Les appelants internes ne doivent <strong>jamais</strong> écrire
     * dans ce tableau.
     */
    char[] view() {
        return pixels;
    }

    /** Même image (mêmes attributs), autres pixels ; le tableau est copié et validé. */
    PixelImage withPixels(char[] newPixels) {
        return new PixelImage(rows, cols, bitsStored, photometric, newPixels, burnedInAnnotation, overlayPresent);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PixelImage p && rows == p.rows && cols == p.cols && bitsStored == p.bitsStored
                && photometric == p.photometric && burnedInAnnotation == p.burnedInAnnotation
                && overlayPresent == p.overlayPresent && Arrays.equals(pixels, p.pixels);
    }

    @Override
    public int hashCode() {
        int h = Integer.hashCode(rows);
        h = 31 * h + Integer.hashCode(cols);
        h = 31 * h + Integer.hashCode(bitsStored);
        h = 31 * h + photometric.hashCode();
        h = 31 * h + Boolean.hashCode(burnedInAnnotation);
        h = 31 * h + Boolean.hashCode(overlayPresent);
        return 31 * h + Arrays.hashCode(pixels);
    }

    /** Aucune valeur de l'image n'est imprimée (ni dimension ni pixel). */
    @Override
    public String toString() {
        return "PixelImage[contenu masqué]";
    }
}
