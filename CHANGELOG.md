# Changelog

All notable changes to ATP specifications and conformance artifacts are recorded
here.

The project follows Semantic Versioning for document/profile releases. Protocol
wire compatibility is governed by the applicable ATP specification.

## [Unreleased]

### Added

- Apache-2.0 licensing, contribution, governance, security, conduct, and CI
  policies.
- ATP-0004 mapping vectors and Rust mapping primitives.
- Canonical ATP-TAB renderer coverage for all 14 value types.
- Opaque payload length-mismatch conformance vector.
- Exact 152-byte chain-head checkpoint vectors and Rust wire parsing.
- Collector-side key-rotation, over-range varint, non-ASCII opaque media-type,
  and overlapping-sequence conformance vectors.

### Corrected

- Producer key registration now preserves historical bindings and fails closed
  on key-ID and duplicate-public-key collisions.
- Coverage authorization inputs and overlapping-range classification are
  explicit in ATP-0001.
- Coverage status precedence now also holds for invalid requests.
- Python and Rust parser behavior is aligned at integer and ASCII boundaries.
- Float constraints retain exact signed-integer bounds above `2^53`.
- ATP-0001 explicitly identifies the paper's unprefixed schema-hash equation as
  superseded by the domain-separated manifest digest.
- ATP-0004 now states the normalized empty `AnyValue` case without conflicting
  with its single-variant rule.
- Rust formatting helpers pass warning-free clippy checks on the documented
  Rust 1.85 minimum toolchain.

## [0.1.0-rc.1] - 2026-08-18

### Added

- ATP-0001 Core Protocol release candidate.
- ATP-0002 Schema Manifest Format release candidate.
- ATP-0004 OpenTelemetry Mapping Profile release candidate.
- Byte-exact core, schema, collector, coverage, opaque, Merkle, and OTel vector
  suites.
- Independent Python and Rust reference implementations.

### Corrected

- Canonical Appendix B values and entity identifiers.
- Checkpoint wire size to 152 bytes.
- Exact-byte retransmission, coverage closure, key rotation, parser limits,
  strict SemVer, and closed manifest-map behavior.
