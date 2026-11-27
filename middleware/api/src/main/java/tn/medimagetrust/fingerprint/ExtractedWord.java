package tn.medimagetrust.fingerprint;

/** Mot lu dans une copie : symboles 0/1 ou {@link #ERASURE}. */
public record ExtractedWord(byte[] symbols, int erasures) {
    public static final byte ERASURE = -1;

    public int readable() { return symbols.length - erasures; }
}
