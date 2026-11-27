package tn.medimagetrust.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Sha256Test {

    @Test
    void vecteurConnuAbc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hashHex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void vecteurConnuVide() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hashHex(new byte[0]));
    }

    @Test
    void alterationDunOctetChangeLEmpreinte() {
        byte[] original = "document médical".getBytes(StandardCharsets.UTF_8);
        byte[] altere = original.clone();
        altere[0] ^= 0x01;
        assertNotEquals(Sha256.hashHex(original), Sha256.hashHex(altere));
    }
}
