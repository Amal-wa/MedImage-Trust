package tn.medimagetrust.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import java.util.stream.Stream;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Vecteurs officiels AES-256-GCM du NIST (dossier v2.6 §8.7 : « vecteurs officiels à chaque commit »).
 *
 * <p>Source : programme CAVP du NIST, archive {@code gcmtestvectors.zip}, fichier {@code gcmEncryptExtIV256.rsp}
 * (« CAVS 14.0, GCM Encrypt with keysize 256 »), section {@code [Keylen = 256] [IVlen = 96] [PTlen = 128]
 * [AADlen = 128] [Taglen = 128]}, entrées {@code Count = 0} et {@code Count = 1}, recopiées sans modification.
 * Chaque vecteur est vérifié en chiffrement (JCA, nonce imposé) et en déchiffrement ({@link AesGcm#decrypt}, format
 * {@code nonce ‖ chiffré ‖ étiquette}), puis avec une étiquette et une AAD altérées.</p>
 */
@DisplayName("AES-256-GCM : vecteurs officiels NIST CAVP (gcmEncryptExtIV256)")
class AesGcmNistVectorsTest {

    private static final HexFormat HEX = HexFormat.of();

    static Stream<Arguments> vecteurs() {
        return Stream.of(
                Arguments.of("Count = 0",
                        "92e11dcdaa866f5ce790fd24501f92509aacf4cb8b1339d50c9c1240935dd08b",
                        "ac93a1a6145299bde902f21a",
                        "2d71bcfa914e4ac045b2aa60955fad24",
                        "1e0889016f67601c8ebea4943bc23ad6",
                        "8995ae2e6df3dbf96fac7b7137bae67f",
                        "eca5aa77d51d4a0a14d9c51e1da474ab"),
                Arguments.of("Count = 1",
                        "7da3bccaffb3464178ca7c722379836db50ce0bfb47640b9572163865332e486",
                        "c04fd2e701c3dc62b68738b3",
                        "fd671cab1ee21f0df6bb610bf94f0e69",
                        "fec0311013202e4ffdc4204926ae0ddf",
                        "6be61b17b7f7d494a7cdf270562f37ba",
                        "5e702a38323fe1160b780d17adad3e96"));
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vecteurs")
    @DisplayName("Chiffrement : chiffré et étiquette identiques au vecteur")
    void chiffrement(String nom, String key, String iv, String pt, String aad, String ct, String tag) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(HEX.parseHex(key), "AES"),
                new GCMParameterSpec(128, HEX.parseHex(iv)));
        c.updateAAD(HEX.parseHex(aad));
        assertEquals(ct + tag, HEX.formatHex(c.doFinal(HEX.parseHex(pt))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vecteurs")
    @DisplayName("Déchiffrement par AesGcm : texte clair du vecteur ; étiquette ou AAD altérée refusée")
    void dechiffrement(String nom, String key, String iv, String pt, String aad, String ct, String tag) {
        SecretKey k = new SecretKeySpec(HEX.parseHex(key), "AES");
        byte[] blob = concat(HEX.parseHex(iv), HEX.parseHex(ct), HEX.parseHex(tag));
        assertArrayEquals(HEX.parseHex(pt), AesGcm.decrypt(k, blob, HEX.parseHex(aad)));

        byte[] badTag = blob.clone();
        badTag[badTag.length - 1] ^= 0x01;
        assertThrows(IllegalArgumentException.class, () -> AesGcm.decrypt(k, badTag, HEX.parseHex(aad)));

        byte[] badAad = HEX.parseHex(aad);
        badAad[0] ^= 0x01;
        assertThrows(IllegalArgumentException.class, () -> AesGcm.decrypt(k, blob, badAad));
    }
}
