package tn.medimagetrust.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** SEC07, MT11, SEC-P01 : schéma fixe et liste blanche. */
class AuditEventTest {

    private static final Instant T = Instant.parse("2026-11-23T10:00:00Z");

    private static AuditEvent withActor(String actor) {
        return new AuditEvent(T, EventType.AUTH_FAILURE, actor, "res-1", Result.FAILURE, "corr-1");
    }

    @Test
    void evenementValideAccepte() {
        AuditEvent e = new AuditEvent(T, EventType.COPY_ISSUED, "3f2c9e1a-7b4d-4c1e-9a55-0d2f6b7c8e90",
                "emission:11111111-2222-3333-4444-555555555555", Result.SUCCESS, "req-42");
        assertEquals("", e.digestHex());
    }

    @Test
    void injectionDeLigneRetourChariotRejetee() {
        assertThrows(IllegalArgumentException.class, () -> withActor("bob\n{\"seq\":999}"));
        assertThrows(IllegalArgumentException.class, () -> withActor("bob\r"));
    }

    @Test
    void guillemetsAntislashEspacesEtSeparateursRejetes() {
        for (String bad : new String[] {"a\"b", "a\\b", "a b", "a,b", "a{b}", "a/b", "é", "", "a\u0000b", "<script>"}) {
            assertThrows(IllegalArgumentException.class, () -> withActor(bad), "doit rejeter : " + bad.length());
        }
    }

    @Test
    void longueurBorneeA64() {
        withActor("a".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> withActor("a".repeat(65)));
    }

    @Test
    void champsNulsRejetes() {
        assertThrows(NullPointerException.class,
                () -> new AuditEvent(null, EventType.AUTH_FAILURE, "a", "b", Result.FAILURE, "c"));
        assertThrows(IllegalArgumentException.class, () -> withActor(null));
    }

    @Test
    void condensatDoitEtreSha256HexMinuscule() {
        String ok = "a".repeat(64);
        new AuditEvent(T, EventType.FILE_IMPORTED, "u", "r", Result.SUCCESS, "c", ok);
        for (String bad : new String[] {"A".repeat(64), "a".repeat(63), "a".repeat(65), "z".repeat(64)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AuditEvent(T, EventType.FILE_IMPORTED, "u", "r", Result.SUCCESS, "c", bad));
        }
    }

    @Test
    void messageDErreurNeReproduitPasLaValeurRefusee() {
        String secret = "Dupont^Jean";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> withActor(secret));
        assertFalse(ex.getMessage().contains("Dupont"));
    }

    @Test
    void horodatageTronqueAuxMillisecondes() {
        AuditEvent e = new AuditEvent(Instant.parse("2026-11-23T10:00:00.123456789Z"), EventType.AUTH_SUCCESS,
                "u", "r", Result.SUCCESS, "c");
        assertEquals(Instant.parse("2026-11-23T10:00:00.123Z"), e.timestamp());
    }

    @Test
    void formeCanoniqueStableEtTriee() {
        AuditEvent e = new AuditEvent(T, EventType.COPY_ISSUED, "u1", "r1", Result.SUCCESS, "c1");
        String json = new String(AuditChain.canonical(7, e), StandardCharsets.UTF_8);
        assertEquals("{\"actor\":\"u1\",\"corr\":\"c1\",\"digest\":\"\",\"resource\":\"r1\",\"result\":\"SUCCESS\","
                + "\"seq\":7,\"ts\":" + T.toEpochMilli() + ",\"type\":\"COPY_ISSUED\"}", json);
    }
}
