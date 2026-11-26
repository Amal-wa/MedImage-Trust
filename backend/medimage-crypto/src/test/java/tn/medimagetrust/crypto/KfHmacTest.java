package tn.medimagetrust.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** Test du point d'extension kfHmac utilisé par le module fingerprint. */
class KfHmacTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static CryptoService service() {
        InMemoryKeyProvider kp = new InMemoryKeyProvider();
        kp.registerNewKek(1);
        return new CryptoService(kp);
    }

    @Test
    void kfHmacEstDeterministePourUnKid() {
        CryptoService cs = service();
        UnaryOperator<byte[]> f = cs.kfHmac(1);
        assertArrayEquals(f.apply(b("m")), f.apply(b("m")));
        assertArrayEquals(f.apply(b("m")), cs.kfHmac(1).apply(b("m")));
    }

    @Test
    void kfHmacDependDuKidEtDuMessage() {
        InMemoryKeyProvider kp = new InMemoryKeyProvider();
        kp.registerNewKek(1);
        kp.registerNewKek(2);
        CryptoService cs = new CryptoService(kp);
        assertFalse(Arrays.equals(cs.kfHmac(1).apply(b("m")), cs.kfHmac(2).apply(b("m"))));
        assertFalse(Arrays.equals(cs.kfHmac(1).apply(b("m")), cs.kfHmac(1).apply(b("n"))));
    }

    @Test
    void kfHmacEstCoherentAvecComputeFCommit() {
        CryptoService cs = service();
        byte[] msg = b("MIT-FC-v1|emission|destinataire");
        byte[] viaPrf = Arrays.copyOf(cs.kfHmac(1).apply(msg), 16);
        assertArrayEquals(cs.computeFCommit(1, msg), viaPrf);
    }

    @Test
    void kidInconnuEstRefuse() {
        assertThrows(IllegalArgumentException.class, () -> service().kfHmac(42));
    }
}
