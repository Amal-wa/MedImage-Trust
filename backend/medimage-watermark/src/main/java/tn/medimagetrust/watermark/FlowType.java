package tn.medimagetrust.watermark;

public enum FlowType {
    CLINICAL((byte) 0x01),
    RESEARCH((byte) 0x02);

    private final byte code;

    FlowType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static FlowType fromCode(byte code) {
        return switch (code) {
            case 0x01 -> CLINICAL;
            case 0x02 -> RESEARCH;
            default -> throw new IllegalArgumentException("Code de flux inconnu: " + code);
        };
    }
}