package tn.medimagetrust.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tn.medimagetrust.audit.TestSupport.T0;
import static tn.medimagetrust.audit.TestSupport.event;
import static tn.medimagetrust.audit.TestSupport.fill;

import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.audit.TestSupport.TamperableStore;
import tn.medimagetrust.audit.TestSupport.TestClock;

/** SEC09 : altération détectée, alerte A7 ; anchrage signé ; comparaison avec la copie ; MT28. */
class AuditVerifierTest {

    private TamperableStore store;
    private InMemoryAnchorStore primary;
    private InMemoryAnchorStore copy;
    private InMemoryLogKeyProvider keys;
    private InMemoryAlertSink sink;
    private TestClock clock;
    private AuditLog log;
    private AnchorService anchors;
    private AuditVerifier verifier;

    @BeforeEach
    void setUp() {
        store = new TamperableStore();
        primary = new InMemoryAnchorStore();
        copy = new InMemoryAnchorStore();
        keys = new InMemoryLogKeyProvider(1);
        sink = new InMemoryAlertSink();
        clock = new TestClock(T0);
        log = new AuditLog(store, null, null);
        anchors = new AnchorService(store, primary, copy, keys, sink, clock);
        verifier = new AuditVerifier(store, primary, copy, keys, sink, clock);
    }

    /** Remplace le contenu de la ligne seq par un autre événement, SANS recalculer les condensats. */
    private void tamperContent(long seq) {
        AuditEntry old = store.get(seq).orElseThrow();
        store.replace(seq, new AuditEntry(seq, event(T0, EventType.ACCESS_DENIED, "attaquant"),
                old.prevHash(), old.entryHash()));
    }

    @Test
    void journalIntegreVerifieSansAlerte() {
        fill(log, 50);
        anchors.anchorToday();
        VerificationReport r = verifier.verify();
        assertTrue(r.ok(), r.problems().toString());
        assertEquals(50, r.entriesChecked());
        assertEquals(1, r.anchorsChecked());
        assertTrue(sink.alerts().isEmpty());
    }

    @Test
    void journalVideEtSansAncrageEstValide() {
        assertTrue(verifier.verify().ok());
    }

    // ------------------------------------------------------------ altérations de la chaîne

    @Test
    void modificationDUneLigneDetecteeAvecSaPosition() {
        fill(log, 30);
        tamperContent(17);
        VerificationReport r = verifier.verify();
        assertFalse(r.ok());
        assertTrue(r.has(ProblemKind.HASH_MISMATCH));
        assertEquals(17, r.problems().get(0).seq());
        assertEquals(1, sink.alerts(AlertRule.A7).size(), "alerte A7");
    }

    @Test
    void suppressionDUneLigneDetectee() {
        fill(log, 30);
        store.delete(10);
        VerificationReport r = verifier.verify();
        assertTrue(r.has(ProblemKind.SEQ_GAP));
        assertEquals(10, r.problems().get(0).seq());
        assertEquals(1, sink.alerts(AlertRule.A7).size());
    }

    @Test
    void reordonnancementDeDeuxLignesDetecte() {
        fill(log, 20);
        AuditEntry a = store.get(5).orElseThrow();
        AuditEntry b = store.get(6).orElseThrow();
        store.replace(5, new AuditEntry(5, b.event(), b.prevHash(), b.entryHash()));
        store.replace(6, new AuditEntry(6, a.event(), a.prevHash(), a.entryHash()));
        assertFalse(verifier.verify().ok());
    }

    @Test
    void insertionDUneLigneForgeeDetectee() {
        fill(log, 20);
        AuditEntry old = store.get(8).orElseThrow();
        // ligne forgée avec un condensat cohérent pour elle-même, mais le chaînage suivant casse
        AuditEntry forged = AuditChain.build(8, event(T0, EventType.ACCESS_DENIED, "faux"), old.prevHash());
        store.replace(8, forged);
        VerificationReport r = verifier.verify();
        assertTrue(r.has(ProblemKind.PREV_MISMATCH));
        assertEquals(9, r.problems().get(0).seq());
    }

