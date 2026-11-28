package tn.medimagetrust.fingerprint;

import tn.medimagetrust.embedding.EmbeddingProfile;

/**
 * Paramètres figés par le dossier de spécification v2.4 (§4.1, §8.2, SEC-D04).
 *
 * <p>Les grandeurs de la couche robuste (bits par bloc, blocs utilisés, taille minimale de la classe F, pas Δ) sont
 * reprises de {@link EmbeddingProfile}, jamais recopiées.</p>
 */
public final class FingerprintParams {
    private FingerprintParams() {}

    /** Longueur du code de Tardos (symboles). */
    public static final int SYMBOLS = 1024;
    /** Répétitions de chaque symbole dans la classe F : une seule en v2.4 (1 bit par coefficient). */
    public static final int REPETITIONS = 1;
    /** Nombre de bits écrits dans la classe F : 1 024. */
    public static final int CODE_BITS = SYMBOLS * REPETITIONS;
    /** Bits portés par un bloc F (8 coefficients DCT par QIM). */
    public static final int BITS_PER_BLOCK = EmbeddingProfile.F_BITS_PER_BLOCK;
    /** Blocs F utilisés par émission : 1 024 / 8 = 128, tirés secrètement dans la classe F. */
    public static final int USED_BLOCKS = EmbeddingProfile.F_USED_BLOCKS;
    /** Taille minimale de la classe F, en blocs : marge × 2 sur les blocs utilisés (§4.1). */
    public static final int MIN_F_CLASS_BLOCKS = EmbeddingProfile.MIN_F_CLASS_BLOCKS;
    // Pas Δ de la QIM : pas de constante ici. Il dépend de la profondeur effective B de l'émission et vient du canal F
    // (FBlockChannel.qimDelta()) : session à l'émission, registre à l'analyse.
    /** Nombre de colluders face auxquels le code est dimensionné. */
    public static final int MAX_COLLUDERS = 2;
    /** Destinataires maximum par image. */
    public static final int MAX_RECIPIENTS = 64;
    /** Coupure des biais : t = 1 / (300 c). */
    public static final double CUTOFF = 1.0 / (300.0 * MAX_COLLUDERS);
    /** Objectif de fausse accusation (SEC-D04) : <= 10^-3 face à 2 colluders. */
    public static final double TARGET_FALSE_ACCUSATION = 1e-3;
    /** En dessous de cette part de symboles lisibles (recadrage), on ne conclut pas. */
    public static final double MIN_READABLE_RATIO = 0.5;

    static {
        // Cohérence avec le profil d'incorporation : 128 blocs × 8 bits = 1 024 symboles.
        if (USED_BLOCKS * BITS_PER_BLOCK != CODE_BITS || MIN_F_CLASS_BLOCKS < 2 * USED_BLOCKS) {
            throw new ExceptionInInitializerError("profil d'incorporation incompatible avec le code F");
        }
    }

    // Étiquettes de domaine (une par usage de la clé K_F).
    public static final String LABEL_CODE = "MIT-F-v1";    // mots de code (spec §8.2)
    public static final String LABEL_COMMIT = "MIT-FC-v1";  // engagement fCommit (spec §8.2)
    public static final String LABEL_POS = "MIT-POS-v1";    // positions secrètes (spec §8.2)
    /** AJOUT de l'équipe (non défini par la spec) : biais p_i communs à tous les destinataires d'une image. */
    public static final String LABEL_BIAS = "MIT-FB-v1";
    /**
     * AJOUT de l'équipe (dossier : « dither dérivé de K_F », sans étiquette) : 8 dithers QIM par bloc utilisé,
     * flux HMAC sur {@code maskDigest ‖ indice du bloc}.
     */
    public static final String LABEL_DITHER = "MIT-FD-v1";
}
