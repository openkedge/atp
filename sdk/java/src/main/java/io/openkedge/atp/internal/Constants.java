package io.openkedge.atp.internal;

import java.nio.charset.StandardCharsets;

/**
 * Fixed protocol constants (ATP-0001 Appendix A, ATP-0002 §4). TCB-internal.
 *
 * <p>These values are frozen by the ATP v0.1 specifications and the golden
 * vector {@code test-vectors/CV-CORE-001.json}. Any change here is a protocol
 * change, not an implementation detail.
 */
public final class Constants {
    private Constants() {}

    // Domain separation strings (ASCII, no NUL terminator).
    public static final byte[] D_BATCH = "ATP/0.1/batch-root".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] D_MANIFEST = "ATP/0.1/schema-manifest".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] D_CHECKPOINT = "ATP/0.1/chain-head-checkpoint".getBytes(StandardCharsets.US_ASCII);

    public static final byte[] ZERO32 = new byte[32];
    public static final int PROTOCOL_VERSION_V0_1 = 0x0001;

    // ATP value type codes (ATP-0001 §6.3 / ATP-0002 §4).
    public static final int T_BOOL = 0;
    public static final int T_U32 = 1;
    public static final int T_U64 = 2;
    public static final int T_I32 = 3;
    public static final int T_I64 = 4;
    public static final int T_F32 = 5;
    public static final int T_F64 = 6;
    public static final int T_ENUM = 7;
    public static final int T_STRING = 8;
    public static final int T_BYTES = 9;
    public static final int T_TIMESTAMP_MS = 10;
    public static final int T_ENTITY_REF = 11;
    public static final int T_OPAQUE_REF = 12;
    public static final int T_DURATION_MS = 13;

    // Primitive codes (ATP-0001 §3.1).
    public static final int P_TRANSITION = 0;
    public static final int P_OBSERVATION = 1;
    public static final int P_RELATION = 2;
    public static final int P_STATE_CHECKPOINT = 3;

    // Static limits (ATP-0001 Appendix A).
    public static final int MAX_BATCH_BYTES = 16 * 1024 * 1024;
    public static final int MAX_RECORDS_PER_BATCH = 65_535;
    public static final int MAX_RECORD_BYTES = 65_535;
    public static final int MAX_SCHEMA_DICTIONARY_ENTRIES = 65_535;
    public static final int MAX_ENTITY_DICTIONARY_DELTA_ENTRIES = 65_535;
    public static final int MAX_ENTITY_ID_BYTES = 1_024;
    public static final int MAX_MANIFEST_BYTES = 1_048_576;
    public static final int MAX_SCHEMA_FIELDS = 1_024;
    public static final int MAX_SCHEMA_ENUMS = 1_024;

    public static final int MAX_OPAQUE_REF_BYTES = 4_096;
    public static final int MAX_OPAQUE_ID_BYTES = 128;
    public static final int MAX_MEDIA_TYPE_BYTES = 127;
    public static final int MAX_STORAGE_URI_BYTES = 2_048;

    public static final long DEFAULT_MAX_LEN = 1024;
    public static final long HARD_MAX_LEN = 4096;

    // Error codes (ATP-0001 Appendix C).
    public static final String E_MALFORMED_BATCH = "ATP_ERR_MALFORMED_BATCH";
    public static final String E_UNSUPPORTED_VERSION = "ATP_ERR_UNSUPPORTED_VERSION";
    public static final String E_MALFORMED_RECORD = "ATP_ERR_MALFORMED_RECORD";
    public static final String E_SCHEMA_VIOLATION = "ATP_ERR_SCHEMA_VIOLATION";
    public static final String E_BUFFER_FULL = "ATP_ERR_BUFFER_FULL";
    public static final String E_OPAQUE_DIGEST_MISMATCH = "ATP_ERR_OPAQUE_DIGEST_MISMATCH";
    public static final String E_OPAQUE_LENGTH_MISMATCH = "ATP_ERR_OPAQUE_LENGTH_MISMATCH";
}
