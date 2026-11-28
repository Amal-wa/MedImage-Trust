package tn.medimagetrust.dicom;

/**
 * Statut d'un fichier lisible : marquable, ou lisible mais non marquable avec un motif (SEC-M04). Distinct d'un rejet
 * ({@link DicomRejectedException}) : l'image est valide, mais aucune copie marquée ne peut en être produite.
 */
public enum Markability {
    /** Marquable. */
    MARKABLE,
    /** {@code BurnedInAnnotation = YES} : texte incrusté dans les pixels. */
    BURNED_IN_ANNOTATION,
    /** Overlay présent (groupes 60xx). */
    OVERLAY_PRESENT,
    /** Courbe présente (groupes 50xx, attributs retirés de la norme). */
    CURVE_PRESENT,
    /**
     * {@code PixelPaddingRangeLimit} déclaré : l'embedding n'exclut qu'une valeur de remplissage, pas une plage. Les
     * pixels de la plage pourraient être modifiés : marquage refusé.
     */
    PADDING_RANGE_UNSUPPORTED;

    /** {@code true} seulement pour {@link #MARKABLE}. */
    public boolean markable() {
        return this == MARKABLE;
    }
}
