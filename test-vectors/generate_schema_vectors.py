#!/usr/bin/env python3
"""
ATP-0002 Schema Manifest Format — reference vector generator.

Reuses the dependency-free CBOR / digest primitives from generate_vectors.py
(the ATP-0001 reference generator) so schema digests are guaranteed identical
across the two documents. Emits schema-vectors.json.

Key continuity guarantee (asserted below): the digest of the
`k8s.pod.transition:1.0.0` manifest MUST equal the value baked into
ATP-0001 Appendix B / CV-CORE-001 (b8fafb40...4103f2). ATP-0002 does not
change any ATP-0001 byte.
"""

import argparse
import json
import os
import re

import generate_vectors as g  # importing does NOT run its main()

# ATP value type codes (ATP-0001 s6.3 / ATP-0002 s4)
(T_BOOL, T_U32, T_U64, T_I32, T_I64, T_F32, T_F64, T_ENUM, T_STRING,
 T_BYTES, T_TIMESTAMP_MS, T_ENTITY_REF, T_OPAQUE_REF, T_DURATION_MS) = range(14)
TYPE_NAMES = ["BOOL", "U32", "U64", "I32", "I64", "F32", "F64", "ENUM",
              "STRING", "BYTES", "TIMESTAMP_MS", "ENTITY_REF", "OPAQUE_REF",
              "DURATION_MS"]

# Primitive codes (ATP-0001 s3.1)
P_TRANSITION, P_OBSERVATION, P_RELATION, P_STATE_CHECKPOINT = range(4)

# constraints sub-map keys (ATP-0002 s4.2)
C_MAX_LEN, C_MIN, C_MAX = 1, 2, 3
# compatibility mode enum (ATP-0002 s7.3)
COMPAT_NONE, COMPAT_BACKWARD, COMPAT_FORWARD, COMPAT_FULL = range(4)

NUMERIC = {T_U32, T_U64, T_I32, T_I64, T_F32, T_F64,
           T_TIMESTAMP_MS, T_DURATION_MS}
LEN_TYPES = {T_STRING, T_BYTES}
MAX_MANIFEST_BYTES = 1_048_576
MAX_FIELDS = 1_024
MAX_ENUMS = 1_024
MAX_NAME_BYTES = 128
MAX_FIELD_NAME_BYTES = 64
MAX_ENUM_MEMBERS = 65_535
NAME_RE = re.compile(r"^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")
FIELD_RE = re.compile(r"^[a-z][a-z0-9_]*$")


def valid_semver(value):
    """Strict SemVer 2.0.0 syntax, bounded to 64 ASCII bytes."""
    if not isinstance(value, str) or not (1 <= len(value) <= 64) or not value.isascii():
        return False
    without_build, plus, build = value.partition("+")
    if plus and (not build or "+" in build or not _valid_semver_ids(build, False)):
        return False
    core, dash, prerelease = without_build.partition("-")
    if dash and not _valid_semver_ids(prerelease, True):
        return False
    parts = core.split(".")
    return len(parts) == 3 and all(
        part.isdigit() and (part == "0" or not part.startswith("0"))
        for part in parts
    )


def _valid_semver_ids(value, reject_numeric_leading_zero):
    for part in value.split("."):
        if not part or any(not (ch.isascii() and (ch.isalnum() or ch == "-"))
                           for ch in part):
            return False
        if reject_numeric_leading_zero and part.isdigit() \
                and part != "0" and part.startswith("0"):
            return False
    return True


def compare_semver(left, right):
    """SemVer 2.0.0 precedence; build metadata does not affect ordering."""
    def parse(value):
        without_build = value.split("+", 1)[0]
        core, separator, prerelease = without_build.partition("-")
        return tuple(int(part) for part in core.split(".")), \
            prerelease.split(".") if separator else None

    left_core, left_pre = parse(left)
    right_core, right_pre = parse(right)
    if left_core != right_core:
        return -1 if left_core < right_core else 1
    if left_pre is None or right_pre is None:
        return 0 if left_pre is right_pre else (1 if left_pre is None else -1)
    for a, b in zip(left_pre, right_pre):
        if a == b:
            continue
        a_numeric, b_numeric = a.isdigit(), b.isdigit()
        if a_numeric and b_numeric:
            return -1 if int(a) < int(b) else 1
        if a_numeric != b_numeric:
            return -1 if a_numeric else 1
        return -1 if a < b else 1
    return (len(left_pre) > len(right_pre)) - (len(left_pre) < len(right_pre))


