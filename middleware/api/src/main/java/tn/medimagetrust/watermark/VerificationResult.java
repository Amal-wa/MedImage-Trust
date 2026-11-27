package tn.medimagetrust.watermark;

public record VerificationResult(
        boolean valid,
        VerificationReason reason,
        WatermarkPayload payload
) {
    public static VerificationResult accepted(WatermarkPayload payload) {
        return new VerificationResult(true, VerificationReason.CONFORME, payload);
    }

    public static VerificationResult rejected(VerificationReason reason) {
        return new VerificationResult(false, reason, null);
    }
}