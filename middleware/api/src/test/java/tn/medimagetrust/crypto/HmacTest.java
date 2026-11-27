package tn.medimagetrust.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class HmacTest {

    // Vecteur officiel RFC 4231 - Cas de test 2 ("Jefe" / "what do ya want for nothing?")
    @Test
    void vecteurOfficielRfc4231Cas2() {
        byte[] key = "Jefe".getBytes(StandardCharsets.UTF_8);
        byte[] data = "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8);
        String attendu = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";

        assertEquals(attendu, HexFormat.of().formatHex(Hmac.compute(key, data)));
    }

    @Test
    void verificationValideEtRejetSiAltere() {
        byte[] key = Hmac.generateKey();
        byte[] msg = "empreinte-tardos-destinataire".getBytes(StandardCharsets.UTF_8);
        byte[] tag = Hmac.compute(key, msg);

        assertTrue(Hmac.verify(key, msg, tag));

        byte[] tagAltere = tag.clone();
        tagAltere[0] ^= 0x01;
        assertFalse(Hmac.verify(key, msg, tagAltere));

        byte[] msgAltere = msg.clone();
        msgAltere[0] ^= 0x01;
        assertFalse(Hmac.verify(key, msgAltere, tag));
    }

    @Test
    void fCommitFait16Octets() {
        byte[] key = Hmac.generateKey();
        byte[] commit = Hmac.fCommit128(key, "destinataire-1".getBytes(StandardCharsets.UTF_8));
        assertEquals(16, commit.length);
    }
}
