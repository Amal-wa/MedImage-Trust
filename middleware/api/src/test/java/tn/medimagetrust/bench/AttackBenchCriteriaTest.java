package tn.medimagetrust.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntUnaryOperator;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tn.medimagetrust.embedding.Phantoms;
import tn.medimagetrust.fingerprint.FingerprintEmbedder;
import tn.medimagetrust.fingerprint.FingerprintService;
import tn.medimagetrust.fingerprint.HmacSha256Prf;
import tn.medimagetrust.fingerprint.Prf;
import tn.medimagetrust.fingerprint.TardosCodeGenerator;

/**
 * Test du critère bloquant d'attribution du banc de robustesse : dans un cas où l'attribution doit échouer (Δ de
 * 8 bits passé à l'extraction pour une copie 12 bits, comme si B était lue dans des attributs modifiés), le banc doit
 * le signaler par le code de sortie 1, une ligne {@code ::error::} et le statut « ÉCHEC (attribution) » du rapport.
 * Témoin : avec la profondeur du registre, aucun échec d'attribution.
 */
@Tag("SEC-D04")
@DisplayName("Banc de robustesse : le critère d'attribution signale un échec")
class AttackBenchCriteriaTest {
    private static final int COPIES = 2;

    private record Bench(FingerprintService service, FingerprintEmbedder embedder, TardosCodeGenerator generator,
                         List<String> recipients) {}

    /** Même clé K_F de test et mêmes destinataires que le banc. */
    private static Bench bench() {
        byte[] key = new byte[32];
        new Random(AttackBench.TEST_SEED_BENCH).nextBytes(key);
        Prf prf = new HmacSha256Prf(key);
        List<String> recipients = new ArrayList<>();
        for (int i = 0; i < AttackBench.RECIPIENTS; i++) recipients.add("recipient-" + i);
        return new Bench(new FingerprintService(prf), new FingerprintEmbedder(prf), new TardosCodeGenerator(prf),
                recipients);
    }

    private static List<AttackBench.PhantomResult> run12Bits(IntUnaryOperator extractionBits) {
        Bench b = bench();
        return List.of(AttackBench.run("12 bits (512 × 512)", Phantoms.normalSpec(), COPIES, b.service(),
                b.embedder(), b.generator(), b.recipients(), extractionBits));
    }

    private static String log(List<AttackBench.PhantomResult> results, int[] code) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            code[0] = AttackBench.exitCode(results, out);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Δ de 8 bits sur une copie 12 bits : code 1, ::error:: d'attribution, « ÉCHEC (attribution) » au rapport")
    void wrongDeltaIsReportedAsAttributionFailure() {
        List<AttackBench.PhantomResult> results = run12Bits(b -> 8);
        int[] code = new int[1];
        String log = log(results, code);
        assertEquals(1, code[0], "le banc doit sortir avec le code 1");
        // Chaque scénario signale l'échec : moins de COPIES copies sur COPIES. Après JPEG, la quantification en 8 bits
        // laisse parfois une accusation correcte malgré le mauvais Δ (observé : 1 sur 2) ; le critère échoue quand même.
        for (String scenario : List.of("sans attaque", "JPEG Q75", "redimensionnement 75 %")) {
            assertTrue(Pattern.compile("::error::12 bits \\(512 × 512\\), " + Pattern.quote(scenario)
                            + " : vrai destinataire accusé dans [0-" + (COPIES - 1) + "] copies sur " + COPIES)
                    .matcher(log).find(), "erreur d'attribution absente pour " + scenario + " :\n" + log);
        }
        String report = AttackBench.markdown(results, COPIES);
        assertTrue(report.contains("ÉCHEC (attribution)"), "statut absent du rapport");
        // Le mauvais Δ n'accuse aucun innocent : c'est bien le critère d'attribution qui fait échouer le banc.
        assertFalse(report.contains("ÉCHEC (faux positifs)"));
    }

    @Test
    @DisplayName("Témoin : profondeur du registre, aucun échec d'attribution (code 0)")
    void registryDepthPasses() {
        List<AttackBench.PhantomResult> results = run12Bits(IntUnaryOperator.identity());
        int[] code = new int[1];
        String log = log(results, code);
        assertEquals(0, code[0], log);
        assertFalse(log.contains("::error::"), log);
        assertFalse(AttackBench.markdown(results, COPIES).contains("ÉCHEC"));
    }
}
