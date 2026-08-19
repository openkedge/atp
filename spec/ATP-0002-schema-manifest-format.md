# ATP-0002 - Schema Manifest Format

**Agent Telemetry Protocol (ATP) v0.1: Canonical Schema Manifests and Identity**

| | |
|---|---|
| **Document** | ATP-0002 |
| **Title** | Schema Manifest Format |
| **Document version** | 0.1.0-rc.1 |
| **Status** | Release Candidate |
| **Authors** | Jun He; Deying Yu |
| **Publisher** | OpenKedge.io |
| **Category** | Standards Track |
| **License** | Apache-2.0 |
| **Updated** | 2026-08-18 |
| **Depends on** | ATP-0001 |
| **Normative dependencies** | RFC 8949; Semantic Versioning 2.0.0 |
| **Informative background** | `agent-native-logging.pdf` |

> ATP-0002 is authoritative for manifest bytes and validity. It preserves the
> ATP-0001 continuity digest
> `b8fafb40cf935d94f1ef933ce411f5d7d881a82413fc463a45db30a37a4103f2`.
> The paper is design background, not a manifest interoperability contract.

---

## Table of Contents

1. [Status and Scope](#1-status-and-scope)
2. [Manifest Object Model](#2-manifest-object-model)
3. [Names, Text, and Limits](#3-names-text-and-limits)
4. [Value Types and Constraints](#4-value-types-and-constraints)
5. [Manifest Validity](#5-manifest-validity)
6. [Canonical CBOR and Digest](#6-canonical-cbor-and-digest)
7. [Versioning and Compatibility](#7-versioning-and-compatibility)
8. [Publisher Authorization](#8-publisher-authorization)
9. [Registry Contract](#9-registry-contract)
10. [Conformance](#10-conformance)
- [Appendix A. Continuity Manifest](#appendix-a-continuity-manifest)
- [Appendix B. Rejection Reasons](#appendix-b-rejection-reasons)

---

## 1. Status and Scope

### 1.1 Defined here

ATP-0002 defines:

- every v0.1 manifest, field, constraint, enum, and compatibility key;
- exact requiredness, text grammar, and static limits;
- an ordered validity algorithm;
- deterministic CBOR bytes and `H_S`;
- schema-version and compatibility metadata; and
- registry and authorization requirements.

It adds no ATP batch or record field.

### 1.2 Closed format

Every CBOR map defined by ATP-0002 is **closed** in v0.1. Unknown keys are
invalid. Ignoring or preserving an unknown key is not conformant.

Future ATP revisions that add manifest syntax must assign new version semantics
and domain separation. V0.1 decoders continue to reject unknown keys rather than
silently deriving a digest for semantics they do not understand.

### 1.3 Primitive semantics

The manifest declares a primitive code, types, units, and values. ATP-0002 does
not assign universal semantic roles such as transition "from" and "to" or
relation endpoints. Mapping and domain profiles define those roles using exact
field names and types.

---

## 2. Manifest Object Model

All keys below are unsigned CBOR integers.

### 2.1 Manifest map

| Key | Name | CBOR type | Required | Meaning |
|---:|---|---|:---:|---|
| 1 | `schema_name` | text | yes | Canonical dotted name |
| 2 | `schema_version` | text | yes | SemVer 2.0.0 |
| 3 | `primitive` | uint | yes | ATP-0001 primitive `0..3` |
| 4 | `publisher` | text | yes | Authorization identity |
| 5 | `fields` | array of field maps | yes | Slot order |
| 6 | `enums` | map text to array of text | yes | Empty map when unused |
| 7 | `compatibility` | map | no | Section 7.3 |

Key 6 is unconditionally required. Omission and an empty map are different CBOR
objects; omission is invalid in v0.1.

### 2.2 Field map

| Key | Name | CBOR type | Required | Meaning |
|---:|---|---|:---:|---|
| 1 | `slot` | uint | yes | Equal to array index |
| 2 | `name` | text | yes | Unique field name |
| 3 | `type` | uint | yes | Type code `0..13` |
| 4 | `required` | bool | yes | Required or bitmap-gated |
| 5 | `unit` | text | no | Physical unit |
| 6 | `enum_ref` | text | only for `ENUM` | Key in `enums` |
| 7 | `constraints` | map | no | Section 4.2 |

`enum_ref` MUST appear exactly when `type == ENUM`.

### 2.3 Enum map

Each `enums` key names one nonempty ordered array of unique member strings.
The array index is the wire ordinal. Array order is semantic.

Unused enum dictionaries MAY remain in a manifest, but they affect `H_S` and
SHOULD be rejected by publisher lint unless intentionally reserved.

### 2.4 Compatibility map

When present, key 7 is a map containing exactly:

| Key | Name | CBOR type |
|---:|---|---|
| 1 | `min_compatible_version` | text |
| 2 | `mode` | uint `0..3` |

No key is optional within this sub-map.

---

## 3. Names, Text, and Limits

Lengths are UTF-8 byte lengths. Every text form in this section is ASCII, so
byte and character counts coincide.

### 3.1 Grammars

```text
schema-name = lower *( lower / digit )
              *( separator 1*( lower / digit ) )
separator   = "." / "_" / "-"

field-name  = lower *( lower / digit / "_" )
enum-name   = field-name

vchar-text  = 1*%x21-7E
```

These correspond to:

```text
schema-name: ^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$
field-name:  ^[a-z][a-z0-9_]*$
```

Comparison is exact byte equality. Unicode normalization and locale-sensitive
case conversion are never applied.

### 3.2 Limits

| Item | Limit |
|---|---:|
| canonical manifest bytes | 1,048,576 |
| `schema_name` | 1..128 |
| `schema_version` | 1..64 |
| `publisher` | 1..128 ASCII VCHAR |
| fields | 1..1,024 |
| field or enum name | 1..64 |
| `unit` | 1..64 ASCII VCHAR |
| enum dictionaries | 0..1,024 |
| members per enum | 1..65,535 |
| enum member | 1..128 ASCII VCHAR |

The `publisher`, `unit`, and enum member forms contain no spaces or control
characters.

---

## 4. Value Types and Constraints

### 4.1 Type registry

| Code | Type | Allowed constraints |
|---:|---|---|
| 0 | `BOOL` | none |
| 1 | `U32` | `min`, `max` |
| 2 | `U64` | `min`, `max` |
| 3 | `I32` | `min`, `max` |
| 4 | `I64` | `min`, `max` |
| 5 | `F32` | `min`, `max` |
| 6 | `F64` | `min`, `max` |
| 7 | `ENUM` | none; requires `enum_ref` |
| 8 | `STRING` | `max_len` |
| 9 | `BYTES` | `max_len` |
| 10 | `TIMESTAMP_MS` | `min`, `max` |
| 11 | `ENTITY_REF` | none |
| 12 | `OPAQUE_REF` | none |
| 13 | `DURATION_MS` | `min`, `max` |

Unknown codes are invalid.

### 4.2 Constraint map

| Key | Name | CBOR type | Domain |
|---:|---|---|---|
| 1 | `max_len` | int | `1..4096` |
| 2 | `min` | int | signed 64-bit |
| 3 | `max` | int | signed 64-bit |

The map is closed. Duplicate or unknown keys are invalid. Constraint values
MUST use a CBOR integer in `[-2^63, 2^63-1]`.

Rules:

1. `max_len` applies only to `STRING` and `BYTES`.
2. `min` and `max` apply only to numeric types.
3. If both are present, `min <= max`.
4. Bounds for `U32`, `U64`, and `DURATION_MS` cannot be negative.
5. `U32` bounds cannot exceed `2^32-1`.
6. `I32` bounds must fit signed 32-bit.
7. Other numeric bounds already fit their manifest domain because every bound
   is signed 64-bit.
8. `F32` and `F64` bounds are integral mathematical bounds. Infinity compares
   normally; NaN violates any declared bound.

The signed-64 manifest domain means an upper constraint above `2^63-1` cannot
be expressed for `U64` or `DURATION_MS`. Omitting `max` leaves their full wire
range available.

An absent `max_len` means 1,024 bytes at record validation. The default is not
materialized into canonical CBOR.

### 4.3 Reserved `intent_ref`

A field named `intent_ref` is valid only when:

- manifest primitive is `Transition` or `Relation`; and
- field type is `BYTES`.

Record validation additionally requires exactly 32 bytes when present.

---

## 5. Manifest Validity

### 5.1 Ordered algorithm

A conformant validator evaluates checks in this order and returns the first
failure:

1. top-level item is one definite-length map;
2. no trailing bytes, duplicate keys, non-preferred encodings, or unknown
   manifest keys;
3. keys 1 through 6 are present with their declared CBOR types;
4. schema name, version, primitive, publisher, fields collection, and enums
   collection satisfy Sections 2 and 3;
5. for each field in array order:
   - item is a closed map with keys 1 through 4;
   - slot equals array index;
   - name is valid and not previously used;
   - type and `required` are valid;
   - unit and enum-reference rules hold;
   - `intent_ref` rules hold; and
   - constraints are a closed, applicable, in-domain map;
6. enum names, member arrays, member text, and uniqueness are valid;
7. optional compatibility map has exactly keys 1 and 2 and satisfies Section
   7.3; and
8. canonical encoded size is at most 1 MiB.

For structured APIs that have already parsed CBOR, the same semantic ordering
applies. A malformed raw CBOR representation fails before semantic checks.

### 5.2 Dense slots

For field array index `i`, `slot` MUST equal `i`. This single rule ensures slots
are ordered, dense, and unique.

### 5.3 Primitive validation boundary

Core validators enforce only primitive range and the reserved `intent_ref`
rule. A domain profile may impose additional field-role rules. Those rules are
profile conformance, not ATP-0002 manifest validity unless a future manifest
version adds role metadata.

### 5.4 Stable reasons

The negative vectors supply canonical CBOR input and the required first
rejection reason. Validators exposed as interoperability tools SHOULD return
those stable strings. A production registry MAY map them to local diagnostics,
but MUST make the same accept/reject decision.

---

## 6. Canonical CBOR and Digest

### 6.1 Accepted CBOR subset

Canonical manifests use RFC 8949 Core Deterministic Encoding with:

- shortest integer and length heads;
- definite lengths only;
- no duplicate map keys;
- no tags, floats, byte strings, null, or undefined values;
- text as valid UTF-8;
- booleans exactly `f4` and `f5`; and
- array order preserved.

The permitted types are those explicitly listed in Section 2. Numeric
constraints are CBOR major type 0 or 1.

### 6.2 Map ordering

Map entries are ordered by bytewise lexical comparison of the **complete
deterministically encoded key bytes**.

For keys 1 through 7 this is ascending numeric order. For enum text keys, the
CBOR text-length head participates. It is not equivalent to sorting only raw
UTF-8. For example, encoded key `"z"` (`61 7a`) sorts before `"aa"`
(`62 61 61`). Vector `SV-006` pins this edge case.

### 6.3 Canonical ingest

A registry accepting raw manifest bytes MUST:

1. reject input above 1 MiB;
2. parse exactly one item under the accepted subset;
3. apply Section 5;
4. deterministically re-encode the parsed object;
5. require the re-encoded bytes to equal the original bytes exactly; and
6. hash that exact validated byte string.

A noncanonical but logically equivalent CBOR item is invalid. A registry MUST
NOT normalize and silently register it under a digest.

### 6.4 Digest

```text
H_S = SHA-256(
    "ATP/0.1/schema-manifest"
 || canonical_manifest_bytes
)
```

The prefix is ASCII with no NUL terminator. `H_S` is the only protocol schema
identity.

---

## 7. Versioning and Compatibility

### 7.1 SemVer syntax

`schema_version` and `min_compatible_version` use Semantic Versioning 2.0.0
syntax, including optional prerelease and build metadata, with these parser
requirements:

- ASCII only, at most 64 bytes;
- core numeric identifiers have no leading zero unless exactly `0`;
- identifiers are nonempty ASCII alphanumeric/hyphen strings;
- numeric prerelease identifiers have no leading zero unless exactly `0`; and
- build metadata does not affect precedence.

### 7.2 Identity and registry uniqueness

Version text is metadata; `H_S` remains identity. Authorization policy MUST NOT
decode by version alone.

A publishing registry SHOULD enforce one active digest for each
`(publisher, schema_name, schema_version)` tuple. A conflicting digest at the
same tuple is a publication conflict, even though each manifest may be
individually valid.

### 7.3 Compatibility metadata

Modes:

| Value | Name | Publisher assertion |
|---:|---|---|
| 0 | `NONE` | No cross-version compatibility; minimum MUST equal this version |
| 1 | `BACKWARD` | A consumer for this version can consume lineage versions from minimum through this version |
| 2 | `FORWARD` | Consumers for lineage versions from minimum through this version can consume this version |
| 3 | `FULL` | Both BACKWARD and FORWARD assertions |

`min_compatible_version` MUST have SemVer precedence less than or equal to this
manifest's version. For `NONE`, precedence and text after removing build
metadata MUST identify this version.

Compatibility is a publisher assertion and consumer hint. It never permits a
consumer to decode bytes without the exact manifest and never changes
authorization. Registries SHOULD mechanically compare lineage manifests before
accepting an assertion.

### 7.4 Evolution guidance

Typically backward-compatible:

- append optional fields at higher slots;
- append enum members at higher ordinals;
- widen `max_len`; or
- loosen numeric bounds.

Typically incompatible:

- add a required field;
- rename, remove, or reorder a field;
- change type, unit, requiredness, primitive, publisher, or schema name;
- remove, reorder, or rename an enum member; or
- tighten a bound.

Every change still creates a different `H_S`.

---

## 8. Publisher Authorization

Authorization policy is part of the TCB and MUST retain historical decisions.
It defines:

- publisher identities allowed to register schemas;
- Producer-to-digest bindings;
- Producer-to-entity-namespace scope; and
- registration, deprecation, prospective revocation, and compromise handling.

Multi-tenant deployments MUST enforce namespace scope. Prospective schema
revocation rejects new batches but does not rewrite already accepted history.
If compromise policy distrusts historical data, affected coverage cannot be
`complete` and is `gap` absent a higher-precedence ATP-0001 defect.

---

## 9. Registry Contract

A registry is an untrusted content store plus trusted authorization policy.

On registration or fetch:

1. validate exact canonical bytes;
2. recompute `H_S`;
3. require the digest to equal the requested address;
4. apply publisher and tuple-conflict policy; and
5. retain immutable bytes by digest.

Canonical manifests are cacheable indefinitely. Registry unavailability blocks
new decoding and causes read-time `gap` when required bytes are unavailable; it
does not mutate previously cached manifests.

---

## 10. Conformance

### 10.1 Classes

- **Manifest encoder**: emits exact canonical CBOR and digest.
- **Manifest validator**: applies Section 5 and rejects each negative input for
  its listed first reason.
- **Registry**: additionally applies Section 9 and authorization policy.

### 10.2 Vectors

`test-vectors/schema-vectors.json` contains:

- six positive vectors, including all 14 type codes, reversed insertion order,
  and CBOR text-key ordering; and
- executable negative canonical-CBOR inputs covering requiredness, closed maps,
  slots, names, types, constraints, SemVer, compatibility, `intent_ref`, and
  enum uniqueness.

The generator is `test-vectors/generate_schema_vectors.py`.

### 10.3 Continuity requirement

The Appendix A manifest MUST hash to:

```text
b8fafb40cf935d94f1ef933ce411f5d7d881a82413fc463a45db30a37a4103f2
```

A different value is nonconformant to both ATP-0001 and ATP-0002.

---

## Appendix A. Continuity Manifest

Logical manifest:

```text
schema_name    = k8s.pod.transition
schema_version = 1.0.0
primitive      = Transition (0)
publisher      = openkedge.io/k8s

fields:
  0 old_state ENUM required enum_ref=pod_phase
  1 new_state ENUM required enum_ref=pod_phase
  2 exit_code I32 optional
  3 reason STRING optional

enums:
  pod_phase = [Pending, Running, Succeeded, Failed, Unknown]
```

Canonical CBOR:

```text
a601726b38732e706f642e7472616e736974696f6e0265312e302e30030004706f
70656e6b656467652e696f2f6b38730584a5010002696f6c645f73746174650307
04f50669706f645f7068617365a5010102696e65775f7374617465030704f50669
706f645f7068617365a401020269657869745f636f6465030304f4a40103026672
6561736f6e030804f406a169706f645f7068617365856750656e64696e67675275
6e6e696e6769537563636565646564664661696c656467556e6b6e6f776e
```

---

## Appendix B. Rejection Reasons

The vector suite is authoritative for exact first-failure examples. Registered
reason families are:

```text
MANIFEST_NOT_MAP
UNKNOWN_MANIFEST_KEY:<key>
MISSING_KEY:<key>
BAD_SCHEMA_NAME
BAD_SCHEMA_VERSION
BAD_PRIMITIVE
BAD_PUBLISHER
EMPTY_FIELDS
ENUMS_NOT_MAP
TOO_MANY_ENUMS
FIELD_NOT_MAP:<index>
UNKNOWN_FIELD_KEY:<key>
FIELD_MISSING_KEY:<key>
SLOT_NOT_DENSE:expected=<index>:got=<value>
BAD_FIELD_NAME:<index>
DUP_FIELD_NAME:<name>
UNKNOWN_TYPE:<value>
BAD_REQUIRED
BAD_UNIT:<name>
ENUM_MISSING_ENUM_REF
ENUM_REF_UNRESOLVED:<name>
ENUM_REF_ON_NONENUM:<field>
BAD_INTENT_REF
CONSTRAINTS_NOT_MAP:<field>
UNKNOWN_CONSTRAINT:<key>
BAD_CONSTRAINT_VALUE:<field>:<key>
MAX_LEN_ON_NONLEN_TYPE:<type>
RANGE_ON_NONNUMERIC:<type>
BAD_MAX_LEN:<field>
MIN_GT_MAX:<field>
UNSIGNED_RANGE_OUT_OF_DOMAIN:<field>
I32_RANGE_OUT_OF_DOMAIN:<field>
RANGE_OUT_OF_DOMAIN:<field>
BAD_ENUM_NAME
DUP_ENUM:<name>
EMPTY_ENUM:<name>
BAD_ENUM_MEMBER:<name>
DUP_ENUM_MEMBER:<name>
BAD_COMPATIBILITY_MAP
BAD_COMPATIBILITY_VERSION
BAD_COMPATIBILITY_MODE
BAD_COMPATIBILITY_RANGE
MANIFEST_TOO_LARGE
```
