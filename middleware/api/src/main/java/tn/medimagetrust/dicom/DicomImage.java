package tn.medimagetrust.dicom;

import java.util.Optional;
import java.util.OptionalInt;
import org.dcm4che3.data.Attributes;
import tn.medimagetrust.embedding.Photometric;
import tn.medimagetrust.embedding.PixelImage;

/**
 * Fichier DICOM accepté par {@link DicomReader} : attributs utiles et pixels.
 *
 * <p><strong>Pixels.</strong> {@link #image()} est l'entrée de l'embedding, toujours non signée : valeur stockée
 * {@code v} pour {@code PixelRepresentation = 0}, {@code v + 2^(B − 1)} pour {@code PixelRepresentation = 1}
 * ({@link #storedValueOffset()}). Le décalage est exact et réversible : le masque, les condensats et Δ(B) portent sur
 * les mêmes rapports de valeurs, et {@link DicomWriter} le retire à l'écriture. Le {@code PixelPaddingValue} est passé
 * à l'embedding avec le même décalage.</p>
 *
 * <p>Les valeurs exposées viennent du fichier : elles sont non fiables, à ne jamais exécuter ni concaténer dans une
 * requête, un chemin ou un message (SEC-D02, SEC-F01). Les attributs dcm4che restent internes au package.</p>
 */
public final class DicomImage {
    private final Attributes fileMetaInformation;
    private final Attributes dataset;
    private final String transferSyntaxUid;
    private final String modality;
    private final int bitsAllocated;
    private final int highBit;
    private final int pixelRepresentation;
    private final Integer pixelPaddingValue;
    private final Integer pixelPaddingRangeLimit;
    private final Markability markability;
    private final PixelImage image;

    DicomImage(Attributes fileMetaInformation, Attributes dataset, String transferSyntaxUid, String modality,
               int bitsAllocated, int highBit, int pixelRepresentation, Integer pixelPaddingValue,
               Integer pixelPaddingRangeLimit, Markability markability, PixelImage image) {
        this.fileMetaInformation = fileMetaInformation;
        this.dataset = dataset;
        this.transferSyntaxUid = transferSyntaxUid;
        this.modality = modality;
        this.bitsAllocated = bitsAllocated;
        this.highBit = highBit;
        this.pixelRepresentation = pixelRepresentation;
        this.pixelPaddingValue = pixelPaddingValue;
        this.pixelPaddingRangeLimit = pixelPaddingRangeLimit;
        this.markability = markability;
        this.image = image;
    }

    /** Syntaxe de transfert du fichier lu (l'une des deux acceptées). */
    public String transferSyntaxUid() { return transferSyntaxUid; }

    /** Modalité (l'une des modalités acceptées). */
    public String modality() { return modality; }

    /** Lignes. */
    public int rows() { return image.rows(); }

    /** Colonnes. */
    public int cols() { return image.cols(); }

    /** {@code BitsAllocated} (8 ou 16). */
    public int bitsAllocated() { return bitsAllocated; }

    /** {@code BitsStored} : profondeur effective B de l'original. */
    public int bitsStored() { return image.bitsStored(); }

    /** {@code HighBit} (toujours {@code BitsStored − 1}). */
    public int highBit() { return highBit; }

    /** {@code PixelRepresentation} (0 non signé, 1 signé). */
    public int pixelRepresentation() { return pixelRepresentation; }

    /** {@code PhotometricInterpretation}. */
    public Photometric photometric() { return image.photometric(); }

    /** {@code RescaleIntercept}, tel que déclaré (chaîne DS), s'il est présent. */
    public Optional<String> rescaleIntercept() {
        return Optional.ofNullable(dataset.getString(org.dcm4che3.data.Tag.RescaleIntercept));
    }

    /** {@code PixelPaddingValue} déclaré, en valeur stockée (signée si {@code PixelRepresentation = 1}). */
    public OptionalInt pixelPaddingValue() {
        return pixelPaddingValue == null ? OptionalInt.empty() : OptionalInt.of(pixelPaddingValue);
    }

    /** {@code PixelPaddingRangeLimit} déclaré, en valeur stockée. */
    public OptionalInt pixelPaddingRangeLimit() {
        return pixelPaddingRangeLimit == null ? OptionalInt.empty() : OptionalInt.of(pixelPaddingRangeLimit);
    }

    /** {@code SOPInstanceUID} du fichier lu. */
    public String sopInstanceUid() { return dataset.getString(org.dcm4che3.data.Tag.SOPInstanceUID); }

    /** Marquable, ou motif de non-marquabilité (SEC-M04). */
    public Markability markability() { return markability; }

    /** Décalage ajouté aux valeurs stockées pour l'embedding : {@code 2^(B − 1)} si signé, 0 sinon. */
    public int storedValueOffset() {
        return pixelRepresentation == 1 ? 1 << (image.bitsStored() - 1) : 0;
    }

    /** Image non signée donnée à l'embedding (voir la description de la classe). */
    public PixelImage image() { return image; }

    /**
     * Copie signée relue ({@code PixelRepresentation = 1}, {@code BitsStored = 16}, produite en mode signé) sous la
     * forme attendue par l'embedding pour la vérification : {@code PixelImage} signée de profondeur B, dans le domaine
     * de valeurs de l'émission. B et la représentation de l'original sont lus dans le registre, jamais dans la copie :
     * un original signé a été marqué avec le décalage {@code 2^(B − 1)}, qui est rajouté ici. Alloue un nouveau
     * tableau (chemin de vérification seulement).
     *
     * @param registryBitsStored           profondeur effective B de l'émission, lue dans le registre
     * @param registryPixelRepresentation  {@code PixelRepresentation} de l'original (0 ou 1), lu dans le registre
     * @throws IllegalStateException    si le fichier n'est pas une copie en mode signé
     * @throws IllegalArgumentException représentation hors de {0, 1}
     */
    public PixelImage signedCopyImage(int registryBitsStored, int registryPixelRepresentation) {
        if (pixelRepresentation != 1 || image.bitsStored() != 16) {
            throw new IllegalStateException("copie en mode signé attendue");
        }
        if (registryPixelRepresentation != 0 && registryPixelRepresentation != 1) {
            throw new IllegalArgumentException("représentation hors limites");
        }
        int toStored = storedValueOffset();
        int toEmbedding = registryPixelRepresentation == 1 ? 1 << (registryBitsStored - 1) : 0;
        return PixelImage.filled(image.rows(), image.cols(), registryBitsStored, image.photometric(), true,
                image.burnedInAnnotation(), image.overlayPresent(), w -> {
                    for (int i = 0; i < w.length(); i++) w.put(i, image.pixel(i) - toStored + toEmbedding);
                });
    }

    /** En-tête analysé (sans les données de pixels), pour l'écriture de la copie ; ne jamais exposer hors du package. */
    Attributes dataset() { return dataset; }

    /** Méta-informations du fichier lu. */
    Attributes fileMetaInformation() { return fileMetaInformation; }

    /** Aucune valeur du fichier n'est imprimée. */
    @Override
    public String toString() {
        return "DicomImage[contenu masqué]";
    }
}
