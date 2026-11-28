package tn.medimagetrust.stego;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.crypto.CryptoService;
import tn.medimagetrust.crypto.InMemoryKeyProvider;
import tn.medimagetrust.embedding.EmbeddingProfile;
import tn.medimagetrust.embedding.EmbeddingSession;
import tn.medimagetrust.embedding.LsbChannel;
import tn.medimagetrust.embedding.Partitions;
import tn.medimagetrust.embedding.Phantoms;
import tn.medimagetrust.embedding.PixelImage;
import tn.medimagetrust.embedding.RoniDetector;
import tn.medimagetrust.embedding.RoniProfile;

/** Jeton S (SEC-D01) : référence retrouvée avec la bonne clé, échec fermé sinon, jamais de référence en clair. */
class StegoServiceTest {
    private static final long TEST_SEED_STEGO = 0x5354_4547L;
    private static final int KID = 7;
    private static final int ISSUER = 0x0A0B0C0D;

    private InMemoryKeyProvider keys;
    private StegoService stego;

    @BeforeEach
    void setUp() {
        keys = new InMemoryKeyProvider();
        keys.registerNewKek(KID);
        stego = new StegoService(new CryptoService(keys));
    }

    record Emission(PixelImage copy, Partitions partitions, byte[] emissionId, byte[] reference, byte[] s) {}

    private static byte[] bytes(Random rnd, int n) {
        byte[] b = new byte[n];
        rnd.nextBytes(b);
        return b;
    }

    /** Session complète : F aléatoire, puis S (jeton, ou {@code sOverride} tel quel), W aléatoire. */
    private Emission emit(long seed, StegoService service, byte[] sOverride) {
        Random rnd = new Random(seed);
        PixelImage original = Phantoms.build(Phantoms.normalSpec(), seed).image();
        EmbeddingSession session = EmbeddingSession.open(original);
        double delta = session.fChannel().qimDelta();
        for (int k = 0; k < EmbeddingProfile.F_USED_BLOCKS; k++) {
            boolean[] bits = new boolean[EmbeddingProfile.F_BITS_PER_BLOCK];
            double[] dither = new double[EmbeddingProfile.F_BITS_PER_BLOCK];
            for (int j = 0; j < bits.length; j++) {
                bits[j] = rnd.nextBoolean();
                dither[j] = rnd.nextDouble() * delta;
            }
            session.fChannel().write(k * 2, bits, dither);
        }
        byte[] emissionId = bytes(rnd, 16);
        byte[] reference = bytes(rnd, 16);
        if (sOverride != null) {
            session.writeS(sOverride);
        } else {
            service.embed(session, reference, emissionId, ISSUER, KID, StegoService.Protocol.CLINICAL);
        }
        session.writeW(bytes(rnd, EmbeddingProfile.W_BYTES));
        PixelImage copy = session.finish().image();
        byte[] s = LsbChannel.reader(copy, session.partitions()).readS();
        return new Emission(copy, session.partitions(), emissionId, reference, s);
    }

    /** Chemin de vérification : partitions recalculées sur la copie (candidats de seuil), sans la session. */
    private static StegoService.Token extractFromCopy(StegoService service, PixelImage copy, byte[] emissionId)
            throws StegoException {
        for (RoniProfile candidate : RoniDetector.candidates(copy)) {
            try {
                return service.extract(copy, Partitions.of(candidate), emissionId, ISSUER);
            } catch (StegoException e) {
                // candidat suivant
            }
        }
        throw new StegoException();
    }

    @Test
    void twentyCopiesRightKeyGiveTheExpectedReference() throws StegoException {
        for (int i = 0; i < 20; i++) {
            Emission e = emit(TEST_SEED_STEGO + i, stego, null);
            StegoService.Token token = extractFromCopy(stego, e.copy(), e.emissionId());
            assertArrayEquals(e.reference(), token.folderReference(), "copie " + i);
            assertEquals(StegoService.Protocol.CLINICAL, token.protocol());
            assertEquals(KID, token.kid());
        }
    }

    @Test
    void missingOrWrongKeyFails() {
        Emission e = emit(TEST_SEED_STEGO + 100, stego, null);
        InMemoryKeyProvider none = new InMemoryKeyProvider();
        none.registerNewKek(KID + 1); // le kid du jeton est inconnu
        StegoService noKey = new StegoService(new CryptoService(none));
        assertGenericFailure(() -> noKey.extract(e.copy(), e.partitions(), e.emissionId(), ISSUER));

        InMemoryKeyProvider other = new InMemoryKeyProvider();
        other.registerNewKek(KID); // même kid, autre clé
        StegoService wrongKey = new StegoService(new CryptoService(other));
        assertGenericFailure(() -> wrongKey.extract(e.copy(), e.partitions(), e.emissionId(), ISSUER));
    }

