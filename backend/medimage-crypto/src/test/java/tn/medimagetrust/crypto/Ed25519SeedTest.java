package tn.medimagetrust.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Paire Ed25519 déterministe depuis une graine de 32 octets (dérivation des clés depuis la KEK). */
class Ed25519SeedTest {

    private static final HexFormat HEX = HexFormat.of();

    // RFC 8032, §7.1, TEST 1.
    private static final String RFC_SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
    private static final String RFC_PUBLIC = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String RFC_SIG_EMPTY = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555f"
            + "b8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";

    @Test
    @DisplayName("RFC 8032 §7.1 TEST 1 : clé publique et signature du message vide identiques au vecteur officiel")
    void rfc8032Test1() {
        KeyPair kp = Ed25519.keyPairFromSeed(HEX.parseHex(RFC_SEED));
        byte[] encoded = kp.getPublic().getEncoded(); // X.509 : en-tête de 12 octets puis les 32 octets de la clé
        assertEquals(44, encoded.length);
        assertEquals(RFC_PUBLIC, HEX.formatHex(Arrays.copyOfRange(encoded, 12, 44)));
        byte[] sig = Ed25519.sign(kp.getPrivate(), new byte[0]);
        assertEquals(RFC_SIG_EMPTY, HEX.formatHex(sig));
        assertTrue(Ed25519.verify(kp.getPublic(), new byte[0], sig));
    }

    @Test
    @DisplayName("Même graine → même paire ; la graine passée n'est pas modifiée")
    void deterministeEtSansEffetDeBord() {
        byte[] seed = HEX.parseHex(RFC_SEED);
        byte[] copy = seed.clone();
        KeyPair a = Ed25519.keyPairFromSeed(seed);
        KeyPair b = Ed25519.keyPairFromSeed(seed);
        assertArrayEquals(copy, seed);
        assertArrayEquals(a.getPublic().getEncoded(), b.getPublic().getEncoded());
    }

    @Test
    @DisplayName("Graine nulle ou de taille différente de 32 : refus")
    void graineInvalide() {
        assertThrows(IllegalArgumentException.class, () -> Ed25519.keyPairFromSeed(null));
        assertThrows(IllegalArgumentException.class, () -> Ed25519.keyPairFromSeed(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> Ed25519.keyPairFromSeed(new byte[33]));
    }
}
