# ATP Java Producer SDK

A [Protocol-Driven Development](https://github.com/openkedge/pdd-protocol-author)
(PDD) treatment of the ATP Java Producer: the **protocol** is authored first as a
machine-checkable PDD bundle, then the Java **code and tests** are written to
satisfy its invariants. *Code is transient; protocol is sovereign.*

This SDK is a **Conformant Producer** (ATP-0001 §18.1) and nothing more. It lets a
JVM application emit ATP v0.1 evidence with log4j-comparable ergonomics, producing
bytes that are bit-identical to any other conformant producer derived from the
same logical schema and inputs.

## Layout

```
sdk/java/
├── DESIGN.md                         # the original design note (informative)
├── pdd-bundles/atp-java-producer/    # THE PROTOCOL SPEC (PDD bundle)
│   ├── protocol.yaml                 #   purpose, boundary, handshakes
│   ├── schemas/                      #   typed emit request / RecordHandle response
│   ├── capability-manifest.yaml      #   operational authority (deny-by-default)
│   ├── invariants/{structural,behavioral,operational}.yaml
│   ├── validators/validation-plan.yaml   # invariant -> validator -> Java test
│   ├── evidence-requirements.yaml
│   └── ambiguity-log.md              #   resolved assumptions / open questions
├── src/main/java/io/openkedge/atp/
│   ├── internal/                     # TCB core (byte-exact; app never touches it)
│   │   ├── Varint, Cbor, Crypto, Merkle
│   │   ├── FieldDef, Manifest        # ATP-0002 manifest -> canonical CBOR -> H_S
│   │   ├── RecordEncoder             # ATP-0001 §6 record encoding
│   │   └── Batch                     # §7 wire, batch_root, Ed25519 signing
│   ├── annotations/                  # @AtpTransition/@AtpObservation/@AtpRelation, @Field, ...
│   ├── EntityId, SchemaId, ValueType, OpaqueRef
│   ├── SchemaBinder                  # record class -> Manifest (reflection path)
│   ├── AtpEmitter / AtpProducer / Atp # app-facing surface + producer state machine
│   └── Transport, EpochStore, KeyRegistry, RecordHandle, Ack
└── src/test/java/io/openkedge/atp/   # dependency-free conformance harness
```

## Build & test

Requires a JDK ≥ 17 (records + platform Ed25519). No Maven/Gradle/network.

```bash
./build.sh            # compile (main + tests, -Werror) and run the conformance suite
./build.sh validate   # additionally run the PDD bundle validator (needs python3)
```

The suite reproduces the checked-in golden vectors in `../../test-vectors/`
without modifying them (ATP-0001 §18.2). Point it elsewhere with
`-Datp.vectors.dir=...` if needed.

## The interop result

The Java binder derives the canonical manifest of `k8s.pod.transition` and
reproduces the CV-CORE-001 (ATP-0001 Appendix B) schema digest, records, Merkle
root, 197-byte `batch_root` preimage, `batch_root`, Ed25519 signature, and full
wire batch **byte-for-byte** — matching the independent Python and Rust reference
stacks. The JDK platform Ed25519 signature over the `batch_root` is byte-identical
to `ed25519-dalek`.

Schema continuity anchor:

```
b8fafb40cf935d94f1ef933ce411f5d7d881a82413fc463a45db30a37a4103f2
```

## Using it

```java
@AtpEnum(name = "job_state")
enum JobState { Queued, Running, Succeeded, Failed }

@AtpTransition(name = "payments.job.transition", version = "1.0.0",
               publisher = "openkedge.io/payments")
record JobTransition(
        @Field(name = "old_state") JobState oldState,
        @Field(name = "new_state") JobState newState,
        @Optional @Field(name = "exit_code") Integer exitCode) {}

AtpProducer producer = Atp.producer()
        .producerId(producerId16)          // registry-assigned
        .signingKey(seed32, signingKeyId8) // TCB secret; never exposed
        .transport(myTransport)
        .build();

AtpEmitter<JobTransition> JOB = producer.emitter(JobTransition.class);
JOB.emit(EntityId.of("payments.job.v1", "job", jobId),
         new JobTransition(JobState.Running, JobState.Failed, 137));
```

The application deals only with schemas, entities, and typed values. The SDK owns
sequence, `boot_epoch`, `previous_root`, batching, Merkle, `batch_root`, and
signing — the entire trust path (DESIGN §2).

## Invariant → test traceability

Every PDD invariant maps to a concrete test via
`pdd-bundles/atp-java-producer/validators/validation-plan.yaml`. The 37-check
conformance run is the admission evidence (`evidence-requirements.yaml`).

| Class | Invariants (examples) | Tests |
|---|---|---|
| Structural | manifest CBOR, H_S, record layout, batch wire, EntityId, OpaqueRef, type mapping | `ConformanceVectorTest`, `SchemaVectorTest`, `MerkleVectorTest`, `RecordCodecPropertyTest`, `EntityIdTest`, `OpaqueRefTest`, `SchemaBinderTest` |
| Behavioral | cross-language digest, determinism, dual-path, epoch/sequence/chain, exact retransmit, back-pressure, signature, NaN | `DeterminismTest`, `DualPathTest`, `ProducerStateTest`, `BackpressureTest`, `CryptoTest`, `ApiShapeTest` |
| Operational | TCB ownership, JDK-only crypto, no network on call path, opaque boundary, key registry | `ApiShapeTest`, `CallPathTest`, `DependencyScanTest`, `OpaqueRefTest`, `KeyRegistryTest` |

## Scope

Producer side only. Out of scope: the collector, verifier internals, storage,
transport wire framing, and any gateway/LLM/MCP integration (ATP-0001 §1.4). The
compile-time annotation processor is deferred; v0.1 uses the runtime-reflection
binder, guarded against drift by `dual-path-equivalence` (see `ambiguity-log.md`).
