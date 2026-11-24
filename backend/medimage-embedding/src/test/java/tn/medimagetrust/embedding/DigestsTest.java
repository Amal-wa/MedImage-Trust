package tn.medimagetrust.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("SEC-D03")
@DisplayName("Condensats roiDigest, maskDigest, sDigest : encodage figé, sensibles à la ROI, insensibles à F")
class DigestsTest {
    private static final long TEST_SEED_DIGEST = 0x44494753L;

    /** Image 16 × 16, 12 bits, pixel = 17·r + c ; blocs 8 × 8 numérotés 0 à 3 ; RONI = blocs 0 et 3. */
    private static PixelImage tinyImage() {
        int[] px = new int[256];
        for (int r = 0; r < 16; r++) for (int c = 0; c < 16; c++) px[r * 16 + c] = 17 * r + c;
        return PixelImage.fromInts(16, 16, 12, Photometric.MONOCHROME2, px, false, false);
    }

    private static RoniProfile tinyProfile() {
        return new RoniProfile(16, 16, 30, BackgroundPolarity.DARK, new int[] {0, 3});
    }

    private static byte[] sha(byte[] in) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(in);
    }

    @Test
    @DisplayName("roiDigest = SHA-256(\"MIT-ROI-v1\" ‖ rows(4) ‖ cols(4) ‖ bits(1) ‖ pixels hors RONI sur 2 octets)")
    void roiEncodingIsExact() throws Exception {
        PixelImage img = tinyImage();
        RoniProfile p = tinyProfile();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("MIT-ROI-v1".getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[] {0, 0, 0, 16, 0, 0, 0, 16, 12});
        for (int r = 0; r < 16; r++) {
            for (int c = 0; c < 16; c++) {
                boolean roni = (r < 8 && c < 8) || (r >= 8 && c >= 8);
                if (!roni) {
                    int v = 17 * r + c;
                    out.write(v >>> 8);
                    out.write(v & 0xFF);
                }
            }
        }
        assertArrayEquals(sha(out.toByteArray()), Digests.roiDigest(img, p));
    }

    @Test
    @DisplayName("maskDigest = SHA-256(\"MIT-MASK-v1\" ‖ rows(4) ‖ cols(4) ‖ masque compacté, poids fort d'abord)")
    void maskEncodingIsExact() throws Exception {
        RoniProfile p = tinyProfile();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("MIT-MASK-v1".getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[] {0, 0, 0, 16, 0, 0, 0, 16});
        // Lignes 0 à 7 : 8 pixels RONI puis 8 hors RONI → 0xFF 0x00 ; lignes 8 à 15 : 0x00 0xFF.
        for (int r = 0; r < 16; r++) {
            out.write(r < 8 ? 0xFF : 0x00);
            out.write(r < 8 ? 0x00 : 0xFF);
        }
        assertArrayEquals(sha(out.toByteArray()), Digests.maskDigest(p));
    }

    @Test
    @DisplayName("Masque compacté : dernier octet complété par des zéros (image 3 × 3)")
    void packedMaskPadding() {
        RoniProfile empty = new RoniProfile(3, 3, 0, BackgroundPolarity.DARK, new int[0]);
        assertEquals(2, empty.packedMask().length);
        assertArrayEquals(new byte[] {0, 0}, empty.packedMask());
    }

    @Test
    @DisplayName("Vecteurs fixes (non-régression pour le module watermark)")
    void fixedVectors() {
        HexFormat hex = HexFormat.of();
        assertEquals("f5a5fd42d16a20302798ef6ed309979b43003d2320d9f0e8ea9831a92759fb4b",
                hex.formatHex(Digests.sDigest(new byte[64])));
        assertEquals(ROI_VECTOR, hex.formatHex(Digests.roiDigest(tinyImage(), tinyProfile())));
        assertEquals(MASK_VECTOR, hex.formatHex(Digests.maskDigest(tinyProfile())));
    }

    // Vecteurs recalculés par une implémentation indépendante (Python, hashlib + struct) sur la même image 16 × 16.
    static final String ROI_VECTOR ="f1512a464cb98064ff8ca59726ee5301211cb94b761dc5893835e9617a079549";
    static final String MASK_VECTOR = "1f3b65c09c2938ca9acb4d2cf37601a64b53e455e68300756f4437e690833401";

    @Test
    @DisplayName("Même entrée → même condensat ; un pixel de ROI modifié → roiDigest différent")
    void roiDigestSensitiveToRoi() {
        PixelImage img = Phantoms.normal().image();
        RoniProfile p = RoniDetector.detect(img);
        byte[] d1 = Digests.roiDigest(img, p);
        assertArrayEquals(d1, Digests.roiDigest(Phantoms.normal().image(), p));
        int roiPixel = 0;
        while (p.inRoni(roiPixel)) roiPixel++;
        char[] px = img.pixels();
        px[roiPixel] ^= 1;
        assertFalse(java.util.Arrays.equals(d1, Digests.roiDigest(img.withPixels(px), p)));
    }

    @Test
    @DisplayName("Pixels F (et S, W) modifiés par le marquage → roiDigest identique")
    void roiDigestInsensitiveToMarks() {
        PixelImage img = Phantoms.normal().image();
        Markings.Marking m = Markings.markRandom(img, new Random(TEST_SEED_DIGEST));
        assertFalse(img.equals(m.marked()));
        assertArrayEquals(Digests.roiDigest(img, m.profile()), Digests.roiDigest(m.marked(), m.profile()));
    }

    @Test
    @DisplayName("Un bloc de plus ou de moins dans le masque → maskDigest différent ; S différent → sDigest différent")
    void maskAndSDigestsAreSensitive() {
        RoniProfile a = new RoniProfile(16, 16, 30, BackgroundPolarity.DARK, new int[] {0, 3});
        RoniProfile b = new RoniProfile(16, 16, 30, BackgroundPolarity.DARK, new int[] {0});
        RoniProfile c = new RoniProfile(16, 16, 99, BackgroundPolarity.LIGHT, new int[] {0, 3});
        assertFalse(java.util.Arrays.equals(Digests.maskDigest(a), Digests.maskDigest(b)));
        assertArrayEquals(Digests.maskDigest(a), Digests.maskDigest(c)); // seul le masque compte
        byte[] s = new byte[64];
        byte[] s2 = new byte[64];
        s2[63] = 1;
        assertFalse(java.util.Arrays.equals(Digests.sDigest(s), Digests.sDigest(s2)));
    }

    @Test
    @DisplayName("Entrées invalides : INVALID_INPUT")
    void invalidInputs() {
        assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                assertThrows(EmbeddingException.class, () -> Digests.sDigest(new byte[63])).reason());
        assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                assertThrows(EmbeddingException.class, () -> Digests.sDigest(null)).reason());
        assertEquals(EmbeddingException.Reason.INVALID_INPUT,
                assertThrows(EmbeddingException.class, () -> Digests.maskDigest(null)).reason());
        assertEquals(EmbeddingException.Reason.INVALID_INPUT, assertThrows(EmbeddingException.class,
                () -> Digests.roiDigest(Phantoms.small64().image(), tinyProfile())).reason());
    }
}
