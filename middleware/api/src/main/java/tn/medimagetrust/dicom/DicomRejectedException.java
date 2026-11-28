package tn.medimagetrust.dicom;

import java.util.Objects;

/**
 * Rejet d'un fichier DICOM à l'entrée (échec fermé, SEC-D02, SEC-F01, SEC-F02, SEC-F04).
 *
 * <p>Le motif est une constante d'{@code enum} stable, utilisable telle quelle comme identifiant opaque dans le journal
 * d'audit. Le message est un texte fixe propre au motif : il ne contient <strong>jamais</strong> de valeur venant du
 * fichier (ni attribut, ni dimension, ni octet, ni nom), ni aucun chiffre. Aucune cause n'est attachée : une exception
 * d'une bibliothèque pourrait citer le contenu du fichier.</p>
 */
public final class DicomRejectedException extends Exception {
    private static final long serialVersionUID = 1L;

    /** Motifs de rejet : liste fermée. */
    public enum Reason {
        /** Taille annoncée au-delà du plafond de fichier. */
        FILE_TOO_LARGE("fichier refusé : taille hors limites"),
        /** Le flux contient plus d'octets que la taille annoncée. */
        SIZE_MISMATCH("fichier refusé : taille différente de la taille annoncée"),
        /** Préambule d'exécutable (PE, ELF, Mach-O : fichier polyglotte, CVE-2019-11687). */
        SUSPICIOUS_PREAMBLE("fichier refusé : préambule exécutable"),
        /** Signature {@code DICM} absente, ou fichier trop court. */
        NOT_DICOM("fichier refusé : signature DICOM absente"),
        /** Structure invalide : longueur ou représentation incohérente, élément illisible, attribut obligatoire absent. */
        MALFORMED("fichier refusé : structure invalide"),
        /** En-tête (hors données de pixels) au-delà du plafond. */
        HEADER_TOO_LARGE("fichier refusé : en-tête trop volumineux"),
        /** Trop d'éléments dans l'en-tête. */
        TOO_MANY_ELEMENTS("fichier refusé : trop d'éléments"),
        /** Séquences imbriquées au-delà de la profondeur autorisée. */
        SEQUENCE_TOO_DEEP("fichier refusé : séquences trop imbriquées"),
        /** Syntaxe de transfert hors des deux syntaxes non compressées en petit-boutiste acceptées. */
        UNSUPPORTED_TRANSFER_SYNTAX("fichier refusé : syntaxe de transfert non prise en charge"),
        /** Image hors profil : plusieurs images, plusieurs échantillons, photométrie, bits alloués ou stockés. */
        UNSUPPORTED_PROFILE("fichier refusé : profil d'image non pris en charge"),
        /** Modalité absente ou hors liste. */
        UNSUPPORTED_MODALITY("fichier refusé : modalité non prise en charge"),
        /** Dimensions hors limites. */
        IMAGE_TOO_LARGE("fichier refusé : dimensions hors limites"),
        /** Pas de données de pixels. */
        PIXEL_DATA_MISSING("fichier refusé : données de pixels absentes"),
        /** Longueur des données de pixels différente de celle attendue d'après l'en-tête. */
        PIXEL_DATA_LENGTH_MISMATCH("fichier refusé : longueur des données de pixels incohérente"),
        /** Données de pixels plus courtes que leur longueur déclarée. */
        PIXEL_DATA_TRUNCATED("fichier refusé : données de pixels tronquées"),
        /** Valeur de pixel (ou de remplissage) hors de la profondeur déclarée. */
        PIXEL_VALUE_OUT_OF_RANGE("fichier refusé : valeur hors de la profondeur déclarée"),
        /** Texte d'attribut avec un caractère non autorisé (balise, caractère de contrôle). */
        SUSPICIOUS_TEXT("fichier refusé : texte d'attribut non autorisé"),
        /** Lecture plus longue que le délai de traitement. */
        TIMEOUT("fichier refusé : délai de lecture dépassé");

        private final String message;

        Reason(String message) { this.message = message; }

        /** Texte fixe associé au motif (sans aucune donnée du fichier). */
        public String message() { return message; }
    }

    private final Reason reason;

    /** Crée un rejet pour le motif donné ; aucune cause n'est conservée. */
    public DicomRejectedException(Reason reason) {
        super(Objects.requireNonNull(reason, "motif").message(), null, false, false);
        this.reason = reason;
    }

    /** Motif fermé du rejet. */
    public Reason reason() { return reason; }

    /** Identifiant opaque pour le journal d'audit, par exemple {@code DICOM_REJECTED:SUSPICIOUS_PREAMBLE}. */
    public String auditCode() {
        return "DICOM_REJECTED:" + reason.name();
    }
}