def digest(manifest: dict) -> bytes:
    return g.schema_digest(manifest)


# ---------------------------------------------------------------------------
# Manifest validator (ATP-0002 s5). Raises ValueError with a stable reason.
# ---------------------------------------------------------------------------
def validate_manifest(m: dict):
    if not isinstance(m, dict):
        raise ValueError("MANIFEST_NOT_MAP")
    unknown = set(m) - set(range(1, 8))
    if unknown:
        raise ValueError(f"UNKNOWN_MANIFEST_KEY:{min(unknown)}")
    for k in (1, 2, 3, 4, 5, 6):
        if k not in m:
            raise ValueError(f"MISSING_KEY:{k}")
    if not isinstance(m[1], str) or len(m[1].encode()) > MAX_NAME_BYTES \
            or not NAME_RE.fullmatch(m[1]):
        raise ValueError("BAD_SCHEMA_NAME")
    if not valid_semver(m[2]):
        raise ValueError("BAD_SCHEMA_VERSION")
    if type(m[3]) is not int or not (0 <= m[3] <= 3):
        raise ValueError("BAD_PRIMITIVE")
    if not isinstance(m[4], str) or not (1 <= len(m[4].encode()) <= MAX_NAME_BYTES) \
            or not m[4].isascii() or any(not (0x21 <= ord(c) <= 0x7E) for c in m[4]):
        raise ValueError("BAD_PUBLISHER")
    fields = m[5]
    if not isinstance(fields, list) or not (1 <= len(fields) <= MAX_FIELDS):
        raise ValueError("EMPTY_FIELDS")
    enums = m[6]
    if not isinstance(enums, dict):
        raise ValueError("ENUMS_NOT_MAP")
    if len(enums) > MAX_ENUMS:
        raise ValueError("TOO_MANY_ENUMS")
    seen_slots = set()
    seen_names = set()
    for idx, f in enumerate(fields):
        if not isinstance(f, dict):
            raise ValueError(f"FIELD_NOT_MAP:{idx}")
        unknown_field = set(f) - set(range(1, 8))
        if unknown_field:
            raise ValueError(f"UNKNOWN_FIELD_KEY:{min(unknown_field)}")
        for k in (1, 2, 3, 4):
            if k not in f:
                raise ValueError(f"FIELD_MISSING_KEY:{k}")
        slot, typ, req = f[1], f[3], f[4]
        if type(slot) is not int or slot != idx:
            raise ValueError(f"SLOT_NOT_DENSE:expected={idx}:got={slot}")
        if slot in seen_slots:
            raise ValueError(f"DUP_SLOT:{slot}")
        seen_slots.add(slot)
        name = f[2]
        if not isinstance(name, str) or len(name.encode()) > MAX_FIELD_NAME_BYTES \
                or not FIELD_RE.fullmatch(name):
            raise ValueError(f"BAD_FIELD_NAME:{idx}")
        if name in seen_names:
            raise ValueError(f"DUP_FIELD_NAME:{name}")
        seen_names.add(name)
        if type(typ) is not int or not (0 <= typ <= 13):
            raise ValueError(f"UNKNOWN_TYPE:{typ}")
        if not isinstance(req, bool):
            raise ValueError("BAD_REQUIRED")
        if 5 in f and (not isinstance(f[5], str) or not (1 <= len(f[5].encode()) <= 64)
                       or not f[5].isascii()
                       or any(not (0x21 <= ord(c) <= 0x7E) for c in f[5])):
            raise ValueError(f"BAD_UNIT:{name}")
        if typ == T_ENUM:
            er = f.get(6)
            if not isinstance(er, str):
                raise ValueError("ENUM_MISSING_ENUM_REF")
            if er not in enums:
                raise ValueError(f"ENUM_REF_UNRESOLVED:{er}")
        elif 6 in f:
            raise ValueError(f"ENUM_REF_ON_NONENUM:{name}")
        if name == "intent_ref":
            if m[3] not in (P_TRANSITION, P_RELATION) or typ != T_BYTES:
                raise ValueError("BAD_INTENT_REF")
        if 7 in f:  # constraints
            if not isinstance(f[7], dict):
                raise ValueError(f"CONSTRAINTS_NOT_MAP:{name}")
            unknown_constraint = set(f[7]) - {C_MAX_LEN, C_MIN, C_MAX}
            if unknown_constraint:
                raise ValueError(f"UNKNOWN_CONSTRAINT:{min(unknown_constraint)}")
            for ck, value in f[7].items():
                if type(value) is not int or not (-(1 << 63) <= value <= (1 << 63) - 1):
                    raise ValueError(f"BAD_CONSTRAINT_VALUE:{name}:{ck}")
                if ck in (C_MAX_LEN,) and typ not in LEN_TYPES:
                    raise ValueError(f"MAX_LEN_ON_NONLEN_TYPE:{TYPE_NAMES[typ]}")
                if ck in (C_MIN, C_MAX) and typ not in NUMERIC:
                    raise ValueError(f"RANGE_ON_NONNUMERIC:{TYPE_NAMES[typ]}")
            if C_MAX_LEN in f[7] and not (1 <= f[7][C_MAX_LEN] <= 4096):
                raise ValueError(f"BAD_MAX_LEN:{name}")
            lo, hi = f[7].get(C_MIN), f[7].get(C_MAX)
            if lo is not None and hi is not None and lo > hi:
                raise ValueError(f"MIN_GT_MAX:{name}")
            if typ in (T_U32, T_U64, T_DURATION_MS):
                ceiling = (1 << 32) - 1 if typ == T_U32 else (1 << 64) - 1
                if any(v is not None and not (0 <= v <= ceiling) for v in (lo, hi)):
                    raise ValueError(f"UNSIGNED_RANGE_OUT_OF_DOMAIN:{name}")
            if typ == T_I32 and any(
                    v is not None and not (-(1 << 31) <= v <= (1 << 31) - 1)
                    for v in (lo, hi)):
                raise ValueError(f"I32_RANGE_OUT_OF_DOMAIN:{name}")
            if typ in (T_I64, T_TIMESTAMP_MS, T_F32, T_F64) and any(
                    v is not None and not (-(1 << 63) <= v <= (1 << 63) - 1)
                    for v in (lo, hi)):
                raise ValueError(f"RANGE_OUT_OF_DOMAIN:{name}")
    for name, members in enums.items():
        if not isinstance(name, str) or len(name.encode()) > MAX_FIELD_NAME_BYTES \
                or not FIELD_RE.fullmatch(name):
            raise ValueError("BAD_ENUM_NAME")
        if not isinstance(members, list) or not (1 <= len(members) <= MAX_ENUM_MEMBERS):
            raise ValueError(f"EMPTY_ENUM:{name}")
        if any(not isinstance(member, str) or not (1 <= len(member.encode()) <= 128)
               or not member.isascii()
               or any(not (0x21 <= ord(c) <= 0x7E) for c in member)
               for member in members):
            raise ValueError(f"BAD_ENUM_MEMBER:{name}")
        if len(set(members)) != len(members):
            raise ValueError(f"DUP_ENUM_MEMBER:{name}")
    if 7 in m:
        compat = m[7]
        if not isinstance(compat, dict) or set(compat) != {1, 2}:
            raise ValueError("BAD_COMPATIBILITY_MAP")
        if not valid_semver(compat[1]):
            raise ValueError("BAD_COMPATIBILITY_VERSION")
        if type(compat[2]) is not int or not (0 <= compat[2] <= 3):
            raise ValueError("BAD_COMPATIBILITY_MODE")
        ordering = compare_semver(compat[1], m[2])
        if ordering > 0 or (compat[2] == COMPAT_NONE and ordering != 0):
            raise ValueError("BAD_COMPATIBILITY_RANGE")
    if len(g.cbor(m)) > MAX_MANIFEST_BYTES:
        raise ValueError("MANIFEST_TOO_LARGE")
    return True


