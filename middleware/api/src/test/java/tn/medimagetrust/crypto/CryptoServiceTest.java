package tn.medimagetrust.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CryptoServiceTest {

    private static final int KID_V1 = 1;
    private static final int KID_V2 = 2;

    private InMemoryKeyProvider keyProvider;
    private CryptoService crypto;

    @BeforeEach
    void setUp() {
        keyProvider = new InMemoryKeyProvider();
        keyProvider.registerNewKek(KID_V1);
        crypto = new CryptoService(keyProvider);
    }

    private static byte[] id16(int seed) {
        byte[] id = new byte[16];
        Arrays.fill(id, (byte) seed);
        return id;
    }

    @Test
    void parcoursAmalChiffrementEtDechiffrementJetonS() {
        byte[] jetonS = Arrays.copyOf("PATIENT-PSEUDO-8841|EXAM-IRM".getBytes(StandardCharsets.UTF_8), 32);
        byte[] emission = id16(7);

        byte[] chiffre = crypto.sealTokenS(KID_V1, emission, 1001, jetonS);
        byte[] dechiffre = crypto.openTokenS(KID_V1, emission, 1001, chiffre);

        assertArrayEquals(jetonS, dechiffre);

        assertThrows(Exception.class, () -> crypto.openTokenS(KID_V1, id16(8), 1001, chiffre));
    }

    @Test
    void rotationDeCleParKidConserveLaVerificationDesAnciensDocuments() {
        String json = "{\"docId\":\"DOC-01\",\"status\":\"ISSUED\"}";
        CryptoService.SignedPayload signeAvecV1 = crypto.signCanonicalJson(json);
        assertEquals(KID_V1, signeAvecV1.kid());

        keyProvider.registerNewKek(KID_V2);
        assertEquals(KID_V2, keyProvider.activeKid());

        assertTrue(crypto.verifyCanonicalJson(KID_V1, json, signeAvecV1.signature()));
        assertFalse(crypto.verifyCanonicalJson(KID_V2, json, signeAvecV1.signature()));
    }

    @Test
    void parcoursNourhenEngagementFCommit() {
        byte[] destinataire = "medecin-dest-42".getBytes(StandardCharsets.UTF_8);
        byte[] commit = crypto.computeFCommit(KID_V1, destinataire);
        assertEquals(16, commit.length);
    }

    @Test
    void signatureBinaireDeLaChargeW() {
        byte[] chargeW = new byte[144];
        chargeW[0] = 0x01;
        byte[] sig = crypto.signBytes(KID_V1, chargeW);
        assertEquals(64, sig.length);
        assertTrue(crypto.verifyBytes(KID_V1, chargeW, sig));
        byte[] altere = chargeW.clone();
        altere[100] ^= 0x01;
        assertFalse(crypto.verifyBytes(KID_V1, altere, sig));
        assertFalse(crypto.verifyBytes(KID_V1, chargeW, Arrays.copyOf(sig, 63)));
        assertTrue(Ed25519.verify(crypto.publicKey(KID_V1), chargeW, sig));
    }

    @Test
    void seulLeKidActifPeutSignerApresRotation() {
        keyProvider.registerNewKek(KID_V2);
        assertThrows(IllegalArgumentException.class, () -> crypto.signBytes(KID_V1, new byte[8]));
        byte[] sig = crypto.signBytes(KID_V2, new byte[8]);
        assertTrue(crypto.verifyBytes(KID_V2, new byte[8], sig));
        assertFalse(crypto.verifyBytes(KID_V1, new byte[8], sig));
    }

    @Test
    void clesDeriveesDeterministesEtSepareesParUsage() {
        // Même KEK, même kid : même clé publique ; SK (signature W) et K_log (journal) sont des paires distinctes.
        assertArrayEquals(crypto.publicKey(KID_V1).getEncoded(), crypto.publicKey(KID_V1).getEncoded());
        KeyPair log = crypto.logKeyPair(KID_V1);
        assertFalse(Arrays.equals(crypto.publicKey(KID_V1).getEncoded(), log.getPublic().getEncoded()));
        byte[] sig = Ed25519.sign(log.getPrivate(), new byte[] {1, 2, 3});
        assertTrue(Ed25519.verify(crypto.logKeyPair(KID_V1).getPublic(), new byte[] {1, 2, 3}, sig));
    }

    @Test
    void kidInconnuOuHorsBornesRefuseSansDetail() {
        for (int kid : new int[] {99, -1, 65_536}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> crypto.publicKey(kid));
            assertFalse(e.getMessage().matches(".*\\d.*"), "le message ne doit contenir aucun chiffre");
        }
    }
}
