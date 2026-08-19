#!/usr/bin/env python3
"""
ATP-0001 conformance vector generator (stateful categories).

Drives real batches through the reference Collector (§9) and read-time Verifier
(§10/§11) in reference_collector.py, asserts the expected verdict for each
scenario, and emits conformance-vectors.json. Covers the categories ATP-0001
§18.2 lists but CV-CORE-001 / SV-* did not: continuity, corruption, records,
identity/version/framing, coverage, and opaque.

Each collector scenario carries an explicit `setup` descriptor so ANY
implementation can rebuild the collector state and replay the batches.
"""

import argparse
import json
import os

import generate_vectors as g
import reference_collector as rc

PARSER = argparse.ArgumentParser()
PARSER.add_argument("--check", action="store_true")
ARGS = PARSER.parse_args()

# ---- fixed producer + schema setup ----------------------------------------
SK = bytes.fromhex("9d61b19deffe5a60651c9e0d0e6c1e6b" "f0a1b2c3d4e5f60718293a4b5c6d7e8f")
PK = g.ed25519_publickey(SK)
PID = bytes.fromhex("00112233445566778899aabbccddeeff")
SKID = bytes.fromhex("a1b2c3d4e5f60708")
SK2 = bytes.fromhex("4f" * 32)
PK2 = g.ed25519_publickey(SK2)
SKID2 = bytes.fromhex("1020304050607080")
BASE_TIME = 1_760_000_000_000

CK_SK = bytes.fromhex("aa" * 32)
CK_PK = g.ed25519_publickey(CK_SK)
CK_KID = bytes.fromhex("c0ffee00c0ffee00")

POD_STATE = ["Pending", "Running", "Succeeded", "Failed", "Unknown"]
SCHEMA = g.Schema(
    name="k8s.pod.transition", version="1.0.0", primitive=0,
    publisher="openkedge.io/k8s",
    fields=[
        g.Field(0, "old_state", 7, True, enum_ref="pod_phase"),
        g.Field(1, "new_state", 7, True, enum_ref="pod_phase"),
        g.Field(2, "exit_code", 3, False),
        g.Field(3, "reason", 8, False),
    ],
    enums={"pod_phase": POD_STATE},
)
SDIG = SCHEMA.digest()
RUNNING, FAILED = 1, 3
ENT2 = [
    "k8s:pod:prod-us-east-1/pay-7d9b",
    "k8s:pod:prod-us-east-1/auth-4a2c",
]
ENT_X = "k8s:pod:prod-us-east-1/x"

VALIDATION_SCHEMA = g.Schema(
    name="test.record.validation", version="1.0.0", primitive=1,
    publisher="openkedge.io/test",
    fields=[
        g.Field(0, "u32_value", g.T_U32, False),
        g.Field(1, "i32_value", g.T_I32, False),
        g.Field(2, "f32_value", g.T_F32, False),
        g.Field(3, "text_value", g.T_STRING, False),
        g.Field(4, "target", g.T_ENTITY_REF, False),
        g.Field(5, "opaque", g.T_OPAQUE_REF, False),
        g.Field(6, "bounded_f64", g.T_F64, False,
                constraints={2: (1 << 53) + 1}),
    ],
)
VDIG = VALIDATION_SCHEMA.digest()

# Standard collector setup: producer + key + schema all registered & authorized.
STD = {"producer_registered": True, "key_id": SKID.hex(),
       "rotated_key_registered": False,
       "schema_registered": True, "schema_authorized": True,
       "validation_schema_registered": False,
       "validation_schema_authorized": False}
VSTD = {**STD, "schema_registered": False, "schema_authorized": False,
        "validation_schema_registered": True,
        "validation_schema_authorized": True}


def build_collector(setup):
    c = rc.Collector()
    if setup["producer_registered"]:
        kid = bytes.fromhex(setup["key_id"]) if setup["key_id"] else SKID
        auth = set()
        if setup["schema_authorized"]:
            auth.add(SDIG)
        if setup.get("validation_schema_authorized", False):
            auth.add(VDIG)
        c.register_producer(PID, kid, PK, auth)
        if setup["rotated_key_registered"]:
            c.register_producer_key(PID, SKID2, PK2)
    if setup["schema_registered"]:
        c.register_schema(SCHEMA.manifest())
    if setup.get("validation_schema_registered", False):
        c.register_schema(VALIDATION_SCHEMA.manifest())
    return c


def new_collector():
    return build_collector(STD)


def rec(entity_ref, td, old=RUNNING, new=FAILED, exit_code=137):
    vals = {0: old, 1: new}
    if exit_code is not None:
        vals[2] = exit_code
    return g.encode_record(SCHEMA, 0, entity_ref, td, vals)