    @Test
    void premiereLigneNonChaineeAGeneseDetectee() {
        fill(log, 5);
        AuditEntry e = store.get(1).orElseThrow();
        store.replace(1, new AuditEntry(1, e.event(), new byte[32].length == 32 ? filled() : null, e.entryHash()));
        assertTrue(verifier.verify().has(ProblemKind.PREV_MISMATCH));
    }

    private static byte[] filled() {
        byte[] b = new byte[32];
        b[0] = 1;
        return b;
    }

    // ------------------------------------------------------------ ancrages : ce que la chaîne seule ne voit pas

    @Test
    void reecritureCompleteDeLaChaineParUnSuperutilisateurDetecteeParLAncrage() {
        fill(log, 20);
        anchors.anchorToday();
        // L'attaquant modifie la ligne 7 PUIS recalcule toute la chaîne : la chaîne seule reste cohérente.
        byte[] prev = store.get(6).orElseThrow().entryHash();
        for (long seq = 7; seq <= 20; seq++) {
            AuditEvent ev = seq == 7 ? event(T0, EventType.ACCESS_DENIED, "attaquant") : store.get(seq).orElseThrow().event();
            AuditEntry rebuilt = AuditChain.build(seq, ev, prev);
            store.replace(seq, rebuilt);
            prev = rebuilt.entryHash();
        }
        VerificationReport r = verifier.verify();
        assertFalse(r.ok());
        assertFalse(r.has(ProblemKind.HASH_MISMATCH), "la chaîne recalculée est cohérente");
        assertTrue(r.has(ProblemKind.ANCHOR_HEAD_MISMATCH), "l'ancrage signé la contredit");
        assertEquals(1, sink.alerts(AlertRule.A7).size());
    }

    @Test
    void troncatureDuJournalApresLAncrageDetectee() {
        fill(log, 20);
        anchors.anchorToday();
        store.truncateAfter(15);
        VerificationReport r = verifier.verify();
        assertTrue(r.has(ProblemKind.ANCHOR_ENTRY_MISSING));
    }

    @Test
    void journalVideApresAncrageDetecte() {
        fill(log, 10);
        anchors.anchorToday();
        store.clear();
        assertTrue(verifier.verify().has(ProblemKind.ANCHOR_ENTRY_MISSING));
    }

    @Test
    void suppressionDeLAncrageDuJourDetecteeParLaCopie() {
        fill(log, 10);
        anchors.anchorToday();
        primary.anchors.clear();
        VerificationReport r = verifier.verify();
        assertTrue(r.has(ProblemKind.ANCHOR_COPY_MISSING));
        assertEquals(1, sink.alerts(AlertRule.A7).size());
    }

    @Test
    void suppressionDeLaCopieDetectee() {
        fill(log, 10);
        anchors.anchorToday();
        copy.anchors.clear();
        assertTrue(verifier.verify().has(ProblemKind.ANCHOR_COPY_MISSING));
    }

    @Test
    void modificationDeLAncrageDuJourDetecteeParComparaisonAvecLaCopie() {
        fill(log, 10);
        anchors.anchorToday();
        byte[] raw = primary.anchors.get(LocalDate.of(2026, 11, 23)).clone();
        raw[30] ^= 0x01; // condensat de tête modifié
        primary.anchors.put(LocalDate.of(2026, 11, 23), raw);
        VerificationReport r = verifier.verify();
        assertTrue(r.has(ProblemKind.ANCHOR_COPY_MISMATCH));
        assertTrue(r.has(ProblemKind.ANCHOR_SIGNATURE_INVALID));
    }

    @Test
    void ancrageIllisibleSignale() {
        fill(log, 10);
        primary.anchors.put(LocalDate.of(2026, 11, 20), new byte[7]);
        copy.anchors.put(LocalDate.of(2026, 11, 20), new byte[7]);
        assertTrue(verifier.verify().has(ProblemKind.ANCHOR_MALFORMED));
    }

