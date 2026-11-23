package tn.medimagetrust.fingerprint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Point d'entrée de la CI : java -cp target/classes tn.medimagetrust.fingerprint.SimulationMain [essais] [dossier]
 * Écrit fingerprint-simulation.md et .json ; code de sortie 1 si la fausse accusation dépasse l'objectif.
 */
public final class SimulationMain {
    public static void main(String[] args) throws Exception {
        int trials = args.length > 0 ? Integer.parseInt(args[0]) : 3000;
        Path dir = Path.of(args.length > 1 ? args[1] : "target/reports");
        var cfg = new CollusionSimulator.Config(trials, FingerprintParams.MAX_RECIPIENTS, 0.0, 20260101L);
        List<CollusionSimulator.StrategyResult> results = new CollusionSimulator().run(cfg);

        Files.createDirectories(dir);
        String md = SimulationReport.toMarkdown(cfg, results);
        Files.writeString(dir.resolve("fingerprint-simulation.md"), md);
        Files.writeString(dir.resolve("fingerprint-simulation.json"), SimulationReport.toJson(cfg, results));
        System.out.println(md);

        boolean ok = results.stream().allMatch(
                r -> r.falseAccusationRate() <= FingerprintParams.TARGET_FALSE_ACCUSATION);
        if (!ok) {
            System.err.println("ECHEC : taux de fausse accusation > " + FingerprintParams.TARGET_FALSE_ACCUSATION);
            System.exit(1);
        }
    }
}
