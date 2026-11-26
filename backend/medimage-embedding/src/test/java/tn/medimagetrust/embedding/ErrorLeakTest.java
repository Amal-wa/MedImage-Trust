package tn.medimagetrust.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("SEC-D02")
@Tag("SEC-F01")
@DisplayName("Pas de fuite par les erreurs : messages fixes sans chiffre, motifs compatibles avec le journal d'audit")
class ErrorLeakTest {
    /** Format des identifiants opaques accepté par medimage-audit. */
    private static final Pattern AUDIT_ID = Pattern.compile("[A-Za-z0-9._:@-]{1,64}");
    private static final Pattern DIGIT = Pattern.compile("\\p{Nd}");

    @Test
    @DisplayName("Chaque motif et sous-motif : identifiant d'audit valide, message sans aucun chiffre")
    void everyReasonIsAuditSafe() {
        for (EmbeddingException.Reason r : EmbeddingException.Reason.values()) {
            assertTrue(AUDIT_ID.matcher(r.name()).matches(), r.name());
            assertFalse(DIGIT.matcher(r.message()).find(), r.name());
            if (r != EmbeddingException.Reason.FINAL_CHECK_FAILED) {
                EmbeddingException e = new EmbeddingException(r);
                assertTrue(AUDIT_ID.matcher(e.auditCode()).matches());
                assertFalse(DIGIT.matcher(e.getMessage()).find());
            }
        }
        for (EmbeddingException.FinalCheck f : EmbeddingException.FinalCheck.values()) {
            EmbeddingException e = new EmbeddingException(f);
            assertTrue(AUDIT_ID.matcher(e.auditCode()).matches(), e.auditCode());
            assertFalse(DIGIT.matcher(e.getMessage()).find(), e.auditCode());
        }
    }

    @Test
    @DisplayName("Exceptions réellement levées (refus, entrées malformées, contrôle final) : aucun chiffre")
    void thrownExceptionsLeakNothing() {
        List<Supplier<Object>> ops = new ArrayList<>();
        ops.add(() -> { EmbeddingLimits.checkDimensions(20_000, 20_000, 16); return null; });
        ops.add(() -> { EmbeddingLimits.checkDimensions(0, 513, 12); return null; });
        ops.add(() -> PixelImage.fromInts(511, 513, 12, Photometric.MONOCHROME2, new int[7], false, false));
        ops.add(() -> new PixelImage(511, 513, 12, Photometric.MONOCHROME2, new char[7], false, false));
        ops.add(() -> PixelImage.fromInts(2, 2, 12, Photometric.MONOCHROME2, new int[] {1, 2, 3, 4097}, false, false));
        ops.add(() -> new PixelImage(2, 2, 12, Photometric.MONOCHROME2, new char[] {1, 2, 3, 4097}, false, false));
        ops.add(() -> RoniDetector.detect(Phantoms.small64().image()));
        ops.add(() -> RoniDetector.detect(Phantoms.lowContrast().image()));
        ops.add(() -> RoniDetector.detect(Phantoms.tinyBody().image()));
        ops.add(() -> RoniDetector.detect(Phantoms.withFlags(Phantoms.small64().image(), true, false)));
        ops.add(() -> RoniDetector.detect(Phantoms.withFlags(Phantoms.small64().image(), false, true)));
        ops.add(() -> {
            EmbeddingSession s = EmbeddingSession.open(Phantoms.normal().image());
            s.writeS(new byte[64]);
            s.writeW(new byte[208]);
            int i = 0;
            while (s.profile().inRoni(i)) i++;
            s.injectFault(i, 4095);
            return s.finish();
        });
        int thrown = 0;
        for (Supplier<Object> op : ops) {
            try {
                op.get();
            } catch (EmbeddingException e) {
                thrown++;
                assertFalse(DIGIT.matcher(e.getMessage()).find(), e.getMessage());
                assertTrue(AUDIT_ID.matcher(e.auditCode()).matches());
            }
        }
        assertTrue(thrown == ops.size(), "chaque opération doit lever une EmbeddingException");
    }

    @Test
    @DisplayName("toString() des objets métier n'imprime ni dimension ni pixel")
    void toStringLeaksNothing() {
        PixelImage img = Phantoms.normal().image();
        RoniProfile p = RoniDetector.detect(img);
        for (Object o : new Object[] {img, p, Partitions.of(p)}) {
            assertFalse(DIGIT.matcher(o.toString()).find(), o.getClass().getSimpleName());
        }
    }
}
