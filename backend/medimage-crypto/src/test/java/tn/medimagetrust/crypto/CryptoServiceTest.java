package tn.medimagetrust.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CryptoServiceTest {

    private InMemoryKeyProvider keyProvider;
    private CryptoService crypto;

    @BeforeEach
    void setUp() {
        keyProvider = new InMemoryKeyProvider();
        keyProvider.registerNewKeyVersion("mit-key-v1");
        crypto = new CryptoService(keyProvider);
    }

    @Test
    void parcoursAmalChiffrementEtDechiffrementJetonS() {
        byte[] jetonS = "PATIENT-PSEUDO-8841|EXAM-IRM".getBytes(StandardCharsets.UTF_8);
        String objectId = "1.2.840.113619.2.55.3.604688";

        byte[] chiffre = crypto.encryptTokenS("mit-key-v1", objectId, jetonS);
        byte[] dechiffre = crypto.decryptTokenS("mit-key-v1", objectId, chiffre);

        assertArrayEquals(jetonS, dechiffre);

        assertThrows(Exception.class, () ->
            crypto.decryptTokenS("mit-key-v1", "AUTRE-OBJECT-ID", chiffre));
    }

    @Test
    void rotationDeCleParKidConserveLaVerificationDesAnciensDocuments() {
        String json = "{\"docId\":\"DOC-01\",\"status\":\"ISSUED\"}";
        CryptoService.SignedPayload signeAvecV1 = crypto.signCanonicalJson(json);
        assertEquals("mit-key-v1", signeAvecV1.kid());

        keyProvider.registerNewKeyVersion("mit-key-v2");
        assertEquals("mit-key-v2", keyProvider.activeSigningKeyId());

        assertTrue(crypto.verifyCanonicalJson("mit-key-v1", json, signeAvecV1.signature()));
        assertFalse(crypto.verifyCanonicalJson("mit-key-v2", json, signeAvecV1.signature()));
    }

    @Test
    void parcoursNourhenEngagementFCommit() {
        byte[] destinataire = "medecin-dest-42".getBytes(StandardCharsets.UTF_8);
        byte[] commit = crypto.computeFCommit("mit-key-v1", destinataire);
        assertEquals(16, commit.length);
    }
}
