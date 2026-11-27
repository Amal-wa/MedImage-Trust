package tn.medimagetrust;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.api.rest.HealthResource;
import tn.medimagetrust.archfixture.UnsecuredResource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test du test : la règle 1 de {@link ArchitectureTest} détecte bien une ressource non annotée. */
class ArchitectureRuleSelfTest {

    @Test
    @DisplayName("Règle 1 : une méthode @GET sans annotation de sécurité est détectée (la CI deviendrait rouge)")
    void denyByDefaultDetectsUnsecuredResource() {
        JavaClasses fixture = new ClassFileImporter().importClasses(UnsecuredResource.class);
        AssertionError e = assertThrows(AssertionError.class,
                () -> ArchitectureTest.R1_REFUS_PAR_DEFAUT.check(fixture));
        assertTrue(e.getMessage().contains("UnsecuredResource.oubli()"), e.getMessage());
    }

    @Test
    @DisplayName("Règle 1 : la ressource de production /api/health (@PermitAll) est acceptée")
    void denyByDefaultAcceptsSecuredResource() {
        ArchitectureTest.R1_REFUS_PAR_DEFAUT.check(new ClassFileImporter().importClasses(HealthResource.class));
    }
}
