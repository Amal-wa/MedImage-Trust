package tn.medimagetrust.dicom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.FILE_TOO_LARGE;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.HEADER_TOO_LARGE;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.IMAGE_TOO_LARGE;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.MALFORMED;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.NOT_DICOM;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.PIXEL_DATA_LENGTH_MISMATCH;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.PIXEL_DATA_MISSING;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.PIXEL_DATA_TRUNCATED;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.PIXEL_VALUE_OUT_OF_RANGE;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.SEQUENCE_TOO_DEEP;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.SIZE_MISMATCH;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.SUSPICIOUS_PREAMBLE;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.SUSPICIOUS_TEXT;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.TOO_MANY_ELEMENTS;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.UNSUPPORTED_MODALITY;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.UNSUPPORTED_PROFILE;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.UNSUPPORTED_TRANSFER_SYNTAX;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tn.medimagetrust.dicom.DicomRejectedException.Reason;

/**
 * Fichiers piégés générés dans le test (SEC-D02, SEC-F01, SEC-F02) : chacun est rejeté avec le motif attendu, et le
 * message ne contient aucune valeur du fichier.
 */
class DicomTrapTest {
    private static final int ROWS = 64;
    private static final int COLS = 64;

    /** Cas piégé : fabrique du fichier, taille annoncée (null : taille réelle), motif attendu. */
    record Trap(String name, Consumer<DicomFixture> setup, Long declaredSize, Reason expected) {
        @Override
        public String toString() { return name; }
    }

