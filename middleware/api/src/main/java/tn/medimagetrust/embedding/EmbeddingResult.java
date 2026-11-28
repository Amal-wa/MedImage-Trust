package tn.medimagetrust.embedding;

import java.util.Objects;
import java.util.Optional;

import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;

/**
 * Résultat d'une émission réussie ({@link EmbeddingSession#finish()}) : la copie marquée et ce que le registre
 * d'émission et le module dicom doivent en retenir.
 *
 * <ul>
 *   <li>{@link #bitsStored()} : profondeur effective B de l'original, qui fixe Δ(B). Elle est enregistrée dans le
 *       registre d'émission et redonnée à l'analyse ({@link FBlockReader#of(PixelImage, Partitions, int)}) : Δ n'est
 *       jamais déduit d'une copie analysée.</li>
 *   <li>{@link #signedOutput()} : la couche F sortait de la plage stockable de l'original ; la copie est en entier
 *       signé 16 bits, sans écrêtage, et {@link #pixelAttributes()} donne les attributs à appliquer par le module
 *       dicom. En mode non signé, aucun attribut d'interprétation ne change.</li>
 * </ul>
 *
 * @param image        copie marquée (en mode signé, {@code image.signed()} est vrai et {@code image.bitsStored()} vaut
 *                     toujours B)
 * @param bitsStored   profondeur effective B de l'original, de 8 à 16
 * @param signedOutput copie produite en mode signé
 */
public record EmbeddingResult(PixelImage image, int bitsStored, boolean signedOutput) {

    /**
     * Attributs DICOM du module Image Pixel à appliquer à une copie signée. Tous les autres attributs
     * d'interprétation restent inchangés (SEC-M03 c ; seule dérogation documentée, voir api/docs/embedding.md).
     *
     * @param pixelRepresentation {@code PixelRepresentation} (1 : entier signé)
     * @param bitsAllocated       {@code BitsAllocated} (16) : un original 8 bits stocké sur 8 bits ne peut pas porter
     *                            un entier signé 16 bits sans changer la taille de l'échantillon
     * @param bitsStored          {@code BitsStored} (16)
     * @param highBit             {@code HighBit} (15)
     */
    public record PixelAttributes(int pixelRepresentation, int bitsAllocated, int bitsStored, int highBit) {}

    /**
     * Attributs d'une copie en mode signé : {@code PixelRepresentation = 1}, {@code BitsAllocated = 16},
     * {@code BitsStored = 16}, {@code HighBit = 15}.
     */
    public static final PixelAttributes SIGNED_ATTRIBUTES = new PixelAttributes(1, 16, 16, 15);

    /**
     * @throws EmbeddingException {@code INVALID_INPUT} si l'image manque, si B est hors de {@code [8, 16]} ou si
     *                            l'indicateur ne correspond pas à la représentation de l'image
     */
    public EmbeddingResult {
        Objects.requireNonNull(image, "image");
        EmbeddingProfile.depthScale(bitsStored);
        if (image.bitsStored() != bitsStored || image.signed() != signedOutput) {
            throw new EmbeddingException(INVALID_INPUT);
        }
    }

    /** Pas de la QIM utilisé pour cette émission : Δ(B). */
    public int qimDelta() {
        return EmbeddingProfile.qimDelta(bitsStored);
    }

    /** Attributs à appliquer par le module dicom : présents seulement en mode signé. */
    public Optional<PixelAttributes> pixelAttributes() {
        return signedOutput ? Optional.of(SIGNED_ATTRIBUTES) : Optional.empty();
    }
}
