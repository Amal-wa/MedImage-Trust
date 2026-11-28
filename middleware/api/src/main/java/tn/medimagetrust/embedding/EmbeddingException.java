package tn.medimagetrust.embedding;

import java.util.Objects;
import java.util.Optional;

/**
 * Refus ou échec du module d'incorporation (échec fermé).
 *
 * <p>Le motif est une constante d'{@code enum} stable, utilisable telle quelle comme identifiant opaque dans le
 * journal d'audit ({@code [A-Za-z0-9._:@-]{1,64}}). Le message est un texte fixe, propre au motif : il ne contient
 * jamais de valeur venant de l'image (ni dimension, ni valeur de pixel, ni nom de fichier), ni aucun chiffre.</p>
 */
public final class EmbeddingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Motifs de refus ou d'échec : liste fermée. */
    public enum Reason {
        /** La RONI compte moins de {@link EmbeddingProfile#MIN_RONI_BLOCKS} blocs. */
        TOO_FEW_RONI_BLOCKS("image non marquable : RONI trop petite"),
        /** Un pixel de la RONI est trop proche du seuil de fond. */
        GUARD_VIOLATION("image non marquable : garde du seuil de fond non respectée"),
        /** La ROI couvre moins de {@link EmbeddingProfile#MIN_ROI_RATIO} de l'image. */
        ROI_TOO_SMALL("image non marquable : ROI trop petite"),
        /** Annotation incrustée déclarée dans l'image. */
        BURNED_IN_ANNOTATION("image non marquable : annotation incrustée"),
        /** Overlay présent. */
        OVERLAY_PRESENT("image non marquable : overlay présent"),
        /** Dimensions au-delà des limites du profil. */
        IMAGE_TOO_LARGE("image refusée : dimensions hors limites"),
        /** Entrée malformée ou paramètre invalide. */
        INVALID_INPUT("entrée invalide"),
        /** L'insertion F écarterait un pixel de plus que l'écart autorisé. */
        F_DEVIATION_EXCEEDED("insertion F refusée : écart par pixel dépassé"),
        /**
         * La couche F ferait sortir une valeur de la plage stockable et aucune représentation sans écrêtage n'est
         * possible : profondeur de seize bits (pas de mode signé), mode signé non autorisé, ou valeur hors de la plage
         * d'un entier signé de seize bits. Jamais d'écrêtage silencieux.
         */
        EMBEDDING_RANGE_UNSUPPORTED("image non marquable : la couche F sortirait de la plage stockable"),
        /**
         * L'original déclare une valeur de remplissage ({@code PixelPaddingValue}) présente dans le fond : les blocs
         * qui en contiennent sont exclus de la RONI, et le reste ne suffit pas à la capacité.
         */
        PIXEL_PADDING_IN_RONI("image non marquable : pixels de remplissage nécessaires à la capacité"),
        /** Le contrôle final de la copie marquée a échoué ; voir {@link #finalCheck()}. */
        FINAL_CHECK_FAILED("copie annulée : contrôle final en échec"),
        /** Opération interdite dans l'état courant de la session. */
        ILLEGAL_STATE("opération refusée dans l'état courant");

        private final String message;

        Reason(String message) { this.message = message; }

        /** Texte fixe associé au motif (sans aucune donnée de l'image). */
        public String message() { return message; }
    }

    /** Sous-motifs de {@link Reason#FINAL_CHECK_FAILED} : liste fermée. */
    public enum FinalCheck {
        /** Un pixel hors RONI diffère de l'original. */
        ROI_MODIFIED("pixel hors RONI modifié"),
        /**
         * Une valeur sort de la plage stockable de l'original hors de la RONI, ou dans une copie non signée : seule la
         * RONI d'une copie en mode signé peut contenir des valeurs négatives ou supérieures au maximum.
         */
        RANGE_OUTSIDE_RONI("valeur hors plage en dehors de la RONI"),
        /** Un bloc de la classe F non utilisé a été modifié. */
        UNUSED_BLOCK_MODIFIED("bloc non utilisé modifié"),
        /** Écart par pixel au-delà de la borne de sa partition. */
        DEVIATION_EXCEEDED("écart par pixel dépassé"),
        /** PSNR de l'image entière sous le minimum. */
        PSNR_TOO_LOW("PSNR insuffisant"),
        /** S relu différent de S écrit. */
        S_READBACK_MISMATCH("relecture de S différente"),
        /** W relu différent de W écrit. */
        W_READBACK_MISMATCH("relecture de W différente"),
        /** Au moins un bit F écrit est relu différent. */
        F_READBACK_MISMATCH("relecture de F différente"),
        /** Le masque recalculé sur la copie ne contient pas le masque d'origine. */
        MASK_UNSTABLE("masque instable après marquage");

        private final String message;

        FinalCheck(String message) { this.message = message; }

        /** Texte fixe associé au sous-motif. */
        public String message() { return message; }
    }

    private final Reason reason;
    private final FinalCheck finalCheck;

    /** Crée un refus pour un motif autre que {@link Reason#FINAL_CHECK_FAILED}. */
    public EmbeddingException(Reason reason) {
        super(Objects.requireNonNull(reason, "motif").message(), null, false, true);
        if (reason == Reason.FINAL_CHECK_FAILED) {
            throw new IllegalArgumentException("sous-motif obligatoire pour le contrôle final");
        }
        this.reason = reason;
        this.finalCheck = null;
    }

    /** Crée un échec du contrôle final avec son sous-motif. */
    public EmbeddingException(FinalCheck finalCheck) {
        super(Reason.FINAL_CHECK_FAILED.message() + " (" + Objects.requireNonNull(finalCheck, "sous-motif").message()
                + ")", null, false, true);
        this.reason = Reason.FINAL_CHECK_FAILED;
        this.finalCheck = finalCheck;
    }

    /** Motif fermé du refus. */
    public Reason reason() { return reason; }

    /** Sous-motif, présent seulement pour {@link Reason#FINAL_CHECK_FAILED}. */
    public Optional<FinalCheck> finalCheck() { return Optional.ofNullable(finalCheck); }

    /**
     * Identifiant opaque prêt pour le journal d'audit : {@code TOO_FEW_RONI_BLOCKS}, ou
     * {@code FINAL_CHECK_FAILED:ROI_MODIFIED} pour un échec du contrôle final.
     */
    public String auditCode() {
        return finalCheck == null ? reason.name() : reason.name() + ":" + finalCheck.name();
    }
}
