package tn.medimagetrust.fingerprint;

/** Paramètres figés par le dossier de spécification (§4.4, §8.2, SEC-D04). */
public final class FingerprintParams {
    private FingerprintParams() {}

    /** Longueur du code de Tardos (symboles). */
    public static final int SYMBOLS = 1024;
    /** Répétitions de chaque symbole dans la partition F (vote majoritaire). */
    public static final int REPETITIONS = 3;
    /** Nombre de bits écrits dans la partition F. */
    public static final int CODE_BITS = SYMBOLS * REPETITIONS; // 3 072
    /** Nombre de colluders face auxquels le code est dimensionné. */
    public static final int MAX_COLLUDERS = 2;
    /** Destinataires maximum par image. */
    public static final int MAX_RECIPIENTS = 64;
    /** Coupure des biais : t = 1 / (300 c). */
    public static final double CUTOFF = 1.0 / (300.0 * MAX_COLLUDERS);
    /** Capacité minimale de la classe F : marge x2 (§4.1), soit 6 144 pixels. */
    public static final int MIN_F_CLASS_PIXELS = 2 * CODE_BITS;
    /** Objectif de fausse accusation (SEC-D04) : <= 10^-3 face à 2 colluders. */
    public static final double TARGET_FALSE_ACCUSATION = 1e-3;
    /** En dessous de cette part de symboles lisibles (recadrage), on ne conclut pas. */
    public static final double MIN_READABLE_RATIO = 0.5;

    // Étiquettes de domaine (une par usage de la clé K_F).
    public static final String LABEL_CODE = "MIT-F-v1";    // mots de code (spec §8.2)
    public static final String LABEL_COMMIT = "MIT-FC-v1";  // engagement fCommit (spec §8.2)
    public static final String LABEL_POS = "MIT-POS-v1";    // positions secrètes (spec §8.2)
    /** AJOUT de l'équipe (non défini par la spec) : biais p_i communs à tous les destinataires d'une image. */
    public static final String LABEL_BIAS = "MIT-FB-v1";
}
