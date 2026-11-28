package tn.medimagetrust.embedding;

import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;

/**
 * Image monochrome immuable : valeurs stockées sur 16 bits ({@code char}), en ordre raster.
 *
 * <p>Stockage en {@code char[]} (2 octets par pixel, couvre {@code bitsStored} de 8 à 16) : 72 Mo pour 36 Mpx au lieu
 * de 144 Mo en {@code int[]} (budget mémoire du dossier v2.4, voir api/docs/embedding.md, « Bilan mémoire »).</p>
 *
 * <p><strong>Représentation.</strong> Une image non signée ({@link #signed()} faux) contient des valeurs dans
 * {@code [0, 2^bitsStored − 1]}. Une copie marquée en mode signé ({@link #signed()} vrai, {@code PixelRepresentation =
 * 1}) contient dans le même {@code char[]} la représentation en complément à deux sur 16 bits de valeurs dans
 * {@code [−32 768, 32 767]}. Les conversions entre les deux ({@link #encode(int, boolean)},
 * {@link #decode(char, boolean)}) ne sont faites qu'ici : tout autre code lit les valeurs par {@link #pixel(int)} ou,
 * dans le paquet, par {@link #valueAt(int)}.</p>
 *
 * <p>{@code bitsStored} est toujours la profondeur effective B de l'original, même pour une copie signée (dont les
 * attributs DICOM portent {@code BitsStored = 16}, voir {@link EmbeddingResult}) : le condensat de la ROI, qui inclut
 * {@code bitsStored}, reste celui de l'émission.</p>
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
    /** Plus petite valeur d'une copie signée (entier signé 16 bits). */
    static final int SIGNED_MIN = Short.MIN_VALUE;
    /** Plus grande valeur d'une copie signée (entier signé 16 bits). */
    static final int SIGNED_MAX = Short.MAX_VALUE;

    private final int rows;
    private final int cols;
    private final int bitsStored;
    private final Photometric photometric;
    private final char[] pixels;
    private final boolean burnedInAnnotation;
    private final boolean overlayPresent;
    private final boolean signed;
    /** {@code PixelPaddingValue} déclaré par l'original, ou {@code null}. */
    private final Integer pixelPaddingValue;

    /**
     * Image non signée.
     *
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
                burnedInAnnotation, overlayPresent, false, null);
    }

    /** Constructeur interne : {@code owned} est déjà validé et n'appartient qu'à cette image (ou à une image sœur). */
    private PixelImage(int rows, int cols, int bitsStored, Photometric photometric, char[] owned,
                       boolean burnedInAnnotation, boolean overlayPresent, boolean signed, Integer pixelPaddingValue) {
        this.rows = rows;
        this.cols = cols;
        this.bitsStored = bitsStored;
        this.photometric = photometric;
        this.pixels = owned;
        this.burnedInAnnotation = burnedInAnnotation;
        this.overlayPresent = overlayPresent;
        this.signed = signed;
        this.pixelPaddingValue = pixelPaddingValue;
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
        return new PixelImage(rows, cols, bitsStored, photometric, owned, burnedInAnnotation, overlayPresent, false,
                null);
    }

    /**
     * Copie signée relue ({@code PixelRepresentation = 1}), par exemple par le module dicom.
     *
     * @param bitsStored profondeur effective B de l'émission, lue dans le registre (jamais dans la copie)
     * @param values     valeurs signées sur 16 bits, longueur {@code rows × cols} ; le tableau est recopié
     * @throws EmbeddingException {@code IMAGE_TOO_LARGE} ou {@code INVALID_INPUT}
     */
    public static PixelImage signedFromShorts(int rows, int cols, int bitsStored, Photometric photometric,
                                              short[] values, boolean burnedInAnnotation, boolean overlayPresent) {
        EmbeddingLimits.checkDimensions(rows, cols, bitsStored);
        if (photometric == null || values == null || values.length != rows * cols) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        char[] owned = new char[values.length];
        for (int i = 0; i < values.length; i++) {
            owned[i] = encode(values[i], true);
        }
        return new PixelImage(rows, cols, bitsStored, photometric, owned, burnedInAnnotation, overlayPresent, true,
                null);
    }

    /**
     * Même image, qui déclare {@code PixelPaddingValue = value} (valeur de remplissage, hors image utile). Les pixels
     * ne sont pas recopiés : les deux images, immuables, partagent le même tableau.
     *
     * @throws EmbeddingException {@code INVALID_INPUT} si la valeur n'est pas représentable dans cette image
     */
    public PixelImage withPixelPaddingValue(int value) {
        if (value < storableMin() || value > storableMax()) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        return new PixelImage(rows, cols, bitsStored, photometric, pixels, burnedInAnnotation, overlayPresent, signed,
                value);
    }

    /**
     * Image non signée de mêmes attributs que {@code like}, qui <strong>prend possession</strong> de {@code owned}
     * sans le copier (réservé à la session : sa copie de travail devient l'image marquée). L'appelant ne doit plus
     * jamais écrire dans {@code owned}. Les valeurs sont revalidées.
     */
    static PixelImage adopt(PixelImage like, char[] owned) {
        if (owned == null || owned.length != like.pixels.length) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        checkValues(owned, like.bitsStored);
        return new PixelImage(like.rows, like.cols, like.bitsStored, like.photometric, owned,
                like.burnedInAnnotation, like.overlayPresent, false, like.pixelPaddingValue);
    }

    /**
     * Comme {@link #adopt(PixelImage, char[])}, mais pour une copie en mode signé : {@code owned} contient la
     * représentation en complément à deux ; toute valeur sur 16 bits est valide. {@code bitsStored} reste celui de
     * {@code like} (profondeur effective de l'original).
     */
    static PixelImage adoptSigned(PixelImage like, char[] owned) {
        if (owned == null || owned.length != like.pixels.length) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        return new PixelImage(like.rows, like.cols, like.bitsStored, like.photometric, owned,
                like.burnedInAnnotation, like.overlayPresent, true, like.pixelPaddingValue);
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

    // ---------------------------------------------------------------------- représentation (seul point de conversion)

    /** Valeur d'un mot stocké : non signée, ou complément à deux sur 16 bits si {@code signed}. */
    static int decode(char stored, boolean signed) {
        return signed ? (short) stored : stored;
    }

    /**
     * Mot stocké d'une valeur : {@code (char) (short) v} en mode signé ({@code v} dans {@code [−32 768, 32 767]}),
     * {@code (char) v} sinon ({@code v} dans {@code [0, 65 535]}). L'appelant a vérifié la plage.
     */
    static char encode(int value, boolean signed) {
        return signed ? (char) (short) value : (char) value;
    }

    // ---------------------------------------------------------------------- accesseurs

    /** Nombre de lignes. */
    public int rows() { return rows; }

    /** Nombre de colonnes. */
    public int cols() { return cols; }

    /** Profondeur effective B ({@code BitsStored} de l'original, même pour une copie signée). */
    public int bitsStored() { return bitsStored; }

    /** Interprétation photométrique. */
    public Photometric photometric() { return photometric; }

    /** {@code BurnedInAnnotation = YES}. */
    public boolean burnedInAnnotation() { return burnedInAnnotation; }

    /** Overlay présent. */
    public boolean overlayPresent() { return overlayPresent; }

    /** {@code true} pour une copie en mode signé ({@code PixelRepresentation = 1}). */
    public boolean signed() { return signed; }

    /** {@code PixelPaddingValue} déclaré, s'il y en a un. */
    public OptionalInt pixelPaddingValue() {
        return pixelPaddingValue == null ? OptionalInt.empty() : OptionalInt.of(pixelPaddingValue);
    }

    /**
     * Copie des mots stockés, tels qu'ils seront écrits dans le fichier : valeurs non signées, ou complément à deux
     * sur 16 bits pour une copie signée. Pour lire une valeur, utiliser {@link #pixel(int)}.
     */
    public char[] pixels() {
        return pixels.clone();
    }

    /** {@code 2^bitsStored - 1} : maximum de la plage stockable de l'original (référence du PSNR). */
    public int maxValue() {
        return (1 << bitsStored) - 1;
    }

    /** Plus petite valeur représentable dans cette image : 0, ou −32 768 en mode signé. */
    int storableMin() {
        return signed ? SIGNED_MIN : 0;
    }

    /** Plus grande valeur représentable dans cette image : {@code 2^bitsStored − 1}, ou 32 767 en mode signé. */
    int storableMax() {
        return signed ? SIGNED_MAX : maxValue();
    }

    /** Nombre de pixels. */
    public int pixelCount() {
        return rows * cols;
    }

    /** Valeur (signée en mode signé) du pixel d'indice raster {@code index}. */
    public int pixel(int index) {
        if (index < 0 || index >= pixels.length) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        return valueAt(index);
    }

    /** Valeur (signée en mode signé) du pixel ({@code row}, {@code col}). */
    public int pixel(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        return valueAt(row * cols + col);
    }

    /** Valeur du pixel d'indice {@code index}, sans contrôle de bornes (boucles internes du paquet). */
    int valueAt(int index) {
        return decode(pixels[index], signed);
    }

    /**
     * Vue directe, sans copie, réservée au paquet. Les appelants internes ne doivent <strong>jamais</strong> écrire
     * dans ce tableau, et lisent les valeurs par {@link #decode(char, boolean)} avec {@link #signed()}.
     */
    char[] view() {
        return pixels;
    }

    /** Même image (mêmes attributs), autres mots stockés ; le tableau est copié (et validé si l'image est non signée). */
    PixelImage withPixels(char[] newPixels) {
        if (!signed) {
            return new PixelImage(rows, cols, bitsStored, photometric, checkedCopy(rows, cols, bitsStored, photometric,
                    newPixels), burnedInAnnotation, overlayPresent, false, pixelPaddingValue);
        }
        if (newPixels == null || newPixels.length != pixels.length) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        return new PixelImage(rows, cols, bitsStored, photometric, newPixels.clone(), burnedInAnnotation,
                overlayPresent, true, pixelPaddingValue);
    }

    /** Comme {@link #withPixels(char[])}, avec la représentation choisie (tests de stabilité du masque). */
    PixelImage withPixels(char[] newPixels, boolean signedRepresentation) {
        if (signedRepresentation == signed) {
            return withPixels(newPixels);
        }
        PixelImage base = new PixelImage(rows, cols, bitsStored, photometric, pixels, burnedInAnnotation,
                overlayPresent, signedRepresentation, pixelPaddingValue);
        return base.withPixels(newPixels);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PixelImage p && rows == p.rows && cols == p.cols && bitsStored == p.bitsStored
                && photometric == p.photometric && burnedInAnnotation == p.burnedInAnnotation
                && overlayPresent == p.overlayPresent && signed == p.signed
                && Objects.equals(pixelPaddingValue, p.pixelPaddingValue) && Arrays.equals(pixels, p.pixels);
    }

    @Override
    public int hashCode() {
        int h = Integer.hashCode(rows);
        h = 31 * h + Integer.hashCode(cols);
        h = 31 * h + Integer.hashCode(bitsStored);
        h = 31 * h + photometric.hashCode();
        h = 31 * h + Boolean.hashCode(burnedInAnnotation);
        h = 31 * h + Boolean.hashCode(overlayPresent);
        h = 31 * h + Boolean.hashCode(signed);
        h = 31 * h + Objects.hashCode(pixelPaddingValue);
        return 31 * h + Arrays.hashCode(pixels);
    }

    /** Aucune valeur de l'image n'est imprimée (ni dimension ni pixel). */
    @Override
    public String toString() {
        return "PixelImage[contenu masqué]";
    }
}