def make_batch(records, boot_epoch, first_sequence, previous_root, entity_delta,
               protocol_version=0x0001, schema_digests=None,
               base_time=BASE_TIME, clock_source=1, clock_skew_ms=50,
               signing_key_id=SKID, signing_key=SK):
    schema_digests = schema_digests or [SDIG]
    b = g.build_batch(
        protocol_version=protocol_version, producer_id=PID, boot_epoch=boot_epoch,
        first_sequence=first_sequence, base_time=base_time, clock_source=clock_source,
        clock_skew_ms=clock_skew_ms, schema_digests=schema_digests,
        entity_delta=entity_delta,
        previous_root=previous_root, records=records, sk=signing_key)
    wire = rc.serialize_batch({
        "protocol_version": protocol_version, "producer_id": PID,
        "boot_epoch": boot_epoch, "first_sequence": first_sequence,
        "base_time": base_time, "clock_quality": b["clock_quality"],
        "schema_digests": schema_digests, "entity_delta": entity_delta,
        "previous_root": previous_root, "records": records,
        "merkle_root": b["merkle_root"], "signing_key_id": signing_key_id,
        "signature": b["signature"]})
    return wire, b["batch_root"], b["merkle_root"], b["signature"]


def serialize_with(records, boot_epoch, first_sequence, previous_root,
                   entity_delta, merkle_root, signature, protocol_version=0x0001,
                   signing_key_id=SKID, schema_digests=None):
    schema_digests = schema_digests or [SDIG]
    clock_quality = bytes([1]) + g.u32be(50)
    return rc.serialize_batch({
        "protocol_version": protocol_version, "producer_id": PID,
        "boot_epoch": boot_epoch, "first_sequence": first_sequence,
        "base_time": BASE_TIME, "clock_quality": clock_quality,
        "schema_digests": schema_digests, "entity_delta": entity_delta,
        "previous_root": previous_root, "records": records,
        "merkle_root": merkle_root, "signing_key_id": signing_key_id,
        "signature": signature})


FAILS = []


def check(cond, msg):
    if not cond:
        FAILS.append(msg)


# Registry configuration must preserve historical key bindings.
registry_probe = rc.Collector()
registry_probe.register_producer(PID, SKID, PK, {SDIG})
registry_probe.register_producer_key(PID, SKID, PK)
try:
    registry_probe.register_producer_key(PID, SKID2, PK)
except ValueError as error:
    check(str(error) == "producer public key already has a different key id",
          f"registry duplicate public key returned {error!s}")
else:
    check(False, "registry accepted one public key under two key ids")
try:
    registry_probe.register_producer_key(PID, SKID, PK2)
except ValueError as error:
    check(str(error) == "signing key id collision",
          f"registry collision returned {error!s}")
else:
    check(False, "registry accepted one key id with different key bytes")


# ---------------------------------------------------------------------------
collector_scenarios = []


def scenario(sid, desc, setup, steps):
    """steps: list of (label, wire, expected_status, expected_code)."""
    coll = build_collector(setup)
    out_steps = []
    for label, wire, exp_status, exp_code in steps:
        status, code, _info = coll.accept(wire)
        check(status == exp_status and code == exp_code,
              f"{sid}/{label}: got ({status},{code}) want ({exp_status},{exp_code})")
        out_steps.append({
            "label": label, "batch_hex": wire.hex(),
            "expected_status": exp_status, "expected_code": exp_code,
            "observed_status": status, "observed_code": code})
    collector_scenarios.append({"id": sid, "description": desc,
                                "setup": setup, "steps": out_steps})


# --- continuity ---
g0, g0root, _, _ = make_batch([rec(0, 10), rec(1, 15)], 7, 1000, rc.ZERO32, ENT2)
scenario("CC-001", "Genesis batch accepted.", STD,
         [("genesis", g0, "ACCEPT", None)])

b1, _, _, _ = make_batch([rec(0, 20)], 7, 1002, g0root, [])
scenario("CC-002", "Second batch continues the chain (correct previous_root + seq).", STD,
         [("genesis", g0, "ACCEPT", None), ("continue", b1, "ACCEPT", None)])

gap, _, _, _ = make_batch([rec(0, 20)], 7, 1005, g0root, [])
scenario("CC-003", "Sequence gap: first_sequence ahead of next_expected.", STD,
         [("genesis", g0, "ACCEPT", None), ("gap", gap, "REJECT", rc.E_SEQUENCE_GAP)])

splice, _, _, _ = make_batch([rec(0, 20)], 7, 1002, rc.ZERO32, [])
scenario("CC-004", "Splice: previous_root does not match retained head.", STD,
         [("genesis", g0, "ACCEPT", None), ("splice", splice, "REJECT", rc.E_PREVIOUS_ROOT_MISMATCH)])

