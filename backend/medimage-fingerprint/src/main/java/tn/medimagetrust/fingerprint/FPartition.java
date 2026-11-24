package tn.medimagetrust.fingerprint;

/**
 * Port vers la brique d'écriture/lecture RONI (module embedding d'Amal).
 * Vue sur la classe F : les pixels de la RONI de rang modulo 10 dans [4, 9], pris en ordre raster.
 * Le rang 0 est le premier pixel de la classe F. Seul le bit 0 du pixel est écrit (LSB).
 */
public interface FPartition {
    int UNREADABLE = -1;

    /** Nombre de pixels de la classe F (>= 6 144 sinon l'image n'est pas marquable). */
    int size();

    void write(int rank, boolean bit);

    /** @return 0, 1, ou {@link #UNREADABLE} si le pixel n'existe plus (copie recadrée) */
    int read(int rank);
}
