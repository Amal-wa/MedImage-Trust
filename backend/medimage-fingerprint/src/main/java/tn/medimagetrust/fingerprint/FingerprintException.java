package tn.medimagetrust.fingerprint;

/** Erreur fermée : l'émission est annulée et journalisée (CU07, fail-closed). */
public final class FingerprintException extends RuntimeException {
    public enum Code { CAPACITY, INVALID_INPUT }

    private final Code code;

    public FingerprintException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }
}
