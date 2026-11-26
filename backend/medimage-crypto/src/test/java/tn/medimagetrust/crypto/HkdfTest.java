package tn.medimagetrust.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class HkdfTest {

    // Vecteur officiel RFC 5869 - Appendix A.1 (Test Case 1)
    @Test
    void vecteurOfficielRfc5869Cas1() {
        HexFormat hex = HexFormat.of();
        byte[] ikm = hex.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = hex.parseHex("000102030405060708090a0b0c");
        byte[] info = hex.parseHex("f0f1f2f3f4f5f6f7f8f9");
        byte[] attendu = hex.parseHex(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");

        assertArrayEquals(attendu, Hkdf.derive(ikm, salt, info, 42));
    }

    // Vecteur officiel RFC 5869 - Appendix A.3 (Test Case 3 : salt et info vides)
    @Test
    void vecteurOfficielRfc5869Cas3SansSaltNiInfo() {
        HexFormat hex = HexFormat.of();
        byte[] ikm = hex.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] attendu = hex.parseHex(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8");

        assertArrayEquals(attendu, Hkdf.derive(ikm, null, new byte[0], 42));
    }

    @Test
    void contextesDifferentsProduisentDesClesIndependantes() {
        byte[] master = Hmac.generateKey();
        byte[] kTok = Hkdf.derive256(master, null, "MIT-KTOK-v1");
        byte[] kLog = Hkdf.derive256(master, null, "MIT-KLOG-v1");

        assertEquals(32, kTok.length);
        assertEquals(32, kLog.length);
        assertFalse(Arrays.equals(kTok, kLog));
    }
}
