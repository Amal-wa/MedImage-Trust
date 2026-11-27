package tn.medimagetrust.embedding;

/**
 * Interprétation photométrique acceptée (SEC-F01).
 *
 * <p>Information seulement : la détection de la RONI ne s'appuie pas dessus, elle déduit la polarité du fond de la
 * majorité des pixels du bord (§4.1, étape 2). Les valeurs manipulées sont toujours les valeurs stockées.</p>
 */
public enum Photometric {
    /** La valeur minimale s'affiche en blanc. */
    MONOCHROME1,
    /** La valeur minimale s'affiche en noir. */
    MONOCHROME2
}
