package tn.medimagetrust.api.rest;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import tn.medimagetrust.common.web.NoStore;

/**
 * Sonde de disponibilité publique : {@code GET /api/health}.
 *
 * <p>Un point d'accès public ne doit rien révéler (OWASP A05, mauvaise configuration de sécurité) : ni version de
 * Java, ni nom ou version du serveur, ni horodatage. La réponse est toujours {@code {"status":"UP"}}, et
 * {@link NoStore} ajoute {@code Cache-Control: no-store}.</p>
 */
@Path("/health")
@PermitAll
public class HealthResource {

    /** Corps unique de la réponse. */
    static final String BODY = "{\"status\":\"UP\"}";

    /** Renvoie {@code {"status":"UP"}}, sans aucune autre information. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    @NoStore
    public String health() {
        return BODY;
    }
}
