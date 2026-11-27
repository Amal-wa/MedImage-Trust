package tn.medimagetrust.fingerprint;

import java.util.List;

/**
 * Rapport d'attribution (CU08). Jamais une preuve : une liste éventuellement vide d'accusés avec score (RM09).
 * Le verdict appartient à une liste fermée ; aucune donnée médicale n'y figure.
 */
public record AttributionReport(
        Verdict verdict,
        List<Accusation> accused,
        double threshold,
        double topScore,
        int readableSymbols,
        int erasures,
        int candidates) {

    public enum Verdict {
        /** Au moins un destinataire dépasse le seuil. */
        ACCUSED,
        /** Aucun score au-dessus du seuil : attribution impossible ou incertaine. */
        NO_ACCUSATION_BELOW_THRESHOLD,
        /** Trop de symboles illisibles (copie recadrée) : on ne conclut pas. */
        TOO_MANY_ERASURES,
        /** La preuve W désigne un destinataire absent de la liste des accusés : greffe suspectée, aucune accusation. */
        W_F_INCONSISTENT
    }

    public record Accusation(String recipientId, double score) {}
}