e8, _, _, _ = make_batch([rec(0, 5)], 8, 0, rc.ZERO32, ENT2)
old7, _, _, _ = make_batch([rec(0, 20)], 7, 1002, g0root, [])
scenario("CC-005", "Epoch reuse: batch on an epoch below the highest accepted.", STD,
         [("epoch7-genesis", g0, "ACCEPT", None),
          ("epoch8-genesis", e8, "ACCEPT", None),
          ("epoch7-again", old7, "REJECT", rc.E_EPOCH_REUSE)])

scenario("CC-006", "Idempotent retransmission of a committed batch is re-acked.", STD,
         [("genesis", g0, "ACCEPT", None), ("retransmit", g0, "ACCEPT_IDEMPOTENT", None)])

rotated, _, _, _ = make_batch(
    [rec(0, 20)], 7, 1002, g0root, [],
    signing_key_id=SKID2, signing_key=SK2)
scenario("CC-007", "A registered in-epoch key rotation preserves chain continuity.",
         {**STD, "rotated_key_registered": True},
         [("genesis-old-key", g0, "ACCEPT", None),
          ("continue-new-key", rotated, "ACCEPT", None)])

# --- corruption ---
recs = [rec(0, 10), rec(1, 15)]
_, _, mroot, sig = make_batch(recs, 7, 1000, rc.ZERO32, ENT2)
r0f = bytearray(recs[0]); r0f[-1] ^= 0x01
cr1 = serialize_with([bytes(r0f), recs[1]], 7, 1000, rc.ZERO32, ENT2, mroot, sig)
scenario("CR-001", "Record payload bit flip (merkle_root unchanged) -> merkle mismatch.", STD,
         [("flipped-record", cr1, "REJECT", rc.E_MERKLE_MISMATCH)])

good, _, _, _ = make_batch([rec(0, 10), rec(1, 15)], 7, 1000, rc.ZERO32, ENT2)
hdr = bytearray(good); hdr[38] ^= 0x01               # base_time byte (offset 2+16+8+8+4)
scenario("CR-002", "Header bit flip (base_time) -> batch_root changes -> bad signature.", STD,
         [("flipped-header", bytes(hdr), "REJECT", rc.E_INVALID_SIGNATURE)])

sigflip = bytearray(good); sigflip[-1] ^= 0x01
scenario("CR-003", "Signature bit flip -> bad signature.", STD,
         [("flipped-sig", bytes(sigflip), "REJECT", rc.E_INVALID_SIGNATURE)])

mbad = bytearray(mroot); mbad[0] ^= 0x01
cr4 = serialize_with(recs, 7, 1000, rc.ZERO32, ENT2, bytes(mbad), sig)
scenario("CR-004", "merkle_root bit flip -> batch_root changes -> bad signature "
                   "(merkle_root is signature-bound).", STD,
         [("flipped-merkle", cr4, "REJECT", rc.E_INVALID_SIGNATURE)])

# --- records (§9 stage 12) ---
badenum = g.encode_record(SCHEMA, 0, 0, 10, {0: 7, 1: FAILED, 2: 137})   # ordinal 7 > 4
be, _, _, _ = make_batch([badenum], 7, 1000, rc.ZERO32, [ENT_X])
scenario("RV-001", "Enum ordinal out of range -> schema violation (steps 1-9 pass).", STD,
         [("bad-enum", be, "REJECT", rc.E_SCHEMA_VIOLATION)])

nonmin = bytes([0x00, 0x00, 0x8A, 0x00, 0x01, 0x01, 0x03, 0x92, 0x02])   # non-minimal time_delta
nm, _, _, _ = make_batch([nonmin], 7, 1000, rc.ZERO32, [ENT_X])
scenario("RV-002", "Non-minimal varint inside a record -> malformed record.", STD,
         [("non-minimal", nm, "REJECT", rc.E_MALFORMED_RECORD)])


def validation_record(bitmap, payload, entity_ref=0, time_delta=10):
    return (g.uvarint(0) + g.uvarint(entity_ref) + g.uvarint(time_delta)
            + bytes([bitmap]) + payload)


def validation_batch(record, **kwargs):
    return make_batch([record], 7, 1000, rc.ZERO32, [ENT_X],
                      schema_digests=[VDIG], **kwargs)[0]


u32_overflow = validation_record(0x01, g.uvarint(1 << 32))
scenario("RV-003", "U32 value above 2^32-1 -> schema violation.", VSTD,
         [("u32-overflow", validation_batch(u32_overflow),
           "REJECT", rc.E_SCHEMA_VIOLATION)])

i32_overflow = validation_record(0x02, g.uvarint(1 << 32))
scenario("RV-004", "I32 ZigZag value above 2^31-1 -> schema violation.", VSTD,
         [("i32-overflow", validation_batch(i32_overflow),
           "REJECT", rc.E_SCHEMA_VIOLATION)])