def field(slot, name, typ, required, unit=None, enum_ref=None, constraints=None):
    f = {1: slot, 2: name, 3: typ, 4: required}
    if unit is not None:
        f[5] = unit
    if enum_ref is not None:
        f[6] = enum_ref
    if constraints is not None:
        f[7] = constraints
    return f


def h(b):
    return b.hex()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    vectors = []

    # ---- SV-001: minimal Observation manifest (1 required scalar) -----------
    sv001 = {
        1: "host.cpu.utilization", 2: "1.0.0", 3: P_OBSERVATION,
        4: "openkedge.io/host",
        5: [field(0, "cpu_pct", T_F64, True, unit="1")],
        6: {},
    }
    validate_manifest(sv001)
    vectors.append({
        "vector_id": "SV-001",
        "description": "Minimal Observation manifest, single required F64 field.",
        "canonical_cbor": h(g.cbor(sv001)),
        "schema_digest_sha256": h(digest(sv001)),
    })

    # ---- SV-002: k8s.pod.transition (MUST match CV-CORE-001) ----------------
    POD_STATE = ["Pending", "Running", "Succeeded", "Failed", "Unknown"]
    sv002_schema = g.Schema(
        name="k8s.pod.transition", version="1.0.0", primitive=P_TRANSITION,
        publisher="openkedge.io/k8s",
        fields=[
            g.Field(0, "old_state", T_ENUM, True, enum_ref="pod_phase"),
            g.Field(1, "new_state", T_ENUM, True, enum_ref="pod_phase"),
            g.Field(2, "exit_code", T_I32, False),
            g.Field(3, "reason", T_STRING, False),
        ],
        enums={"pod_phase": POD_STATE},
    )
    sv002 = sv002_schema.manifest()
    validate_manifest(sv002)
    d002 = digest(sv002)
    CV_CORE_001_DIGEST = "b8fafb40cf935d94f1ef933ce411f5d7d881a82413fc463a45db30a37a4103f2"
    assert h(d002) == CV_CORE_001_DIGEST, "CONTINUITY BROKEN vs CV-CORE-001!"
    vectors.append({
        "vector_id": "SV-002",
        "description": "k8s.pod.transition:1.0.0 — continuity anchor; digest "
                       "MUST equal ATP-0001 CV-CORE-001.",
        "canonical_cbor": h(g.cbor(sv002)),
        "schema_digest_sha256": h(d002),
        "continuity_ok": True,
    })

    # ---- SV-003: all 14 value types, one field each -------------------------
    all_fields = []
    for t in range(14):
        kw = {}
        if t == T_ENUM:
            kw["enum_ref"] = "color"
        if t in LEN_TYPES:
            kw["constraints"] = {C_MAX_LEN: 256}
        all_fields.append(field(t, f"f_{TYPE_NAMES[t].lower()}", t, True, **kw))
    sv003 = {
        1: "test.all_types.observation", 2: "1.0.0", 3: P_OBSERVATION,
        4: "openkedge.io/test",
        5: all_fields,
        6: {"color": ["red", "green", "blue"]},
    }
    validate_manifest(sv003)
    vectors.append({
        "vector_id": "SV-003",
        "description": "One field per value type 0..13 — locks manifest "
                       "encoding of every type code.",
        "canonical_cbor": h(g.cbor(sv003)),
        "schema_digest_sha256": h(digest(sv003)),
    })

    # ---- SV-004: canonicalization/determinism proof -------------------------
    # Same LOGICAL manifest as SV-002, but every map built with keys inserted
    # in reversed order. Deterministic CBOR MUST yield identical bytes.
    def rev(d):
        return {k: d[k] for k in reversed(list(d.keys()))}

    scrambled = rev({
        1: "k8s.pod.transition", 2: "1.0.0", 3: P_TRANSITION,
        4: "openkedge.io/k8s",
        5: [
            rev(field(0, "old_state", T_ENUM, True, enum_ref="pod_phase")),
            rev(field(1, "new_state", T_ENUM, True, enum_ref="pod_phase")),
            rev(field(2, "exit_code", T_I32, False)),
            rev(field(3, "reason", T_STRING, False)),
        ],
        6: {"pod_phase": POD_STATE},
    })
    cbor_scrambled = g.cbor(scrambled)
    assert cbor_scrambled == g.cbor(sv002), "DETERMINISM BROKEN!"
    vectors.append({
        "vector_id": "SV-004",
        "description": "Determinism: SV-002 rebuilt with reversed map-key "
                       "insertion order. Canonical CBOR is byte-identical.",
        "canonical_cbor": h(cbor_scrambled),
        "schema_digest_sha256": h(digest(scrambled)),
        "identical_to": "SV-002",
    })

    # ---- SV-005: constraints + compatibility metadata -----------------------
    sv005 = {
        1: "svc.rpc.result", 2: "2.1.0", 3: P_TRANSITION,
        4: "openkedge.io/rpc",
        5: [
            field(0, "status_code", T_U32, True,
                  constraints={C_MIN: 100, C_MAX: 599}),
            field(1, "method", T_STRING, True, constraints={C_MAX_LEN: 64}),
            field(2, "latency_ms", T_DURATION_MS, False),
        ],
        6: {},
        7: {1: "2.0.0", 2: COMPAT_BACKWARD},  # compatibility
    }
    validate_manifest(sv005)
    vectors.append({
        "vector_id": "SV-005",
        "description": "Numeric range + max_len constraints and a backward-"
                       "compatibility declaration (min_compatible_version 2.0.0).",
        "canonical_cbor": h(g.cbor(sv005)),
        "schema_digest_sha256": h(digest(sv005)),
    })

    # ---- SV-006: text map-key ordering ---------------------------------------
    # Encoded-key ordering puts "z" (0x61 7a) before "aa" (0x62 61 61).
    sv006 = {
        1: "test.enum.order", 2: "1.0.0", 3: P_OBSERVATION,
        4: "openkedge.io/test",
        5: [
            field(0, "short_key", T_ENUM, True, enum_ref="z"),
            field(1, "long_key", T_ENUM, True, enum_ref="aa"),
        ],
        6: {"aa": ["A"], "z": ["Z"]},
    }
    validate_manifest(sv006)
    vectors.append({
        "vector_id": "SV-006",
        "description": "Deterministic CBOR text-key ordering uses encoded key "
                       "bytes, including each key's CBOR length head.",
        "canonical_cbor": h(g.cbor(sv006)),
        "schema_digest_sha256": h(digest(sv006)),
    })

    # ---- Negative vectors: MUST be rejected by the validator ----------------
    negatives = []

    def expect_reject(vid, desc, manifest):
        canonical_cbor = h(g.cbor(manifest))
        try:
            validate_manifest(manifest)
            raise AssertionError(f"{vid} should have been rejected")
        except ValueError as e:
            negatives.append({"vector_id": vid, "description": desc,
                              "canonical_cbor": canonical_cbor,
                              "reject_reason": str(e)})

    expect_reject("SV-NEG-001", "slot gap (0,2) — non-dense slots", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U32, True), field(2, "b", T_U32, True)], 6: {}})
    expect_reject("SV-NEG-002", "duplicate slot 0", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U32, True), field(0, "b", T_U32, True)], 6: {}})
    expect_reject("SV-NEG-003", "ENUM field without enum_ref", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_ENUM, True)], 6: {}})
    expect_reject("SV-NEG-004", "unknown value type code 99", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", 99, True)], 6: {}})
    expect_reject("SV-NEG-005", "max_len constraint on non-length type U32", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U32, True, constraints={C_MAX_LEN: 10})], 6: {}})
    expect_reject("SV-NEG-006", "enum_ref pointing to undefined enum", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_ENUM, True, enum_ref="missing")], 6: {}})
    expect_reject("SV-NEG-007", "duplicate field name", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U32, True), field(1, "a", T_U32, True)], 6: {}})
    expect_reject("SV-NEG-008", "invalid SemVer", {
        1: "x", 2: "v1", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U32, True)], 6: {}})
    expect_reject("SV-NEG-009", "unknown manifest key", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U32, True)], 6: {}, 99: True})
    expect_reject("SV-NEG-010", "unknown field-def key", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [{**field(0, "a", T_U32, True), 99: True}], 6: {}})
    expect_reject("SV-NEG-011", "enum_ref on a non-ENUM field", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U32, True, enum_ref="x")], 6: {"x": ["A"]}})
    expect_reject("SV-NEG-012", "zero max_len", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_STRING, True, constraints={C_MAX_LEN: 0})], 6: {}})
    expect_reject("SV-NEG-013", "max_len above the hard ceiling", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_STRING, True, constraints={C_MAX_LEN: 4097})], 6: {}})
    expect_reject("SV-NEG-014", "numeric min greater than max", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U32, True, constraints={C_MIN: 2, C_MAX: 1})], 6: {}})
    expect_reject("SV-NEG-015", "negative lower bound on unsigned type", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U32, True, constraints={C_MIN: -1})], 6: {}})
    expect_reject("SV-NEG-016", "compatibility mode outside registry", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U32, True)], 6: {}, 7: {1: "1.0.0", 2: 9}})
    expect_reject("SV-NEG-017", "missing required enums map", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U32, True)]})
    expect_reject("SV-NEG-018", "intent_ref on Observation", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "intent_ref", T_BYTES, False)], 6: {}})
    expect_reject("SV-NEG-019", "intent_ref with non-BYTES type", {
        1: "x", 2: "1.0.0", 3: P_TRANSITION, 4: "p",
        5: [field(0, "intent_ref", T_STRING, False)], 6: {}})
    expect_reject("SV-NEG-020", "duplicate enum member", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_ENUM, True, enum_ref="e")], 6: {"e": ["A", "A"]}})
    expect_reject("SV-NEG-021", "numeric prerelease identifier has a leading zero", {
        1: "x", 2: "1.0.0-01", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U32, True)], 6: {}})
    expect_reject("SV-NEG-022", "empty prerelease identifier", {
        1: "x", 2: "1.0.0-", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U32, True)], 6: {}})
    expect_reject("SV-NEG-023", "constraint exceeds the signed 64-bit domain", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U64, True, constraints={C_MAX: 1 << 63})], 6: {}})
    expect_reject("SV-NEG-024", "compatibility minimum is newer than this schema", {
        1: "x", 2: "1.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U32, True)], 6: {},
        7: {1: "2.0.0", 2: COMPAT_BACKWARD}})
    expect_reject("SV-NEG-025", "NONE compatibility must name this exact version", {
        1: "x", 2: "2.0.0", 3: P_OBSERVATION, 4: "p",
        5: [field(0, "a", T_U32, True)], 6: {},
        7: {1: "1.0.0", 2: COMPAT_NONE}})

    out = {
        "suite": "ATP-0002 schema manifest vectors",
        "crypto": {"hash": "SHA-256", "canonical_form": "RFC 8949 s4.2.1 CBOR",
                   "domain_prefix": g.D_MANIFEST.decode()},
        "positive": vectors,
        "negative": negatives,
    }
    rendered = json.dumps(out, indent=2) + "\n"
    outdir = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(outdir, "schema-vectors.json")
    if args.check:
        with open(path, encoding="utf-8") as existing:
            if existing.read() != rendered:
                raise SystemExit("schema-vectors.json is stale")
        print("schema-vectors.json is current")
        return
    with open(path, "w", encoding="utf-8") as output:
        output.write(rendered)
    print(rendered, end="")


if __name__ == "__main__":
    main()
