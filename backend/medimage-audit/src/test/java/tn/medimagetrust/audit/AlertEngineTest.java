package tn.medimagetrust.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tn.medimagetrust.audit.TestSupport.T0;
import static tn.medimagetrust.audit.TestSupport.event;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SEC07 : déclenchement scripté de A1 à A6 avec la bonne règle ; SEC02 : A1B. */
class AlertEngineTest {

    private InMemoryAuditStore store;
    private InMemoryAlertSink sink;
    private AuditLog log;

    @BeforeEach
    void setUp() {
        store = new InMemoryAuditStore();
        sink = new InMemoryAlertSink();
        log = new AuditLog(store, new AlertEngine(), sink);
    }

    private void emit(Instant t, EventType type, String actor) {
        log.append(event(t, type, actor));
    }

    // ------------------------------------------------------------------ A1

    @Test
    void a1_cinqEchecsEnCinqMinutesSurUnCompte() {
        for (int i = 0; i < 4; i++) {
            emit(T0.plusSeconds(i * 60L), EventType.AUTH_FAILURE, "alice");
        }
        assertTrue(sink.alerts(AlertRule.A1).isEmpty(), "4 échecs : pas d'alerte");
        emit(T0.plusSeconds(4 * 60L), EventType.AUTH_FAILURE, "alice");
        List<Alert> a1 = sink.alerts(AlertRule.A1);
        assertEquals(1, a1.size());
        assertEquals("alice", a1.get(0).subject());
        assertEquals(5, a1.get(0).count());
    }

    @Test
    void a1_cinqEchecsRepartisSurPlusDeCinqMinutesNeDeclenchePas() {
        for (int i = 0; i < 5; i++) {
            emit(T0.plusSeconds(i * 90L), EventType.AUTH_FAILURE, "alice"); // 6 min entre le 1er et le 5e
        }
        assertTrue(sink.alerts(AlertRule.A1).isEmpty());
    }

    @Test
    void a1_echecsDeComptesDifferentsNeSAdditionnentPas() {
        for (int i = 0; i < 5; i++) {
            emit(T0.plusSeconds(i), EventType.AUTH_FAILURE, "user-" + i);
        }
        assertTrue(sink.alerts(AlertRule.A1).isEmpty());
    }

    @Test
    void a1_uneSeuleAlertePourUnMemeEpisodeEtRearmement() {
        for (int i = 0; i < 8; i++) {
            emit(T0.plusSeconds(i), EventType.AUTH_FAILURE, "alice");
        }
        assertEquals(1, sink.alerts(AlertRule.A1).size(), "pas de tempête d'alertes");
        // Plus tard, la fenêtre est vide : un nouvel épisode déclenche à nouveau.
        Instant later = T0.plus(Duration.ofMinutes(30));
        for (int i = 0; i < 5; i++) {
            emit(later.plusSeconds(i), EventType.AUTH_FAILURE, "alice");
        }
        assertEquals(2, sink.alerts(AlertRule.A1).size());
    }

    @Test
    void a1b_vingtEchecsEnUneHeure() {
        for (int i = 0; i < 19; i++) {
            emit(T0.plusSeconds(i * 150L), EventType.AUTH_FAILURE, "bob"); // 2,5 min : jamais 5 en 5 min
        }
        assertTrue(sink.alerts(AlertRule.A1B).isEmpty());
        assertTrue(sink.alerts(AlertRule.A1).isEmpty());
        emit(T0.plusSeconds(19 * 150L), EventType.AUTH_FAILURE, "bob");
        assertEquals(1, sink.alerts(AlertRule.A1B).size());
    }

    // ------------------------------------------------------------------ A2 / A3

    @Test
    void a2_troisRejetsDeFichiersSuspectsEnUneHeure() {
        emit(T0, EventType.FILE_REJECTED, "carol");
        emit(T0.plusSeconds(600), EventType.FILE_REJECTED, "carol");
        assertTrue(sink.alerts(AlertRule.A2).isEmpty());
        emit(T0.plusSeconds(1200), EventType.FILE_REJECTED, "carol");
        assertEquals(1, sink.alerts(AlertRule.A2).size());
    }

    @Test
    void a2_hors_fenetre() {
        emit(T0, EventType.FILE_REJECTED, "carol");
        emit(T0.plus(Duration.ofMinutes(40)), EventType.FILE_REJECTED, "carol");
        emit(T0.plus(Duration.ofMinutes(80)), EventType.FILE_REJECTED, "carol");
        assertTrue(sink.alerts(AlertRule.A2).isEmpty(), "le 1er rejet est sorti de la fenêtre d'1 h");
    }