bad_nan = validation_record(0x04, bytes.fromhex("7fc00001"))
scenario("RV-005", "Non-canonical F32 NaN payload -> malformed record.", VSTD,
         [("noncanonical-nan", validation_batch(bad_nan),
           "REJECT", rc.E_MALFORMED_RECORD)])

canonical_nan = validation_record(0x04, bytes.fromhex("7fc00000"))
scenario("RV-006", "Canonical F32 NaN payload is accepted.", VSTD,
         [("canonical-nan", validation_batch(canonical_nan), "ACCEPT", None)])

invalid_utf8 = validation_record(0x08, g.uvarint(1) + b"\xff")
scenario("RV-007", "Invalid UTF-8 in STRING -> malformed record.", VSTD,
         [("invalid-utf8", validation_batch(invalid_utf8),
           "REJECT", rc.E_MALFORMED_RECORD)])

nested_entity_oob = validation_record(0x10, g.uvarint(1))
scenario("RV-008", "ENTITY_REF field alias outside cumulative dictionary -> malformed record.", VSTD,
         [("nested-entity-oob", validation_batch(nested_entity_oob),
           "REJECT", rc.E_MALFORMED_RECORD)])

malformed_opaque = validation_record(0x20, g.uvarint(1) + b"\x01")
scenario("RV-009", "Truncated canonical OpaqueRef -> malformed record.", VSTD,
         [("malformed-opaque", validation_batch(malformed_opaque),
           "REJECT", rc.E_MALFORMED_RECORD)])

padding_bits = validation_record(0x40, b"")
scenario("RV-010", "Non-zero unused presence-bitmap bit -> malformed record.", VSTD,
         [("padding-bit", validation_batch(padding_bits),
           "REJECT", rc.E_MALFORMED_RECORD)])

bad_schema_ref = bytes([0x01]) + rec(0, 10)[1:]
bsr, _, _, _ = make_batch([bad_schema_ref], 7, 1000, rc.ZERO32, [ENT_X])
scenario("RV-011", "schema_ref outside the batch dictionary -> malformed record.", STD,
         [("schema-ref-oob", bsr, "REJECT", rc.E_MALFORMED_RECORD)])

trailing_record = rec(0, 10) + b"\x00"
trailing_batch, _, _, _ = make_batch(
    [trailing_record], 7, 1000, rc.ZERO32, [ENT_X])
scenario("RV-012", "Trailing bytes after schema-defined values -> malformed record.", STD,
         [("record-trailing-byte", trailing_batch,
           "REJECT", rc.E_MALFORMED_RECORD)])

time_overflow, _, _, _ = make_batch(
    [rec(0, 1)], 7, 1000, rc.ZERO32, [ENT_X],
    base_time=(1 << 64) - 1)
scenario("RV-013", "base_time + time_delta overflow -> malformed record.", STD,
         [("time-overflow", time_overflow, "REJECT", rc.E_MALFORMED_RECORD)])

f64_below_exact_min = validation_record(
    0x40, bytes.fromhex("4340000000000000"))
scenario("RV-017", "F64 comparison preserves an integral bound above 2^53.", VSTD,
         [("f64-below-exact-min", validation_batch(f64_below_exact_min),
           "REJECT", rc.E_SCHEMA_VIOLATION)])

# --- identity / version / framing ---
g0x, g0xroot, _, _ = make_batch([rec(0, 10)], 7, 1000, rc.ZERO32, [ENT_X])
scenario("SC-001", "Schema resolvable but not authorized for producer.",
         {**STD, "schema_authorized": False},
         [("unauthorized", g0x, "REJECT", rc.E_SCHEMA_UNAUTHORIZED)])
scenario("SC-002", "Schema digest unresolvable in registry.",
         {**STD, "schema_registered": False},
         [("unknown-schema", g0x, "REJECT", rc.E_SCHEMA_UNKNOWN)])
scenario("ID-001", "Unknown producer.",
         {**STD, "producer_registered": False},
         [("unknown-producer", g0x, "REJECT", rc.E_UNKNOWN_PRODUCER)])
scenario("ID-002", "Signing key id not registered for producer.",
         {**STD, "key_id": "dead0000dead0000"},
         [("unknown-key", g0x, "REJECT", rc.E_UNKNOWN_KEY)])
verbad, _, _, _ = make_batch([rec(0, 10)], 7, 1000, rc.ZERO32, [ENT_X], protocol_version=0x0100)
scenario("ID-003", "Unsupported protocol major version.", STD,
         [("bad-version", verbad, "REJECT", rc.E_UNSUPPORTED_VERSION)])
