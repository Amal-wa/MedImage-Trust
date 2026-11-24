package tn.medimagetrust.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static tn.medimagetrust.embedding.EmbeddingException.FinalCheck;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.FINAL_CHECK_FAILED;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.ILLEGAL_STATE;

@Tag("SEC-M03")
@Tag("SEC-M06")
@Tag("SEC-D07")
@DisplayName("Échec fermé : toute anomalie annule la copie, l'original n'est jamais écrit")
class FailClosedTest {
    private static final long TEST_SEED_FAIL = 0x4641494CL;

    private final PixelImage original = Phantoms.normal().image();

    /** Session prête à finir (F, S, W écrits) ; {@code fault} injecte un défaut juste avant finish(). */
    private EmbeddingException finishWithFault(Consumer<EmbeddingSession> fault) {
        Random rnd = new Random(TEST_SEED_FAIL);
        EmbeddingSession s = EmbeddingSession.open(original);
        Markings.writeF(s, rnd);
        s.writeS(new byte[64]);   // S et W à zéro : le bit 0 écrit vaut 0, l'injection le met à 1
        s.writeW(new byte[208]);
        fault.accept(s);
        EmbeddingException e = assertThrows(EmbeddingException.class, s::finish);
        // Après un échec, la session est inutilisable : pas de seconde chance.
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class, s::finish).reason());
        return e;
    }

    private int firstRoiPixel(EmbeddingSession s) {
        for (int i = 0; i < original.pixelCount(); i++) if (!s.profile().inRoni(i)) return i;
        throw new AssertionError();
    }

    private static int pixelOf(Partitions p, int block, int dr, int dc) {
        return (p.blockTop(block) + dr) * p.cols() + p.blockLeft(block) + dc;
    }

    @Test
    @DisplayName("Mutation : un pixel de la ROI écrit par erreur → FINAL_CHECK_FAILED / ROI_MODIFIED")
    void roiWriteIsDetected() {
        EmbeddingException e = finishWithFault(s -> {
            int i = firstRoiPixel(s);
            s.injectFault(i, original.pixel(i) ^ 1);
        });
        assertEquals(FINAL_CHECK_FAILED, e.reason());
        assertEquals(FinalCheck.ROI_MODIFIED, e.finalCheck().orElseThrow());
        assertEquals("FINAL_CHECK_FAILED:ROI_MODIFIED", e.auditCode());
    }

    @Test
    @DisplayName("Mutation : un bloc F non utilisé modifié → UNUSED_BLOCK_MODIFIED")
    void unusedFBlockWriteIsDetected() {
        // Avec la graine fixe, on cherche un bloc F que writeF n'a pas tiré.
        Random rnd = new Random(TEST_SEED_FAIL);
        Partitions parts = Partitions.of(RoniDetector.detect(original));
        int[] used = Markings.pickFIndices(parts.fClassSize(), EmbeddingProfile.F_USED_BLOCKS, rnd);
        java.util.Set<Integer> usedSet = new java.util.HashSet<>();
        for (int u : used) usedSet.add(u);
        int unused = 0;
        while (usedSet.contains(unused)) unused++;
        int idx = pixelOf(parts, parts.fBlock(unused), 3, 3);
        EmbeddingException e = finishWithFault(s -> s.injectFault(idx, original.pixel(idx) ^ 1));
        assertEquals(FinalCheck.UNUSED_BLOCK_MODIFIED, e.finalCheck().orElseThrow());
    }

    @Test
    @DisplayName("Mutation : écart de 2 dans un bloc W → DEVIATION_EXCEEDED")
    void lsbDeviationIsDetected() {
        Partitions parts = Partitions.of(RoniDetector.detect(original));
        int idx = pixelOf(parts, parts.wBlock(0), 0, 0);
        int v = original.pixel(idx);
        int bad = v >= 2 ? v - 2 : v + 2;
        EmbeddingException e = finishWithFault(s -> s.injectFault(idx, bad));
        assertEquals(FinalCheck.DEVIATION_EXCEEDED, e.finalCheck().orElseThrow());
    }

    @Test
    @DisplayName("Mutation : un bit de S basculé après écriture → S_READBACK_MISMATCH")
    void sReadbackIsChecked() {
        Partitions parts = Partitions.of(RoniDetector.detect(original));
        int idx = pixelOf(parts, parts.sBlock(0), 0, 0);
        EmbeddingException e = finishWithFault(s -> s.injectFault(idx, (original.pixel(idx) & ~1) | 1));
        // L'écart reste ≤ 1 : seule la relecture de S peut le voir.
        assertEquals(FinalCheck.S_READBACK_MISMATCH, e.finalCheck().orElseThrow());
    }

    @Test
    @DisplayName("Mutation : un bit de W basculé après écriture → W_READBACK_MISMATCH")
    void wReadbackIsChecked() {
        Partitions parts = Partitions.of(RoniDetector.detect(original));
        int idx = pixelOf(parts, parts.wBlock(10), 4, 4);
        EmbeddingException e = finishWithFault(s -> s.injectFault(idx, (original.pixel(idx) & ~1) | 1));
        assertEquals(FinalCheck.W_READBACK_MISMATCH, e.finalCheck().orElseThrow());
    }

    @Test
    @DisplayName("Écriture après finish() ou seconde finish() : ILLEGAL_STATE")
    void noWriteAfterFinish() {
        Random rnd = new Random(TEST_SEED_FAIL);
        EmbeddingSession s = EmbeddingSession.open(original);
        s.writeS(new byte[64]);
        s.writeW(new byte[208]);
        s.finish();
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class, s::finish).reason());
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class, () -> s.writeS(new byte[64])).reason());
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class, () -> s.writeW(new byte[208])).reason());
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class,
                () -> s.fChannel().write(0, Markings.randomBits(rnd), Markings.randomDither(rnd))).reason());
    }

    @Test
    @DisplayName("Fond à 0 : le bornage casse des bits F, le contrôle final annule la copie (F_READBACK_MISMATCH)")
    void zeroBackgroundIsCancelledNotReturnedBroken() {
        // Comportement mesuré, non corrigé en douce : voir README, « Décisions à valider ».
        Random rnd = new Random(TEST_SEED_FAIL);
        EmbeddingSession s = EmbeddingSession.open(Phantoms.zeroBackground().image());
        Markings.writeF(s, rnd);
        s.writeS(new byte[64]);
        s.writeW(new byte[208]);
        EmbeddingException e = assertThrows(EmbeddingException.class, s::finish);
        assertEquals(FinalCheck.F_READBACK_MISMATCH, e.finalCheck().orElseThrow());
    }

    @Test
    @DisplayName("Une erreur d'entrée place la session en échec : plus aucune opération acceptée")
    void sessionIsPoisonedAfterAnyError() {
        EmbeddingSession s = EmbeddingSession.open(original);
        assertThrows(EmbeddingException.class, () -> s.writeS(new byte[3]));
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class, () -> s.writeS(new byte[64])).reason());
        assertEquals(ILLEGAL_STATE, assertThrows(EmbeddingException.class, s::roiDigest).reason());
    }

    @Test
    @DisplayName("L'image d'origine et le tableau de l'appelant ne sont jamais modifiés")
    void originalNeverModified() {
        char[] callerArray = original.pixels();
        PixelImage img = new PixelImage(original.rows(), original.cols(), original.bitsStored(),
                original.photometric(), callerArray, false, false);
        char[] before = callerArray.clone();
        Markings.Marking m = Markings.markRandom(img, new Random(TEST_SEED_FAIL));
        assertArrayEquals(before, img.pixels());
        assertArrayEquals(before, callerArray);
        // Copies défensives en entrée et en sortie.
        callerArray[0] = (char) (callerArray[0] ^ 1);
        assertEquals((int) before[0], img.pixel(0));
        char[] out = m.marked().pixels();
        out[0] = (char) (out[0] ^ 1);
        assertEquals((int) m.marked().pixels()[0], m.marked().pixel(0));
        // Même un échec du contrôle final ne touche pas l'original.
        finishWithFault(s -> s.injectFault(firstRoiPixel(s), 0));
        assertEquals(Phantoms.normal().image(), original);
    }
}
