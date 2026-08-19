# Ambiguity Log — atp-java-producer

Natural-language source: `sdk/java/DESIGN.md` (ATP Java Producer SDK design note),
governed by the frozen ATP-0001 / ATP-0002 v0.1 specifications and the golden
vector `test-vectors/CV-CORE-001.json`. Blocking ambiguity prevents sealing;
non-blocking ambiguity is recorded here as an explicit assumption.

## Resolved Assumptions

- **Schema derivation path (v0.1 = runtime reflection).** DESIGN §5.5 recommends a
  compile-time `javax.annotation.processing` processor but permits a runtime
  reflection fallback that MUST produce identical bytes. This bundle admits the
  **runtime reflection** binder for v0.1 (no build-tool dependency, fully
  testable here); the compile-time processor is deferred (Open Question below).
  The `dual-path-equivalence` invariant guards that the two paths cannot diverge.
- **Crypto provider = JDK platform (java.security).** SHA-256 and Ed25519 come
  from the JDK (>= 17). Verified empirically: the JDK Ed25519 signature over the
  CV-CORE-001 `batch_root` is byte-identical to the `ed25519-dalek` reference
  (`ce2d2674…140e05`). No third-party cryptography is admitted (`jdk-only-crypto`).
- **Language level = Java 17.** Records (JDK 16+), `HexFormat`, and platform
  Ed25519 (JDK 15+) are all available; Amazon Corretto 17 is present on the build
  host. Built with `--release 17`.
- **Build system = dependency-free `javac`.** No Maven/Gradle/network is available
  or required; `build.sh` compiles and runs a self-contained test harness, mirroring
  the dependency-free Python and single-crate Rust references.
- **Canonical field names are explicit and snake_case.** `@Field(name=…)` supplies
  the canonical name; Java camelCase component names are not used verbatim when a
  canonical snake_case name exists (DESIGN §5.6). Reproducing `old_state` etc. is
  a hard requirement for the CV-CORE-001 digest.
- **Decode-time defaults are never materialized.** A STRING/BYTES field without an
  explicit `@MaxLen` emits **no** constraints key; the ATP-0002 §4.2 default
  (1024) is applied only at decode time (DESIGN §5.6). Adding `@MaxLen(1024)` would
  change the digest and is treated as a distinct schema.
- **`StateCheckpoint` (primitive 3) is SDK-emitted, never app-authored.** The
  app-facing annotations expose only Transition (0), Observation (1), Relation (2).
- **Emit does no network I/O.** Transmission/ack/retransmit are off-call-path
  background work; the checked-in transport is an in-memory synchronous stub used
  only to exercise the call path deterministically.
- **Golden bytes are the acceptance gate.** Admission = reproducing CV-CORE-001,
  schema-vectors, and the Merkle/opaque vectors byte-for-byte; throughput/latency
  are monitoring budgets, not admission gates.

## Open Questions

- **Compile-time processor as default.** When annotation-processing friction is
  acceptable, should the processor become the default path (build-time schema diff
  in code review, no startup hashing)? Requires a build-tool integration story
  (DESIGN Open Decision #1).
- **Back-pressure contract surface.** The exact blocking/timeout policy on a full
  durable buffer (`RecordHandle.acknowledged()` semantics) needs a documented
  contract so callers cannot reintroduce silent loss (DESIGN Open Decision #3).
- **Unsigned/temporal wrapper types.** Whether to add dedicated `U32`/`TimestampMs`
  wrapper types instead of `@AtpType` overrides, to make signed-vs-unsigned intent
  harder to get wrong (DESIGN Open Decision #2).
- **Signed test-run attestation.** Whether evidence should include a signed
  attestation of the validation result, not just reproduced golden bytes.

## Rejected Interpretations

- **Free-form `log.info(String)` as canonical evidence.** Rejected — it re-creates
  logging and forfeits verifiability. The two-argument floor is mandatory
  (ATP-0001 §3, §12.4); prose belongs in an OpaqueRef, not a canonical field.
- **Deriving field order from reflection/hash-map iteration.** Rejected — order is
  the JLS-guaranteed record component declaration order and enum ordinal order only.
- **Hand-rolled Ed25519.** Rejected in favor of the vetted JDK provider (a
  hand-rolled curve implementation in a production SDK is a supply-chain and
  side-channel risk); the JDK output is already proven byte-identical to the
  reference stacks.
- **Emitting a per-attribute semantic registry.** Out of scope; ATP is not a
  semantic-conventions catalog (that is the ATP-0004 informative profile).