verzero, _, _, _ = make_batch(
    [rec(0, 10)], 7, 1000, rc.ZERO32, [ENT_X], protocol_version=0x0000)
scenario("ID-004", "Unassigned protocol version 0x0000.", STD,
         [("zero-version", verzero, "REJECT", rc.E_UNSUPPORTED_VERSION)])
scenario("MB-001", "Truncated wire -> malformed batch (framing).", STD,
         [("truncated", g0x[:-10], "REJECT", rc.E_MALFORMED_BATCH)])

zero_count = bytearray(g0x)
zero_count[34:38] = b"\x00\x00\x00\x00"
scenario("MB-002", "record_count zero -> malformed batch.", STD,
         [("zero-record-count", bytes(zero_count),
           "REJECT", rc.E_MALFORMED_BATCH)])

oversized_record, _, _, _ = make_batch(
    [b"\x00" * (rc.MAX_RECORD_BYTES + 1)], 7, 1000, rc.ZERO32, [ENT_X])
scenario("MB-003", "Record length above 65535 -> malformed batch.", STD,
         [("oversized-record", oversized_record,
           "REJECT", rc.E_MALFORMED_BATCH)])

invalid_entity, _, _, _ = make_batch(
    [rec(0, 10)], 7, 1000, rc.ZERO32, ["pod/x"])
scenario("MB-004", "Non-canonical entity_id -> malformed batch.", STD,
         [("invalid-entity-id", invalid_entity,
           "REJECT", rc.E_MALFORMED_BATCH)])

duplicate_entity, _, _, _ = make_batch(
    [rec(0, 10)], 7, 1000, rc.ZERO32, [ENT_X, ENT_X])
scenario("MB-005", "Duplicate entity_id within a dictionary delta -> malformed batch.", STD,
         [("duplicate-entity-id", duplicate_entity,
           "REJECT", rc.E_MALFORMED_BATCH)])

reuse_entity, _, _, _ = make_batch(
    [rec(0, 20)], 7, 1001, g0xroot, [ENT_X])
scenario("MB-006", "Dictionary delta reintroduces an existing epoch entity_id.", STD,
         [("genesis", g0x, "ACCEPT", None),
          ("entity-reintroduced", reuse_entity,
           "REJECT", rc.E_MALFORMED_BATCH)])

duplicate_schema, _, _, _ = make_batch(
    [rec(0, 10)], 7, 1000, rc.ZERO32, [ENT_X],
    schema_digests=[SDIG, SDIG])
scenario("MB-007", "Duplicate digest in schema_dictionary -> malformed batch.", STD,
         [("duplicate-schema", duplicate_schema,
           "REJECT", rc.E_MALFORMED_BATCH)])

bad_clock_source, _, _, _ = make_batch(
    [rec(0, 10)], 7, 1000, rc.ZERO32, [ENT_X], clock_source=5)
scenario("MB-008", "Unknown clock source -> malformed batch.", STD,
         [("bad-clock-source", bad_clock_source,
           "REJECT", rc.E_MALFORMED_BATCH)])

bad_unsynced, _, _, _ = make_batch(
    [rec(0, 10)], 7, 1000, rc.ZERO32, [ENT_X],
    clock_source=0, clock_skew_ms=0)
scenario("MB-009", "UNSYNCED requires max_skew_ms=0xffffffff.", STD,
         [("bad-unsynced-skew", bad_unsynced,
           "REJECT", rc.E_MALFORMED_BATCH)])

sequence_overflow, _, _, _ = make_batch(
    [rec(0, 10)], 7, (1 << 64) - 1, rc.ZERO32, [ENT_X])
scenario("MB-010", "Exclusive sequence end overflows u64 -> malformed batch.", STD,
         [("sequence-overflow", sequence_overflow,
           "REJECT", rc.E_MALFORMED_BATCH)])

escaped_ascii, _, _, _ = make_batch(
    [rec(0, 10)], 7, 1000, rc.ZERO32,
    ["k8s:pod:prod-us-east-1%2Fx"])
scenario("MB-011", "Printable ASCII must not be percent-escaped in entity_id.", STD,
         [("escaped-ascii", escaped_ascii,
           "REJECT", rc.E_MALFORMED_BATCH)])

invalid_escaped_utf8, _, _, _ = make_batch(
    [rec(0, 10)], 7, 1000, rc.ZERO32,
    ["k8s:pod:prod-us-east-1/%FF"])
scenario("MB-012", "Percent escapes in entity_id must decode as UTF-8.", STD,
         [("invalid-escaped-utf8", invalid_escaped_utf8,
           "REJECT", rc.E_MALFORMED_BATCH)])

canonical_escaped_utf8, _, _, _ = make_batch(
    [rec(0, 10)], 7, 1000, rc.ZERO32,
    ["k8s:pod:prod-us-east-1/caf%C3%A9"])
