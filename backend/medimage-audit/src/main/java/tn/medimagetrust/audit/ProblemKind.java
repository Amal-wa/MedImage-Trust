package tn.medimagetrust.audit;

/** Nature d'une anomalie détectée par la vérification (liste fermée). */
public enum ProblemKind {
    /** entry_hash recalculé différent : contenu d'une ligne modifié. */
    HASH_MISMATCH,
    /** prev_hash différent du condensat de la ligne précédente : ligne insérée, retirée ou réordonnée. */
    PREV_MISMATCH,
    /** Numéro de séquence manquant ou en trop. */
    SEQ_GAP,
    /** Première ligne conservée non rattachée à un ancrage valide (purge illégitime). */
    BASE_UNTRUSTED,
    /** Ancrage illisible ou de format invalide. */
    ANCHOR_MALFORMED,
    /** kid de K_log inconnu. */
    ANCHOR_UNKNOWN_KEY,
    /** Signature d'ancrage invalide. */
    ANCHOR_SIGNATURE_INVALID,
    /** La ligne ancrée n'existe plus : journal tronqué ou vidé. */
    ANCHOR_ENTRY_MISSING,
    /** La ligne ancrée existe mais son condensat diffère : journal réécrit. */
    ANCHOR_HEAD_MISMATCH,
    /** Ancrage présent sur un volume mais absent de l'autre. */
    ANCHOR_COPY_MISSING,
    /** Ancrage différent entre le volume principal et la copie. */
    ANCHOR_COPY_MISMATCH
}
