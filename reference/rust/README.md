# `atp-ref`

`atp-ref` is an independent Rust reference for ATP-0001, ATP-0002, and the
ATP-0004 Core Profile. It exists to prove byte-level interoperability with the
standard-library-only Python reference.

Implemented surfaces:

- canonical records and signed batches;
- deterministic-CBOR manifest encoding and semantic validation;
- RFC 6962 Merkle Tree Hash;
- strict Ed25519 signing and verification;
- the ordered collector acceptance state machine;
- exact chain-head checkpoint serialization and parsing;
- schema-aware, historical-key-aware coverage verification;
- canonical entity identity and opaque-reference validation;
- ATP-0004 canonical values, identities, standard manifests, and opaque refs;
- opaque payload length/digest verification; and
- complete ATP-TAB rendering for all 14 value types.

## Build and Test

Rust 1.85 or newer is required.

```bash
cargo fmt --all -- --check
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked --all-targets
```

The current suite runs 3 unit tests and 8 vector integration tests. It checks
the foundational batch, positive schema vectors, collector and coverage
scenarios, opaque dereference outcomes, odd-leaf Merkle roots, ATP-0004 vectors,
strict SemVer, and every ATP-TAB token.

## Examples

```bash
cargo run --locked --example end_to_end
cargo run --locked --example otel_bridge
```

`end_to_end` builds chained signed batches, runs collector and coverage
verification, demonstrates tamper/truncation outcomes, and renders canonical
ATP-TAB.

`otel_bridge` maps a completed Span, LogRecord, and Sum data point with the exact
ATP-0004 standard schemas. It asserts that an untrusted LogRecord body is absent
from signed canonical bytes.

## Layout

```text
src/
  batch.rs       batch construction, parsing, roots, signatures
  cbor.rs        deterministic CBOR encoder subset
  collector.rs   ordered ATP-0001 acceptance state machine
  constants.rs   domains, limits, type codes, error codes
  crypto.rs      SHA-256 and strict Ed25519 wrappers
  identity.rs    canonical entity identifier validation
  manifest.rs    typed manifests, validation, canonical CBOR, digest
  merkle.rs      RFC 6962 Merkle Tree Hash
  otel.rs        ATP-0004 canonicalization and standard manifests
  record.rs      record encoding/validation and opaque verification
  tab.rs         canonical ATP-TAB rendering
  varint.rs      canonical LEB128, ZigZag, bounded reader
  verifier.rs    coverage and checkpoint verification
tests/vectors.rs cross-language conformance tests
```

## Deliberate Limits

This crate is not a shipping SDK. It does not provide:

- a durable producer buffer, transport, or acknowledgement loop;
- a schema-registry service;
- an untrusted raw-CBOR manifest decoder that enforces canonical
  decode/re-encode equality;
- checkpoint discovery or witness-log infrastructure;
- opaque URI fetching or its deployment-specific SSRF policy; or
- a Semantic Gateway.

Callers accepting manifest bytes from an untrusted source must implement the
ATP-0002 raw parser and registry contract before constructing `Manifest`.