    @Test
    void a3_troisExtractionsRefuseesEnUneHeure() {
        for (int i = 0; i < 3; i++) {
            emit(T0.plusSeconds(i * 100L), EventType.EXTRACTION_DENIED, "dave");
        }
        assertEquals(1, sink.alerts(AlertRule.A3).size());
        assertEquals("dave", sink.alerts(AlertRule.A3).get(0).subject());
    }

    // ------------------------------------------------------------------ A4

    @Test
    void a4_quatriemeEmissionDegradeeOuRefusDeRoniEn24h() {
        emit(T0, EventType.COPY_ISSUED_DEGRADED, "erin");
        emit(T0.plus(Duration.ofHours(3)), EventType.ROI_REFUSED, "erin");
        emit(T0.plus(Duration.ofHours(10)), EventType.COPY_ISSUED_DEGRADED, "erin");
        assertTrue(sink.alerts(AlertRule.A4).isEmpty(), "3 occurrences : pas d'alerte");
        emit(T0.plus(Duration.ofHours(20)), EventType.ROI_REFUSED, "erin");
        assertEquals(1, sink.alerts(AlertRule.A4).size(), "alerte à la 4e occurrence");
    }

    @Test
    void a4_quatreOccurrencesSurPlusDe24hNeDeclenchentPas() {
        for (int i = 0; i < 4; i++) {
            emit(T0.plus(Duration.ofHours(9L * i)), EventType.ROI_REFUSED, "erin"); // 27 h entre 1re et 4e
        }
        assertTrue(sink.alerts(AlertRule.A4).isEmpty());
    }

    // ------------------------------------------------------------------ A5 / A6

    @Test
    void a5_toutChangementDePrivilegeAlerteACHaqueFois() {
        emit(T0, EventType.ROLE_CHANGE, "admin-1");
        emit(T0.plusSeconds(1), EventType.ROLE_CHANGE, "admin-1");
        assertEquals(2, sink.alerts(AlertRule.A5).size());
    }

    @Test
    void a6_ecartDeSha256DUnOriginal() {
        log.append(new AuditEvent(T0, EventType.ORIGINAL_HASH_MISMATCH, "system", "image-77", Result.FAILURE,
                "corr-9", "a".repeat(64)));
        List<Alert> a6 = sink.alerts(AlertRule.A6);
        assertEquals(1, a6.size());
        assertEquals("image-77", a6.get(0).subject(), "A6 désigne l'image concernée");
    }

    // ------------------------------------------------------------------ transversal

    @Test
    void evenementsOrdinairesNeLevantAucuneAlerte() {
        for (EventType t : new EventType[] {EventType.AUTH_SUCCESS, EventType.FILE_IMPORTED, EventType.COPY_ISSUED,
                EventType.EXTRACTION_PERFORMED, EventType.VERIFICATION_PERFORMED, EventType.COPY_ANALYZED}) {
            emit(T0, t, "u1");
        }
        assertTrue(sink.alerts().isEmpty());
    }

    @Test
    void chaqueAlerteEstInscriteDansLaChaine() {
        emit(T0, EventType.ROLE_CHANGE, "admin-1");
        List<AuditEntry> all = store.range(1, 10);
        assertEquals(2, all.size());
        assertEquals(EventType.ALERT_RAISED, all.get(1).event().type());
        assertEquals("A5", all.get(1).event().resource());
        assertEquals("system", all.get(1).event().actor());
    }

    @Test
    void lesEntreesALERT_RAISEDNeRelancentPasLesRegles() {
        for (int i = 0; i < 40; i++) {
            emit(T0.plusSeconds(i), EventType.AUTH_FAILURE, "alice");
        }
        // A1 (1 fois) + A1B (1 fois) seulement, pas de boucle
        assertEquals(1, sink.alerts(AlertRule.A1).size());
        assertEquals(1, sink.alerts(AlertRule.A1B).size());
        long raised = store.range(1, 1000).stream().filter(e -> e.event().type() == EventType.ALERT_RAISED).count();
        assertEquals(sink.alerts().size(), raised);
    }

    @Test
    void uneDestinationEnPanneNeCasseNiLOperationNiLaChaine() {
        InMemoryAuditStore s = new InMemoryAuditStore();
        AuditLog l = new AuditLog(s, new AlertEngine(), alert -> {
            throw new IllegalStateException("console hors ligne");
        });
        l.append(event(T0, EventType.ROLE_CHANGE, "admin-1"));
        assertEquals(1, l.sinkFailures());
        assertEquals(2, s.head().orElseThrow().seq(), "l'alerte est quand même dans la chaîne");
    }
}