    @Test
    void ancrageSigneParUneCleInconnueRefuse() {
        fill(log, 10);
        AuditEntry head = store.head().orElseThrow();
        InMemoryLogKeyProvider pirate = new InMemoryLogKeyProvider(77);
        Anchor faux = Anchor.sign(LocalDate.of(2026, 11, 23), head.seq(), head.entryHash(), pirate);
        primary.write(faux.day(), faux.encode());
        copy.write(faux.day(), faux.encode());
        assertTrue(verifier.verify().has(ProblemKind.ANCHOR_UNKNOWN_KEY));
    }

    // ------------------------------------------------------------ service d'ancrage

    @Test
    void ancrageEcritSurLesDeuxVolumesEtIdempotent() {
        fill(log, 10);
        Anchor a = anchors.anchorToday();
        assertEquals(10, a.lastSeq());
        assertEquals(primary.days(), copy.days());
        fill(log, 5); // le journal avance, mais l'ancrage du jour existe déjà
        Anchor again = anchors.anchorToday();
        assertEquals(10, again.lastSeq());
    }

    @Test
    void ancragesDeJoursSuccessifsEtVerificationGlobale() {
        fill(log, 10);
        anchors.anchorToday();
        clock.advance(Duration.ofDays(1));
        fill(log, 10);
        Anchor second = anchors.anchorToday();
        assertEquals(20, second.lastSeq());
        VerificationReport r = verifier.verify();
        assertTrue(r.ok(), r.problems().toString());
        assertEquals(2, r.anchorsChecked());
    }

    @Test
    void echecFermeSiLeVolumeEstPleinAlerteEtRefus() {
        fill(log, 10);
        copy.setFailWrites(true);
        assertThrows(AuditException.class, () -> anchors.anchorToday());
        assertEquals(1, sink.alerts(AlertRule.ANCHOR_FAILURE).size());
        copy.setFailWrites(false);
        Anchor retried = anchors.anchorToday(); // reprise : complète la copie
        assertEquals(10, retried.lastSeq());
        assertEquals(primary.days(), copy.days());
    }

    @Test
    void refuseDeSignerUneTeteIncoherenteAvecLAncragePrecedent() {
        fill(log, 20);
        anchors.anchorToday();
        clock.advance(Duration.ofDays(1));
        // falsification : on remplace la ligne ancrée par une ligne cohérente avec sa propre chaîne
        AuditEntry e = store.get(20).orElseThrow();
        store.replace(20, AuditChain.build(20, event(T0, EventType.ACCESS_DENIED, "attaquant"), e.prevHash()));
        assertThrows(AuditException.class, () -> anchors.anchorToday());
        assertEquals(1, sink.alerts(AlertRule.ANCHOR_FAILURE).size());
        assertEquals(1, primary.days().size(), "aucun nouvel ancrage n'a blanchi la falsification");
    }

    @Test
    void refuseDeSignerUnJournalPlusCourtQueLAncragePrecedent() {
        fill(log, 20);
        anchors.anchorToday();
        clock.advance(Duration.ofDays(1));
        store.truncateAfter(12);
        assertThrows(AuditException.class, () -> anchors.anchorToday());
    }

    @Test
    void journalVidePasDAncrage() {
        assertThrows(AuditException.class, () -> anchors.anchorToday());
    }

    // ------------------------------------------------------------ fraîcheur (SEC-R04, MT28)

    @Test
    void alerteSiAucuneVerificationNaJamaisEuLieu() {
        assertTrue(verifier.checkFreshness().isPresent());
        assertEquals(1, sink.alerts(AlertRule.VERIFICATION_STALE).size());
    }

    @Test
    void pasDAlerteDansLes26HEtAlerteAuDela() {
        fill(log, 3);
        verifier.verify();
        clock.advance(Duration.ofHours(25));
        assertTrue(verifier.checkFreshness().isEmpty());
        clock.advance(Duration.ofHours(2)); // 27 h
        assertTrue(verifier.checkFreshness().isPresent());
        assertEquals(1, sink.alerts(AlertRule.VERIFICATION_STALE).size());
    }

    @Test
    void rapportNeContientAucuneDonneeDeLEvenement() {
        fill(log, 5);
        tamperContent(3);
        String dump = verifier.verify().toString();
        assertFalse(dump.contains("attaquant"));
    }
}
