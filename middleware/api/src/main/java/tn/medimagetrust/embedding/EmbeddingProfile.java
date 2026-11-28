package tn.medimagetrust.embedding;

/**
 * Paramètres figés du profil d'incorporation (dossier v2.4, §4.1).
 *
 * <p>Toutes les constantes du module sont ici et nulle part ailleurs. Une modification de l'une d'elles change le
 * profil : elle impose d'incrémenter {@link #PROFILE_VERSION}, car le vérificateur doit recalculer exactement le même
 * masque et lire les mêmes bits que l'émetteur.</p>
 *
 * <p>Profil 3 : le pas de la QIM et les tolérances qui en dépendent suivent la profondeur effective B de l'original
 * ({@link #qimDelta(int)}, {@link #maxFDeviation(int)}, {@link #guardQ(int)}), avec un facteur {@code 2^(B − 8)}.
 * Pour B = 8, toutes les valeurs du profil 2 sont inchangées.</p>
 */
public final class EmbeddingProfile {
    private EmbeddingProfile() {}

    /** Version du profil d'incorporation (enregistrée avec la validation de la RONI). */
    public static final int PROFILE_VERSION = 3;

    /** Décalage appliqué aux valeurs stockées pour la détection : {@code q = pixel >> Q_SHIFT}. */
    public static final int Q_SHIFT = 2;
    /** Marge d'érosion autour du corps, en pixels (élément structurant carré de côté {@code 2 × 8 + 1}). */
    public static final int EROSION_PX = 8;
    /** Côté des blocs de la RONI, en pixels. */
    public static final int BLOCK = 8;
    /** Nombre de pixels d'un bloc. */
    public static final int BLOCK_PIXELS = BLOCK * BLOCK;
    /**
     * Garde pour B = 8 : distance minimale, en niveaux de q, entre un pixel de la RONI et le seuil, du côté du fond.
     * La garde suit Δ(B) : voir {@link #guardQ(int)}.
     */
    public static final int GUARD_Q_BASE = 4;
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

    /**
     * Pas de la QIM pour B = 8, en valeurs stockées. Seule occurrence de cette valeur dans le projet : toute autre
     * grandeur liée au pas passe par {@link #qimDelta(int)}.
     */
    public static final int QIM_DELTA_BASE = 12;
    /** Premier rang zigzag (ordre JPEG, rang 0 = DC) porteur d'un bit. */
    public static final int ZIGZAG_FIRST = 6;
    /** Dernier rang zigzag porteur d'un bit. */
    public static final int ZIGZAG_LAST = 13;
    /** Bits portés par un bloc F. */
    public static final int F_BITS_PER_BLOCK = ZIGZAG_LAST - ZIGZAG_FIRST + 1;
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
     * Ajout par rapport à la spécification du module : voir api/docs/embedding.md, « Décisions de conception ».
     */
    public static final int MAX_DIMENSION = 8192;
    /** Profondeur minimale ({@code BitsStored}). */
    public static final int MIN_BITS_STORED = 8;
    /** Profondeur maximale ({@code BitsStored}). */
    public static final int MAX_BITS_STORED = 16;
    /** Plus grande profondeur pour laquelle une copie peut être produite en mode signé (entier signé 16 bits). */
    public static final int MAX_BITS_SIGNED_OUTPUT = 15;

    /**
     * Facteur d'échelle {@code 2^(B − 8)} du pas et des tolérances associées.
     *
     * @param bitsStored profondeur effective B de l'original, de 8 à 16
     * @throws EmbeddingException {@code INVALID_INPUT} si B est hors de {@code [8, 16]}
     */
    static int depthScale(int bitsStored) {
        if (bitsStored < MIN_BITS_STORED || bitsStored > MAX_BITS_STORED) {
            throw new EmbeddingException(EmbeddingException.Reason.INVALID_INPUT);
        }
        return 1 << (bitsStored - MIN_BITS_STORED);
    }

    /**
     * Pas de la QIM pour la profondeur effective B : {@code Δ(B) = 12 × 2^(B − 8)} (12, 48, 192, …, 3 072).
     *
     * <p>B est celle de l'original au moment de l'émission, enregistrée dans le registre ({@link EmbeddingResult}) ;
     * elle n'est jamais lue dans une copie analysée, dont les attributs peuvent avoir été modifiés.</p>
     *
     * @throws EmbeddingException {@code INVALID_INPUT} si B est hors de {@code [8, 16]}
     */
    public static int qimDelta(int bitsStored) {
        return QIM_DELTA_BASE * depthScale(bitsStored);
    }

    /**
     * Écart maximal, en valeurs stockées, d'un pixel d'un bloc F : Δ(B). Borne théorique {@code (Δ/2) × 1,78 ≈ 0,89 Δ}
     * avant arrondi (voir {@link QimBlockCodec}) ; pour B = 8, 12 comme dans le profil 2.
     */
    public static int maxFDeviation(int bitsStored) {
        return qimDelta(bitsStored);
    }

    /**
     * Garde (zone morte autour du seuil, du côté du fond), en niveaux de q : {@code 4 × 2^(B − 8)}. Elle reste plus
     * large que l'écart F exprimé en q ({@code Δ(B) >> 2 = 3 × 2^(B − 8)}) : un pixel de la RONI ne franchit jamais
     * le seuil d'origine, et le masque reste stable.
     */
    public static int guardQ(int bitsStored) {
        return GUARD_Q_BASE * depthScale(bitsStored);
    }
}
