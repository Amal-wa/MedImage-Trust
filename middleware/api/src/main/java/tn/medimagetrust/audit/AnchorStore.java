package tn.medimagetrust.audit;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Volume d'ancrages (« medimage-anchors », §7). Un ancrage par jour UTC, immuable : création seule.
 * Deux instances sont utilisées : le stockage principal et la copie sur un volume séparé, accessible en écriture
 * au seul service d'audit (SEC09).
 */
public interface AnchorStore {

    /** Crée l'ancrage du jour ; lève {@link AuditException} s'il existe déjà ou si l'écriture échoue. */
    void write(LocalDate day, byte[] encoded);

    Optional<byte[]> read(LocalDate day);

    /** Jours présents, par ordre croissant. */
    List<LocalDate> days();
}
