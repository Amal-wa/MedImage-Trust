package tn.medimagetrust.stego;

/**
 * Échec de lecture du jeton S : un seul motif, sans détail ni cause (SEC-D01). Clé absente ou fausse, jeton transplanté
 * sur une autre image ou une autre émission, partition S aléatoire ou altérée, version ou contenu invalide : le message
 * est toujours le même, et aucune donnée partielle n'est renvoyée.
 */
public final class StegoException extends Exception {
    private static final long serialVersionUID = 1L;

    /** Message unique. */
    static final String MESSAGE = "jeton S illisible";

    StegoException() {
        super(MESSAGE, null, false, false);
    }
}
