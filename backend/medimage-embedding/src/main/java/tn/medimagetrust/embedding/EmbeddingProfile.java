package tn.medimagetrust.embedding;

/**
 * Paramètres figés du « profil d'incorporation v2 » (dossier v2.4, §4.1).
 *
 * <p>Toutes les constantes du module sont ici et nulle part ailleurs. Une modification de l'une d'elles change le
 * profil : elle impose d'incrémenter {@link #PROFILE_VERSION}, car le vérificateur doit recalculer exactement le même
 * masque et lire les mêmes bits que l'émetteur.</p>
 */
public final class EmbeddingProfile {
    private EmbeddingProfile() {}

    /** Version du profil d'incorporation (enregistrée avec la validation de la RONI). */
    public static final int PROFILE_VERSION = 2;

    /** Décalage appliqué aux valeurs stockées pour la détection : {@code q = pixel >> Q_SHIFT}. */
    public static final int Q_SHIFT = 2;
    /** Marge d'érosion autour du corps, en pixels (élément structurant carré de côté {@code 2 × 8 + 1}). */
    public static final int EROSION_PX = 8;
    /** Côté des blocs de la RONI, en pixels. */
    public static final int BLOCK = 8;
    /** Nombre de pixels d'un bloc. */
    public static final int BLOCK_PIXELS = BLOCK * BLOCK;
    /** Garde : distance minimale, en niveaux de q, entre un pixel de la RONI et le seuil, du côté du fond. */
    public static final int GUARD_Q = 4;
    /** Le vérificateur essaie les seuils {@code T - T_SEARCH … T + T_SEARCH}. */
    public static final int T_SEARCH = 4;

    /** Blocs de la partition W (preuve signée, 208 octets). */
    public static final int W_BLOCKS = 26;
    /** Blocs de la partition S (jeton chiffré, 64 octets). */
    public static final int S_BLOCKS = 8;
    /** Taille de la charge W en octets ({@code 26 × 64 / 8}). */
    public static final int W_BYTES = W_BLOCKS * BLOCK_PIXELS / 8;
    /** Taille de la charge S en octets ({@code 8 × 64 / 8}). */
    public static final int S_BYTES = S_BLOCKS * BLOCK_PIXELS / 8;
    /** Blocs de la classe F effectivement marqués (choisis secrètement par le module fingerprint). */
    public static final int F_USED_BLOCKS = 128;
    /** Taille minimale de la classe F (marge × 2 pour le secret des positions). */
    public static final int MIN_F_CLASS_BLOCKS = 256;
    /** Taille minimale de la RONI : {@code 26 + 8 + 256}. */
    public static final int MIN_RONI_BLOCKS = 290;

    /** Pas de la QIM sur les coefficients DCT. */
    public static final int QIM_DELTA = 12;
    /** Premier rang zigzag (ordre JPEG, rang 0 = DC) porteur d'un bit. */
    public static final int ZIGZAG_FIRST = 6;
    /** Dernier rang zigzag porteur d'un bit. */
    public static final int ZIGZAG_LAST = 13;
    /** Bits portés par un bloc F. */
    public static final int F_BITS_PER_BLOCK = ZIGZAG_LAST - ZIGZAG_FIRST + 1;
    /** Écart maximal, en valeurs stockées, d'un pixel d'un bloc F. */
    public static final int MAX_F_DEVIATION = 12;
    /** Écart maximal, en valeurs stockées, d'un pixel d'un bloc S ou W. */
    public static final int MAX_LSB_DEVIATION = 1;

    /** PSNR minimal de l'image marquée, en dB, avec {@code MAX = 2^bitsStored - 1}. */
    public static final double MIN_PSNR_DB = 40.0;
    /** Part minimale de l'image couverte par la ROI. */
    public static final double MIN_ROI_RATIO = 0.05;

    /** Nombre maximal de pixels ({@code rows × cols}) accepté (SEC-F02). */
    public static final long MAX_PIXELS = 36_000_000L;
    /**
     * Dimension maximale de {@code rows} et de {@code cols} (SEC-F02, dossier v2.4 : « Rows et Columns ≤ 8 192 »).
     * Ajout par rapport à la consigne du module : voir README, « Décisions à valider ».
     */
    public static final int MAX_DIMENSION = 8192;
    /** Profondeur minimale ({@code BitsStored}). */
    public static final int MIN_BITS_STORED = 8;
    /** Profondeur maximale ({@code BitsStored}). */
    public static final int MAX_BITS_STORED = 16;
}
