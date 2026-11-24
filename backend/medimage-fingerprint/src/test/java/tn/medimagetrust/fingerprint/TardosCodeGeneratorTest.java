package tn.medimagetrust.fingerprint;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TardosCodeGeneratorTest {
    private final TardosCodeGenerator gen = new TardosCodeGenerator(TestKeys.prf(1));
    private final byte[] image = TestKeys.id(1);

    @Test
    void codeIsReproducibleAndNeverStored() {
        assertArrayEquals(gen.symbols(image, "alice"), gen.symbols(image, "alice"));
    }

    @Test
    void differentRecipientsGetDifferentCodes() {
        assertFalse(Arrays.equals(gen.symbols(image, "alice"), gen.symbols(image, "bob")));
    }

    @Test
    void differentKeyGivesDifferentCode() {
        var other = new TardosCodeGenerator(TestKeys.prf(2));
        assertFalse(Arrays.equals(gen.symbols(image, "alice"), other.symbols(image, "alice")));
    }

    @Test
    void biasesRespectTheCutoff() {
        double t = FingerprintParams.CUTOFF;
        for (double p : gen.biases(image)) {
            assertTrue(p >= t - 1e-12 && p <= 1 - t + 1e-12, "biais hors coupure : " + p);
        }
    }

    @Test
    void symbolFrequencyFollowsTheBias() {
        double[] p = gen.biases(image);
        double expected = Arrays.stream(p).average().orElseThrow();
        // moyenne sur 64 destinataires : écart-type ~ 0,5/sqrt(65536) = 0,002
        double ones = 0;
        for (int r = 0; r < 64; r++) {
            for (byte b : gen.symbols(image, "r" + r, p)) ones += b;
        }
        assertEquals(expected, ones / (64.0 * FingerprintParams.SYMBOLS), 0.01);
    }

    @Test
    void expandRepeatsEachSymbolThreeTimes() {
        byte[] s = gen.symbols(image, "alice");
        boolean[] bits = TardosCodeGenerator.expand(s);
        assertEquals(FingerprintParams.CODE_BITS, bits.length);
        for (int r = 0; r < FingerprintParams.REPETITIONS; r++)
            for (int i = 0; i < FingerprintParams.SYMBOLS; i++)
                assertEquals(s[i] == 1, bits[r * FingerprintParams.SYMBOLS + i]);
    }

    @Test
    void commitIs128BitsDeterministicAndBoundToRecipient() {
        byte[] e = TestKeys.id(9);
        assertEquals(16, gen.commit(e, "alice").length);
        assertArrayEquals(gen.commit(e, "alice"), gen.commit(e, "alice"));
        assertFalse(Arrays.equals(gen.commit(e, "alice"), gen.commit(e, "bob")));
        assertFalse(Arrays.equals(gen.commit(e, "alice"), gen.commit(TestKeys.id(10), "alice")));
    }
}
