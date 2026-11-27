package tn.medimagetrust.watermark;

/** Liste fermée des motifs de vérification (SEC-D03, CU06). */
public enum VerificationReason {
    CONFORME,
    PREUVE_ABSENTE,
    CLE_INCONNUE,
    CLE_EXPIREE_OU_REVOQUEE,
    SIGNATURE_INVALIDE,
    ROI_MODIFIEE,
    MASQUE_MODIFIE,
    PARTITION_S_MODIFIEE
}