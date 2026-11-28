package tn.medimagetrust.dicom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomInputStream;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.embedding.EmbeddingResult;
import tn.medimagetrust.embedding.FBlockReader;
import tn.medimagetrust.embedding.LsbChannel;
import tn.medimagetrust.embedding.Partitions;
import tn.medimagetrust.embedding.Phantoms;
import tn.medimagetrust.embedding.Photometric;
import tn.medimagetrust.embedding.PixelImage;
import tn.medimagetrust.embedding.RoniProfile;

/** Écriture de la copie (SEC-D07) et mode signé. */
class DicomWriterTest {
    private static final long SEED = 0x44434D57L;
    private static final byte[] PRIVATE_VALUE = "VALEUR-PRIVEE-A-RETIRER!".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TRAILING = "MZ-OCTETS-DE-FIN-A-IGNORER".getBytes(StandardCharsets.US_ASCII);

    /** Fichier lu, copie écrite et copie relue. */
    record RoundTrip(DicomImage original, DicomMarking.Marked marked, byte[] copyBytes, String newUid,
                     DicomImage copy) {}

    static RoundTrip roundTrip(DicomFixture f, long seed) throws Exception {
        byte[] file = f.bytes();
        DicomImage original = DicomReaderTest.read(file);
        DicomMarking.Marked m = DicomMarking.mark(original.image(), seed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String uid = new DicomWriter().writeCopy(original, m.result(), out);
        byte[] copyBytes = out.toByteArray();
        return new RoundTrip(original, m, copyBytes, uid, DicomReaderTest.read(copyBytes));
    }

    private static PixelImage phantom(int bits, int bg, int bgNoise, int body, int bodyNoise) {
        return Phantoms.build(new Phantoms.Spec(512, 512, bits, Photometric.MONOCHROME2, bg, bgNoise, body, bodyNoise,
                256, 256, 150, 190), SEED).image();
    }

    private static Attributes parse(byte[] bytes, Attributes[] fmi, long[] pixelEnd) throws IOException {
        try (DicomInputStream dis = new DicomInputStream(new ByteArrayInputStream(bytes))) {
            fmi[0] = dis.getFileMetaInformation();
            Attributes ds = dis.readDatasetUntilPixelData();
            pixelEnd[0] = dis.getPosition() + dis.length();
            return ds;
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static void collectTags(Attributes a, Set<Integer> out) {
        for (int tag : a.tags()) {
            out.add(tag);
            if (a.getVR(tag) == org.dcm4che3.data.VR.SQ) {
                for (Attributes item : a.getSequence(tag)) collectTags(item, out);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- SEC-D07

    @Test
    void copyIsRebuiltWithoutPrivateElementsNorTrailingBytes() throws Exception {
        PixelImage img = phantom(12, 200, 40, 2000, 200);
        DicomFixture f = DicomFixture.of(img, 16, 0);
        f.str(0x00090010, "LO", "CREATEUR PRIVE");
        f.raw(0x00091001, "LO", PRIVATE_VALUE);
        f.encoded(0x00081110, f.sequence(0x00081110,
                DicomFixture.element(0x00291001, "LO", PRIVATE_VALUE, PRIVATE_VALUE.length, true)));
        f.str(0x00280106, "US", "ab"); // SmallestImagePixelValue (2 octets)
        f.trailing = TRAILING;
        RoundTrip rt = roundTrip(f, SEED);
        assertFalse(rt.marked().result().signedOutput());
        byte[] copy = rt.copyBytes();

        // Préambule nul, signature, syntaxe Explicit VR LE.
        for (int i = 0; i < 128; i++) assertEquals(0, copy[i], "préambule");
        assertEquals("DICM", new String(copy, 128, 4, StandardCharsets.US_ASCII));
        assertEquals(UID.ExplicitVRLittleEndian, rt.copy().transferSyntaxUid());

        // Ni élément privé, ni octets de fin, rien après les données de pixels.
        assertFalse(contains(copy, PRIVATE_VALUE));
        assertFalse(contains(copy, TRAILING));
        Attributes[] fmi = new Attributes[1];
        long[] end = new long[1];
        Attributes ds = parse(copy, fmi, end);
        assertEquals(copy.length, end[0], "octets après les données de pixels");
        Set<Integer> tags = new HashSet<>();
        collectTags(ds, tags);
        for (int tag : tags) assertEquals(0, (tag >>> 16) & 1, "élément privé");
        assertFalse(ds.contains(Tag.SmallestImagePixelValue));

        // Nouveau SOPInstanceUID, repris dans les méta-informations.
        assertTrue(rt.newUid().startsWith("2.25."));
        assertTrue(rt.newUid().length() <= 64);
        assertNotEquals(rt.original().sopInstanceUid(), rt.newUid());
        assertEquals(rt.newUid(), ds.getString(Tag.SOPInstanceUID));
        assertEquals(rt.newUid(), fmi[0].getString(Tag.MediaStorageSOPInstanceUID));

        // Tous les autres attributs identiques (en dehors des éléments retirés et du SOPInstanceUID).
        Attributes expected = DicomWriter.sanitizedCopy(rt.original().dataset());
        expected.setString(Tag.SOPInstanceUID, org.dcm4che3.data.VR.UI, rt.newUid());
        assertEquals(expected, ds);

        // Pixels : identiques sauf dans la RONI ; ROI identique ; PSNR ≥ 40 dB.
        RoniProfile profile = rt.marked().session().profile();
        PixelImage o = rt.original().image();
        PixelImage c = rt.copy().image();
        int changed = 0;
        for (int i = 0; i < o.pixelCount(); i++) {
            if (o.pixel(i) != c.pixel(i)) {
                assertTrue(profile.inRoni(i), "pixel de ROI modifié");
                changed++;
            }
        }
        assertTrue(changed > 0);
        assertTrue(DicomMarking.psnr(o, c) >= 40.0);
        assertSamePixels(rt.marked().result().image(), c);
    }

    @Test
    void implicitOriginalIsWrittenExplicit() throws Exception {
        PixelImage img = phantom(12, 200, 40, 2000, 200);
        DicomFixture ref = DicomFixture.of(img, 16, 0);
        DicomFixture implicit = DicomFixture.of(img, 16, 0);
        implicit.transferSyntax = DicomFixture.IMPLICIT_LE;
        implicit.elements.clear();
        DicomReaderTest.reencode(ref, implicit);
        RoundTrip rt = roundTrip(implicit, SEED + 1);
        assertEquals(DicomFixture.IMPLICIT_LE, rt.original().transferSyntaxUid());
        assertEquals(UID.ExplicitVRLittleEndian, rt.copy().transferSyntaxUid());
        assertSamePixels(rt.marked().result().image(), rt.copy().image());
    }

    @Test
    void nonMarkableOriginalIsRefused() throws Exception {
        DicomFixture f = DicomFixture.of(phantom(12, 200, 40, 2000, 200), 16, 0);
        DicomImage markable = DicomReaderTest.read(f.bytes());
        EmbeddingResult result = DicomMarking.mark(markable.image(), SEED).result();
        f.str(0x00280301, "CS", "YES");
        DicomImage burned = DicomReaderTest.read(f.bytes());
        assertThrows(IllegalArgumentException.class,
                () -> new DicomWriter().writeCopy(burned, result, new ByteArrayOutputStream()));
    }

    // ---------------------------------------------------------------------------------------------- mode signé

    @Test
    void signedCopyTwelveBitsZeroBackground() throws Exception {
        assertSignedRoundTrip(DicomFixture.of(phantom(12, 0, 0, 2000, 200), 16, 0), 12, 0);
    }

    @Test
    void signedCopyEightBitsZeroBackground() throws Exception {
        RoundTrip rt = assertSignedRoundTrip(DicomFixture.of(phantom(8, 0, 0, 153, 12), 8, 0), 8, 0);
        assertEquals(8, rt.original().bitsAllocated());
    }

    @Test
    void signedCopyOfSignedOriginal() throws Exception {
        // Original signé dont le fond est au minimum stockable (−2 048 en 12 bits) : même cas que le fond à 0.
        assertSignedRoundTrip(DicomFixture.of(phantom(12, 0, 0, 2000, 200), 16, 1), 12, 1);
    }

    private static RoundTrip assertSignedRoundTrip(DicomFixture f, int b, int pr) throws Exception {
        // Valeur de remplissage au-dessus du corps et loin du fond marqué (une valeur proche de 0 serait atteinte par
        // le marquage du fond et rendrait le masque instable) : la RONI n'est pas réduite, et la réécriture en SS est
        // vérifiée.
        int padding = b == 8 ? 250 : 3000;
        if (pr == 1) f.ss(0x00280120, padding - (1 << (b - 1))); else f.us(0x00280120, padding);
        RoundTrip rt = roundTrip(f, SEED + b + pr);
        EmbeddingResult result = rt.marked().result();
        assertTrue(result.signedOutput(), "mode signé attendu");
        DicomImage copy = rt.copy();

        // Attributs signés 1 / 16 / 16 / 15 ; le reste de l'interprétation identique.
        assertEquals(1, copy.pixelRepresentation());
        assertEquals(16, copy.bitsAllocated());
        assertEquals(16, copy.bitsStored());
        assertEquals(15, copy.highBit());
        Attributes expected = DicomWriter.sanitizedCopy(rt.original().dataset());
        expected.setString(Tag.SOPInstanceUID, org.dcm4che3.data.VR.UI, rt.newUid());
        expected.setInt(Tag.PixelRepresentation, org.dcm4che3.data.VR.US, 1);
        expected.setInt(Tag.BitsAllocated, org.dcm4che3.data.VR.US, 16);
        expected.setInt(Tag.BitsStored, org.dcm4che3.data.VR.US, 16);
        expected.setInt(Tag.HighBit, org.dcm4che3.data.VR.US, 15);
        expected.setInt(Tag.PixelPaddingValue, org.dcm4che3.data.VR.SS,
                rt.original().pixelPaddingValue().getAsInt());
        assertEquals(expected, copy.dataset());
        assertEquals(rt.original().pixelPaddingValue(), copy.pixelPaddingValue());

        // Valeurs négatives relues correctement ; valeurs dans le domaine de l'émission identiques.
        PixelImage verify = copy.signedCopyImage(b, pr);
        PixelImage marked = result.image();
        int negatives = 0;
        for (int i = 0; i < marked.pixelCount(); i++) {
            if (copy.image().pixel(i) - copy.storedValueOffset() < 0) negatives++;
        }
        assertTrue(negatives > 0, "valeurs stockées négatives attendues");
        assertSamePixels(marked, verify);

        // Mêmes blocs W, S, F que la copie émise, avec le B du registre.
        Partitions p = rt.marked().session().partitions();
        assertArrayEquals(rt.marked().s(), LsbChannel.reader(verify, p).readS());
        assertArrayEquals(rt.marked().w(), LsbChannel.reader(verify, p).readW());
        FBlockReader fromCopy = FBlockReader.of(verify, p, b);
        FBlockReader fromEmission = FBlockReader.of(marked, p, b);
        int[] idx = rt.marked().fIndices();
        for (int k = 0; k < idx.length; k++) {
            assertArrayEquals(fromEmission.read(idx[k], rt.marked().dither()[k]),
                    fromCopy.read(idx[k], rt.marked().dither()[k]));
        }
        return rt;
    }

    private static void assertSamePixels(PixelImage expected, PixelImage actual) {
        assertEquals(expected.pixelCount(), actual.pixelCount());
        for (int i = 0; i < expected.pixelCount(); i++) {
            if (expected.pixel(i) != actual.pixel(i)) {
                assertEquals(expected.pixel(i), actual.pixel(i), "pixel " + i);
            }
        }
    }
}
