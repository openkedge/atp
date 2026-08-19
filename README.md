# Agent Telemetry Protocol (ATP)

ATP is a protocol for verifiable, schema-bound operational evidence produced for
automated operators. It encodes state transitions, observations, relations, and
checkpoints as canonical records inside signed, hash-chained batches.

This repository contains the release-candidate specifications, conformance
vectors, and independent Python and Rust reference implementations. The paper
[*Agent-Native Telemetry: Verifiable State-Delta Evidence for Autonomous
Operations*](agent-native-logging.pdf) explains the motivation and evaluation;
it is informative. The specifications and golden vectors define ATP.

## Specifications

ATP uses its own document series. These are not IETF RFC numbers.

| ID | Title | Status |
|---|---|---|
| [ATP-0001](spec/ATP-0001-core-protocol.md) | Core Protocol | Release Candidate |
| [ATP-0002](spec/ATP-0002-schema-manifest-format.md) | Schema Manifest Format | Release Candidate |
| ATP-0003 | Coverage Receipt and Verification Profile | Reserved |
| [ATP-0004](spec/ATP-0004-opentelemetry-mapping-profile.md) | OpenTelemetry Mapping Profile | Release Candidate |
| ATP-0005 | Semantic Gateway Profile | Reserved |

ATP-0001 currently defines coverage classification and chain-head checkpoints.
A future ATP-0003 may add profiles without changing the v0.1 wire contract.

Normative precedence is:

1. the applicable ATP specification;
2. checked-in golden vectors for byte-level and verdict-level behavior; and
3. reference implementations.

The paper does not override the specifications. Corrections to paper examples,
including manifest domain separation, entity-alias scope, checkpoint size, and
ATP-TAB invertibility, are called out in ATP-0001.

## Protocol Scope

ATP-0001 defines:

- canonical evidence records and schema identities;
- deterministic batch, Merkle, and signature bytes;
- producer epochs, sequencing, retransmission, and chain continuity;
- ordered collector validation with stable error codes;
- coverage outcomes and independently signed checkpoints;
- bounded opaque evidence references; and
- canonical ATP-TAB resolved-record rendering.

ATP does not require a storage engine, transport, gateway, model, user
interface, or anomaly detector.

## Repository Layout

```text
spec/                       Normative ATP documents
test-vectors/               Python generators and machine-readable vectors
reference/rust/             Independent Rust reference implementation
sdk/java/DESIGN.md          Non-normative Java producer SDK design note
agent-native-logging.pdf    Informative research paper
```

## Verify

Python 3.9 or newer and Rust 1.85 or newer are supported.

```bash
# Verify that every checked-in vector is current, without rewriting files.
python3 test-vectors/generate_vectors.py --check
python3 test-vectors/generate_schema_vectors.py --check
python3 test-vectors/generate_conformance_vectors.py --check
python3 test-vectors/generate_otel_mapping_vectors.py --check
python3 test-vectors/check_spec_examples.py

# Run the independent Rust implementation against all vector suites.
cd reference/rust
cargo test --locked --all-targets
cargo run --locked --example end_to_end
cargo run --locked --example otel_bridge
```

The current suites contain:

- one foundational byte-exact signed batch;
- 6 positive and 25 executable negative manifest vectors;
- 48 collector, 17 coverage, 3 opaque dereference, and 3 Merkle scenarios;
- 3 ATP-0004 standard manifests, all canonical `AnyValue` forms, 4 identity
  vectors, and Span, Log, and Metric mapping vectors; and
- Rust tests for every vector family and all 14 ATP-TAB value types.

See [test-vectors/README.md](test-vectors/README.md) for the conformance
contract.

## Implementations

`reference/rust` is an interoperability reference, not a production SDK. It
implements canonical encoding, signing, collection, coverage verification,
ATP-0004 mapping primitives, and ATP-TAB rendering. It intentionally omits
durable buffering, transport, registry deployment, and an untrusted raw-CBOR
manifest decoder.

The Java directory currently contains a design note only. It is not a released
or compile-tested SDK.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before changing normative text or golden
vectors. Security issues must follow [SECURITY.md](SECURITY.md), not the public
issue tracker. Project decisions follow [GOVERNANCE.md](GOVERNANCE.md).

## License

Specifications, source code, and test vectors are licensed under Apache License
2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). The included research paper is
informative background and is not relicensed by this repository.
