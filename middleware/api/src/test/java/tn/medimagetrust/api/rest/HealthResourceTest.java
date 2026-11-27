package tn.medimagetrust.api.rest;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.common.web.NoStore;
import tn.medimagetrust.common.web.NoStoreFilter;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("OWASP-A05")
@DisplayName("Sonde /api/health : publique, sans aucune information sur la plateforme, jamais mise en cache")
class HealthResourceTest {
    private static final Pattern DIGIT = Pattern.compile("\\p{Nd}");

    @Test
    @DisplayName("Le corps vaut {\"status\":\"UP\"} : ni « java », ni « wildfly », ni chiffre de version")
    void bodyRevealsNothing() {
        String body = new HealthResource().health();
        assertEquals("{\"status\":\"UP\"}", body);
        String lower = body.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("java"));
        assertFalse(lower.contains("wildfly"));
        assertFalse(DIGIT.matcher(body).find());
    }

    @Test
    @DisplayName("@PermitAll explicite sur la classe et sur la méthode, @NoStore sur la méthode")
    void annotations() throws NoSuchMethodException {
        assertTrue(HealthResource.class.isAnnotationPresent(PermitAll.class));
        var method = HealthResource.class.getMethod("health");
        assertTrue(method.isAnnotationPresent(PermitAll.class));
        assertTrue(method.isAnnotationPresent(NoStore.class));
        assertTrue(NoStoreFilter.class.isAnnotationPresent(NoStore.class));
    }

    @Test
    @DisplayName("Le filtre @NoStore pose Cache-Control: no-store et remplace une valeur existante")
    void filterSetsNoStore() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.putSingle(HttpHeaders.CACHE_CONTROL, "public, max-age=3600");
        ContainerResponseContext response = (ContainerResponseContext) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ContainerResponseContext.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("getHeaders")) return headers;
                    throw new UnsupportedOperationException(m.getName());
                });
        new NoStoreFilter().filter(null, response);
        assertEquals(List.of("no-store"), headers.get(HttpHeaders.CACHE_CONTROL));
    }
}
