package tn.medimagetrust.iam;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import tn.medimagetrust.common.web.NoStore;

/**
 * Sonde de disponibilité publique du service iam : {@code GET /iam/health}.
 *
 * <p>Un point d'accès public ne doit rien révéler (OWASP A05) : la réponse est toujours {@code {"status":"UP"}}, et
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
