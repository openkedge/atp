# ATP-0004 - OpenTelemetry Mapping Profile

**Agent Telemetry Protocol (ATP) v0.1: Deterministic OpenTelemetry Bridge Profile**

| | |
|---|---|
| **Document** | ATP-0004 |
| **Title** | OpenTelemetry Mapping Profile |
| **Document version** | 0.1.0-rc.1 |
| **Status** | Release Candidate |
| **Authors** | Jun He; Deying Yu |
| **Nature** | Normative implementation profile |
| **Publisher** | OpenKedge.io |
| **License** | Apache-2.0 |
| **Updated** | 2026-08-18 |
| **Depends on** | ATP-0001, ATP-0002 |
| **Input model** | OpenTelemetry data model normalized to the fields in Section 2 |
| **Informative background** | `agent-native-logging.pdf` |

> ATP-0004 defines one deterministic bridge profile. It does not claim to map
> every OpenTelemetry semantic convention. An implementation may support other
> mappings, but those outputs are not ATP-0004 Core Profile outputs unless a
> later profile standardizes them.

---

## Table of Contents

1. [Status and Conformance Scope](#1-status-and-conformance-scope)
2. [Normalized Input Model](#2-normalized-input-model)
3. [Bridge Trust and Configuration](#3-bridge-trust-and-configuration)
4. [Canonical OpenTelemetry Values](#4-canonical-opentelemetry-values)
5. [Resource and Span Identity](#5-resource-and-span-identity)
6. [Span Completion Mapping](#6-span-completion-mapping)
7. [LogRecord Mapping](#7-logrecord-mapping)
8. [Numeric Metric Mapping](#8-numeric-metric-mapping)
9. [Trace Correlation](#9-trace-correlation)
10. [Time, Ordering, and Clock Quality](#10-time-ordering-and-clock-quality)
11. [Opaque Isolation](#11-opaque-isolation)
12. [Unsupported Inputs and Loss Disclosure](#12-unsupported-inputs-and-loss-disclosure)
13. [Conformance](#13-conformance)
- [Appendix A. Standard Schema Layouts](#appendix-a-standard-schema-layouts)
- [Appendix B. Constants](#appendix-b-constants)

---

## 1. Status and Conformance Scope

### 1.1 Core Profile

The ATP-0004 Core Profile standardizes:

- deterministic identity for OTel Resources and Spans;
- completed Span to ATP `Transition`;
- LogRecord to ATP `Observation`;
- Gauge and Sum number data points to ATP `Observation`;
- trace and span correlation;
- deterministic timestamps and severity/status mappings;
- canonical fallback encoding of OTel `AnyValue`; and
- deterministic quarantine of free-form content.

### 1.2 Explicit exclusions

The Core Profile does not map:

- Histogram, ExponentialHistogram, or Summary data points;
- Span events or Span links;
- parent/child relations;
- profiles;
- arbitrary semantic-convention attributes as first-class ATP fields; or
- dynamically invented per-payload schema slots.

A bridge MAY implement those through a separately versioned mapping profile.
It MUST record that profile in the deployment observation profile and MUST NOT
claim ATP-0004 Core equivalence for those additional records.

### 1.3 Determinism boundary

Given the same normalized input item and identical Bridge Configuration, two
conformant bridges MUST derive the same:

- canonical entity identity;
- target schema identity;
- absolute millisecond timestamp;
- field presence and logical values;
- `intent_ref`; and
- canonical opaque payload bytes and `OpaqueRef`.

Batch boundaries, sequence numbers, and `base_time` are Producer choices and are
not part of ATP-0004 mapping equivalence.

---

## 2. Normalized Input Model

An adapter for a concrete OTel/OTLP release MUST normalize into these fields
without changing their meaning.

### 2.1 Resource

```text
Resource {
    attributes: map<string, AnyValue>
}
```

Attribute keys are unique UTF-8 strings. Duplicate keys are invalid input.

### 2.2 Span

```text
Span {
    resource: Resource
    trace_id: 16 bytes, nonzero
    span_id: 8 bytes, nonzero
    parent_span_id: absent or 8 bytes
    name: UTF-8 string
    kind: integer 0..5
    status_code: integer 0..2
    end_time_unix_nano: u64, nonzero
    attributes: map<string, AnyValue>
}
```

Span kind uses OTel values:

```text
0 Unspecified, 1 Internal, 2 Server, 3 Client, 4 Producer, 5 Consumer
```

Status uses:

```text
0 Unset, 1 Ok, 2 Error
```

### 2.3 LogRecord

```text
LogRecord {
    resource: Resource
    time_unix_nano: u64
    observed_time_unix_nano: u64
    severity_number: integer 0..24
    body: absent or AnyValue
    attributes: map<string, AnyValue>
    trace_id: absent or 16 bytes
    span_id: absent or 8 bytes
}
```

All-zero trace/span IDs are treated as absent.

### 2.4 NumberDataPoint

```text
NumberDataPoint {
    resource: Resource
    metric_name: UTF-8 string
    unit: UTF-8 string
    metric_kind: Gauge or Sum
    value: exactly one of int64 or float64
    time_unix_nano: u64, nonzero
    start_time_unix_nano: u64
    aggregation_temporality: 0 Unspecified, 1 Delta, 2 Cumulative
    is_monotonic: bool
    flags: u32
    attributes: map<string, AnyValue>
}
```

For Gauge, temporality MUST be Unspecified and `is_monotonic` MUST be false.

### 2.5 Input validity and resource policy

Every `AnyValue` is either the explicit empty/unset form or selects exactly one
of the remaining variants. Map keys are unique and array order is preserved.
Wrongly typed fields are invalid unless a later mapping section explicitly says
to ignore that candidate and continue precedence selection.

The accepted input profile MUST publish recursion, collection-count, and
payload-size limits. A bridge hitting one of those limits handles the item under
Section 12 before ATP sequencing; it MUST NOT truncate a value and claim Core
Profile equivalence. These deployment limits are part of the Bridge
Configuration assumed by Section 1.3.

---

## 3. Bridge Trust and Configuration

### 3.1 Producer role

The bridge is an ATP Producer and part of the TCB. It MUST satisfy ATP-0001
producer, key, buffer, sequence, chain, and signing requirements.

### 3.2 Required configuration

```text
BridgeConfiguration {
    identity_scope: ASCII lower-case component
    clock_source: ATP clock source
    max_skew_ms: u32
    accepted_input_profile: identifier
}
```

`identity_scope` matches:

```text
^[a-z][a-z0-9._-]*$
```

It is 1 through 128 ASCII bytes and identifies the tenant/account/cluster
boundary, for example
`prod-us-east-1`. A conformant bridge MUST NOT run without an explicit scope.

The accepted input profile identifies the OTel SDK/Collector normalization and
sampling policy. Sampling, filtering, and dropped-input behavior are part of
`Omega`; ATP cannot recover telemetry the bridge never receives.

### 3.3 Mapping immutability

The Core Profile schema digests and this document version identify the mapping.
A deployment using custom source precedence, coercion, dropping, or schema
slots MUST publish a separate immutable mapping document and include its digest
in `Omega`.

---

## 4. Canonical OpenTelemetry Values

### 4.1 Purpose

Resource fallback identity and structured opaque payloads use one canonical
encoding. Ad hoc JSON serialization is prohibited.

### 4.2 `AnyValue` encoding

```text
AnyValue =
    00                                      # empty / unset
  | 01 || lp_utf8(string)
  | 02 || bool_byte                         # 00 false, 01 true
  | 03 || i64be                             # two's-complement
  | 04 || f64be                             # canonical NaN bits
  | 05 || uvarint(len) || bytes
  | 06 || uvarint(count) || AnyValue...     # array, input order
  | 07 || CanonicalAttributeMap             # kvlist
```

F64 NaN is normalized to `7ff8000000000000`; other bits, including negative
zero and infinities, are preserved.

### 4.3 Attribute-map encoding

```text
CanonicalAttributeMap =
    uvarint(count)
 || repeated(
        lp_utf8(key)
     || AnyValue(value)
    )
```

Entries sort by bytewise lexical order of UTF-8 key bytes. Keys are unique and
valid UTF-8. Counts and lengths use ATP canonical `uvarint`.

### 4.4 Resource digest

```text
resource_digest = SHA-256(
    D_OTEL_RESOURCE
 || CanonicalAttributeMap(resource.attributes)
)
```

The full 32-byte digest is rendered as 64 lowercase hex characters. It is never
truncated for identity.

---

## 5. Resource and Span Identity

### 5.1 Namespace

The ATP namespace is:

```text
k8s.<identity_scope>   for Kubernetes UID identities
otel.<identity_scope>  otherwise
```

### 5.2 Kubernetes Pod

If `k8s.pod.uid` is a nonempty scalar string that can be represented as an
ATP-0001 canonical identifier:

```text
k8s.<scope>:pod:<canonical k8s.pod.uid>
```

`k8s.pod.name` and `k8s.namespace.name` are display metadata, not identity.
This prevents name reuse from aliasing recreated Pods.

If the UID is absent, invalid, or would make the complete entity ID exceed the
ATP-0001 1,024-byte limit, continue to the Service rule in Section 5.3. If that
rule also does not apply, use fallback Resource identity.

### 5.3 Service

If `service.name` is a nonempty scalar string, form an identity attribute map
from:

- `service.namespace`, when present as a scalar string;
- `service.name`; and
- `service.instance.id`, when present as a scalar string.

Optional keys with other `AnyValue` variants are treated as absent and reported
through bridge operational diagnostics.

Hash `D_OTEL_RESOURCE || CanonicalAttributeMap(identity attributes)`.

```text
otel.<scope>:service_instance:<64-lowerhex>  # instance id present
otel.<scope>:service:<64-lowerhex>           # otherwise
```

### 5.4 Fallback Resource

```text
otel.<scope>:resource:<64-lowerhex-resource_digest>
```

### 5.5 Span entity

A completed Span is its own operational entity:

```text
otel.<scope>:span:<32-lowerhex-trace-id>/<16-lowerhex-span-id>
```

The bridge MUST NOT apply concurrent Span outcomes as state transitions of the
shared service or Pod entity. Resource association remains available through
the Span's source input and future relation profiles.

---

## 6. Span Completion Mapping

### 6.1 Target

A valid completed Span emits one
`otel.span.transition:1.0.0` `Transition` against the Span entity.

### 6.2 Timestamp

Use `end_time_unix_nano`, truncated toward zero:

```text
timestamp_ms = end_time_unix_nano / 1_000_000
```

Zero end time is invalid input and emits no Core Profile record.

### 6.3 State and kind

```text
old_state = Running
new_state = Unset | Ok | Error, from status_code
span_kind = Unspecified | Internal | Server | Client | Producer | Consumer
```

Unknown status or kind values are invalid; they MUST NOT be coerced.

### 6.4 Structured operation

The optional `operation` field uses the first valid scalar string:

1. `http.route`;
2. `rpc.method`;
3. `db.operation.name`.

A valid value is UTF-8, at most 256 bytes, and contains no C0 controls or DEL.
If no candidate is valid, omit `operation`.

The raw Span `name` is always untrusted diagnostic text. If nonempty, encode it
as `span_name_ref` using Section 11. It is never copied into `operation`.

### 6.5 Status code precedence

The optional `status_code` uses:

1. integer `http.response.status_code`, when in `100..599`; otherwise
2. integer `rpc.grpc.status_code`, when in `0..16`.

Wrongly typed or out-of-range candidates are ignored and reported through the
bridge's own operational diagnostics. They are not coerced from strings.

### 6.6 Correlation fields

- `intent_ref` is Section 9's 32-byte hash.
- `trace_ref` is the exact 16-byte trace ID.
- `span_ref` is the exact 8-byte span ID.

All are bytes, not hexadecimal text.

### 6.7 Remaining attributes

Start with a copy of the Span attribute map. Remove only the exact attribute
that successfully supplied `operation` and the exact attribute that
successfully supplied `status_code`. An inspected but invalid candidate is not
consumed. If attributes remain, encode their canonical map and emit
`attributes_ref` with media type
`application/vnd.atp.otel-attributes`.

This rule preserves non-promoted attributes without placing diagnostic or user
content in canonical scalar fields.

---

## 7. LogRecord Mapping

### 7.1 Target

Each valid LogRecord emits one
`otel.log.observation:1.0.0` `Observation` against its Resource entity.

### 7.2 Timestamp

Use the first nonzero value:

1. `time_unix_nano`;
2. `observed_time_unix_nano`.

Truncate to milliseconds. If both are zero, the input is invalid and no Core
Profile record is emitted.

### 7.3 Severity

| OTel number | ATP enum |
|---:|---|
| 0 | `UNSPECIFIED` |
| 1..4 | `TRACE` |
| 5..8 | `DEBUG` |
| 9..12 | `INFO` |
| 13..16 | `WARN` |
| 17..20 | `ERROR` |
| 21..24 | `FATAL` |

Values outside `0..24` are invalid and MUST NOT map to `FATAL`.

### 7.4 Structured event name

The optional `event_name` comes from string attribute `event.name` when it is
UTF-8, at most 256 bytes, and contains no C0 controls or DEL. Otherwise omit it.

### 7.5 Correlation

When valid and nonzero:

- `trace_ref` contains the exact 16-byte trace ID;
- `span_ref` contains the exact 8-byte span ID.

Observation does not use the reserved `intent_ref`.

### 7.6 Body and remaining attributes

If `body` is present, emit `body_ref`:

- string body payload is its UTF-8 bytes, media type `text/plain`;
- bytes body payload is the bytes, media type `application/octet-stream`;
- every other `AnyValue` payload is Section 4.2 bytes, media type
  `application/vnd.atp.otel-anyvalue`.

Remove consumed attribute `event.name`. If attributes remain, encode their
canonical map and emit `attributes_ref` with media type
`application/vnd.atp.otel-attributes`.

`event.name` is consumed only when it successfully supplied `event_name`.
An invalid or wrongly typed value remains in the quarantined attribute map.

---

## 8. Numeric Metric Mapping

### 8.1 Target

Each valid Gauge or Sum `NumberDataPoint` emits one
`otel.metric.number.observation:1.0.0` Observation against its Resource entity.

Histogram, ExponentialHistogram, and Summary inputs are unsupported by the Core
Profile and MUST follow Section 12.

### 8.2 Fields

- `metric_name`: exact input string, UTF-8, 1..256 bytes, no C0/DEL.
- `unit`: exact input string, UTF-8, 0..64 bytes, no C0/DEL. Empty is allowed.
- `metric_kind`: `Gauge` or `Sum`.
- exactly one of `value_int` or `value_float`.
- `start_time_ms`: present when `start_time_unix_nano != 0`.
- `temporality`: `Unspecified`, `Delta`, or `Cumulative`.
- `is_monotonic`: exact input boolean.
- `flags`: exact input `u32`.
- `attributes_ref`: canonical remaining attributes when nonempty.

The record timestamp is `time_unix_nano / 1_000_000`.

`start_time_ms` uses signed `TIMESTAMP_MS`; an input whose truncated value
exceeds `2^63-1` is invalid. A nonzero start time later than the point timestamp
is also invalid.

### 8.3 Descriptor semantics

The runtime `unit` field is retained because one generic standard schema covers
many OTel descriptors. Consumers MUST treat `(metric_name, unit, metric_kind)`
as the descriptor key. A publisher needing static unit metadata SHOULD use a
separate domain schema rather than this generic bridge schema.

---

## 9. Trace Correlation

For valid Span trace IDs:

```text
intent_ref = SHA-256(D_OTEL_INTENT || trace_id)
```

`D_OTEL_INTENT` is ASCII without NUL. This groups one trace under a stable
32-byte intent while `trace_ref` retains exact correlation where the target
schema permits it.

Log Observations carry `trace_ref`, not `intent_ref`, because ATP-0001 reserves
`intent_ref` for Transition and Relation.

---

## 10. Time, Ordering, and Clock Quality

### 10.1 Precision

Nanoseconds are truncated to milliseconds. Bridges MUST NOT round. A custom
profile may preserve the `0..999999` nanosecond remainder in a declared field.

### 10.2 Record order

For a stable ordered input stream, a bridge preserves input item order. The Core
Profile emits one record per supported item, so no within-item ordering choice
exists.

### 10.3 Batch grouping

ATP clock quality describes the source clock of every record in a batch. A
bridge MUST NOT combine records from different clock-provenance domains into
one batch unless the configured source and skew bound honestly cover all of
them.

OTel itself does not provide ATP clock quality. If deployment configuration
cannot establish a source and bound, use:

```text
source = UNSYNCED
max_skew_ms = 0xffffffff
```

---

## 11. Opaque Isolation

### 11.1 Deterministic opaque reference

For payload bytes `p`:

```text
d = SHA-256(p)
opaque_id      = "sha256-" || lowerhex(d)
storage_uri    = "atp-opaque:sha256:" || lowerhex(d)
byte_length    = len(p)
payload_digest = d
retention_class = 0
```

The bridge stores `p` in a content-addressed opaque backend configured to
resolve `atp-opaque:` URIs. The resulting `OpaqueRef` is deterministic across
implementations.

### 11.2 Mandatory quarantine

The Core Profile always quarantines:

- raw Span names;
- LogRecord bodies;
- unconsumed attribute maps;
- exception messages and stack traces within those maps;
- HTTP/RPC bodies, raw query strings, and equivalent user content; and
- structured values not represented by a standard scalar slot.

No size threshold turns free-form content into a trusted canonical string.
Bounded semantic values such as `http.route` and `event.name` are inline only
under their explicit Section 6/7 validation.

### 11.3 Payload composition

Distinct logical values use distinct opaque objects. A bridge MUST NOT define
ambiguous concatenation such as `body || stacktrace`. A custom profile combining
parts must use an explicit length-prefixed container.

---

## 12. Unsupported Inputs and Loss Disclosure

A bridge receiving an unsupported or invalid item MUST do exactly one of:

1. reject it before ATP sequencing and increment a profile-defined,
   time-bounded loss counter;
2. route it through a separately identified mapping profile; or
3. stop/back-pressure ingestion.

It MUST NOT silently coerce, silently invent a schema, or silently report Core
Profile completeness. Any rejected input interval prevents an aggregate
verified-negative result for the affected observation profile.

OTel sampling, Collector filters, dropped attribute/event/link counters, and
unsupported signal kinds MUST be declared in `Omega`.

---

## 13. Conformance

A conformant ATP-0004 Core bridge:

1. is a conformant ATP-0001 Producer;
2. uses the exact standard schema layouts in Appendix A;
3. derives identities, timestamps, fields, correlation, severity/status, and
   opaque references exactly as specified;
4. does not place Section 11 content inline;
5. handles unsupported input only as Section 12 permits; and
6. passes `test-vectors/otel-mapping-vectors.json`.

The vectors compare normalized OTel inputs to logical ATP records and canonical
opaque bytes. ATP-0001 vectors separately validate batching and signatures.

---

## Appendix A. Standard Schema Layouts

All manifests use publisher `openkedge.io/otel`, version `1.0.0`, and an
unconditionally present `enums` map. The vector suite pins their canonical CBOR
and digests.

### A.1 `otel.span.transition`

Primitive: Transition.

| Slot | Name | Type | Required | Constraint / enum |
|---:|---|---|:---:|---|
| 0 | `old_state` | ENUM | yes | `span_state` |
| 1 | `new_state` | ENUM | yes | `span_state` |
| 2 | `operation` | STRING | no | `max_len=256` |
| 3 | `span_name_ref` | OPAQUE_REF | no | |
| 4 | `status_code` | I32 | no | |
| 5 | `intent_ref` | BYTES | no | `max_len=32`; profile requires exactly 32 |
| 6 | `trace_ref` | BYTES | yes | `max_len=16`; profile requires exactly 16 |
| 7 | `span_ref` | BYTES | yes | `max_len=8`; profile requires exactly 8 |
| 8 | `span_kind` | ENUM | yes | `span_kind` |
| 9 | `attributes_ref` | OPAQUE_REF | no | |

```text
span_state = [Running, Unset, Ok, Error]
span_kind  = [Unspecified, Internal, Server, Client, Producer, Consumer]
```

### A.2 `otel.log.observation`

Primitive: Observation.

| Slot | Name | Type | Required | Constraint / enum |
|---:|---|---|:---:|---|
| 0 | `severity` | ENUM | yes | `severity` |
| 1 | `event_name` | STRING | no | `max_len=256` |
| 2 | `trace_ref` | BYTES | no | `max_len=16`; profile requires exactly 16 |
| 3 | `span_ref` | BYTES | no | `max_len=8`; profile requires exactly 8 |
| 4 | `body_ref` | OPAQUE_REF | no | |
| 5 | `attributes_ref` | OPAQUE_REF | no | |

```text
severity = [UNSPECIFIED, TRACE, DEBUG, INFO, WARN, ERROR, FATAL]
```

### A.3 `otel.metric.number.observation`

Primitive: Observation.

| Slot | Name | Type | Required | Constraint / enum |
|---:|---|---|:---:|---|
| 0 | `metric_name` | STRING | yes | `max_len=256` |
| 1 | `unit` | STRING | yes | `max_len=64` |
| 2 | `metric_kind` | ENUM | yes | `metric_kind` |
| 3 | `value_int` | I64 | no | exactly one value slot |
| 4 | `value_float` | F64 | no | exactly one value slot |
| 5 | `start_time_ms` | TIMESTAMP_MS | no | |
| 6 | `temporality` | ENUM | yes | `temporality` |
| 7 | `is_monotonic` | BOOL | yes | |
| 8 | `flags` | U32 | yes | |
| 9 | `attributes_ref` | OPAQUE_REF | no | |

```text
metric_kind = [Gauge, Sum]
temporality = [Unspecified, Delta, Cumulative]
```

Profile validation requires exactly one of slots 3 and 4.

---

## Appendix B. Constants

ASCII without NUL:

```text
D_OTEL_INTENT   = "ATP/0.1/otel-intent"
D_OTEL_RESOURCE = "ATP/0.1/otel-resource"
```
