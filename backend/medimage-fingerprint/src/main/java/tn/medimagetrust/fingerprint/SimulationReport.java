package tn.medimagetrust.fingerprint;

import java.util.List;
import java.util.Locale;

/** Rapport publié par la CI : taux de fausse accusation et de détection (AC06). */
public final class SimulationReport {
    private SimulationReport() {}

    public static String toMarkdown(CollusionSimulator.Config cfg, List<CollusionSimulator.StrategyResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Simulation de collusions - fingerprinting (SEC-D04)\n\n");
        sb.append(String.format(Locale.ROOT,
                "Essais par stratégie : %d - destinataires : %d - effacements : %.1f %% - graine : %d\n\n",
                cfg.trialsPerStrategy(), cfg.recipients(), cfg.erasureRate() * 100, cfg.seed()));
        sb.append("| Stratégie | Fausses accusations | Taux FA | Borne haute 95 % | Détection >= 1 complice | Les 2 complices |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (var r : results) {
            sb.append(String.format(Locale.ROOT, "| %s | %d / %d | %.4f %% | %.4f %% | %.2f %% | %.2f %% |\n",
                    r.strategy(), r.falseAccusationTrials(), r.trials(), r.falseAccusationRate() * 100,
                    r.falseAccusationUpper95() * 100, r.detectionRate() * 100, r.bothDetectedRate() * 100));
        }
        sb.append("\nObjectifs : fausse accusation <= 0,1 % ; détection d'au moins un complice >= 90 % (objectif initial, révisé après simulation).\n");
        return sb.toString();
    }

    public static String toJson(CollusionSimulator.Config cfg, List<CollusionSimulator.StrategyResult> results) {
        StringBuilder sb = new StringBuilder("{\"trialsPerStrategy\":").append(cfg.trialsPerStrategy())
                .append(",\"recipients\":").append(cfg.recipients()).append(",\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT,
                    "{\"strategy\":\"%s\",\"falseAccusations\":%d,\"falseAccusationUpper95\":%.6f,\"detectionRate\":%.4f,\"bothDetectedRate\":%.4f}",
                    r.strategy(), r.falseAccusationTrials(), r.falseAccusationUpper95(), r.detectionRate(), r.bothDetectedRate()));
        }
        return sb.append("]}").toString();
    }
}