    private static Trap trap(String name, Reason expected, Consumer<DicomFixture> setup) {
        return new Trap(name, setup, null, expected);
    }

    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }

    private static Consumer<DicomFixture> preamble(int... head) {
        return f -> {
            for (int i = 0; i < head.length; i++) f.preamble[i] = (byte) head[i];
        };
    }

    static Stream<Trap> traps() {
        List<Trap> t = new ArrayList<>();
        // Préambules d'exécutables (fichiers polyglottes).
        t.add(trap("préambule PE (MZ)", SUSPICIOUS_PREAMBLE, preamble('M', 'Z', 0x90, 0)));
        t.add(trap("préambule ELF", SUSPICIOUS_PREAMBLE, preamble(0x7F, 'E', 'L', 'F')));
        t.add(trap("préambule Mach-O FE ED FA CE", SUSPICIOUS_PREAMBLE, preamble(0xFE, 0xED, 0xFA, 0xCE)));
        t.add(trap("préambule Mach-O FE ED FA CF", SUSPICIOUS_PREAMBLE, preamble(0xFE, 0xED, 0xFA, 0xCF)));
        t.add(trap("préambule Mach-O CE FA ED FE", SUSPICIOUS_PREAMBLE, preamble(0xCE, 0xFA, 0xED, 0xFE)));
        t.add(trap("préambule Mach-O CF FA ED FE", SUSPICIOUS_PREAMBLE, preamble(0xCF, 0xFA, 0xED, 0xFE)));
        // Signature et taille.
        t.add(trap("DICM absent", NOT_DICOM, f -> f.dicm = false));
        t.add(new Trap("taille annoncée au-delà de 80 Mio", f -> { }, DicomReader.MAX_FILE_BYTES + 1, FILE_TOO_LARGE));
        t.add(new Trap("flux plus long que la taille annoncée", f -> { }, 2_000L, SIZE_MISMATCH));
        t.add(new Trap("flux vide", f -> { }, 0L, NOT_DICOM));
        // Structure de l'en-tête.
        t.add(trap("longueur impaire sur US", MALFORMED,
                f -> f.rawWithLength(0x00280010, "US", new byte[] {64, 0, 0}, 3)));
        t.add(trap("longueur FL non multiple de 4", MALFORMED,
                f -> f.raw(0x00181050, "FL", new byte[6])));
        t.add(trap("longueur d'élément aberrante", HEADER_TOO_LARGE,
                f -> f.rawWithLength(0x00204000, "UT", ascii("ab"), 0x7FFFFFF0L)));
        t.add(trap("en-tête au-delà de 1 Mio", HEADER_TOO_LARGE, f -> {
            f.raw(0x00091010, "OB", new byte[600_000]);
            f.raw(0x00091011, "OB", new byte[600_000]);
        }));
        t.add(trap("méta-informations au-delà de 1 Mio", HEADER_TOO_LARGE, f -> f.extraFmi =
                DicomFixture.element(0x00020102, "OB", new byte[1_100_000], 1_100_000, true)));
        t.add(trap("plus de 5 000 éléments dans les méta-informations", TOO_MANY_ELEMENTS, f -> {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < 5_001; i++) o.writeBytes(DicomFixture.element(0x00021000 + i, "LO", ascii("ab"), 2, true));
            f.extraFmi = o.toByteArray();
        }));
        t.add(trap("longueur indéfinie hors séquence", MALFORMED,
                f -> f.rawWithLength(0x00204000, "UT", ascii("ab"), 0xFFFFFFFFL)));
        t.add(trap("profondeur de séquence 9", SEQUENCE_TOO_DEEP, f -> f.encoded(0x00081110, f.nested(9))));
        t.add(trap("plus de 5 000 éléments", TOO_MANY_ELEMENTS, f -> {
            for (int i = 0; i < 5_001; i++) f.raw(0x00091000 + i, "LO", ascii("ab"));
        }));
        t.add(trap("en-tête tronqué", MALFORMED, f -> {
            f.pixelDataPresent = false;
            f.raw(0x00204000, "UT", new byte[0]);
            f.rawWithLength(0x00204000, "UT", ascii("abcd"), 40);
        }));
        // Syntaxes de transfert.
        t.add(trap("JPEG Baseline", UNSUPPORTED_TRANSFER_SYNTAX, f -> f.transferSyntax = "1.2.840.10008.1.2.4.50"));
        t.add(trap("Explicit VR Big Endian", UNSUPPORTED_TRANSFER_SYNTAX, f -> f.transferSyntax = "1.2.840.10008.1.2.2"));
        t.add(trap("Deflated Explicit VR LE", UNSUPPORTED_TRANSFER_SYNTAX,
                f -> f.transferSyntax = "1.2.840.10008.1.2.1.99"));
        // Profil.
        t.add(trap("Rows × Columns au-delà de 36 000 000", IMAGE_TOO_LARGE, f -> {
            f.us(0x00280010, 6_000);
            f.us(0x00280011, 6_001);
        }));
        t.add(trap("Rows au-delà de 8 192", IMAGE_TOO_LARGE, f -> f.us(0x00280010, 8_193)));
        t.add(trap("Rows nul", MALFORMED, f -> f.us(0x00280010, 0)));
        t.add(trap("multi-image (NumberOfFrames 2)", UNSUPPORTED_PROFILE, f -> f.str(0x00280008, "IS", "2")));
        t.add(trap("RGB (SamplesPerPixel 3)", UNSUPPORTED_PROFILE, f -> {
            f.us(0x00280002, 3);
            f.str(0x00280004, "CS", "RGB");
        }));
        t.add(trap("PALETTE COLOR", UNSUPPORTED_PROFILE, f -> f.str(0x00280004, "CS", "PALETTE COLOR")));
        t.add(trap("BitsAllocated 32", UNSUPPORTED_PROFILE, f -> {
            f.us(0x00280100, 32);
            f.us(0x00280101, 32);
            f.us(0x00280102, 31);
        }));
        t.add(trap("HighBit incohérent", UNSUPPORTED_PROFILE, f -> f.us(0x00280102, 15)));
        t.add(trap("BitsStored 7", UNSUPPORTED_PROFILE, f -> {
            f.us(0x00280101, 7);
            f.us(0x00280102, 6);
        }));
        t.add(trap("modalité US", UNSUPPORTED_MODALITY, f -> f.str(0x00080060, "CS", "US")));
        t.add(trap("modalité absente", UNSUPPORTED_MODALITY, f -> f.remove(0x00080060)));
        t.add(trap("SOPInstanceUID absent", MALFORMED, f -> f.remove(0x00080018)));
        // Textes.
        t.add(trap("<script> dans un attribut texte", SUSPICIOUS_TEXT,
                f -> f.str(0x00080080, "LO", "<script>alert(1)</script>")));
        t.add(trap("caractère de contrôle dans un attribut texte", SUSPICIOUS_TEXT,
                f -> f.str(0x00080080, "LO", "HOPITAL\u0007")));
        // Données de pixels.
        t.add(trap("PixelData absent", PIXEL_DATA_MISSING, f -> f.pixelDataPresent = false));
        t.add(trap("PixelData tronqué", PIXEL_DATA_TRUNCATED, f -> {
            f.pixelLength = (long) f.pixelData.length;
            f.pixelData = Arrays.copyOf(f.pixelData, f.pixelData.length - 100);
        }));
        t.add(trap("PixelData trop long", PIXEL_DATA_LENGTH_MISMATCH, f -> f.pixelData = new byte[ROWS * COLS * 2 + 2]));
        t.add(trap("PixelData trop court", PIXEL_DATA_LENGTH_MISMATCH, f -> f.pixelData = new byte[ROWS * COLS * 2 - 2]));
        t.add(trap("PixelData encapsulé dans une syntaxe non compressée", MALFORMED, f -> {
            f.pixelVr = "OB";
            f.pixelLength = 0xFFFFFFFFL;
        }));
        t.add(trap("valeur de pixel hors de la profondeur déclarée", PIXEL_VALUE_OUT_OF_RANGE, f -> {
            f.pixelData[200] = 0x00;
            f.pixelData[201] = 0x10; // 4 096 sur 12 bits
        }));
        t.add(trap("PixelPaddingValue hors de la profondeur déclarée", PIXEL_VALUE_OUT_OF_RANGE,
                f -> f.us(0x00280120, 0x2000)));
        return t.stream();
    }

    private static byte[] build(Trap trap) {
        DicomFixture f = DicomFixture.ct(ROWS, COLS);
        f.str(0x00080080, "LO", "HOPITAL DE TEST");
        trap.setup().accept(f);
        return f.bytes();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("traps")
    void trapIsRejectedWithItsReasonAndAFixedMessage(Trap trap) {
        byte[] file = build(trap);
        long declared = trap.declaredSize() != null ? trap.declaredSize() : file.length;
        List<DicomAuditSink.Event> events = new ArrayList<>();
        DicomReader reader = new DicomReader(events::add);
        DicomRejectedException e = assertThrows(DicomRejectedException.class,
                () -> reader.read(new ByteArrayInputStream(file), declared));
        assertEquals(trap.expected(), e.reason(), trap.name());
        assertMessageIsFixed(e);
        if (trap.expected() == SUSPICIOUS_PREAMBLE) {
            assertEquals(List.of(DicomAuditSink.Event.DICOM_SUSPICIOUS_PREAMBLE), events);
        } else {
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void atLeastThirtyTraps() {
        assertTrue(traps().count() >= 30);
    }

    @Test
    void baseFileIsAccepted() throws DicomRejectedException {
        byte[] file = DicomFixture.ct(ROWS, COLS).bytes();
        DicomImage image = new DicomReader(DicomAuditSink.NONE).read(new ByteArrayInputStream(file), file.length);
        assertEquals(Markability.MARKABLE, image.markability());
    }

    @Test
    void sequenceDepthEightIsAccepted() throws DicomRejectedException {
        DicomFixture f = DicomFixture.ct(ROWS, COLS);
        f.encoded(0x00081110, f.nested(8));
        byte[] file = f.bytes();
        DicomImage image = new DicomReader(DicomAuditSink.NONE).read(new ByteArrayInputStream(file), file.length);
        assertEquals(Markability.MARKABLE, image.markability());
    }

    /** Message = texte fixe du motif : aucun chiffre, aucune chaîne du fichier, aucune cause. */
    static void assertMessageIsFixed(DicomRejectedException e) {
        assertEquals(e.reason().message(), e.getMessage());
        assertFalse(e.getMessage().matches(".*\\d.*"), "chiffre dans le message");
        for (String fileValue : List.of("script", "HOPITAL", "FANTOME", "2.25", "MONOCHROME", "CT", "DICM")) {
            assertFalse(e.getMessage().contains(fileValue), "valeur du fichier dans le message");
            assertFalse(e.toString().contains(fileValue), "valeur du fichier dans toString");
        }
        assertEquals(null, e.getCause());
        assertEquals(0, e.getSuppressed().length);
    }
}
