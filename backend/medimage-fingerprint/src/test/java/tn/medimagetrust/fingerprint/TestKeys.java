package tn.medimagetrust.fingerprint;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Outils de test. Clé de test uniquement : en production K_F vient du coffre de secrets. */
final class TestKeys {
    private TestKeys() {}

    static Prf prf(int seed) {
        byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) k[i] = (byte) (seed * 31 + i * 7);
        return new HmacSha256Prf(k);
    }

    static byte[] id(long n) {
        return Messages.uuidBytes(new UUID(n, ~n));
    }

    static List<String> recipients(int n) {
        List<String> r = new ArrayList<>();
        for (int i = 0; i < n; i++) r.add("recipient-" + i);
        return r;
    }
}
