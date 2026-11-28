package tn.medimagetrust.dicom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.embedding.Photometric;
import tn.medimagetrust.embedding.PixelImage;

/** Lecture de fichiers valides : syntaxes, profondeurs, représentation signée, remplissage, statuts, délai. */
class DicomReaderTest {
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @AfterAll
    static void shutdown() {
        EXECUTOR.shutdownNow();
    }

    static DicomImage read(byte[] file) throws DicomRejectedException {
        return new DicomReader(DicomAuditSink.NONE).read(new ByteArrayInputStream(file), file.length);
    }

    private static PixelImage ramp(int rows, int cols, int bits) {
        int[] v = new int[rows * cols];
        int max = (1 << bits) - 1;
        for (int i = 0; i < v.length; i++) v[i] = (i * 37) % (max + 1);
        return PixelImage.fromInts(rows, cols, bits, Photometric.MONOCHROME2, v, false, false);
    }

    private static void assertSamePixels(PixelImage expected, PixelImage actual) {
        assertEquals(expected.pixelCount(), actual.pixelCount());
        for (int i = 0; i < expected.pixelCount(); i++) {
            assertEquals(expected.pixel(i), actual.pixel(i), "pixel " + i);
        }
    }

    @Test
    void explicitTwelveBits() throws DicomRejectedException {
        PixelImage img = ramp(64, 80, 12);
        DicomImage d = read(DicomFixture.of(img, 16, 0).bytes());
        assertEquals(DicomFixture.EXPLICIT_LE, d.transferSyntaxUid());
        assertEquals("CT", d.modality());
        assertEquals(64, d.rows());
        assertEquals(80, d.cols());
        assertEquals(16, d.bitsAllocated());
        assertEquals(12, d.bitsStored());
        assertEquals(11, d.highBit());
        assertEquals(0, d.pixelRepresentation());
        assertEquals(Optional.of("-1024"), d.rescaleIntercept());
        assertTrue(d.pixelPaddingValue().isEmpty());
        assertEquals(DicomFixture.TEST_SOP_INSTANCE, d.sopInstanceUid());
        assertEquals(Markability.MARKABLE, d.markability());
        assertSamePixels(img, d.image());
        assertEquals("DicomImage[contenu masqué]", d.toString());
    }

    @Test
    void implicitLittleEndianIsAccepted() throws DicomRejectedException {
        PixelImage img = ramp(64, 64, 12);
        // La fabrique encode en Explicit VR : les éléments sont réencodés en Implicit VR.
        DicomFixture implicit = DicomFixture.of(img, 16, 0);
        implicit.transferSyntax = DicomFixture.IMPLICIT_LE;
        implicit.elements.clear();
        DicomFixture ref = DicomFixture.of(img, 16, 0);
        reencode(ref, implicit);
        DicomImage d = read(implicit.bytes());
        assertEquals(DicomFixture.IMPLICIT_LE, d.transferSyntaxUid());
        assertSamePixels(img, d.image());
    }

    /** Réencode les éléments simples de {@code from} (Explicit VR) en Implicit VR dans {@code to}. */
    static void reencode(DicomFixture from, DicomFixture to) {
        for (byte[] e : from.elements.values()) {
            int tag = (e[0] & 0xFF) | (e[1] & 0xFF) << 8;
            tag = tag << 16 | (e[2] & 0xFF) | (e[3] & 0xFF) << 8;
            int len = (e[6] & 0xFF) | (e[7] & 0xFF) << 8;
            byte[] value = java.util.Arrays.copyOfRange(e, 8, 8 + len);
            to.elements.put(tag & 0xFFFFFFFFL, DicomFixture.element(tag, null, value, len, false));
        }
    }

    @Test
    void eightBitsWithOddPixelCount() throws DicomRejectedException {
        PixelImage img = ramp(63, 65, 8);
        DicomFixture f = DicomFixture.of(img, 8, 0);
        DicomImage d = read(f.bytes());
        assertEquals(8, d.bitsAllocated());
        assertSamePixels(img, d.image());
    }

    @Test
    void signedRepresentationIsShiftedExactly() throws DicomRejectedException {
        // Valeurs stockées signées de −2 048 à 2 047 : l'image de l'embedding vaut valeur + 2 048.
        PixelImage img = ramp(64, 64, 12);
        DicomFixture f = DicomFixture.of(img, 16, 1);
        f.ss(0x00280120, -2048);
        DicomImage d = read(f.bytes());
        assertEquals(1, d.pixelRepresentation());
        assertEquals(2048, d.storedValueOffset());
        assertSamePixels(img, d.image());
        assertEquals(-2048, d.pixelPaddingValue().getAsInt());
        assertEquals(0, d.image().pixelPaddingValue().getAsInt());
    }

