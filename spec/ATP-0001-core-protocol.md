# ATP-0001 - Core Protocol

**Agent Telemetry Protocol (ATP) v0.1: Verifiable State-Delta Operational Evidence**

| | |
|---|---|
| **Document** | ATP-0001 |
| **Title** | Core Protocol |
| **Document version** | 0.1.0-rc.1 |
| **Wire version** | `0x0001` |
| **Status** | Release Candidate |
| **Authors** | Jun He; Deying Yu |
| **Publisher** | OpenKedge.io |
| **Category** | Standards Track |
| **License** | Apache-2.0 |
| **Updated** | 2026-08-18 |
| **Normative dependencies** | ATP-0002; RFC 2119; RFC 8174; RFC 6962; RFC 8032; RFC 8949 |
| **Informative background** | *Agent-Native Telemetry: Verifiable State-Delta Evidence for Autonomous Operations* (He and Yu), `agent-native-logging.pdf`, cited as **[ANT]** |

> ATP documents are not IETF RFCs and have no IETF RFC numbers. The research
> paper explains the design and evaluates a prototype. This specification and
> its conformance vectors define the protocol. If the paper, an example, or
> explanatory prose conflicts with a normative ATP requirement or a golden
> vector, the ATP specification and golden vector take precedence.

---

## Table of Contents