scenario("MB-013", "Non-ASCII entity identifiers use uppercase UTF-8 percent escapes.", STD,
         [("canonical-escaped-utf8", canonical_escaped_utf8,
           "ACCEPT", None)])

u64_overflow_varint = b"\x80" * 9 + b"\x02"
overflow_schema_count = g0x[:51] + u64_overflow_varint + g0x[52:]
scenario("MB-014", "A 10-byte uvarint above 2^64-1 is malformed.", STD,
         [("uvarint-overflow", overflow_schema_count,
           "REJECT", rc.E_MALFORMED_BATCH)])

# ---------------------------------------------------------------------------
# Coverage scenarios (§10/§11)
# ---------------------------------------------------------------------------
def committed_ledger():
    c = new_collector()
    b0, r0, _, _ = make_batch([rec(0, 10), rec(1, 15)], 7, 1000, rc.ZERO32, ENT2)
    b1, r1, _, _ = make_batch([rec(0, 20), rec(1, 25)], 7, 1002, r0, [])
    b2, r2, _, _ = make_batch([rec(0, 30), rec(1, 35)], 7, 1004, r1, [])
    for w in (b0, b1, b2):
        assert c.accept(w)[0] == "ACCEPT"
    return [b0, b1, b2], [r0, r1, r2]


seg, roots = committed_ledger()
checkpoint = rc.build_checkpoint(PID, 7, 1005, roots[2], BASE_TIME + 1, 1, CK_KID, CK_SK)
coverage_scenarios = []
REQUEST = {
    "producer_id": PID,
    "boot_epoch": 7,
    "first_sequence": 1000,
    "last_sequence": 1005,
}


def cov(cid, desc, segment, ck, expected, request=REQUEST):
    status = rc.classify_coverage(
        segment,
        ck,
        {CK_KID: CK_PK},
        {SKID: PK, SKID2: PK2},
        {SDIG: SCHEMA.manifest()},
        {SDIG},
        request,
    )
    check(status == expected, f"{cid}: got {status} want {expected}")
    coverage_scenarios.append({
        "id": cid, "description": desc, "expected_status": expected,
        "observed_status": status,
        "checkpoint_signature_valid": None if ck is None else rc.verify_checkpoint(ck, CK_PK),
        "request": {
            "producer_id": request["producer_id"].hex(),
            "boot_epoch": request["boot_epoch"],
            "first_sequence": request["first_sequence"],
            "last_sequence": request["last_sequence"],
        },
        "segment_hex": [w.hex() for w in segment],
        "checkpoint": None if ck is None else {
            "producer_id": ck["producer_id"].hex(),
            "boot_epoch": ck["boot_epoch"],
            "highest_sequence": ck["highest_sequence"],
            "batch_root": ck["batch_root"].hex(),
            "checkpoint_time": ck["checkpoint_time"],
            "checkpoint_sequence": ck["checkpoint_sequence"],
            "signing_key_id": ck["signing_key_id"].hex(),
            "checkpoint_root": ck["checkpoint_root"].hex(),
            "signature": ck["signature"].hex(),
            "checkpoint_hex": rc.serialize_checkpoint(ck).hex(),
        },
    })


cov("COV-001", "Contiguous, signed, anchored to checkpoint.", seg, checkpoint, "complete")
cov("COV-002", "Missing middle batch -> gap.", [seg[0], seg[2]], checkpoint, "gap")

seg_t0 = bytearray(seg[0]); seg_t0[-1] ^= 0x01       # flip last signature byte of batch 0
seg_t = [bytes(seg_t0), seg[1], seg[2]]
cov("COV-003", "Mutated stored batch (signature) -> tampered.", seg_t, checkpoint, "tampered")

cov("COV-004", "Retained tail below checkpoint.highest_sequence -> truncated.",
    [seg[0], seg[1]], checkpoint, "truncated")

cov("COV-005", "Tamper + gap present -> tampered wins (precedence).",
    [bytes(seg_t0), seg[2]], checkpoint, "tampered")

cov("COV-006", "Unanchored (no valid checkpoint) -> cannot certify complete -> gap.",
    seg, None, "gap")

bad_checkpoint_sig = {**checkpoint, "signature": checkpoint["signature"][:-1]
                      + bytes([checkpoint["signature"][-1] ^ 1])}
cov("COV-007", "Invalid checkpoint signature -> tampered.",
    seg, bad_checkpoint_sig, "tampered")

wrong_epoch_checkpoint = rc.build_checkpoint(
    PID, 8, 1005, roots[2], BASE_TIME + 1, 2, CK_KID, CK_SK)
cov("COV-008", "Valid checkpoint for a different epoch -> tampered.",
    seg, wrong_epoch_checkpoint, "tampered")

