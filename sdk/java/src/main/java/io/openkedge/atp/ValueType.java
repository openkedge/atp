package io.openkedge.atp;

/**
 * The 14 ATP canonical value types (ATP-0001 §6.3 / ATP-0002 §4), with their
 * wire codes. Used by {@code @AtpType} to pin a mapping that differs from the
 * Java-type default.
 */
public enum ValueType {
    BOOL(0),
    U32(1),
    U64(2),
    I32(3),
    I64(4),
    F32(5),
    F64(6),
    ENUM(7),
    STRING(8),
    BYTES(9),
    TIMESTAMP_MS(10),
    ENTITY_REF(11),
    OPAQUE_REF(12),
    DURATION_MS(13),
    /** Sentinel meaning "use the Java-type default mapping". */
    DEFAULT(-1);

    private final int code;

    ValueType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
