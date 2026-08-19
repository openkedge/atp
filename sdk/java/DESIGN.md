# ATP Java Producer SDK — Design Note

| | |
|---|---|
| **Document** | `sdk/java/DESIGN.md` (design note, not normative) |
| **Status** | Design only; no SDK implementation is included |
| **Conformance target** | ATP-0001/ATP-0002 v0.1 release candidates |
| **Normative references** | ATP-0001 §4 (identity), §6 (record encoding), §7–§9 (batch/producer/collector), §12 (opaque); ATP-0002 (manifests) |
| **Scope** | The **producer** side only: how an existing Java application emits conformant ATP evidence with log4j-comparable call-site ergonomics. |

> This note designs an SDK. It is **not** part of the ATP standards series and defines no wire behavior. The canonical artifacts are always the ATP-0002 schema **manifest** and ATP-0001 bytes — the Java surface is a convenience binding to them (§5.1 here).

---

## 1. Scope and non-goals

**In scope:** the app-facing API; the mapping from annotated Java types to ATP schema manifests; the guarantee that a Java-derived `H_S` is bit-identical to what a Go/Rust/Python SDK derives from the same logical schema (ATP-0001 §1.6).

**Explicitly out of scope** (unchanged from ATP-0001 §1.4): the collector, the verifier internals, the storage engine, the transport wire framing, any gateway/LLM/MCP integration. The SDK is a **Conformant Producer** (ATP-0001 §18.1) and nothing more.

---

## 2. Design principle: the app never touches the trust path

The single ergonomic idea: **the application deals only with schemas, entities, and typed values; the SDK owns the entire TCB/crypto path.** An application author who has never read §7 must still emit bit-perfect batches.

