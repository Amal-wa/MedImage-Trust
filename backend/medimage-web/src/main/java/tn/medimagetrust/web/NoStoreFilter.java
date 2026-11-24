package tn.medimagetrust.web;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;

/**
 * Ajoute {@code Cache-Control: no-store} aux réponses des méthodes annotées {@link NoStore}. La valeur remplace tout
 * en-tête {@code Cache-Control} déjà posé.
 */
@Provider
@NoStore
public class NoStoreFilter implements ContainerResponseFilter {

    /** Valeur de l'en-tête posé. */
    static final String NO_STORE = "no-store";

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        response.getHeaders().putSingle(HttpHeaders.CACHE_CONTROL, NO_STORE);
    }
}
