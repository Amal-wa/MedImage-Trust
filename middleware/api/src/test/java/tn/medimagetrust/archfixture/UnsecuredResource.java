package tn.medimagetrust.archfixture;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * Ressource <strong>volontairement non sécurisée</strong>, uniquement dans les sources de test : elle sert à prouver
 * que la règle 1 (refus par défaut) détecte une méthode sans {@code @RolesAllowed}, {@code @PermitAll} ni
 * {@code @DenyAll}. Elle ne fait pas partie du code de production (absente du WAR, exclue de l'analyse principale).
 */
@Path("/fixture")
public class UnsecuredResource {

    @GET
    public String oubli() {
        return "";
    }
}
