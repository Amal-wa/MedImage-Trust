package tn.medimagetrust.api.rest;

import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.common.web.NoStore;
import tn.medimagetrust.common.web.NoStoreFilter;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sans serveur : {@link NoStoreFilter} est un fournisseur Jakarta REST ({@code @Provider}) lié par {@link NoStore}, et
 * il vient du module {@code medimage-core}, embarqué dans {@code WEB-INF/lib} du WAR api. Sa découverte réelle par
 * WildFly (en-tête {@code Cache-Control: no-store} sur {@code /api/health}) relève du test de fumée de déploiement.
 */
@Tag("OWASP-A05")
@DisplayName("NoStoreFilter : @Provider de medimage-core, lié par @NoStore")
class NoStoreFilterPackagingTest {

    @Test
    @DisplayName("La classe porte @Provider et @NoStore, implémente ContainerResponseFilter ; @NoStore est un @NameBinding")
    void isJaxRsProviderBoundByNoStore() {
        assertTrue(NoStoreFilter.class.isAnnotationPresent(Provider.class));
        assertTrue(NoStoreFilter.class.isAnnotationPresent(NoStore.class));
        assertTrue(ContainerResponseFilter.class.isAssignableFrom(NoStoreFilter.class));
        assertTrue(NoStore.class.isAnnotationPresent(NameBinding.class));
    }

    @Test
    @DisplayName("La classe est chargée depuis medimage-core (jar ou core/target/classes), pas depuis le module api")
    void comesFromCoreModule() {
        String location = NoStoreFilter.class.getProtectionDomain().getCodeSource().getLocation().toString()
                .replace('\\', '/');
        boolean fromCoreJar = location.contains("medimage-core");
        boolean fromCoreClasses = location.endsWith("/core/target/classes/");
        assertTrue(fromCoreJar || fromCoreClasses, "origine inattendue : " + location);
        String apiLocation = HealthResource.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(!location.equals(apiLocation), "NoStoreFilter ne doit pas être compilé dans le module api");
    }
}
