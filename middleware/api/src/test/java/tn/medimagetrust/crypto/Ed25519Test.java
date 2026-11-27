package tn.medimagetrust.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class Ed25519Test {

    private static final byte[] MESSAGE = "document médical".getBytes(StandardCharsets.UTF_8);

    @Test
    void signatureValideAcceptee() {
        KeyPair kp = Ed25519.generateKeyPair();
        byte[] sig = Ed25519.sign(kp.getPrivate(), MESSAGE);
        assertTrue(Ed25519.verify(kp.getPublic(), MESSAGE, sig));
    }

    @Test
    void signatureFait64Octets() {
        KeyPair kp = Ed25519.generateKeyPair();
        assertEquals(64, Ed25519.sign(kp.getPrivate(), MESSAGE).length);
    }

    @Test
    void messageAltereRejete() {
        KeyPair kp = Ed25519.generateKeyPair();
        byte[] sig = Ed25519.sign(kp.getPrivate(), MESSAGE);
        byte[] altere = MESSAGE.clone();
        altere[0] ^= 0x01;
        assertFalse(Ed25519.verify(kp.getPublic(), altere, sig));
    }

    @Test
    void signatureAltereeRejetee() {
        KeyPair kp = Ed25519.generateKeyPair();
        byte[] sig = Ed25519.sign(kp.getPrivate(), MESSAGE);
        sig[10] ^= 0x01;
        assertFalse(Ed25519.verify(kp.getPublic(), MESSAGE, sig));
    }

    @Test
    void mauvaiseClePubliqueRejetee() {
        KeyPair signataire = Ed25519.generateKeyPair();
        KeyPair autre = Ed25519.generateKeyPair();
        byte[] sig = Ed25519.sign(signataire.getPrivate(), MESSAGE);
        assertFalse(Ed25519.verify(autre.getPublic(), MESSAGE, sig));
    }

    @Test
    void signatureTronqueeRejeteeSansException() {
        KeyPair kp = Ed25519.generateKeyPair();
        byte[] sig = Ed25519.sign(kp.getPrivate(), MESSAGE);
        assertFalse(Ed25519.verify(kp.getPublic(), MESSAGE, Arrays.copyOf(sig, 63)));
    }

    @Test
    void signatureSurJsonCanonique() {
        KeyPair kp = Ed25519.generateKeyPair();
        byte[] sig = Ed25519.sign(kp.getPrivate(), Jcs.canonicalize("{\"b\":1,\"a\":2}"));
        // Même contenu, écrit autrement : la forme canonique est identique, la signature reste valide.
        assertTrue(Ed25519.verify(kp.getPublic(), Jcs.canonicalize("{ \"a\": 2, \"b\": 1 }"), sig));
    }
}