| Concern | Owner | ATP-0001 |
|---|---|---|
| Event shape (schema) + content-addressed `H_S` | **App** (design-time, one `record` per event) | ATP-0002 |
| Canonical entity identity | **App** (one `EntityId` per domain object) | §4 |
| Typed field values | **App** (the `record`'s components) | §6.3–6.4 |
| Sequence, `boot_epoch`, `previous_root`, batching, Merkle, `batch_root`, Ed25519 signing | **SDK (TCB)** — invisible to the app | §7, §8 |
| Transport, ack, byte-identical retransmit, durable buffer, **no silent drops** | **SDK (TCB)** | §8.1–§8.4, §9.2 |
| Opaque payload storage + `payload_digest` | **SDK** | §12 |

**The irreducible floor.** The minimum a call can carry is *which typed event* and *about which entity*: `emit(entity, event)`, or `emit(event)` when the entity is bound once. It cannot degrade to `log.info("string")` — accepting free-form prose re-creates logging and forfeits verifiability (ATP-0001 §3, §12.4). This two-argument floor is the price of evidence.

---

## 3. Public API surface

### 3.1 Declaring an event — the schema *is* the type

```java
@AtpEnum(name = "job_state")
enum JobState { Queued, Running, Succeeded, Failed }   // ordinal order == enum ordinal (§6.3)

@AtpTransition(name = "payments.job.transition", version = "1.0.0",
               publisher = "openkedge.io/payments")
public record JobTransition(
        @Field(name = "old_state") JobState oldState,      // slot 0, required
        @Field(name = "new_state") JobState newState,      // slot 1, required
        @Optional @Field(name = "exit_code") Integer exitCode,   // slot 2, optional
        @Optional @Field(name = "detail")   @Opaque Throwable detail) {}  // slot 3, OPAQUE_REF
```

- Record component **declaration order == slot order** (`0..k-1`, dense — the JLS guarantees `RecordComponent[]` order).
- `@AtpTransition` / `@AtpObservation` / `@AtpRelation` fix the primitive (0/1/2). `StateCheckpoint` (3) is SDK-emitted, never app-authored.
- `@Optional` ⇒ `required = false` (presence-bitmap gated, §6.4); otherwise required. Optional components should be a **boxed/reference type** so `null` = absent.
- `@Opaque Throwable` is Java sugar: at emit time the SDK serializes the stack trace to bytes and stores it out-of-band (§12); the *manifest* sees only `OPAQUE_REF`.

### 3.2 Emitting — one line, log4j-shaped

```java
// mirrors: private static final Logger log = LoggerFactory.getLogger(PaymentWorker.class);
private static final AtpEmitter<JobTransition> JOB = Atp.emitter(JobTransition.class);

void onJobFailed(String jobId, int exitCode, Throwable cause) {
    JOB.emit(EntityId.of("payments.job.v1", "job", jobId),
             new JobTransition(Running, Failed, exitCode, cause));
}
```

Bind the entity once for self-reporting producers (true one-argument call):

```java
static final AtpEmitter<HealthObservation> HEALTH =
        Atp.emitter(HealthObservation.class).forEntity(SELF);
HEALTH.emit(new HealthObservation(0.42, true));
```

### 3.3 Core interfaces (abridged)

```java
public interface AtpEmitter<E> {
    RecordHandle emit(EntityId entity, E event);
    RecordHandle emit(E event);                 // requires forEntity(...) binding
    AtpEmitter<E> forEntity(EntityId bound);
    AtpEmitter<E> at(java.time.Instant when);   // default = call time; SDK derives time_delta (§3.4)
    SchemaId schemaId();                         // the content-addressed H_S
}

public interface RecordHandle {
    long sequence();                             // assigned (producer_id, boot_epoch, sequence)
    CompletableFuture<Ack> acknowledged();       // resolves on collector commit (§9.1 stage 13)
}

public final class EntityId {                    // namespace:resource_type:canonical_identifier (§4.1)
    public static EntityId of(String namespace, String resourceType, String canonicalId);
    public EntityId withDisplayAlias(String shortForm);   // display only; never the identity (§4.1)
}

public final class Atp {                         // producer bootstrap (TCB config; off the call path)
    public static <E> AtpEmitter<E> emitter(Class<E> eventType);   // uses the process-wide producer
    public static AtpProducer.Builder producer();
}
```

`AtpProducer.Builder` carries the TCB configuration — `producerId`, Ed25519 signing key + `signingKeyId` (§7.7), a **durable monotonic** `epochStore` (§8.2), `transport`, a **bounded durable** `durableBuffer` (back-pressure, never drop — §8.4), `clock` quality/`Δ_clk` (§7.2), and batch-window triggers. This is one-time ops wiring; application code touches only `AtpEmitter`.

---

## 4. Java → ATP type mapping (§6.3)

| Java type | ATP type (code) | Notes |
|---|---|---|
| `boolean` / `Boolean` | `BOOL` (0) | |
| `int` / `Integer` | `I32` (3) | default signed; `@AtpType(U32)` to pin unsigned |
| `long` / `Long` | `I64` (4) | `@AtpType(U64 / DURATION_MS / TIMESTAMP_MS)` to override |
| `float` / `Float` | `F32` (5) | NaN normalized per §6.3 |
| `double` / `Double` | `F64` (6) | |
| `enum` + `@AtpEnum` | `ENUM` (7) | members in **ordinal order**; `enum_ref` = `@AtpEnum` name |
| `String` | `STRING` (8) | `@MaxLen(n)` ⇒ `constraints{1:n}`, `n ≤ 4096` (§6.3) |
| `byte[]` | `BYTES` (9) | `@MaxLen(n)` likewise |
| `java.time.Instant` | `TIMESTAMP_MS` (10) | |
| `EntityId` | `ENTITY_REF` (11) | SDK resolves to an epoch-cumulative alias (§4.3) |
| `OpaqueRef`, `@Opaque Throwable`/`byte[]`/`String` | `OPAQUE_REF` (12) | payload stored + digested by SDK (§12) |
| `java.time.Duration` | `DURATION_MS` (13) | |

`intent_ref` is exposed only on `@AtpTransition`/`@AtpRelation` events (a `byte[32] IntentHash` component or `@Intent` marker) — a **compile error** on Observation, enforcing ATP-0001 §3.3 statically instead of at collector-reject time.

---

## 5. Schema binding → canonical manifest (the interop-critical part)

This is the piece that keeps `@AtpTransition` digests **bit-identical** across SDKs.

### 5.1 Principle: the manifest is canonical; the Java type is a binding

`H_S` is computed over the **manifest** (ATP-0002 §6.4), never over Java reflection artifacts. The processor's job is to emit the canonical manifest; the digest follows from it. A Go SDK reading the *same manifest* derives the *same `H_S`* — the Java `record` is one of several ways to author that manifest, not a competing source of truth. This preserves ATP-0001 §1.6 (two independent implementations, one digest).

### 5.2 Derivation rules (record → manifest, ATP-0002 §2)

The manifest is a CBOR map with the integer keys of ATP-0002 §2:

| Manifest key | Source |
|---|---|
| 1 `schema_name` | `@AtpX(name=…)` |
| 2 `schema_version` | `@AtpX(version=…)` (SemVer) |
| 3 `primitive` | annotation kind: Transition=0, Observation=1, Relation=2 |
| 4 `publisher` | `@AtpX(publisher=…)` (TCB-authorized, ATP-0002 §8) |
| 5 `fields` | record components in declaration order; each → a field-def map |
| 6 `enums` | union of all `ENUM` fields' enum types → members in ordinal order |
| 7 `compatibility` | optional `@Compatible(minVersion=…, mode=…)` (ATP-0002 §7.3); omitted if absent |

Each field-def (ATP-0002 §2.2): `1 slot` = component index; `2 name` = `@Field(name=…)` or the component name verbatim; `3 type` = mapped code (§4); `4 required` = `!@Optional`; `5 unit` = `@Unit(…)` (omit if absent); `6 enum_ref` = `@AtpEnum` name (present iff `type == ENUM`); `7 constraints` = `{1: maxLen}` from `@MaxLen` (omit if absent).

### 5.3 Canonicalization and digest (restating ATP-0002 §6)

1. **Deterministic CBOR** (RFC 8949 §4.2.1). Map keys are the fixed integers above, emitted in ascending numeric order (= bytewise-lexicographic for these small uints).
2. **Shortest-form integers**; no indefinite-length items; no duplicate keys.
3. `text` = UTF-8 (major type 3); `byte[]` = major type 2.
4. Arrays preserve semantic order: `fields` by slot, each `enums` array by ordinal.
5. `H_S = SHA-256( D_MANIFEST || CanonicalCBOR(manifest) )`, `D_MANIFEST = "ATP/0.1/schema-manifest"` (Appendix A).

### 5.4 Determinism guarantees (why Java can't drift from Go/Rust)

- **Field order** is component declaration order — guaranteed stable by the JLS, not by hash-map iteration.
- **Enum member order** is ordinal order — declaration order, stable.
- **No nondeterministic maps**: the only CBOR maps use the fixed §5.2 integer keys, always emitted ascending. There is no locale, no reflection-order, no timestamp input.
- The processor **MUST reject at build time**: a component whose Java type has no mapping; an `ENUM` component whose enum lacks `@AtpEnum`; `@MaxLen > 4096`; a duplicate `@Field(name=…)`; a non-record `@AtpX` type.

### 5.5 Compile-time processor (primary) vs runtime reflection (fallback)

**Primary — `javax.annotation.processing` at build time.** For each `@AtpX` record the processor emits:
- `<schema_name>-<version>.manifest.cbor` — the canonical bytes (the artifact checked into the schema registry, ATP-0002 §9);
- a generated `SchemaId` constant holding the precomputed `H_S` (no startup hashing);
- a human-readable `.manifest.json` mirror for review.

Because a changed `record` yields a changed manifest and therefore a changed `H_S`, **schema evolution shows up as a reviewable diff in the CR** — the anti-drift property of §5.1 is enforced by the build, not by discipline. The processor fails the build if a record changes without a version bump when `compatibility` is declared (ATP-0002 §7).

**Fallback — runtime reflection.** The same derivation at first use, computing `H_S` on `Atp.emitter(...)`. Simpler to adopt; loses the build-time diff and adds startup cost. Both paths MUST produce identical bytes.

### 5.6 Worked example — reproducing Appendix B (`k8s.pod.transition`)

The Java binding of the spec's running-example schema:

```java
@AtpEnum(name = "pod_phase")
enum PodPhase { Pending, Running, Succeeded, Failed, Unknown }

@AtpTransition(name = "k8s.pod.transition", version = "1.0.0", publisher = "openkedge.io/k8s")
record PodTransition(
        @Field(name = "old_state") PodPhase oldState,          // slot 0, ENUM(pod_phase), required
        @Field(name = "new_state") PodPhase newState,          // slot 1, ENUM(pod_phase), required
        @Optional @Field(name = "exit_code") Integer exitCode, // slot 2, I32, optional
        @Optional @Field(name = "reason")    String  reason) {} // slot 3, STRING, optional, no constraints
```

Derivation → canonical manifest (annotated CBOR; this is byte-identical to ATP-0001 Appendix B):

```
a6                                            # map(6)
  01 72 "k8s.pod.transition"                  # 1 schema_name
  02 65 "1.0.0"                               # 2 schema_version
  03 00                                       # 3 primitive = 0 (Transition)
  04 70 "openkedge.io/k8s"                    # 4 publisher
  05 84                                       # 5 fields = array(4)
     a5 01 00 02 "old_state" 03 07 04 f5 06 "pod_phase"   # slot0 ENUM req  enum_ref
     a5 01 01 02 "new_state" 03 07 04 f5 06 "pod_phase"   # slot1 ENUM req  enum_ref
     a4 01 02 02 "exit_code" 03 03 04 f4                  # slot2 I32  opt
     a4 01 03 02 "reason"    03 08 04 f4                  # slot3 STRING opt (no constraints key)
  06 a1 "pod_phase" 85 "Pending" "Running" "Succeeded" "Failed" "Unknown"   # 6 enums
```

```
H_S = SHA-256( "ATP/0.1/schema-manifest" || CanonicalCBOR(manifest) )
    = b8fafb40cf935d94f1ef933ce411f5d7d881a82413fc463a45db30a37a4103f2
```

This equals the Appendix B digest. Two derivation subtleties the example pins down:
- `reason` carries **no** `constraints` key (4-entry field-def). The ATP-0002 §4.2 default `max_len` (1024) is a *decode-time* default and MUST NOT be materialized into the manifest — so **do not** add `@MaxLen(1024)` if you want to reproduce this digest.
- `@Field(name=…)` is required here because the canonical names are snake_case (`old_state`) while the Java components are camelCase (`oldState`); the manifest stores the canonical name.

**Conformance check:** the processor's output for `PodTransition` MUST match `test-vectors/CV-CORE-001.json`; run `python3 test-vectors/generate_vectors.py` and compare `schema_digest`. A Java build that reproduces `b8fafb40…` is digest-conformant for this schema.

---

## 6. Emit path (SDK-internal, TCB — the app sees none of this)

On `emit(...)` the SDK: resolves the schema alias into the batch `schema_dictionary`; resolves `EntityId` to its epoch-cumulative alias, appending only first-seen ids to `entity_dictionary_delta` (§4.3); encodes the record (leading varints + `presence_bitmap` computed from which `@Optional`s are non-null + positional values in slot order, §6.4); durably enqueues (back-pressure on a full buffer, never a silent drop, §8.4). A flush trigger (window/size) seals a batch: assign `first_sequence`, compute the RFC 6962 `merkle_root` over unframed leaves (§7.3), build the `batch_root` preimage (§7.4), Ed25519-sign it (§7.5), transmit, await ack, and retransmit **byte-identical** on timeout (§8.1, §9.2). `boot_epoch` is drawn strictly-increasing from the durable `epochStore` at startup (§8.2); profile-specific `StateCheckpoint`s may be emitted automatically. None of this appears in application code.

---

## 7. The slf4j opaque bridge (keep existing logs)

Existing human logs are **not** rewritten. A Logback/Log4j2 appender routes `WARN`+ events into ATP as **opaque evidence** anchored on an `atp.log.emitted` Observation:

```xml
<appender name="ATP-OPAQUE" class="io.openkedge.atp.slf4j.AtpOpaqueAppender">
    <entity>service:host:payments-worker/pod-7d9b</entity>
    <schema>atp.log.emitted:1.0.0</schema>   <!-- Observation: level(ENUM), logger(STRING), payload(OPAQUE_REF) -->
    <minLevel>WARN</minLevel>
</appender>
```

Each event: the formatted message + throwable → `OpaqueRef` (§12); the Observation records *that a log of class X occurred*, entity-scoped and signed. **Honest boundary:** the verifiable value is the Observation, not the prose — the text is untrusted diagnostic content per §12.1, and putting it inline in canonical fields is forbidden (§12.4). Use this for debug narrative; use §3 structured events for the state deltas agents reason over.

---

## 8. Conformance

The SDK is a **Conformant Producer** (§18.1) iff its batches reproduce `schema_digest`, record encodings, `merkle_root`, `batch_root`, and `signature` bit-for-bit, and it obeys the §8 epoch/sequence invariants. The annotation processor is validated against Appendix B (`CV-CORE-001`) via §5.6. End-to-end batch output is validated against the applicable checked-in suites described in `test-vectors/README.md`.

---

## 9. Open decisions

1. **Processor vs reflection as default** — recommend compile-time (build-time schema diff + no startup hashing); revisit if annotation-processing friction blocks adoption.
2. **Unsigned/temporal defaults** — `int→I32`, `long→I64` are signed by default; `U32`/`U64`/`TIMESTAMP_MS`/`DURATION_MS` require `@AtpType`. Consider dedicated wrapper types (`U32`, `TimestampMs`) if `@AtpType` proves error-prone.
3. **`emit()` back-pressure surface** — `RecordHandle.acknowledged()` exposes delivery, but the blocking/timeout policy on a full durable buffer (§8.4) needs an explicit, documented contract so callers don't reintroduce silent loss.
4. **Entity-alias replay bound** — epoch-cumulative aliases require replay from epoch genesis. A future profile may add a separately versioned snapshot mechanism; v0.1 defines none.
5. **Cross-language name convention** — whether `@Field(name=…)` should be mandatory (forcing explicit canonical names) rather than defaulting to the Java component name, to avoid accidental camelCase digests diverging from a Go/Rust sibling schema.
