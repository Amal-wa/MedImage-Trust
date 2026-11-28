package tn.medimagetrust.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("SEC-M01")
@DisplayName("Otsu : seuil déterministe au milieu du trou entre fond et corps")
class OtsuTest {

    @Test
    @DisplayName("Deux modes séparés (10 à 20 et 80 à 90) : T au milieu du trou, soit (20 + 79) / 2 = 49")
    void bimodalWithGapGivesMidpoint() {
        long[] h = new long[128];
        for (int i = 10; i <= 20; i++) h[i] = 1000;
        for (int i = 80; i <= 90; i++) h[i] = 300;
        assertEquals(49, Otsu.threshold(h));
    }

    @Test
    @DisplayName("Deux pics de Dirac 0 et 100 : T = 49 (moyenne entière des seuils 0 à 99)")
    void twoDiracs() {
        long[] h = new long[101];
        h[0] = 5;
        h[100] = 7;
        assertEquals(49, Otsu.threshold(h));
    }

    @Test
    @DisplayName("Histogramme connu sans plateau : T calculé à la main")
    void knownHistogramWithoutPlateau() {
        // Niveaux 0..3, effectifs 4, 1, 1, 4 : symétrique, maximum unique en t = 1.
        long[] h = {4, 1, 1, 4};
        assertEquals(1, Otsu.threshold(h));
    }

    @Test
    @DisplayName("Une seule valeur occupée : T = cette valeur")
    void singleValue() {
        long[] h = new long[64];
        h[17] = 1000;
        assertEquals(17, Otsu.threshold(h));
    }

    @Test
    @DisplayName("Histogramme vide, nul ou négatif : INVALID_INPUT")
    void invalidHistogram() {
        assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                assertThrows(EmbeddingException.class, () -> Otsu.threshold(new long[8])).reason());
        assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                assertThrows(EmbeddingException.class, () -> Otsu.threshold(null)).reason());
        assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                assertThrows(EmbeddingException.class, () -> Otsu.threshold(new long[] {3, -1, 2})).reason());
    }

    @Test
    @DisplayName("Fantôme normal : T tombe dans le trou entre le fond (q ≤ 60) et le corps (q ≥ 450)")
    void phantomThresholdInGap() {
        int t = RoniDetector.otsuThreshold(Phantoms.normal().image());
        assertEquals(true, t >= 60 + EmbeddingProfile.guardQ(Phantoms.normal().image().bitsStored()) && t < 450 - EmbeddingProfile.guardQ(Phantoms.normal().image().bitsStored()));
    }
}