    @Test
    void wrongEmissionOrIssuerFails() {
        Emission e = emit(TEST_SEED_STEGO + 101, stego, null);
        byte[] otherId = e.emissionId().clone();
        otherId[0] ^= 1;
        assertGenericFailure(() -> stego.extract(e.copy(), e.partitions(), otherId, ISSUER));
        assertGenericFailure(() -> stego.extract(e.copy(), e.partitions(), e.emissionId(), ISSUER + 1));
        assertGenericFailure(() -> stego.extract(e.copy(), e.partitions(), null, ISSUER));
    }

    @Test
    void tokenTransplantedOnAnotherImageFails() {
        Emission a = emit(TEST_SEED_STEGO + 200, stego, null);
        // Le jeton de A, recopié tel quel dans la partition S d'une autre image (émission B).
        Emission b = emit(TEST_SEED_STEGO + 201, stego, a.s());
        assertArrayEquals(a.s(), b.s());
        assertGenericFailure(() -> stego.extract(b.copy(), b.partitions(), b.emissionId(), ISSUER));
    }

    @Test
    void randomSFails() {
        StegoService research = new StegoService(new CryptoService(keys));
        Random rnd = new Random(TEST_SEED_STEGO + 300);
        PixelImage original = Phantoms.build(Phantoms.normalSpec(), TEST_SEED_STEGO + 300).image();
        EmbeddingSession session = EmbeddingSession.open(original);
        research.embedWithoutToken(session);
        session.writeW(bytes(rnd, EmbeddingProfile.W_BYTES));
        PixelImage copy = session.finish().image();
        assertGenericFailure(() -> stego.extract(copy, session.partitions(), bytes(rnd, 16), ISSUER));
        // Autres S aléatoires, dont un premier octet égal à la version.
        for (int i = 0; i < 20; i++) {
            byte[] s = bytes(rnd, 64);
            if (i % 2 == 0) s[0] = StegoService.VERSION;
            Emission e = emit(TEST_SEED_STEGO + 310 + i, stego, s);
            assertGenericFailure(() -> stego.extract(e.copy(), e.partitions(), e.emissionId(), ISSUER));
        }
    }

    @Test
    void referenceNeverAppearsInClear() throws StegoException {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        List<String> logs = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) { logs.add(String.valueOf(r.getMessage())); }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        Emission e;
        StegoService.Token token;
        try {
            PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8);
            System.setOut(capture);
            System.setErr(capture);
            e = emit(TEST_SEED_STEGO + 400, stego, null);
            token = stego.extract(e.copy(), e.partitions(), e.emissionId(), ISSUER);
            assertThrows(StegoException.class, () -> stego.extract(e.copy(), e.partitions(), new byte[16], ISSUER));
        } finally {
            System.setOut(out);
            System.setErr(err);
            root.removeHandler(handler);
        }
        byte[] ref = e.reference();
        String hex = HexFormat.of().formatHex(ref);
        // Ni dans la charge S, ni dans les octets des pixels (petit-boutiste), ni dans les journaux et sorties.
        assertFalse(contains(e.s(), ref));
        byte[] raw = new byte[e.copy().pixelCount() * 2];
        for (int i = 0; i < e.copy().pixelCount(); i++) {
            raw[2 * i] = (byte) e.copy().pixel(i);
            raw[2 * i + 1] = (byte) (e.copy().pixel(i) >> 8);
        }
        assertFalse(contains(raw, ref));
        String text = captured.toString(StandardCharsets.UTF_8) + String.join("\n", logs) + token;
        assertFalse(text.toLowerCase().contains(hex));
        assertFalse(token.toString().contains(hex));
    }

    @Test
    void invalidInputsAreRefused() {
        PixelImage original = Phantoms.build(Phantoms.normalSpec(), TEST_SEED_STEGO + 500).image();
        EmbeddingSession session = EmbeddingSession.open(original);
        assertThrows(IllegalArgumentException.class,
                () -> stego.embed(session, new byte[15], new byte[16], ISSUER, KID, StegoService.Protocol.CLINICAL));
        assertThrows(IllegalArgumentException.class,
                () -> stego.embed(session, new byte[16], new byte[15], ISSUER, KID, StegoService.Protocol.CLINICAL));
        assertThrows(IllegalArgumentException.class,
                () -> stego.embed(session, new byte[16], new byte[16], ISSUER, KID + 9, StegoService.Protocol.RESEARCH));
    }

    private static void assertGenericFailure(org.junit.jupiter.api.function.Executable call) {
        StegoException e = assertThrows(StegoException.class, call);
        assertEquals(StegoException.MESSAGE, e.getMessage());
        assertEquals(null, e.getCause());
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
