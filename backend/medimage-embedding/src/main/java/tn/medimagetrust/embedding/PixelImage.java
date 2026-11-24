package tn.medimagetrust.embedding;

import java.util.Arrays;

import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;

/**
 * Image monochrome immuable : valeurs stockées non signées, en ordre raster.
 *
 * <p>Le constructeur appelle d'abord {@link EmbeddingLimits#checkDimensions(int, int, int)} (aucune allocation avant),
 * puis copie le tableau et valide la copie (pas de modification possible entre le contrôle et l'usage). L'accesseur
 * {@link #pixels()} renvoie une copie : aucun tableau interne n'est exposé.</p>
 *
 * @param rows               nombre de lignes (&gt; 0)
 * @param cols               nombre de colonnes (&gt; 0)
 * @param bitsStored         profondeur, de 8 à 16
 * @param photometric        {@code MONOCHROME1} ou {@code MONOCHROME2}
 * @param pixels             valeurs stockées, longueur {@code rows × cols}, chacune dans {@code [0, 2^bitsStored - 1]}
 * @param burnedInAnnotation {@code BurnedInAnnotation = YES}
 * @param overlayPresent     un overlay (groupes 60xx) est présent
 */
public record PixelImage(int rows, int cols, int bitsStored, Photometric photometric, int[] pixels,
                         boolean burnedInAnnotation, boolean overlayPresent) {

    /** Valide puis copie les pixels. */
    public PixelImage {
        EmbeddingLimits.checkDimensions(rows, cols, bitsStored);
        if (photometric == null || pixels == null) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        int[] copy = pixels.clone();
        if (copy.length != rows * cols) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        int max = (1 << bitsStored) - 1;
        for (int v : copy) {
            if (v < 0 || v > max) {
                throw new EmbeddingException(INVALID_INPUT);
            }
        }
        pixels = copy;
    }

    /** Copie des valeurs stockées. */
    @Override
    public int[] pixels() {
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
    int[] view() {
        return pixels;
    }

    /** Même image (mêmes attributs), autres pixels ; le tableau est copié et validé par le constructeur. */
    PixelImage withPixels(int[] newPixels) {
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