1. [Status, Scope, and Precedence](#1-status-scope-and-precedence)
2. [Terminology and Trust](#2-terminology-and-trust)
3. [Evidence Model](#3-evidence-model)
4. [Entity Identity](#4-entity-identity)
5. [Schema Manifests](#5-schema-manifests)
6. [Canonical Record Encoding](#6-canonical-record-encoding)
7. [Canonical Batch Encoding](#7-canonical-batch-encoding)
8. [Producer State Machine](#8-producer-state-machine)
9. [Collector Validation](#9-collector-validation)
10. [Coverage Verification](#10-coverage-verification)
11. [Independent Chain-Head Checkpoints](#11-independent-chain-head-checkpoints)
12. [Opaque Evidence](#12-opaque-evidence)
13. [ATP-TAB Canonical Record View](#13-atp-tab-canonical-record-view)
14. [Semantic Gateway](#14-semantic-gateway)
15. [Security Considerations](#15-security-considerations)
16. [Privacy](#16-privacy)
17. [Versioning](#17-versioning)
18. [Conformance](#18-conformance)
- [Appendix A. Constants and Limits](#appendix-a-constants-and-limits)
- [Appendix B. Worked Vector CV-CORE-001](#appendix-b-worked-vector-cv-core-001)
- [Appendix C. Error Code Registry](#appendix-c-error-code-registry)
- [Appendix D. Resolved Decisions and Deferred Profiles](#appendix-d-resolved-decisions-and-deferred-profiles)

---

## 1. Status, Scope, and Precedence

### 1.1 Release status

This document is a release candidate for ATP wire version `0x0001`. The release
candidate is suitable for independent implementation and interoperability
testing. Incompatible corrections found before final publication will retain
the document version history and may require a new wire version.

### 1.2 Layering

| Layer | Name | Role |
|---|---|---|
| Paradigm | Agent-Native Telemetry | The design approach described by [ANT]. Informative. |
| Protocol | Agent Telemetry Protocol | This specification series. Normative wire and validation contract. |
| Architecture | State-Delta Evidence Ledger | One architecture that can implement ATP. Its storage and query internals are not standardized here. |

### 1.3 Normative scope

ATP-0001 defines:

1. evidence primitive identifiers;
2. canonical entity identifiers and epoch-scoped aliases;
3. canonical record and batch bytes;
4. sequencing, chaining, hashing, and signing;
5. producer recovery requirements;
6. deterministic Collector acceptance and error precedence;
7. sequence-range coverage classification;
8. independent chain-head checkpoint bytes and verification;
9. canonical opaque references; and
10. the ATP-TAB canonical resolved-record view.

### 1.4 Out of scope

ATP-0001 does not require a storage engine, transport, compression codec,
gateway query language, LLM, MCP integration, UI, anomaly detector, opaque
object-store implementation, or schema catalog. Transport framing MUST preserve
the exact ATP batch byte string.

### 1.5 Specification series

| ID | Title | Status |
|---|---|---|
| **ATP-0001** | **Core Protocol** | **Release Candidate** |
| **ATP-0002** | **Schema Manifest Format** | **Release Candidate** |
| ATP-0003 | Coverage Receipt and Verification Profile | Reserved |
| **ATP-0004** | **OpenTelemetry Mapping Profile** | **Release Candidate** |
| ATP-0005 | Semantic Gateway Profile | Reserved |

ATP-0002 is authoritative for manifest validity and canonicalization. Sections
5 and 17 of this document summarize the integration points. ATP-0004 adds no
ATP-0001 wire fields.

### 1.6 Interoperability rule

Any ambiguity that can cause two conforming implementations to derive different
canonical bytes, hashes, signatures, or first-failure error codes is a protocol
defect. The checked-in machine-readable vectors are normative examples. They do
not override a general rule for inputs not represented by a vector.

---

## 2. Terminology and Trust

### 2.1 Requirement keywords

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**,
**SHOULD**, **SHOULD NOT**, **RECOMMENDED**, **MAY**, and **OPTIONAL** are to be
interpreted as described in BCP 14 (RFC 2119 and RFC 8174) when they appear in
all capitals.

### 2.2 Terms

- **Producer**: trusted component that resolves schemas, assigns sequences,
  encodes and signs batches, and durably retains unacknowledged bytes.
- **Collector**: trusted append-time verifier and atomic ledger writer.
- **Verifier**: component that verifies retained ranges and checkpoints.
- **Canonical Record**: one schema-resolved record encoded by Section 6.
- **Canonical Batch**: one signed byte string encoded by Section 7.
- **Canonical Plane (`E_can`)**: accepted batches, the exact manifests needed
  to decode them, independent checkpoints, and opaque-reference metadata.
- **Derived Plane (`I_der`)**: indexes, coverage receipts, alias snapshots,
  state graphs, scores, embeddings, summaries, and other reproducible views.
- **Schema Digest (`H_S`)**: ATP-0002 content address of a manifest.
- **Stream**: records for one `(producer_id, boot_epoch)`.
- **Sequence**: contiguous record index within a Stream.
- **Batch Root**: signed hash commitment defined by Section 7.4.
- **Coverage Receipt**: Derived-Plane result for a resolved sequence request.
- **Observation Profile (`Omega`)**: deployment declaration of participating
  producers, schemas, known blind spots, context propagation, and clock bounds.

Coverage receipts are not Canonical-Plane records in wire version `0x0001`.
ATP-0003 may define a separately signed receipt format.

### 2.3 Trusted computing base

The TCB consists of Producer code and keys, Collector validation and commit
state, checkpoint signing and discovery, the read-time Verifier, and historical
schema/key authorization policy. Transport, ledger storage, schema storage, and
opaque object storage are untrusted and may drop, reorder, duplicate, splice,
mutate, truncate, or withhold bytes.

ATP authenticates what trusted instrumentation emitted and the Collector
accepted. It cannot prove that compromised instrumentation reported reality or
that an uninstrumented physical event did not occur.

---

## 3. Evidence Model

### 3.1 Primitive registry

Every manifest declares exactly one primitive:

| Code | Primitive | Intended meaning |
|---:|---|---|
| 0 | `Transition` | Discrete change or operation outcome. |
| 1 | `Observation` | Point-in-time measurement or assertion. |
| 2 | `Relation` | Addition, modification, or removal of a relationship. |
| 3 | `StateCheckpoint` | Producer-authored application-state or loss-accounting snapshot. |

Primitive codes classify semantics; they do not change the common record
envelope. ATP-0001 does not assign universal "from", "to", endpoint, operation,
state-vector, or drop-counter slots. A schema profile that needs those roles
MUST define their exact names, types, and validation. A Collector implementing
only ATP-0001 validates the manifest and encoded values, not publisher-specific
business semantics.

### 3.2 Common record envelope

Every record contains:

- `schema_ref`, an index into the current batch's `schema_dictionary`;
- `entity_ref`, an alias in the current Stream's cumulative entity dictionary;
- `time_delta`, unsigned milliseconds added to the batch `base_time`;
- a presence bitmap for optional schema fields; and
- values in schema-slot order.

Record position supplies sequence: record `i` in a batch has sequence
`first_sequence + i`.

### 3.3 `intent_ref`

The reserved field name `intent_ref` has one wire contract:

- it MAY appear only in a `Transition` or `Relation` manifest;
- it MUST have type `BYTES`;
- when present in a record, it MUST contain exactly 32 bytes; and
- its interpretation is a profile-defined `IntentHash`.

An Observation that needs correlation uses a profile-defined field such as
`trace_ref`; it MUST NOT use `intent_ref`. Invalid declarations or values are
`ATP_ERR_SCHEMA_VIOLATION`.

### 3.4 Ordering and time

Wire order is sequence order. `time_delta` is unsigned and need not increase.
`base_time + time_delta` MUST fit in `u64`; overflow is
`ATP_ERR_MALFORMED_RECORD`. Because event time may be out of order, a time-range
query cannot infer a closed sequence range from sequence order alone
(Section 10.5).

---

## 4. Entity Identity

### 4.1 Canonical syntax

An `entity_id` is an ASCII byte string of 1 to 1,024 bytes:

```text
entity_id            = namespace ":" resource_type ":" canonical_identifier
namespace            = component
resource_type        = component
component            = ALNUM *( ALNUM / "." / "-" / "_" )
canonical_identifier = 1*( identifier-vchar / pct-encoded )
identifier-vchar     = %x21-24 / %x26-7E
pct-encoded          = "%" HEXUPPER HEXUPPER
HEXUPPER             = DIGIT / %x41-46
```

Rules:

1. The first two colons are separators. Later colons are part of the identifier.
2. Namespace and resource type are case-sensitive.
3. Unescaped bytes are printable ASCII except `%`.
4. `%25` is the only permitted escape of an ASCII byte. Every other `%HH`
   escape MUST represent a byte at least `0x80`.
5. Percent-decoded bytes MUST form valid UTF-8. Thus non-ASCII source
   characters are represented by uppercase `%HH` for each UTF-8 byte.
6. Printable ASCII MUST remain unescaped. Lowercase hex is non-canonical.
7. No Unicode normalization is performed by ATP. Identity equality is exact
   byte equality of the canonical `entity_id`.

Examples:

```text
k8s:pod:prod-us-east-1/pay-7d9b
k8s:pod:prod-us-east-1/caf%C3%A9
```

`pod/pay-7d9b` is a display alias, not a canonical identity.

### 4.2 Uniqueness and recreation

An identity MUST be globally unique within the applicable observation profile.
Tenant, account, cluster, and incarnation boundaries needed for uniqueness MUST
be encoded by the identity profile. A recreated resource that is semantically
new MUST receive a different identifier, normally by incorporating a stable UID
or incarnation value.

### 4.3 Epoch-scoped aliases

Each Stream has a cumulative alias space beginning at zero:

1. `entity_dictionary_delta` lists new IDs in order.
2. The first entry receives the next unused alias, followed contiguously by the
   remaining entries.
3. An ID MUST NOT be introduced more than once in the same Stream.
4. `entity_ref` in the record envelope and any `ENTITY_REF` value MUST be less
   than the cumulative alias count after applying the current batch's delta.

The alias space is epoch-wide, not batch-local. The paper's "batch-local alias"
wording is superseded by this rule.

### 4.4 Replay requirement

Wire version `0x0001` has no authenticated alias-snapshot record or header.
Semantic decoding therefore requires replay from epoch genesis. An
implementation MAY cache a derived alias snapshot, but that cache is not
canonical and MUST be reproducible from verified batches. A range that cannot
resolve its aliases MUST NOT receive `complete`.

---

## 5. Schema Manifests

### 5.1 Authority

ATP-0002 defines the complete manifest object model, closed-map behavior,
validation order, limits, deterministic CBOR, and compatibility metadata.

### 5.2 Digest

```text
H_S = SHA-256(D_MANIFEST || CanonicalCBOR(manifest))
```

`D_MANIFEST` is the exact Appendix A ASCII domain string. The unprefixed schema
hash equation in [ANT] is superseded by this definition.

The `enums` map is present in every v0.1 manifest, including as an empty map.
All manifest maps are closed in v0.1. A registry MUST validate canonical bytes,
reject unknown or duplicate keys, reject trailing data, and hash the exact
validated canonical byte string.

### 5.3 Resolution and authorization

For each schema digest in a batch, the Collector MUST:

1. resolve exact canonical manifest bytes;
2. recompute and compare `H_S`;
3. validate the manifest under ATP-0002; and
4. confirm that the digest was authorized for the Producer at append time.

Registry availability is not trust. Historical verification requires retaining
the manifest and the relevant authorization decision.

---

## 6. Canonical Record Encoding

### 6.1 Layout

```text
record = uvarint(schema_ref)
      || uvarint(entity_ref)
      || uvarint(time_delta)
      || presence_bitmap
      || positional_values
```

The enclosing batch carries `uvarint(record_length)`. That framing length is not
part of the record and is not a Merkle leaf byte.

### 6.2 Integer encodings

Fixed-width integers and IEEE 754 bit patterns are big-endian.

`uvarint` is unsigned LEB128 over `u64`. It MUST use the shortest encoding and
MUST be at most 10 bytes. A decoder MUST reject overflow and any encoding whose
consumed bytes differ from the canonical encoding of the decoded value.

Signed integers use ZigZag followed by `uvarint`:

```text
zigzag(n) = (unsigned(n) << 1) XOR unsigned(n >> 63)
```

Implementations MUST avoid signed-overflow behavior when computing this map.

### 6.3 Value types

| Code | Type | Canonical wire encoding |
|---:|---|---|
| 0 | `BOOL` | one byte `00` or `01` |
| 1 | `U32` | `uvarint`, value at most `2^32-1` |
| 2 | `U64` | `uvarint` |
| 3 | `I32` | ZigZag `uvarint`, range `[-2^31, 2^31-1]` |
| 4 | `I64` | ZigZag `uvarint` |
| 5 | `F32` | four IEEE 754 bytes; every NaN encodes as `7fc00000` |
| 6 | `F64` | eight IEEE 754 bytes; every NaN encodes as `7ff8000000000000` |
| 7 | `ENUM` | `uvarint` ordinal in the referenced enum |
| 8 | `STRING` | `uvarint(len)` plus valid UTF-8 bytes |
| 9 | `BYTES` | `uvarint(len)` plus bytes |
| 10 | `TIMESTAMP_MS` | ZigZag `uvarint`, signed Unix milliseconds |
| 11 | `ENTITY_REF` | `uvarint` cumulative entity alias |
| 12 | `OPAQUE_REF` | `uvarint(len)` plus Section 12.2 bytes |
| 13 | `DURATION_MS` | `uvarint`, unsigned milliseconds |

Non-NaN float bit patterns, including negative zero and infinities, are
preserved. Numeric constraints are applied as specified by ATP-0002. NaN
violates any declared numeric minimum or maximum.

For `STRING` and `BYTES`, absent `max_len` means 1,024 bytes. A declared value
MUST be between 1 and 4,096. The 4,096-byte ceiling is absolute.

### 6.4 Presence bitmap

Let a schema have `m` optional fields ordered by increasing slot.
`presence_bitmap` contains `ceil(m/8)` bytes. Optional field `j` uses bit
`j mod 8`, least-significant bit first, in byte `j div 8`. Unused high bits in
the final byte MUST be zero.

Values are emitted in increasing slot order. Required values are always
present. Optional values are emitted only when their bit is one.

### 6.5 Record validity

A record is valid only if:

- encoded length is 1 through 65,535 bytes;
- all varints and values are canonical;
- `schema_ref` is in the current batch dictionary;
- both envelope and nested entity aliases are in range;
- `base_time + time_delta` does not overflow;
- bitmap padding is zero;
- required values and present optional values exactly exhaust the record;
- enum ordinals and schema constraints are satisfied;
- canonical NaN and UTF-8 rules are satisfied; and
- `intent_ref` and `OPAQUE_REF` satisfy their special rules.

Malformed encodings use `ATP_ERR_MALFORMED_RECORD`; well-formed values that
violate the resolved schema use `ATP_ERR_SCHEMA_VIOLATION`.

---

## 7. Canonical Batch Encoding

### 7.1 Wire layout

The batch byte string is the following fields in order:

| # | Field | Encoding | Constraint |
|---:|---|---|---|
| 1 | `protocol_version` | u16 BE | exactly `0x0001` |
| 2 | `producer_id` | 16 bytes | registry-assigned |
| 3 | `boot_epoch` | u64 BE | Section 8 |
| 4 | `first_sequence` | u64 BE | exclusive end MUST fit `u64` |
| 5 | `record_count` | u32 BE | 1 through 65,535 |
| 6 | `base_time` | u64 BE | Unix milliseconds |
| 7 | `clock_quality` | 1-byte source plus u32 BE skew | Section 7.2 |
| 8 | `schema_dictionary` | `uvarint(n)` plus `n * 32` bytes | `1 <= n <= 65,535`; digests unique |
| 9 | `entity_dictionary_delta` | `uvarint(n)` plus `n` LP strings | `0 <= n <= 65,535`; IDs canonical and unique |
| 10 | `previous_root` | 32 bytes | zero only at epoch genesis |
| 11 | `encoded_records` | `record_count` LP records | each length 1 through 65,535 |
| 12 | `merkle_root` | 32 bytes | Section 7.3 |
| 13 | `signing_key_id` | 8 bytes | historical Producer key lookup |
| 14 | `signature` | 64 bytes | strict Ed25519 |

`LP bytes` means `uvarint(length) || bytes`. The total batch byte string MUST
not exceed 16 MiB. A decoder MUST reject trailing bytes and MUST check counts
before allocating proportional storage.

### 7.2 Clock quality

```text
clock_quality = source:u8 || max_skew_ms:u32be
```

| Source | Name |
|---:|---|
| 0 | `UNSYNCED` |
| 1 | `NTP` |
| 2 | `PTP` |
| 3 | `GPS_HARDWARE` |
| 4 | `TRUSTED_EXTERNAL` |

Sources 5 through 255 are invalid in v0.1. `UNSYNCED` MUST use
`max_skew_ms = 0xffffffff`. Other sources MAY use `0xffffffff` when the bound
is unknown, though a deployment cannot make bounded wall-clock claims from it.

### 7.3 Merkle root

Leaves are unframed record byte strings in sequence order. The tree is RFC 6962
Merkle Tree Hash:

```text
MTH([x]) = SHA-256(0x00 || x)

MTH(records), n > 1:
    k = largest power of two strictly less than n
    SHA-256(0x01 || MTH(records[0:k]) || MTH(records[k:n]))
```

There is no empty-batch root because `record_count` cannot be zero.

### 7.4 Batch root

The hash of each dictionary covers its exact encoded field, including its count
and every length prefix.

```text
batch_root = SHA-256(
    D_BATCH
 || protocol_version
 || producer_id
 || boot_epoch
 || first_sequence
 || record_count
 || base_time
 || clock_quality
 || SHA-256(encoded_schema_dictionary)
 || SHA-256(encoded_entity_dictionary_delta)
 || previous_root
 || merkle_root
)
```

For v0.1 the preimage is 197 bytes: 18 bytes of `D_BATCH` plus 179 fixed bytes.

### 7.5 Signature verification

```text
signature = Ed25519.Sign(private_key, batch_root)
```

The message is exactly the 32-byte root. Verification MUST be strict RFC 8032
verification: reject non-canonical points or scalars, small-order points,
invalid subgroup encodings, and any signature not valid for the resolved key.

### 7.6 Chain and sequence continuity

- Epoch genesis has `previous_root = 32 * 0x00`.
- Every later batch uses the immediately preceding accepted `batch_root`.
- `first_sequence` equals the preceding exclusive sequence end.
- `first_sequence + record_count` MUST fit in `u64`.

### 7.7 Producer and key registry

`producer_id` is an opaque stable 128-bit identifier. Within one Producer's
history, the key registry mapping MUST be one-to-one: each 8-byte
`signing_key_id` identifies exactly one Ed25519 public key, and each public-key
byte string has exactly one key ID. Registering the same key ID with different
key bytes, or the same key bytes under a different key ID, is a configuration
error and MUST fail closed. The same public key MAY be independently bound in a
different Producer's history.

Historical verification policy MUST identify which key was authorized for a
Producer at the batch's epoch and sequence. Prospective rotation or
deauthorization MUST NOT erase the historical binding needed to verify already
accepted data. A compromise policy MAY distrust an affected historical range;
such a range is `gap` unless a higher-precedence defect applies.

`signing_key_id` is not in `batch_root`, but it is in the exact batch bytes.
Substitution either selects a distinct key under which the signature fails or
violates the one-to-one registry binding. Exact-byte retransmission also
prevents changing it.

---

## 8. Producer State Machine

### 8.1 Durable invariants

A Producer MUST:

1. never assign two different records the same
   `(producer_id, boot_epoch, sequence)`;
2. persist a sealed batch before first transmission;
3. retain the exact sealed bytes until acknowledgement;
4. retransmit those exact bytes after timeout; and
5. advance sequence and `previous_root` only according to sealed batch order.

### 8.2 Epoch allocation

Every process start obtains a `boot_epoch` strictly greater than every epoch the
Producer previously created. The allocation MUST use durable atomic state or a
trusted external monotonic allocator. Wall-clock time alone is insufficient
unless the environment guarantees monotonicity across rollback and restore.

### 8.3 Crash recovery order

After restart, a Producer MAY hold unacknowledged, sealed batches from an older
epoch. It MUST retransmit those exact old-epoch bytes in order and obtain their
acknowledgements before transmitting the first batch of the newly allocated
epoch. Once a Collector accepts a higher epoch, it rejects non-idempotent
extensions of a lower epoch.

An exact retransmission of an already committed old-epoch batch remains
idempotent even after a higher epoch has been accepted.

### 8.4 Buffer exhaustion and shutdown

The default v0.1 behavior on a full durable buffer is back-pressure. A Producer
MUST NOT silently discard evidence. A deployment profile MAY permit accounted
drops, but it MUST define a StateCheckpoint schema and time-range semantics
that cause affected aggregate coverage requests to return `gap`.

On graceful shutdown, a Producer SHOULD seal, persist, transmit, and acknowledge
all buffered records before exit.

### 8.5 StateCheckpoint records

`StateCheckpoint` is a schema primitive, not a special wire envelope. ATP-0001
does not define a universal checkpoint schema, does not reserve alias-map
fields, and does not allow a StateCheckpoint to replace epoch-genesis replay.
Profiles may define application-state and drop-accounting schemas.

---

## 9. Collector Validation

### 9.1 First-failure order

A Collector MUST perform these stages in order and stop at the first failure:

| Stage | Operation | Failure |
|---:|---|---|
| 1 | Parse exact framing; enforce structural limits, canonical entity IDs, dictionary uniqueness, clock rules, and no trailing bytes | `ATP_ERR_MALFORMED_BATCH` |
| 2 | Require `protocol_version == 0x0001` | `ATP_ERR_UNSUPPORTED_VERSION` |
| 3 | Resolve `producer_id` | `ATP_ERR_UNKNOWN_PRODUCER` |
| 4 | Resolve Producer-bound `signing_key_id` | `ATP_ERR_UNKNOWN_KEY` |
| 5 | Recompute `batch_root`; perform strict Ed25519 verification | `ATP_ERR_INVALID_SIGNATURE` |
| 6 | Perform exact-byte committed-batch lookup | re-ack, or `ATP_ERR_EPOCH_REUSE` for a rewrite |
| 7 | Resolve, validate, and authorize every schema digest | `ATP_ERR_SCHEMA_UNKNOWN` or `ATP_ERR_SCHEMA_UNAUTHORIZED` |
| 8 | Validate `previous_root` against Stream state | `ATP_ERR_PREVIOUS_ROOT_MISMATCH` |
| 9 | Validate epoch and sequence continuity | `ATP_ERR_SEQUENCE_GAP` or `ATP_ERR_EPOCH_REUSE` |
| 10 | Apply entity-dictionary continuity and cumulative-alias bounds | `ATP_ERR_MALFORMED_BATCH` |
| 11 | Recompute and compare `merkle_root` | `ATP_ERR_MERKLE_MISMATCH` |
| 12 | Validate every record against its resolved schema | `ATP_ERR_MALFORMED_RECORD` or `ATP_ERR_SCHEMA_VIOLATION` |
| 13 | Atomically commit batch and Stream state, then acknowledge | `ATP_ERR_COMMIT_FAILED` |

Authentication precedes every lookup that can mutate chain state.

### 9.2 Exact-byte idempotence

After Stage 5, the Collector looks up the committed
`(producer_id, boot_epoch, first_sequence)`:

- if the incoming complete batch byte string is byte-for-byte identical to the
  committed byte string, it MUST return success without appending again;
- otherwise, any existing entry at that tuple is `ATP_ERR_EPOCH_REUSE`.

Equality of `batch_root` is not sufficient because key ID and signature bytes
are outside the root. Implementations MAY index by a collision-resistant digest
but MUST confirm exact byte equality before returning idempotent success.

### 9.3 Epoch and sequence rules

- A new higher epoch starts with zero `previous_root`; its first sequence may be
  any value whose exclusive end fits `u64`.
- A non-idempotent batch below the Producer's highest accepted epoch is
  `ATP_ERR_EPOCH_REUSE`.
- In an existing epoch, a first sequence above the expected value is
  `ATP_ERR_SEQUENCE_GAP`; a lower value is `ATP_ERR_EPOCH_REUSE`.

### 9.4 Atomic commit

The batch bytes, cumulative entity dictionary, next expected sequence, epoch
head root, and idempotency record form one transaction. Either all become
durable or none do. A commit failure is retryable and MUST NOT advance state.
Acknowledgement transport is out of scope; it SHOULD carry the highest committed
sequence and `batch_root`.

---

## 10. Coverage Verification

### 10.1 Core request

Core v0.1 classifies one resolved inclusive sequence range:

```text
CoverageRequest = {
    producer_id:    16 bytes,
    boot_epoch:     u64,
    first_sequence: u64,
    last_sequence:  u64
}
```

The Verifier also receives:

- an ordered set of retained batch bytes for that Stream;
- historical Producer key bindings and positive append-time key authorization;
- validated manifests and positive append-time schema authorization;
- the applicable independent checkpoint and historical checkpoint keys; and
- authenticated assurance that the selected checkpoint satisfies the
  deployment's freshness policy.

Authorization evidence is scoped to each represented batch position, not taken
from a current allowlist. Missing, uncertain, or retroactively distrusted
evidence is unavailable for Core classification and produces `gap` unless a
higher-precedence defect applies. A prospective rotation or revocation does not
invalidate retained positive decisions for older batches.

Core v0.1 defines no authenticated alias snapshot. To return `complete`, the
Verifier MUST have epoch genesis through the checkpoint, or an authenticated
snapshot defined by a future profile. The reference Verifier requires genesis.

### 10.2 Total status classification

Status precedence is `tampered`, then `truncated`, then `gap`, then `complete`.

**`tampered`** applies when the Verifier proves any of:

- malformed batch bytes, wrong version, or wrong Stream substitution;
- Merkle or strict signature failure under a resolved historical key;
- non-increasing batch order or overlapping retained sequence ranges;
- broken `previous_root` between sequence-adjacent retained batches;
- canonical entity/dictionary violation;
- a schema-resolved record that is malformed or violates its schema;
- invalid checkpoint signature or checkpoint for a different Stream; or
- a checkpoint root differing from the retained batch that ends at the
  checkpoint's stated highest sequence.

**`truncated`** applies only after checkpoint authentication, when the highest
retained sequence is below `checkpoint.highest_sequence`. An empty retained
segment below a valid nonempty checkpoint is truncated.

**`gap`** applies when no higher-precedence status applies and any of:

- the request is invalid (`first_sequence > last_sequence`);
- there is no sufficiently fresh checkpoint;
- the checkpoint is behind `last_sequence`;
- a required historical Producer/checkpoint key, manifest, positive
  authorization decision, alias prefix, or schema registry object is
  unavailable;
- expected sequence numbers are absent;
- the checkpoint sequence is not represented by a retained terminal batch; or
- a profile-defined accounted drop intersects the query.

**`complete`** applies only when:

1. every batch needed from epoch genesis through the checkpoint passes all
   canonical, schema, Merkle, signature, dictionary, and chain checks;
2. the request is contained in a contiguous retained sequence interval;
3. `checkpoint.highest_sequence >= last_sequence`;
4. the checkpoint identifies exactly the retained batch ending at its highest
   sequence; and
5. all trust and decode dependencies are available.

### 10.3 Missing-batch adjacency

Retained batch sequence ranges MUST be strictly ordered and non-overlapping.
Any overlap is `tampered`.

If retained batches on either side of a missing interval are not sequence
adjacent, the later batch legitimately points to an absent predecessor. This is
`gap`, not `tampered`. A `previous_root` mismatch is `tampered` only when the
two retained batches are sequence adjacent.

### 10.4 Meaning of `complete`

`complete` proves integrity and closure only for the resolved Stream sequence
range and the supplied observation profile. It does not prove physical-world
coverage or Producer honesty.

A consumer MAY draw a verified non-occurrence conclusion only after an exact
query planner has enumerated every Stream required by `Omega` and obtained
`complete` for every required range.

### 10.5 Time-range closure

Record timestamps can be out of sequence order. ATP-0001 therefore does not
define a general time-to-sequence shortcut. A wall-clock or event-time negative
claim additionally requires a deployment profile that proves all potentially
overlapping records were included, for example by scanning the closed epoch
range or by using authenticated event-time watermarks. Clock uncertainty MUST
expand the external interval. Without such closure, the aggregate result is
`gap`, even if an individual sequence request is complete.

### 10.6 Receipts

A v0.1 receipt is a Derived-Plane object and SHOULD include the request, status,
checkpoint identity, first defect, all input batch ranges, observation-profile
identifier, Verifier version, and derivation time. ATP-0001 does not define
canonical receipt bytes or a receipt signature. Consumers requiring portable
receipts must wait for or implement ATP-0003.

---

## 11. Independent Chain-Head Checkpoints

### 11.1 Purpose

A checkpoint commits a trusted Collector's accepted Stream head to a channel
outside the ledger-storage failure domain. It detects suffix deletion up to the
latest checkpoint the Verifier can authenticate and discover.

### 11.2 Canonical bytes

A serialized checkpoint is exactly 152 bytes:

| Field | Encoding | Bytes |
|---|---|---:|
| `producer_id` | opaque | 16 |
| `boot_epoch` | u64 BE | 8 |
| `highest_sequence` | u64 BE | 8 |
| `batch_root` | opaque | 32 |
| `checkpoint_time` | u64 BE | 8 |
| `checkpoint_sequence` | u64 BE | 8 |
| `signing_key_id` | opaque | 8 |
| `signature` | strict Ed25519 | 64 |

```text
checkpoint_root = SHA-256(
    D_CHECKPOINT
 || producer_id
 || boot_epoch
 || highest_sequence
 || batch_root
 || checkpoint_time
 || checkpoint_sequence
)

signature = Ed25519.Sign(checkpoint_private_key, checkpoint_root)
```

`batch_root` MUST identify a batch whose final record sequence equals
`highest_sequence`.

### 11.3 Counter and consistency

`checkpoint_sequence` is a strictly increasing, Producer-global counter across
all epochs. It does not reset at an epoch boundary.

For checkpoints in the same epoch, `highest_sequence` MUST be non-decreasing.
A later checkpoint must extend the same verified chain. Reuse of one
`(producer_id, checkpoint_sequence)` with different signed content is
equivocation and MUST prevent `complete`.

The checkpoint key registry follows the collision, historical retention, and
authorization rules of Section 7.7. The checkpoint key MUST be operationally
separate from Producer keys in deployments that claim rollback resistance.

### 11.4 Freshness and discovery

Storage independence alone does not prove that the Verifier received the latest
checkpoint. A deployment claiming `complete` MUST define authenticated
checkpoint discovery and a freshness policy, such as an append-only witness log
with a maximum publication interval. If the Verifier cannot establish that its
checkpoint satisfies that policy, it returns `gap`.

The backend and publication cadence are profile choices. A 152-byte checkpoint
every 1,024 records amortizes to approximately 0.148 bytes per record. The
paper's 144-byte and 0.141-byte figures are corrected by this wire definition.

---

## 12. Opaque Evidence

### 12.1 Boundary

Unbounded or untrusted diagnostics are stored out of band. The canonical record
contains only bounded metadata and a digest. The digest provides integrity, not
availability, confidentiality, or safety of the payload's content.

### 12.2 Canonical `OpaqueRef`

```text
OpaqueRef =
    lp_string(opaque_id)
 || lp_string(media_type)
 || uvarint(byte_length)
 || payload_digest
 || lp_string(storage_uri)
 || uvarint(retention_class)
```

Constraints:

| Component | Rule |
|---|---|
| entire inner `OpaqueRef` | 1 through 4,096 bytes |
| `opaque_id` | 1 through 128 ASCII VCHAR bytes |
| `media_type` | 3 through 127 lowercase ASCII bytes, exactly `token/token` |
| media token chars | ASCII alphanumeric or `! # $ & ^ _ . + -` |
| `byte_length` | canonical `u64` |
| `payload_digest` | exactly 32 bytes, SHA-256 of stored payload bytes |
| `storage_uri` | 1 through 2,048 ASCII VCHAR bytes |
| URI scheme | `[a-z][a-z0-9+.-]*:` |
| `retention_class` | canonical `uvarint`, at most `2^32-1` |

No trailing bytes are allowed. The outer `OPAQUE_REF` value length and every
inner length use canonical `uvarint`.

### 12.3 Dereference

Before delivery, a consumer MUST:

1. apply an allowlist to the URI scheme and destination;
2. enforce deployment payload and redirect limits before allocation;
3. stream at most the allowed bytes while computing SHA-256;
4. require actual byte count to equal `byte_length`;
5. require the digest to equal `payload_digest`; and
6. expose the payload only as explicitly untrusted data.

Length mismatch is `ATP_ERR_OPAQUE_LENGTH_MISMATCH`; digest mismatch is
`ATP_ERR_OPAQUE_DIGEST_MISMATCH`. Implementations MUST defend URI fetching
against SSRF, redirects to disallowed destinations, DNS rebinding, decompression
bombs, and unsafe content parsers. Those controls are local policy, not encoded
by `OpaqueRef`.

### 12.4 Anti-bypass

Diagnostic prose, request/response bodies, stack traces, and equivalent
unbounded content MUST NOT be split across canonical `STRING` or `BYTES` fields
to bypass this boundary. The 4,096-byte scalar ceiling is a hard parser bound,
not permission to place arbitrary text inline.

---

## 13. ATP-TAB Canonical Record View

### 13.1 Scope

ATP-TAB is a deterministic ASCII rendering of verified, schema-resolved record
values. It preserves sequence, absolute timestamp, canonical entity identity,
field presence, every scalar value, float bits, and complete `OpaqueRef` bytes.

ATP-TAB does **not** reconstruct batch boundaries, aliases, `base_time`,
`time_delta`, dictionaries, Merkle roots, signatures, or original batch bytes.
The paper's bijection claim applies to this resolved record view, not to a
Canonical Batch.

### 13.2 Escaping

`E(bytes)` emits:

- ASCII bytes `0x21..0x7e`, except backslash, as themselves;
- backslash as `\\`; and
- every other byte as `\xHH` with uppercase hex.

Text is first encoded as UTF-8, then passed to `E`. This produces no tabs,
newlines, carriage returns, spaces, or non-ASCII output bytes.

### 13.3 Lines

Lines end in LF. Before each maximal run of records using one schema digest,
emit:

```text
@schema<TAB><64-lowerhex-H_S><TAB>E(schema_name)<TAB>E(schema_version)<TAB>E(field0)...
```

Then emit each record:

```text
<sequence-decimal><TAB><absolute-unix-ms-decimal><TAB>E(entity_id)<TAB><value0>...
```

Every schema slot has one value column. An absent optional field is `~`.
Canonical value tokens are:

| Type | Token |
|---|---|
| `BOOL` | `b:0` or `b:1` |
| `U32`, `U64` | `u:<unsigned-decimal>` |
| `I32`, `I64` | `i:<signed-decimal>` |
| `F32` | `f32:<8-lowerhex-IEEE-bits>` |
| `F64` | `f64:<16-lowerhex-IEEE-bits>` |
| `ENUM` | `e:<ordinal>:E(member)` |
| `STRING` | `s:E(utf8-bytes)` |
| `BYTES` | `x:<lowerhex-bytes>` |
| `TIMESTAMP_MS` | `t:<signed-decimal>` |
| `ENTITY_REF` | `r:E(resolved-entity-id)` |
| `OPAQUE_REF` | `o:<lowerhex-complete-inner-OpaqueRef>` |
| `DURATION_MS` | `d:<unsigned-decimal>` |

Decimal forms use no leading zero except zero itself and no leading plus.
Canonical NaNs use the bits from Section 6.3. Display aliases, shortened hashes,
and `opaque:<id>` are allowed only in explicitly non-canonical human views.

---

## 14. Semantic Gateway

This section is informative. A Semantic Gateway may build versioned state
graphs and evidence capsules from verified records. Any such object is Derived
Plane, must identify its exact input ranges and coverage statuses, and must not
serve as an authorization token. ATP-0005 may standardize gateway behavior.

---

## 15. Security Considerations

| Threat | Protection | Residual limitation |
|---|---|---|
| Transport/storage mutation | strict signatures, Merkle root, chain | availability is not guaranteed |
| Batch replay or rewrite | sequence state and exact-byte idempotence | compromised Producer remains trusted |
| Suffix deletion | independently discovered checkpoints | deletion after latest fresh checkpoint is not yet anchored |
| Schema substitution | content-addressed canonical manifests | registry availability still matters |
| Key-ID substitution | Producer-scoped one-to-one registry plus signature | registry compromise breaks binding |
| Prompt injection in diagnostics | opaque boundary and explicit untrusted handling | dereferenced text remains adversarial |
| Resource exhaustion | normative static parser bounds | transport-layer flooding remains out of scope |
| Confidentiality | none | use transport and storage encryption |

Strict Ed25519 verification and historical key authorization are mandatory.
Multi-tenant deployments MUST enforce Producer-to-entity-namespace and
Producer-to-schema authorization. Keys SHOULD be held in hardware-backed or
equivalent protected storage. Producer and checkpoint keys MUST be separated
when claiming protection against Producer-key compromise.

ATP does not provide forward-secure signatures. A stolen current Producer key
can forge new batches subject to Collector state and policy. Revocation policy
must record the affected sequence/time range rather than silently changing
historical verification outcomes.

---

## 16. Privacy

Canonical records are durable signed evidence. Producers MUST redact or
pseudonymize sensitive data before canonical encoding and signing. Opaque
metadata, URIs, identifiers, and digests may themselves reveal sensitive
information.

Wire version `0x0001` does not define verifiable prefix-pruning certificates.
Deleting an epoch prefix removes chain and alias context and prevents
`complete` unless a future authenticated snapshot/pruning profile supplies the
missing proof. Deployments MUST NOT describe ordinary deletion as verifiable
prefix pruning under ATP-0001.

---

## 17. Versioning

### 17.1 Wire version

This release accepts exactly `protocol_version = 0x0001`. The value is opaque
for v0.1 parsing; implementations MUST NOT infer compatibility from high/low
bytes. Any other value is `ATP_ERR_UNSUPPORTED_VERSION`.

Future versions will define their own negotiation and compatibility. Transport
negotiation MUST NOT alter canonical bytes.

### 17.2 Schema versions

Schema identity is `H_S`. SemVer metadata, evolution, and compatibility hints
are governed by ATP-0002 and never replace digest-specific decoding.

---

## 18. Conformance

### 18.1 Classes

- A **Conformant Producer** emits exact v0.1 bytes and obeys Section 8.
- A **Conformant Collector** implements Section 9 first-failure order.
- A **Conformant Verifier** implements Sections 10 and 11 with complete
  historical trust and schema inputs.
- A **Conformant ATP-TAB Renderer** implements Section 13 for all 14 types.

An implementation MUST state which classes it claims.

### 18.2 Required artifacts

The repository's checked-in vectors cover:

- manifest encoding and invalid-manifest inputs;
- canonical records and representative parser rejection categories, including
  canonical varints and static bounds;
- RFC 6962 trees including one, three, and five leaves;
- batch root and strict Ed25519 signatures;
- exact checkpoint bytes and checkpoint signatures;
- continuity, exact retransmission, epoch behavior, and key rotation;
- all four coverage statuses and their precedence;
- canonical identity and OpaqueRef boundaries; and
- the foundational cross-language vector `CV-CORE-001`.

A claimed class MUST pass every applicable checked-in vector without modifying
the expected output. `test-vectors/README.md` is the suite index.

### 18.3 Golden-vector authority

`test-vectors/CV-CORE-001.json` is the machine-readable source for Appendix B.
The Python generator and independent Rust implementation MUST reproduce it
byte-for-byte. A publication process MUST fail if Appendix B and the JSON
values differ.

---

## Appendix A. Constants and Limits

Domain strings are ASCII without a NUL terminator.

| Name | ASCII |
|---|---|
| `D_BATCH` | `ATP/0.1/batch-root` |
| `D_MANIFEST` | `ATP/0.1/schema-manifest` |
| `D_CHECKPOINT` | `ATP/0.1/chain-head-checkpoint` |

| Limit | Value |
|---|---:|
| batch bytes | 16 MiB |
| records per batch | 65,535 |
| record bytes | 65,535 |
| schema dictionary entries | 65,535 |
| entity delta entries | 65,535 |
| canonical entity ID bytes | 1,024 |
| manifest bytes | 1 MiB |
| manifest fields | 1,024 |
| inline `STRING` or `BYTES` | 4,096 |
| complete inner `OpaqueRef` | 4,096 |
| opaque ID | 128 |
| media type | 127 |
| storage URI | 2,048 |

Hash is SHA-256. Signature is strict Ed25519. Manifest canonicalization is
ATP-0002 Deterministic CBOR. Merkle trees use RFC 6962.

---

## Appendix B. Worked Vector CV-CORE-001

This appendix mirrors `test-vectors/CV-CORE-001.json`.

Producer:

```text
producer_id       = 00112233445566778899aabbccddeeff
boot_epoch        = 7
first_sequence    = 1000
base_time         = 1760000000000
clock_quality     = 0100000032
signing_key_id    = a1b2c3d4e5f60708
```

Schema `k8s.pod.transition:1.0.0`:

```text
canonical manifest CBOR:
a601726b38732e706f642e7472616e736974696f6e0265312e302e30030004706f
70656e6b656467652e696f2f6b38730584a5010002696f6c645f73746174650307
04f50669706f645f7068617365a5010102696e65775f7374617465030704f50669
706f645f7068617365a401020269657869745f636f6465030304f4a40103026672
6561736f6e030804f406a169706f645f7068617365856750656e64696e67675275
6e6e696e6769537563636565646564664661696c656467556e6b6e6f776e

H_S:
b8fafb40cf935d94f1ef933ce411f5d7d881a82413fc463a45db30a37a4103f2
```

Entity aliases:

```text
0 = k8s:pod:prod-us-east-1/pay-7d9b
1 = k8s:pod:prod-us-east-1/auth-4a2c

encoded entity_dictionary_delta:
021f6b38733a706f643a70726f642d75732d656173742d312f7061792d37643962
206b38733a706f643a70726f642d75732d656173742d312f617574682d34613263
```

Records:

```text
sequence 1000: 00000a0101039202
sequence 1001: 00010f0101039202

merkle_root:
7f95e8f0b03bde3349c384dbb19a11e0a899caec3231566de12ded4cdbe94f92
```

Dictionary hashes:

```text
SHA-256(schema_dictionary):
6e2c4a4eb02c3cd9d9dc54b2babcf74f0d894736ffdcffc615446cb167d7fb61

SHA-256(entity_dictionary_delta):
57beaa4b2f34201b202df2769d1fc00d51e7d94cf3cf07cf9f300c5174c22f51
```

Batch-root preimage, 197 bytes:

```text
4154502f302e312f62617463682d726f6f74000100112233445566778899aabbcc
ddeeff000000000000000700000000000003e80000000200000199c82cc0000100
0000326e2c4a4eb02c3cd9d9dc54b2babcf74f0d894736ffdcffc615446cb167d7
fb6157beaa4b2f34201b202df2769d1fc00d51e7d94cf3cf07cf9f300c5174c22
f510000000000000000000000000000000000000000000000000000000000000000
7f95e8f0b03bde3349c384dbb19a11e0a899caec3231566de12ded4cdbe94f92
```

Roots and signature:

```text
batch_root:
fdc55b73af58d0d213b82129273b078a988634f27639a7a91f9ff6de97b98805

Ed25519 public key:
121b96cf6280559ff9e409d9ca18866f42c4724c9a7eab847eb1e3f34428c5bb

signature:
ce2d2674df3d5eb9bbe4786d8e26dd5bc54a4f37a7e1b3384aa8eeaf74d295f6
4cb4657367868cbe3505c42042080a14038b87affeb73fe2a35a8fbb99140e05
```

Test-only Ed25519 seed:

```text
9d61b19deffe5a60651c9e0d0e6c1e6bf0a1b2c3d4e5f60718293a4b5c6d7e8f
```

Never use the test seed in production.

---

## Appendix C. Error Code Registry

| Code | Num | Retryable |
|---|---:|:---:|
| `ATP_ERR_MALFORMED_BATCH` | 1 | no |
| `ATP_ERR_UNSUPPORTED_VERSION` | 2 | no |
| `ATP_ERR_UNKNOWN_PRODUCER` | 3 | no |
| `ATP_ERR_UNKNOWN_KEY` | 4 | no |
| `ATP_ERR_INVALID_SIGNATURE` | 5 | no |
| `ATP_ERR_SCHEMA_UNKNOWN` | 6 | no |
| `ATP_ERR_SCHEMA_UNAUTHORIZED` | 7 | no |
| `ATP_ERR_PREVIOUS_ROOT_MISMATCH` | 8 | no |
| `ATP_ERR_SEQUENCE_GAP` | 9 | no |
| `ATP_ERR_EPOCH_REUSE` | 10 | no |
| `ATP_ERR_MERKLE_MISMATCH` | 11 | no |
| `ATP_ERR_MALFORMED_RECORD` | 12 | no |
| `ATP_ERR_SCHEMA_VIOLATION` | 13 | no |
| `ATP_ERR_COMMIT_FAILED` | 14 | yes |
| `ATP_ERR_OPAQUE_DIGEST_MISMATCH` | 15 | no |
| `ATP_ERR_OPAQUE_LENGTH_MISMATCH` | 16 | no |

---

## Appendix D. Resolved Decisions and Deferred Profiles

1. Entity aliases are cumulative within an epoch. No alias-base header is added
   in wire version `0x0001`.
2. ATP-0002 is published and authoritative; schema extraction is complete.
3. Coverage receipts are Derived-Plane artifacts. Canonical signed receipts are
   deferred to ATP-0003.
4. Checkpoint cadence and authenticated latest-checkpoint discovery are
   deployment-profile requirements pending ATP-0003.
5. ATP-0004 defines the OpenTelemetry mapping profile.
6. Compression is transport/storage framing and cannot change canonical bytes.
7. Idempotence means exact complete batch-byte equality and occurs after
   authentication but before current schema and continuity checks.
8. Prefix pruning and authenticated alias snapshots are not defined in v0.1.
