package tn.medimagetrust.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Un seul secret par établissement : la KEK ; tout le reste en est dérivé (v2.4 §8.2). Kid sur 16 bits. */
class KeyHierarchyTest {

    private static final HexFormat HEX = HexFormat.of();

    /** Fournisseur à KEK fixe, pour vérifier l'encodage exact des info HKDF. */
    private static KeyProvider fixedKek(byte[] kek, int kid) {
        return new KeyProvider() {
            @Override
            public int activeKid() {
                return kid;
            }

            @Override
            public SecretKey kek(int k) {
                if (k != kid) {
                    throw new IllegalArgumentException("kid inconnu");
                }
                return new SecretKeySpec(kek, "HKDF-SHA256");
            }
        };
    }

    @Test
    @DisplayName("Kid : 2 octets gros-boutiste, bornes [0, 65 535], erreurs sans détail")
    void kidSur16Bits() {
        assertArrayEquals(new byte[] {0x01, 0x02}, Kid.toBytes(0x0102));
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFF}, Kid.toBytes(65_535));
        assertArrayEquals(new byte[] {0, 0}, Kid.toBytes(0));
        assertEquals(0x0102, Kid.fromBytes(new byte[] {9, 0x01, 0x02}, 1));
        assertEquals(65_535, Kid.fromBytes(new byte[] {(byte) 0xFF, (byte) 0xFF}, 0));
        for (int bad : new int[] {-1, 65_536, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Kid.toBytes(bad));
            assertFalse(e.getMessage().matches(".*\\d.*"));
        }
        assertThrows(IllegalArgumentException.class, () -> Kid.fromBytes(new byte[] {1}, 0));
        assertThrows(IllegalArgumentException.class, () -> Kid.fromBytes(new byte[] {1, 2}, 1));
        assertThrows(IllegalArgumentException.class, () -> Kid.fromBytes(null, 0));
        assertThrows(IllegalArgumentException.class, () -> Kid.fromBytes(new byte[] {1, 2}, -1));
    }

    @Test
    @DisplayName("info HKDF = étiquette ASCII ‖ 0x00 ‖ kid(2) ; dérivation sans sel depuis la KEK")
    void infoExactEtSansSel() {
        byte[] info = KeyDerivation.kidInfo("MIT-KF-v1", 0x0203);
        byte[] expected = new byte[] {'M', 'I', 'T', '-', 'K', 'F', '-', 'v', '1', 0x00, 0x02, 0x03};
        assertArrayEquals(expected, info);

        byte[] kek = new byte[32];
        for (int i = 0; i < 32; i++) kek[i] = (byte) i;
        KeyProvider keys = fixedKek(kek, 0x0203);
        assertArrayEquals(Hkdf.derive(kek, null, expected, 32), KeyDerivation.kF(keys, 0x0203));
        // Le même résultat avec un sel vide confirme l'absence de sel (RFC 5869 : sel absent = 32 zéros).
        assertArrayEquals(Hkdf.derive(kek, new byte[32], expected, 32), KeyDerivation.kF(keys, 0x0203));
    }

    @Test
    @DisplayName("Cinq clés distinctes par établissement (K_tok, K_F, K_store, graine SK, graine K_log)")
    void cinqClesDistinctes() {
        InMemoryKeyProvider keys = new InMemoryKeyProvider();
        keys.registerNewKek(7);
        Set<String> seen = new HashSet<>();
        seen.add(HEX.formatHex(KeyDerivation.kTok(keys, 7).getEncoded()));
        seen.add(HEX.formatHex(KeyDerivation.kF(keys, 7)));
        seen.add(HEX.formatHex(KeyDerivation.kStore(keys, 7).getEncoded()));
        seen.add(HEX.formatHex(KeyDerivation.signingSeed(keys, 7)));
        seen.add(HEX.formatHex(KeyDerivation.logSeed(keys, 7)));
        seen.add(HEX.formatHex(keys.kek(7).getEncoded()));
        assertEquals(6, seen.size());
    }

    @Test
    @DisplayName("Étiquettes de domaine exactes (v2.4 §8.2)")
    void etiquettes() {
        assertEquals("MIT-KTOK-v1", KeyDerivation.K_TOK);
        assertEquals("MIT-KF-v1", KeyDerivation.K_F);
        assertEquals("MIT-KSTORE-v1", KeyDerivation.K_STORE);
        assertEquals("MIT-SIG-v1", KeyDerivation.SIG_SEED);
        assertEquals("MIT-LOG-v1", KeyDerivation.LOG_SEED);
        assertArrayEquals("MIT-S-v1".getBytes(StandardCharsets.US_ASCII),
                KeyDerivation.S_SUBKEY.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("InMemoryKeyProvider : KEK de 32 octets, kid unique, kid inconnu refusé sans détail, toString muet")
    void fournisseurEnMemoire() {
        InMemoryKeyProvider keys = new InMemoryKeyProvider();
        assertThrows(IllegalStateException.class, keys::activeKid);
        keys.registerNewKek(3);
        assertEquals(32, keys.kek(3).getEncoded().length);
        assertEquals(3, keys.activeKid());
        assertThrows(IllegalArgumentException.class, () -> keys.registerNewKek(3));
        assertThrows(IllegalArgumentException.class, () -> keys.registerNewKek(65_536));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> keys.kek(4));
        assertFalse(e.getMessage().matches(".*\\d.*"));
        String s = keys.toString();
        assertFalse(s.matches(".*\\d.*"));
        assertFalse(s.toLowerCase().contains(HEX.formatHex(keys.kek(3).getEncoded())));
    }

    @Test
    @DisplayName("Deux établissements (deux KEK) avec le même kid : clés différentes")
    void deuxEtablissements() {
        InMemoryKeyProvider a = new InMemoryKeyProvider();
        InMemoryKeyProvider b = new InMemoryKeyProvider();
        a.registerNewKek(1);
        b.registerNewKek(1);
        assertFalse(java.util.Arrays.equals(new CryptoService(a).publicKey(1).getEncoded(),
                new CryptoService(b).publicKey(1).getEncoded()));
    }
}