wrong_root_checkpoint = rc.build_checkpoint(
    PID, 7, 1005, b"\xff" * 32, BASE_TIME + 1, 2, CK_KID, CK_SK)
cov("COV-009", "Checkpoint root does not identify its terminal batch -> tampered.",
    seg, wrong_root_checkpoint, "tampered")

old_checkpoint = rc.build_checkpoint(
    PID, 7, 1003, roots[1], BASE_TIME, 0, CK_KID, CK_SK)
cov("COV-010", "Checkpoint is behind the requested range -> gap.",
    seg, old_checkpoint, "gap")

cov("COV-011", "Stored batches returned out of sequence order -> tampered.",
    [seg[1], seg[0], seg[2]], checkpoint, "tampered")

cov("COV-012", "All retained batches removed below a valid checkpoint -> truncated.",
    [], checkpoint, "truncated")

invalid_record_batch, invalid_record_root, _, _ = make_batch(
    [badenum], 7, 1000, rc.ZERO32, [ENT_X])
invalid_record_checkpoint = rc.build_checkpoint(
    PID, 7, 1000, invalid_record_root, BASE_TIME + 1, 3, CK_KID, CK_SK)
cov("COV-013", "Signed but schema-invalid record cannot produce complete coverage.",
    [invalid_record_batch], invalid_record_checkpoint, "tampered",
    {**REQUEST, "last_sequence": 1000})

rot0, rot0_root, _, _ = make_batch(
    [rec(0, 10), rec(1, 15)], 7, 1000, rc.ZERO32, ENT2)
rot1, rot1_root, _, _ = make_batch(
    [rec(0, 20), rec(1, 25)], 7, 1002, rot0_root, [],
    signing_key_id=SKID2, signing_key=SK2)
rotation_checkpoint = rc.build_checkpoint(
    PID, 7, 1003, rot1_root, BASE_TIME + 1, 4, CK_KID, CK_SK)
cov("COV-014", "Historical key lookup verifies an in-epoch producer key rotation.",
    [rot0, rot1], rotation_checkpoint, "complete",
    {**REQUEST, "last_sequence": 1003})

overlap, overlap_root, _, _ = make_batch(
    [rec(0, 20)], 7, 1001, rot0_root, [])
overlap_checkpoint = rc.build_checkpoint(
    PID, 7, 1001, overlap_root, BASE_TIME + 1, 5, CK_KID, CK_SK)
cov("COV-015", "Overlapping retained sequence ranges cannot be accepted history.",
    [rot0, overlap], overlap_checkpoint, "tampered",
    {**REQUEST, "last_sequence": 1001})

invalid_request = {**REQUEST, "first_sequence": 1005, "last_sequence": 1004}
cov("COV-016", "An invalid request is gap when no higher-precedence defect applies.",
    seg, checkpoint, "gap", invalid_request)
cov("COV-017", "Tampering takes precedence over an invalid request.",
    seg_t, checkpoint, "tampered", invalid_request)

# ---------------------------------------------------------------------------
# Opaque evidence (§12)
# ---------------------------------------------------------------------------
def opaque_ref(opaque_id, media_type, byte_length, payload_digest, storage_uri,
               retention_class):
    return (g.lp_string(opaque_id) + g.lp_string(media_type)
            + g.uvarint(byte_length) + payload_digest
            + g.lp_string(storage_uri) + g.uvarint(retention_class))


def deref(expected_length, ref_payload_digest, stored_payload):
    if len(stored_payload) != expected_length:
        return rc.E_OPAQUE_LENGTH_MISMATCH
    return "OK" if g.sha256(stored_payload) == ref_payload_digest \
        else rc.E_OPAQUE_DIGEST_MISMATCH


payload = b"panic: runtime error: index out of range [3] with length 3\n"
pdig = g.sha256(payload)
ref = opaque_ref("op-0001", "text/plain", len(payload), pdig, "s3://opaque/op-0001", 2)
opaque_vectors = []

valid_opaque_record = validation_record(
    0x20, g.uvarint(len(ref)) + ref)
scenario("RV-014", "Canonical OpaqueRef value is accepted.", VSTD,
         [("valid-opaque", validation_batch(valid_opaque_record), "ACCEPT", None)])

bad_media_ref = opaque_ref(
    "op-0001", "Text/Plain", len(payload), pdig, "s3://opaque/op-0001", 2)
bad_media_record = validation_record(
    0x20, g.uvarint(len(bad_media_ref)) + bad_media_ref)
scenario("RV-015", "Non-canonical OpaqueRef media type -> schema violation.", VSTD,
         [("bad-opaque-media", validation_batch(bad_media_record),
           "REJECT", rc.E_SCHEMA_VIOLATION)])

