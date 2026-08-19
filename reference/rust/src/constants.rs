//! Fixed protocol constants (ATP-0001 Appendix A).

pub const D_BATCH: &[u8] = b"ATP/0.1/batch-root";
pub const D_MANIFEST: &[u8] = b"ATP/0.1/schema-manifest";
pub const D_CHECKPOINT: &[u8] = b"ATP/0.1/chain-head-checkpoint";
pub const D_OTEL_INTENT: &[u8] = b"ATP/0.1/otel-intent";
pub const D_OTEL_RESOURCE: &[u8] = b"ATP/0.1/otel-resource";

pub const ZERO32: [u8; 32] = [0u8; 32];
pub const PROTOCOL_VERSION_V0_1: u16 = 0x0001;

// ATP-0001 static parser limits. These are checked before authentication.
pub const MAX_BATCH_BYTES: usize = 16 * 1024 * 1024;
pub const MAX_RECORDS_PER_BATCH: u32 = 65_535;
pub const MAX_RECORD_BYTES: usize = 65_535;
pub const MAX_SCHEMA_DICTIONARY_ENTRIES: u64 = 65_535;
pub const MAX_ENTITY_DICTIONARY_DELTA_ENTRIES: u64 = 65_535;
pub const MAX_ENTITY_ID_BYTES: usize = 1_024;
pub const MAX_MANIFEST_BYTES: usize = 1_048_576;
pub const MAX_SCHEMA_FIELDS: usize = 1_024;
pub const MAX_SCHEMA_ENUMS: usize = 1_024;

pub const MAX_OPAQUE_REF_BYTES: usize = 4_096;
pub const MAX_OPAQUE_ID_BYTES: usize = 128;
pub const MAX_MEDIA_TYPE_BYTES: usize = 127;
pub const MAX_STORAGE_URI_BYTES: usize = 2_048;

// ATP value type codes (ATP-0001 §6.3 / ATP-0002 §4).
pub const T_BOOL: u64 = 0;
pub const T_U32: u64 = 1;
pub const T_U64: u64 = 2;
pub const T_I32: u64 = 3;
pub const T_I64: u64 = 4;
pub const T_F32: u64 = 5;
pub const T_F64: u64 = 6;
pub const T_ENUM: u64 = 7;
pub const T_STRING: u64 = 8;
pub const T_BYTES: u64 = 9;
pub const T_TIMESTAMP_MS: u64 = 10;
pub const T_ENTITY_REF: u64 = 11;
pub const T_OPAQUE_REF: u64 = 12;
pub const T_DURATION_MS: u64 = 13;

pub const DEFAULT_MAX_LEN: u64 = 1024;
pub const HARD_MAX_LEN: u64 = 4096;

// Error codes (ATP-0001 Appendix C).
pub const E_MALFORMED_BATCH: &str = "ATP_ERR_MALFORMED_BATCH";
pub const E_UNSUPPORTED_VERSION: &str = "ATP_ERR_UNSUPPORTED_VERSION";
pub const E_UNKNOWN_PRODUCER: &str = "ATP_ERR_UNKNOWN_PRODUCER";
pub const E_UNKNOWN_KEY: &str = "ATP_ERR_UNKNOWN_KEY";
pub const E_INVALID_SIGNATURE: &str = "ATP_ERR_INVALID_SIGNATURE";
pub const E_SCHEMA_UNKNOWN: &str = "ATP_ERR_SCHEMA_UNKNOWN";
pub const E_SCHEMA_UNAUTHORIZED: &str = "ATP_ERR_SCHEMA_UNAUTHORIZED";
pub const E_PREVIOUS_ROOT_MISMATCH: &str = "ATP_ERR_PREVIOUS_ROOT_MISMATCH";
pub const E_SEQUENCE_GAP: &str = "ATP_ERR_SEQUENCE_GAP";
pub const E_EPOCH_REUSE: &str = "ATP_ERR_EPOCH_REUSE";
pub const E_MERKLE_MISMATCH: &str = "ATP_ERR_MERKLE_MISMATCH";
pub const E_MALFORMED_RECORD: &str = "ATP_ERR_MALFORMED_RECORD";
pub const E_SCHEMA_VIOLATION: &str = "ATP_ERR_SCHEMA_VIOLATION";
pub const E_COMMIT_FAILED: &str = "ATP_ERR_COMMIT_FAILED";
pub const E_OPAQUE_DIGEST_MISMATCH: &str = "ATP_ERR_OPAQUE_DIGEST_MISMATCH";
pub const E_OPAQUE_LENGTH_MISMATCH: &str = "ATP_ERR_OPAQUE_LENGTH_MISMATCH";
