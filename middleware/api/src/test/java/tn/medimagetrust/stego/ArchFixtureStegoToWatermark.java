package tn.medimagetrust.stego;

import tn.medimagetrust.watermark.FlowType;

/** Fixture du test d'architecture : dépendance de stego vers watermark (doit violer la règle 11). */
public final class ArchFixtureStegoToWatermark {
    private ArchFixtureStegoToWatermark() {}

    static String name(FlowType flow) {
        return flow.name();
    }
}
