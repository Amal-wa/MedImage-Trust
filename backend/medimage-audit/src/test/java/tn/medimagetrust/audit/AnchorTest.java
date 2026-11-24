package tn.medimagetrust.audit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SEC-K03 appliqué aux ancrages : format binaire fixe, tests négatifs ; SEC09 : volumes. */
class AnchorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 23);
    private final InMemoryLogKeyProvider keys = new InMemoryLogKeyProvider(1);

    private Anchor sample() {
        return Anchor.sign(DAY, 42, new byte[32], keys);
    }

    @Test
    void formatFixeDe122Octets() {
        byte[] raw = sample().encode();
        assertEquals(122, raw.length);
        assertEquals("MIT-LOG-v1", new String(raw, 0, 10, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(0x00, raw[10]);
        assertEquals(0x01, raw[11], "version");
        assertEquals(0x00, raw[12]);
        assertEquals(0x01, raw[13], "kid sur 2 octets big-endian");
    }

    @Test
    void allerRetourEtSignatureValide() {
        Anchor a = sample();
        Anchor b = Anchor.decode(a.encode());
        assertEquals(DAY, b.day());
        assertEquals(42, b.lastSeq());
        assertEquals(1, b.kid());
        assertTrue(b.verifySignature(keys.publicKey(1).orElseThrow()));
    }

    @Test
    void troncatureEtOctetAjouteRejetes() {
        byte[] raw = sample().encode();
        assertThrows(IllegalArgumentException.class, () -> Anchor.decode(Arrays.copyOf(raw, 121)));
        assertThrows(IllegalArgumentException.class, () -> Anchor.decode(Arrays.copyOf(raw, 123)));
        assertThrows(IllegalArgumentException.class, () -> Anchor.decode(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Anchor.decode(null));
    }

    @Test
    void versionInconnueEtEtiquetteCroiseeRejetees() {
        byte[] raw = sample().encode();
        byte[] v2 = raw.clone();
        v2[11] = 0x02;
        assertThrows(IllegalArgumentException.class, () -> Anchor.decode(v2));
        byte[] label = raw.clone();
        label[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> Anchor.decode(label));
        byte[] sep = raw.clone();
        sep[10] = 0x01;
        assertThrows(IllegalArgumentException.class, () -> Anchor.decode(sep));
    }

    @Test
    void toutOctetSigneModifieInvalideLaSignature() {
        Anchor ok = sample();
        for (int i = 14; i < 58; i++) { // jour, séquence, condensat (le kid est testé à part)
            byte[] raw = ok.encode();
            raw[i] ^= 0x01;
            try {
                Anchor tampered = Anchor.decode(raw);
                assertFalse(tampered.verifySignature(keys.publicKey(1).orElseThrow()), "octet " + i);
            } catch (IllegalArgumentException refuseAuDecodage) {
                // rejet précoce : acceptable
            }
        }
    }

    @Test
    void signatureModifieeRejetee() {
        byte[] raw = sample().encode();
        raw[100] ^= 0x01;
        assertFalse(Anchor.decode(raw).verifySignature(keys.publicKey(1).orElseThrow()));
    }

    @Test
    void ancienKidResteVerifiableApresRotation() {
        Anchor ancien = sample();
        keys.rotate(2);
        Anchor recent = Anchor.sign(DAY.plusDays(1), 50, new byte[32], keys);
        assertEquals(2, recent.kid());
        assertTrue(ancien.verifySignature(keys.publicKey(1).orElseThrow()));
        assertTrue(recent.verifySignature(keys.publicKey(2).orElseThrow()));
        assertFalse(ancien.verifySignature(keys.publicKey(2).orElseThrow()), "kid croisé");
    }

    @Test
    void kidInconnuSansClePublique() {
        assertTrue(keys.publicKey(99).isEmpty());
    }

    // ------------------------------------------------------------------ volumes

    @Test
    void volumeEnMemoireCreationSeuleEtImmuable() {
        InMemoryAnchorStore s = new InMemoryAnchorStore();
        s.write(DAY, sample().encode());
        assertThrows(AuditException.class, () -> s.write(DAY, sample().encode()));
        assertArrayEquals(sample().encode().length == 122 ? s.read(DAY).orElseThrow() : null, s.read(DAY).orElseThrow());
    }

    @Test
    void volumeFichierCreationSeuleEtListeTriee(@TempDir Path dir) {
        FileAnchorStore s = new FileAnchorStore(dir);
        byte[] a2 = Anchor.sign(DAY.plusDays(1), 60, new byte[32], keys).encode();
        s.write(DAY.plusDays(1), a2);
        s.write(DAY, sample().encode());
        assertEquals(java.util.List.of(DAY, DAY.plusDays(1)), s.days());
        assertArrayEquals(a2, s.read(DAY.plusDays(1)).orElseThrow());
        assertTrue(s.read(DAY.plusDays(5)).isEmpty());
        assertThrows(AuditException.class, () -> s.write(DAY, sample().encode()), "pas d'écrasement");
    }

    @Test
    void volumeFichierSansFichierTemporaireResiduel(@TempDir Path dir) throws Exception {
        FileAnchorStore s = new FileAnchorStore(dir);
        s.write(DAY, sample().encode());
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void fichiersEtrangersIgnoresParLaListe(@TempDir Path dir) throws Exception {
        FileAnchorStore s = new FileAnchorStore(dir);
        Files.writeString(dir.resolve("notes.txt"), "x");
        s.write(DAY, sample().encode());
        assertEquals(java.util.List.of(DAY), s.days());
    }
}
