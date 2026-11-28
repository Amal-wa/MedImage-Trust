package tn.medimagetrust.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.embedding.EmbeddingSession;
import tn.medimagetrust.embedding.Phantoms;
import tn.medimagetrust.embedding.Photometric;
import tn.medimagetrust.embedding.PixelImage;

/**
 * Non-régression 8 bits sur la chaîne complète : code de Tardos, dithers dérivés de K_F par {@link FingerprintEmbedder},
 * QIM de la session, S et W. L'empreinte SHA-256 ci-dessous a été relevée sur le code de {@code main} (commit 5b783b0),
 * avant Δ(B) : pixels marqués en ordre raster, chaque valeur sur 2 octets gros-boutistes.
 */
@DisplayName("Non-régression 8 bits, chaîne embedding + fingerprint : copie identique octet par octet à main")
class NonRegression8BitChainTest {

    private static final long TEST_SEED_CHAIN = 0x4348_4149_4E38L;

    @Test
    @DisplayName("Fantôme 8 bits marqué par FingerprintService : empreinte de main")
    void fingerprintChain8Bit() throws Exception {
        Random rnd = new Random(TEST_SEED_CHAIN);
        byte[] key = new byte[32];
        rnd.nextBytes(key); // clé K_F de test, jamais utilisée en production
        FingerprintService service = new FingerprintService(new HmacSha256Prf(key));
        PixelImage original = Phantoms.build(
                new Phantoms.Spec(512, 512, 8, Photometric.MONOCHROME2, 30, 3, 153, 12, 256, 256, 150, 190),
                TEST_SEED_CHAIN).image();
        byte[] imageId = new byte[16];
        byte[] emissionId = new byte[16];
        rnd.nextBytes(imageId);
        rnd.nextBytes(emissionId);

        EmbeddingSession session = EmbeddingSession.open(original);
        service.embed(session.fChannel(), session.maskDigest(), service.prepare(imageId, emissionId, "recipient-7"));
        byte[] s = new byte[64];
        byte[] w = new byte[208];
        rnd.nextBytes(s);
        rnd.nextBytes(w);
        session.writeS(s);
        session.writeW(w);
        PixelImage marked = session.finish().image();

        ByteBuffer buf = ByteBuffer.allocate(2 * marked.pixelCount());
        for (char c : marked.pixels()) {
            buf.putChar(c);
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buf.array()));
        assertEquals("b706c9264df2c609c88c243612ac38fe24d420cb1067ccaf460174938a19f562", sha);
    }
}