    @Test
    void negativeStoredValueOutOfDepthIsRejected() {
        PixelImage img = ramp(64, 64, 12);
        DicomFixture f = DicomFixture.of(img, 16, 1);
        f.pixelData[10] = 0x00;
        f.pixelData[11] = (byte) 0xF0; // −4 096 : hors de [−2 048, 2 047]
        DicomRejectedException e = assertThrows(DicomRejectedException.class, () -> read(f.bytes()));
        assertEquals(DicomRejectedException.Reason.PIXEL_VALUE_OUT_OF_RANGE, e.reason());
    }

    @Test
    void pixelPaddingValueIsPassedToEmbedding() throws DicomRejectedException {
        DicomFixture f = DicomFixture.of(ramp(64, 64, 12), 16, 0);
        f.us(0x00280120, 0);
        DicomImage d = read(f.bytes());
        assertEquals(0, d.pixelPaddingValue().getAsInt());
        assertEquals(0, d.image().pixelPaddingValue().getAsInt());
    }

    @Test
    void mammographyIsAccepted() throws DicomRejectedException {
        DicomFixture f = DicomFixture.of(ramp(64, 64, 12), 16, 0);
        f.str(0x00080060, "CS", "MG");
        assertEquals("MG", read(f.bytes()).modality());
    }

    @Test
    void readableButNotMarkable() throws DicomRejectedException {
        DicomFixture burned = DicomFixture.ct(64, 64);
        burned.str(0x00280301, "CS", "YES");
        assertEquals(Markability.BURNED_IN_ANNOTATION, read(burned.bytes()).markability());
        assertTrue(read(burned.bytes()).image().burnedInAnnotation());

        DicomFixture overlay = DicomFixture.ct(64, 64);
        overlay.us(0x60000010, 64);
        assertEquals(Markability.OVERLAY_PRESENT, read(overlay.bytes()).markability());

        DicomFixture curve = DicomFixture.ct(64, 64);
        curve.us(0x50000005, 2);
        assertEquals(Markability.CURVE_PRESENT, read(curve.bytes()).markability());

        DicomFixture range = DicomFixture.ct(64, 64);
        range.us(0x00280120, 0);
        range.us(0x00280121, 10);
        DicomImage r = read(range.bytes());
        assertEquals(Markability.PADDING_RANGE_UNSUPPORTED, r.markability());
        assertEquals(10, r.pixelPaddingRangeLimit().getAsInt());
        assertFalse(r.markability().markable());

        DicomFixture no = DicomFixture.ct(64, 64);
        no.str(0x00280301, "CS", "NO");
        assertEquals(Markability.MARKABLE, read(no.bytes()).markability());
    }

    @Test
    void blockedStreamTimesOut() {
        byte[] head = DicomFixture.ct(64, 64).bytes();
        InputStream stalled = new InputStream() {
            int pos;

            @Override
            public int read() throws IOException {
                if (pos < 300) return head[pos++] & 0xFF;
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrompu");
                }
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                int c = read();
                if (c < 0) return -1;
                b[off] = (byte) c;
                return 1;
            }
        };
        DicomReader reader = new DicomReader(DicomAuditSink.NONE, EXECUTOR, Duration.ofMillis(300));
        long start = System.nanoTime();
        DicomRejectedException e = assertThrows(DicomRejectedException.class, () -> reader.read(stalled, head.length));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(DicomRejectedException.Reason.TIMEOUT, e.reason());
        assertTrue(ms < 5_000, "rejet en " + ms + " ms");
    }

    @Test
    void slowStreamTimesOut() {
        byte[] file = DicomFixture.ct(64, 64).bytes();
        InputStream slow = new InputStream() {
            int pos;

            @Override
            public int read() throws IOException {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrompu");
                }
                return pos < file.length ? file[pos++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                int c = read();
                if (c < 0) return -1;
                b[off] = (byte) c;
                return 1;
            }
        };
        DicomReader reader = new DicomReader(DicomAuditSink.NONE, EXECUTOR, Duration.ofMillis(300));
        DicomRejectedException e = assertThrows(DicomRejectedException.class, () -> reader.read(slow, file.length));
        assertEquals(DicomRejectedException.Reason.TIMEOUT, e.reason());
    }
}