non_ascii_media_ref = opaque_ref(
    "op-0001", "téxt/plain", len(payload), pdig, "s3://opaque/op-0001", 2)
non_ascii_media_record = validation_record(
    0x20, g.uvarint(len(non_ascii_media_ref)) + non_ascii_media_ref)
scenario("RV-016", "OpaqueRef media type must contain ASCII token bytes.", VSTD,
         [("non-ascii-opaque-media", validation_batch(non_ascii_media_record),
           "REJECT", rc.E_SCHEMA_VIOLATION)])

d1 = deref(len(payload), pdig, payload)
check(d1 == "OK", f"OP-001 got {d1}")
check(rc.validate_opaque_ref(ref), "OP-001 OpaqueRef structure invalid")
opaque_vectors.append({
    "id": "OP-001", "description": "Valid OpaqueRef; stored payload digest matches.",
    "opaque_ref_hex": ref.hex(), "payload_digest": pdig.hex(),
    "byte_length": len(payload), "structure_valid": True, "deref": d1})

d2 = deref(len(payload), pdig, payload[:-1] + b"X")
check(d2 == rc.E_OPAQUE_DIGEST_MISMATCH, f"OP-002 got {d2}")
opaque_vectors.append({
    "id": "OP-002", "description": "Stored payload altered; digest mismatch -> refuse delivery.",
    "opaque_ref_hex": ref.hex(), "payload_digest": pdig.hex(),
    "byte_length": len(payload), "deref": d2})

d3 = deref(len(payload), pdig, payload + b"X")
check(d3 == rc.E_OPAQUE_LENGTH_MISMATCH, f"OP-003 got {d3}")
opaque_vectors.append({
    "id": "OP-003", "description": "Stored payload length differs from OpaqueRef -> refuse delivery.",
    "opaque_ref_hex": ref.hex(), "payload_digest": pdig.hex(),
    "byte_length": len(payload), "deref": d3})

# CV-CORE-001 covers two leaves; these vectors pin RFC 6962 odd-tree splits.
merkle_vectors = []
for vector_id, leaves in (
        ("MK-001", [b"\x00"]),
        ("MK-003", [b"\x00", b"\x01", b"\x02"]),
        ("MK-005", [b"\x00", b"\x01", b"\x02", b"\x03", b"\x04"])):
    merkle_vectors.append({
        "id": vector_id,
        "description": f"RFC 6962 Merkle Tree Hash for {len(leaves)} leaves.",
        "records_hex": [leaf.hex() for leaf in leaves],
        "merkle_root": g.merkle_root(leaves).hex(),
    })

# ---------------------------------------------------------------------------
out = {
    "suite": "ATP-0001 conformance vectors (stateful)",
    "producer": {
        "producer_id": PID.hex(), "signing_key_id": SKID.hex(),
        "ed25519_public_key": PK.hex(), "schema_digest": SDIG.hex(),
        "rotated_signing_key_id": SKID2.hex(),
        "rotated_ed25519_public_key": PK2.hex(),
        "validation_schema_digest": VDIG.hex(),
        "checkpoint_signing_key_id": CK_KID.hex(),
        "checkpoint_public_key": CK_PK.hex(),
    },
    "collector_scenarios": collector_scenarios,
    "coverage_scenarios": coverage_scenarios,
    "opaque_vectors": opaque_vectors,
    "merkle_vectors": merkle_vectors,
}

print(json.dumps({
    "collector_scenarios": [(s["id"], [(x["label"], x["observed_status"], x["observed_code"])
                                       for x in s["steps"]]) for s in collector_scenarios],
    "coverage_scenarios": [(s["id"], s["observed_status"]) for s in coverage_scenarios],
    "opaque": [(o["id"], o["deref"]) for o in opaque_vectors],
    "merkle": [(m["id"], m["merkle_root"]) for m in merkle_vectors],
}, indent=2))

if FAILS:
    print("\nASSERTION FAILURES:")
    for f in FAILS:
        print("  -", f)
    raise SystemExit(1)

outdir = os.path.dirname(os.path.abspath(__file__))
path = os.path.join(outdir, "conformance-vectors.json")
rendered = json.dumps(out, indent=2) + "\n"
if ARGS.check:
    with open(path, encoding="utf-8") as existing:
        if existing.read() != rendered:
            raise SystemExit("conformance-vectors.json is stale")
    print("conformance-vectors.json is current")
else:
    with open(path, "w", encoding="utf-8") as output:
        output.write(rendered)
print(f"\nAll {len(collector_scenarios)} collector + {len(coverage_scenarios)} "
      f"coverage + {len(opaque_vectors)} opaque + {len(merkle_vectors)} Merkle "
      f"scenarios PASSED. "
      f"{'Checked' if ARGS.check else 'Wrote'} conformance-vectors.json")
