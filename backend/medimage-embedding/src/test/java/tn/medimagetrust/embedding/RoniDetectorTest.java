package tn.medimagetrust.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.BURNED_IN_ANNOTATION;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.GUARD_VIOLATION;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.OVERLAY_PRESENT;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.ROI_TOO_SMALL;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.TOO_FEW_RONI_BLOCKS;

@Tag("SEC-M01")
@Tag("SEC-M04")
@Tag("SEC-M05")
@Tag("SEC-D08")
@DisplayName("Détection de la RONI : masque prudent, déterministe, et refus fermés")
class RoniDetectorTest {

    private static EmbeddingException.Reason refusal(PixelImage img) {
        return assertThrows(EmbeddingException.class, () -> RoniDetector.detect(img)).reason();
    }

    @Test
    @DisplayName("Fantôme normal : au moins 290 blocs, aucun pixel de la RONI à moins de 8 px du corps")
    void normalPhantomIsMarkableAndRoniAvoidsBody() {
        Phantoms.Phantom ph = Phantoms.normal();
        RoniProfile p = RoniDetector.detect(ph.image());
        assertTrue(p.roniBlockCount() >= EmbeddingProfile.MIN_RONI_BLOCKS);
        assertEquals(BackgroundPolarity.DARK, p.polarity());
        assertEquals(EmbeddingProfile.PROFILE_VERSION, p.profileVersion());
        int rows = p.rows();
        int cols = p.cols();
        int m = EmbeddingProfile.EROSION_PX;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (!p.inRoni(r, c)) continue;
                for (int dr = -m; dr <= m; dr++) {
                    for (int dc = -m; dc <= m; dc++) {
                        int rr = r + dr;
                        int cc = c + dc;
                        if (rr >= 0 && rr < rows && cc >= 0 && cc < cols) {
                            assertFalse(ph.body()[rr * cols + cc], "un bloc de la RONI touche le corps");
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Blocs de la RONI en ordre raster strictement croissant, masque par pixel cohérent")
    void blocksAreRasterOrderedAndMaskConsistent() {
        RoniProfile p = RoniDetector.detect(Phantoms.normal().image());
        int[] b = p.roniBlocks();
        for (int i = 1; i < b.length; i++) assertTrue(b[i] > b[i - 1]);
        int count = 0;
        for (int i = 0; i < p.rows() * p.cols(); i++) if (p.inRoni(i)) count++;
        assertEquals(b.length * EmbeddingProfile.BLOCK_PIXELS, count);
        assertEquals(p.rows() * p.cols() - count, p.roiPixelCount());
    }

    @Test
    @DisplayName("Déterminisme : deux détections donnent le même masque, que candidates() retrouve")
    void deterministic() {
        PixelImage img = Phantoms.normal().image();
        RoniProfile a = RoniDetector.detect(img);
        RoniProfile b = RoniDetector.detect(img);
        assertTrue(a.sameMask(b));
        assertEquals(a.threshold(), b.threshold());
        assertTrue(RoniDetector.candidates(img).get(0).sameMask(a));
    }

    @Test
    @DisplayName("Image 64 × 64 : refus TOO_FEW_RONI_BLOCKS")
    void smallImageRefused() {
        assertEquals(TOO_FEW_RONI_BLOCKS, refusal(Phantoms.small64().image()));
    }

    @Test
    @DisplayName("Corps qui touche les quatre bords : refus TOO_FEW_RONI_BLOCKS")
    void bodyTouchingEdgesRefused() {
        assertEquals(TOO_FEW_RONI_BLOCKS, refusal(Phantoms.bodyTouchingEdges().image()));
    }

    @Test
    @DisplayName("BurnedInAnnotation = YES : refus BURNED_IN_ANNOTATION, avant tout calcul")
    void burnedInRefused() {
        assertEquals(BURNED_IN_ANNOTATION, refusal(Phantoms.withFlags(Phantoms.normal().image(), true, false)));
        assertEquals(BURNED_IN_ANNOTATION, refusal(Phantoms.withFlags(Phantoms.small64().image(), true, true)));
    }

    @Test
    @DisplayName("Overlay présent : refus OVERLAY_PRESENT")
    void overlayRefused() {
        assertEquals(OVERLAY_PRESENT, refusal(Phantoms.withFlags(Phantoms.normal().image(), false, true)));
    }

    @Test
    @DisplayName("Contraste faible (fond à moins de 4 niveaux de q du seuil) : refus GUARD_VIOLATION")
    void guardViolationRefused() {
        assertEquals(GUARD_VIOLATION, refusal(Phantoms.lowContrast().image()));
    }

    @Test
    @DisplayName("Corps minuscule (ROI < 5 %) : refus ROI_TOO_SMALL")
    void roiTooSmallRefused() {
        assertEquals(ROI_TOO_SMALL, refusal(Phantoms.tinyBody().image()));
    }

    @Test
    @DisplayName("Image nulle : INVALID_INPUT (detect et candidates)")
    void nullRefused() {
        assertEquals(INVALID_INPUT, refusal(null));
        assertEquals(INVALID_INPUT,
                assertThrows(EmbeddingException.class, () -> RoniDetector.candidates(null)).reason());
    }

    @Test
    @DisplayName("MONOCHROME1, fond clair : polarité LIGHT, la RONI est dans le fond clair et évite le corps")
    void lightBackgroundPolarity() {
        Phantoms.Phantom ph = Phantoms.lightBackground();
        RoniProfile p = RoniDetector.detect(ph.image());
        assertEquals(BackgroundPolarity.LIGHT, p.polarity());
        assertTrue(p.roniBlockCount() >= EmbeddingProfile.MIN_RONI_BLOCKS);
        for (int i = 0; i < p.rows() * p.cols(); i++) {
            if (p.inRoni(i)) {
                assertFalse(ph.body()[i]);
                assertTrue((ph.image().pixel(i) >> EmbeddingProfile.Q_SHIFT) - p.threshold()
                        >= EmbeddingProfile.GUARD_Q);
            }
        }
    }

    @Test
    @DisplayName("Fond à 0 : polarité DARK, image marquable")
    void zeroBackgroundDetected() {
        RoniProfile p = RoniDetector.detect(Phantoms.zeroBackground().image());
        assertEquals(BackgroundPolarity.DARK, p.polarity());
        assertTrue(p.roniBlockCount() >= EmbeddingProfile.MIN_RONI_BLOCKS);
    }

    @Test
    @DisplayName("Limite connue (§4.1) : un corps qui couvre la majorité du bord est pris pour du fond")
    void knownLimitationBodyCoveringBorder() {
        Phantoms.Phantom ph = Phantoms.bodyCoveringBorder();
        RoniProfile p = RoniDetector.detect(ph.image());
        // Le profil accepte l'image avec la polarité inversée : la « RONI » est dans le corps.
        // Seule la validation humaine obligatoire (SEC-M02) rattrape ce cas : voir README, « Décisions à valider ».
        assertEquals(BackgroundPolarity.LIGHT, p.polarity());
        boolean roniInBody = false;
        for (int i = 0; i < p.rows() * p.cols() && !roniInBody; i++) {
            roniInBody = p.inRoni(i) && ph.body()[i];
        }
        assertTrue(roniInBody);
    }

    @Test
    @DisplayName("candidates() : masques distincts, seuils dans T ± 4, premier candidat au seuil d'Otsu")
    void candidatesAreDistinctAndBounded() {
        PixelImage img = Phantoms.normal().image();
        int t = RoniDetector.otsuThreshold(img);
        List<RoniProfile> cands = RoniDetector.candidates(img);
        assertFalse(cands.isEmpty());
        assertEquals(t, cands.get(0).threshold());
        for (int i = 0; i < cands.size(); i++) {
            assertTrue(Math.abs(cands.get(i).threshold() - t) <= EmbeddingProfile.T_SEARCH);
            for (int j = i + 1; j < cands.size(); j++) {
                assertFalse(cands.get(i).sameMask(cands.get(j)));
            }
        }
    }

    @Test
    @DisplayName("candidates() n'applique pas les refus : image 64 × 64 et contraste faible acceptés")
    void candidatesWithoutRefusals() {
        assertFalse(RoniDetector.candidates(Phantoms.small64().image()).isEmpty());
        assertFalse(RoniDetector.candidates(Phantoms.lowContrast().image()).isEmpty());
        assertFalse(RoniDetector.candidates(Phantoms.withFlags(Phantoms.normal().image(), true, true)).isEmpty());
    }

    @Test
    @DisplayName("File de parcours bornée : un débordement (file de 1 à 64 entrées) donne exactement le même fond connexe")
    void boundedQueueOverflowGivesSameResult() {
        for (PixelImage img : new PixelImage[] {Phantoms.normal().image(), Phantoms.lightBackground().image(),
                Phantoms.bodyTouchingEdges().image()}) {
            int t = RoniDetector.otsuThreshold(img);
            char[] px = img.pixels();
            BackgroundPolarity pol = RoniDetector.polarity(px, img.rows(), img.cols(), t);
            long[] reference = RoniDetector.borderConnectedBackground(px, img.rows(), img.cols(), t, pol);
            for (int capacity : new int[] {1, 7, 64}) {
                assertArrayEquals(reference,
                        RoniDetector.borderConnectedBackground(px, img.rows(), img.cols(), t, pol, capacity));
            }
        }
    }

    @Test
    @DisplayName("candidatesContain (contrôle final, un masque à la fois) équivaut à candidates().anyMatch(sameMask)")
    void candidatesContainMatchesCandidates() {
        PixelImage img = Phantoms.normal().image();
        RoniProfile p = RoniDetector.detect(img);
        assertTrue(RoniDetector.candidatesContain(img, p));
        RoniProfile other = new RoniProfile(img.rows(), img.cols(), p.threshold(), p.polarity(), new int[] {0});
        assertFalse(RoniDetector.candidatesContain(img, other));
        assertFalse(RoniDetector.candidates(img).stream().anyMatch(c -> c.sameMask(other)));
    }
}
