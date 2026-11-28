package tn.medimagetrust;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.api.rest.HealthResource;
import tn.medimagetrust.archfixture.Dcm4cheOutsideDicom;
import tn.medimagetrust.archfixture.UnsecuredResource;
import tn.medimagetrust.dicom.ArchFixtureDicomToCrypto;
import tn.medimagetrust.stego.ArchFixtureStegoToWatermark;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test du test : les règles 1, 10, 11 et 12 de {@link ArchitectureTest} détectent bien une violation. */
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

    @Test
    @DisplayName("Règle 10 : une classe de dicom qui dépend de crypto est détectée")
    void dicomDependingOnCryptoIsDetected() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ArchFixtureDicomToCrypto.class);
        assertThrows(AssertionError.class, () -> ArchitectureTest.R10_DICOM.check(fixture));
    }

    @Test
    @DisplayName("Règle 11 : une classe de stego qui dépend de watermark est détectée")
    void stegoDependingOnWatermarkIsDetected() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ArchFixtureStegoToWatermark.class);
        assertThrows(AssertionError.class, () -> ArchitectureTest.R11_STEGO.check(fixture));
    }

    @Test
    @DisplayName("Règle 12 : dcm4che utilisé hors de dicom est détecté")
    void dcm4cheOutsideDicomIsDetected() {
        JavaClasses fixture = new ClassFileImporter().importClasses(Dcm4cheOutsideDicom.class);
        assertThrows(AssertionError.class, () -> ArchitectureTest.R12_DCM4CHE_DANS_DICOM.check(fixture));
    }
}
