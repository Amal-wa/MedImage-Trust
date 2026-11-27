package tn.medimagetrust.iam;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * Application Jakarta REST du service d'identités et d'accès (squelette). L'authentification viendra dans une étape
 * dédiée ; pour l'instant seule la sonde {@code GET /iam/health} existe.
 */
@ApplicationPath("/iam")
public class IamApplication extends Application {
}
