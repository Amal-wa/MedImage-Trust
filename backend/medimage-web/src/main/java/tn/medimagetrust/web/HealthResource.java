package tn.medimagetrust.web;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.Map;

@Path("/health")
@PermitAll
public class HealthResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    public Response health() {
        return Response.ok(Map.of(
            "status", "UP",
            "service", "medimage-trust",
            "runtime", "WildFly 42 / Java " + System.getProperty("java.version"),
            "timestamp", Instant.now().toString()
        )).build();
    }
}
