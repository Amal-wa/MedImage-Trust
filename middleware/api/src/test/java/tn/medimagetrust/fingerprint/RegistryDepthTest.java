package tn.medimagetrust.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.embedding.EmbeddingResult;
import tn.medimagetrust.embedding.EmbeddingSession;
import tn.medimagetrust.embedding.FBlockReader;
import tn.medimagetrust.embedding.Phantoms;
import tn.medimagetrust.embedding.PixelImage;

/**
 * Δ vient du registre d'émission, jamais de la copie analysée : la profondeur B rendue par
 * {@link EmbeddingResult#bitsStored()} permet l'attribution ; un Δ de 8 bits appliqué à une copie 12 bits la fait
 * échouer (et n'accuse personne).
 */
@Tag("SEC-D04")
@DisplayName("Profondeur du registre : Δ(B) de l'émission indispensable à l'attribution")
class RegistryDepthTest {
    private static final long TEST_SEED_REGISTRY = 0x5245_4749_5354L;

    @Test
    @DisplayName("Copie 12 bits : B = 12 du registre → vrai destinataire accusé ; Δ(8) → attribution en échec")
    void wrongDeltaMakesAttributionFail() {
        Random rnd = new Random(TEST_SEED_REGISTRY);
        byte[] key = new byte[32];
        rnd.nextBytes(key); // clé K_F de test, jamais utilisée en production
        FingerprintService service = new FingerprintService(new HmacSha256Prf(key));
        List<String> recipients = new ArrayList<>();
        for (int i = 0; i < 64; i++) recipients.add("recipient-" + i);
        byte[] imageId = new byte[16];
        byte[] emissionId = new byte[16];
        rnd.nextBytes(imageId);
        rnd.nextBytes(emissionId);

        PixelImage original = Phantoms.build(Phantoms.normalSpec(), TEST_SEED_REGISTRY).image();
        EmbeddingSession session = EmbeddingSession.open(original);
        byte[] maskDigest = session.maskDigest();
        service.embed(session.fChannel(), maskDigest, service.prepare(imageId, emissionId, "recipient-9"));
        byte[] s = new byte[64];
        byte[] w = new byte[208];
        rnd.nextBytes(s);
        rnd.nextBytes(w);
        session.writeS(s);
        session.writeW(w);
        EmbeddingResult emission = session.finish();
        assertEquals(12, emission.bitsStored());

        // B lue dans le registre d'émission : attribution correcte.
        AttributionReport right = service.analyze(imageId,
                FBlockReader.of(emission.image(), session.partitions(), emission.bitsStored()), maskDigest,
                recipients, Optional.empty());
        assertEquals(AttributionReport.Verdict.ACCUSED, right.verdict());
        assertEquals(1, right.accused().size());
        assertEquals("recipient-9", right.accused().get(0).recipientId());

        // Δ de 8 bits (comme si B était lue dans des attributs modifiés de la copie) : plus d'attribution.
        AttributionReport wrong = service.analyze(imageId,
                FBlockReader.of(emission.image(), session.partitions(), 8), maskDigest, recipients, Optional.empty());
        assertFalse(wrong.accused().stream().anyMatch(a -> a.recipientId().equals("recipient-9")),
                "le vrai destinataire est encore accusé avec un mauvais Δ");
        assertTrue(wrong.accused().isEmpty(), "un innocent est accusé avec un mauvais Δ");
    }
}
