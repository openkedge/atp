#!/usr/bin/env python3
"""
ATP-0001 reference vector generator.

Dependency-free (Python 3.8+ stdlib only). Implements the normative encoding
rules of ATP-0001 Core Protocol precisely enough to reproduce the canonical
worked example bytes cited in the specification, and to serve as the seed of
the ATP conformance test-vector suite.

Cryptography:
  - Hash:      SHA-256 (hashlib)
  - Signature: Ed25519 (RFC 8032 reference implementation, inlined below so the
               vector is reproducible with zero third-party dependencies)

This is a *reference* for byte-exactness, not a performance implementation.
"""

import argparse
import hashlib
import json
import math
import os
import struct

# ---------------------------------------------------------------------------
# Ed25519 (RFC 8032) reference implementation — inlined, no dependencies.
# ---------------------------------------------------------------------------
_b = 256
_q = 2 ** 255 - 19
_L = 2 ** 252 + 27742317777372353535851937790883648493


def _H(m):
    return hashlib.sha512(m).digest()


def _inv(x):
    return pow(x, _q - 2, _q)


_d = (-121665 * _inv(121666)) % _q
_I = pow(2, (_q - 1) // 4, _q)


def _xrecover(y):
    xx = (y * y - 1) * _inv(_d * y * y + 1)
    x = pow(xx, (_q + 3) // 8, _q)
    if (x * x - xx) % _q != 0:
        x = (x * _I) % _q
    if x % 2 != 0:
        x = _q - x
    return x


_By = (4 * _inv(5)) % _q
_Bx = _xrecover(_By)
_B = [_Bx % _q, _By % _q]


def _edwards(P, Q):
    x1, y1 = P
    x2, y2 = Q
    x3 = (x1 * y2 + x2 * y1) * _inv(1 + _d * x1 * x2 * y1 * y2)
    y3 = (y1 * y2 + x1 * x2) * _inv(1 - _d * x1 * x2 * y1 * y2)
    return [x3 % _q, y3 % _q]


def _scalarmult(P, e):
    if e == 0:
        return [0, 1]
    Q = _scalarmult(P, e // 2)
    Q = _edwards(Q, Q)
    if e & 1:
        Q = _edwards(Q, P)
    return Q


def _encodeint(y):
    bits = [(y >> i) & 1 for i in range(_b)]
    return bytes(sum(bits[i * 8 + j] << j for j in range(8)) for i in range(_b // 8))


def _encodepoint(P):
    x, y = P
    bits = [(y >> i) & 1 for i in range(_b - 1)] + [x & 1]
    return bytes(sum(bits[i * 8 + j] << j for j in range(8)) for i in range(_b // 8))


def _bit(h, i):
    return (h[i // 8] >> (i % 8)) & 1


def ed25519_publickey(sk):
    assert len(sk) == 32
    h = _H(sk)
    a = 2 ** (_b - 2) + sum(2 ** i * _bit(h, i) for i in range(3, _b - 2))
    A = _scalarmult(_B, a)
    return _encodepoint(A)


def ed25519_sign(sk, m):
    assert len(sk) == 32
    h = _H(sk)
    a = 2 ** (_b - 2) + sum(2 ** i * _bit(h, i) for i in range(3, _b - 2))
    r = int.from_bytes(_H(h[_b // 8:_b // 4] + m), "little")
    R = _scalarmult(_B, r)
    A = _encodepoint(_scalarmult(_B, a))
    k = int.from_bytes(_H(_encodepoint(R) + A + m), "little")
    S = (r + k * a) % _L
    return _encodepoint(R) + _encodeint(S)


# ---------------------------------------------------------------------------
# ATP-0001 primitive encoders
# ---------------------------------------------------------------------------
def uvarint(n: int) -> bytes:
    """Unsigned LEB128, minimal (canonical) form. ATP-0001 s6.2."""
    if n < 0 or n > (1 << 64) - 1:
        raise ValueError("uvarint requires 0 <= n <= 2^64-1")
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def zigzag(n: int) -> int:
    """ZigZag map signed -> unsigned. ATP-0001 s6.2."""
    return (n << 1) ^ (n >> 63) if n < 0 else (n << 1)


def svarint(n: int) -> bytes:
    if not (-(1 << 63) <= n <= (1 << 63) - 1):
        raise ValueError("svarint requires an I64 value")
    return uvarint(zigzag(n) & ((1 << 64) - 1))


def u16be(n):
    return struct.pack(">H", n)


def u32be(n):
    return struct.pack(">I", n)


def u64be(n):
    return struct.pack(">Q", n)


def lp_string(s: str) -> bytes:
    """Length-prefixed UTF-8: uvarint(len) || bytes. ATP-0001 s6.2."""
    raw = s.encode("utf-8")
    return uvarint(len(raw)) + raw


def sha256(b: bytes) -> bytes:
    return hashlib.sha256(b).digest()


# ---------------------------------------------------------------------------
# Deterministic CBOR (RFC 8949 s4.2.1 core deterministic) — minimal subset:
# unsigned ints, text strings, byte strings, arrays, maps, bool.
# Sufficient for ATP schema manifests. Map keys emitted in ascending encoded-
# byte order; for the small unsigned-int keys used here that is numeric order.
# ---------------------------------------------------------------------------
def _cbor_head(major, n):
    if n < 24:
        return bytes([(major << 5) | n])
    elif n < 0x100:
        return bytes([(major << 5) | 24, n])
    elif n < 0x10000:
        return bytes([(major << 5) | 25]) + struct.pack(">H", n)
    elif n < 0x100000000:
        return bytes([(major << 5) | 26]) + struct.pack(">I", n)
    else:
        return bytes([(major << 5) | 27]) + struct.pack(">Q", n)


def cbor(v) -> bytes:
    if isinstance(v, bool):
        return b"\xf5" if v else b"\xf4"
    if isinstance(v, int):
        if v < 0:
            return _cbor_head(1, -1 - v)
        return _cbor_head(0, v)
    if isinstance(v, str):
        raw = v.encode("utf-8")
        return _cbor_head(3, len(raw)) + raw
    if isinstance(v, bytes):
        return _cbor_head(2, len(v)) + v
    if isinstance(v, list):
        return _cbor_head(4, len(v)) + b"".join(cbor(x) for x in v)
    if isinstance(v, dict):
        items = [(cbor(k), cbor(val)) for k, val in v.items()]
        # RFC 8949 s4.2.1: sort by bytewise lexicographic order of encoded key.
        items.sort(key=lambda kv: kv[0])
        return _cbor_head(5, len(items)) + b"".join(k + val for k, val in items)
    raise TypeError(f"uncborable: {type(v)}")


# ATP wire value types (ATP-0001 s6.3, Table)
T_BOOL, T_U32, T_U64, T_I32, T_I64 = 0, 1, 2, 3, 4
T_F32, T_F64, T_ENUM, T_STRING, T_BYTES = 5, 6, 7, 8, 9
T_TIMESTAMP_MS, T_ENTITY_REF, T_OPAQUE_REF, T_DURATION_MS = 10, 11, 12, 13

# Primitive codes (ATP-0001 s3)
P_TRANSITION, P_OBSERVATION, P_RELATION, P_STATE_CHECKPOINT = 0, 1, 2, 3

# Domain-separation constants (ATP-0001 s5.2 / s7.4 / s11.2)
D_BATCH = b"ATP/0.1/batch-root"
D_MANIFEST = b"ATP/0.1/schema-manifest"
D_CHECKPOINT = b"ATP/0.1/chain-head-checkpoint"
# Merkle uses RFC 6962 single-byte domain tags 0x00 (leaf) / 0x01 (node).


def schema_digest(manifest: dict) -> bytes:
    """H_S = SHA256(D_MANIFEST || CanonicalCBOR(manifest)). ATP-0001 s5.2."""
    return sha256(D_MANIFEST + cbor(manifest))


def merkle_root(leaves):
    """RFC 6962 Merkle Tree Hash over record byte-strings. ATP-0001 s7.3."""
    n = len(leaves)
    if n == 0:
        raise ValueError("ATP batches MUST contain >= 1 record")
    if n == 1:
        return sha256(b"\x00" + leaves[0])
    k = 1
    while k < n:
        k <<= 1
    k >>= 1  # largest power of two < n
    return sha256(b"\x01" + merkle_root(leaves[:k]) + merkle_root(leaves[k:]))


# ---------------------------------------------------------------------------
# Field / record model
# ---------------------------------------------------------------------------
class Field:
    def __init__(self, slot, name, typ, required, unit=None, enum_ref=None,
                 constraints=None):
        self.slot, self.name, self.typ = slot, name, typ
        self.required, self.unit, self.enum_ref = required, unit, enum_ref
        self.constraints = constraints

    def manifest_map(self):
        m = {1: self.slot, 2: self.name, 3: self.typ, 4: self.required}
        if self.unit is not None:
            m[5] = self.unit
        if self.enum_ref is not None:
            m[6] = self.enum_ref
        if self.constraints is not None:
            m[7] = self.constraints
        return m


class Schema:
    def __init__(self, name, version, primitive, publisher, fields, enums=None):
        self.name, self.version, self.primitive = name, version, primitive
        self.publisher = publisher
        self.fields = sorted(fields, key=lambda f: f.slot)
        self.enums = enums or {}

    def manifest(self):
        return {
            1: self.name,
            2: self.version,
            3: self.primitive,
            4: self.publisher,
            5: [f.manifest_map() for f in self.fields],
            6: {k: v for k, v in self.enums.items()},
        }

    def digest(self):
        return schema_digest(self.manifest())

    def optional_fields(self):
        return [f for f in self.fields if not f.required]


def encode_value(field: Schema, typ, value):
    if typ == T_BOOL:
        return b"\x01" if value else b"\x00"
    if typ == T_U32:
        if not (0 <= value <= (1 << 32) - 1):
            raise ValueError("U32 out of range")
        return uvarint(value)
    if typ in (T_U64, T_DURATION_MS):
        return uvarint(value)
    if typ == T_I32:
        if not (-(1 << 31) <= value <= (1 << 31) - 1):
            raise ValueError("I32 out of range")
        return svarint(value)
    if typ in (T_I64, T_TIMESTAMP_MS):
        return svarint(value)
    if typ == T_ENUM:
        return uvarint(value)  # value == ordinal in the field's enum dictionary
    if typ == T_ENTITY_REF:
        return uvarint(value)
    if typ == T_F32:
        if math.isnan(value):
            return bytes.fromhex("7fc00000")
        return struct.pack(">f", value)
    if typ == T_F64:
        if math.isnan(value):
            return bytes.fromhex("7ff8000000000000")
        return struct.pack(">d", value)
    if typ == T_STRING:
        return lp_string(value)
    if typ in (T_BYTES, T_OPAQUE_REF):
        return uvarint(len(value)) + value
    raise NotImplementedError(typ)


def encode_record(schema: Schema, schema_ref, entity_ref, time_delta, values: dict):
    """
    values: {slot: value} for all required slots + present optional slots.
    Returns the canonical record byte string. ATP-0001 s6.4.
    """
    opt = schema.optional_fields()
    nbytes = (len(opt) + 7) // 8
    bitmap = bytearray(nbytes)
    for j, f in enumerate(opt):
        if f.slot in values:
            bitmap[j >> 3] |= 1 << (j & 7)
    body = bytearray()
    for f in schema.fields:  # ascending slot order
        present = f.required or (f.slot in values)
        if not present:
            continue
        if f.slot not in values:
            raise ValueError(f"required slot {f.slot} missing")
        body += encode_value(schema, f.typ, values[f.slot])
    return uvarint(schema_ref) + uvarint(entity_ref) + uvarint(time_delta) + bytes(bitmap) + bytes(body)


def build_batch(*, protocol_version, producer_id, boot_epoch, first_sequence,
                base_time, clock_source, clock_skew_ms, schema_digests,
                entity_delta, previous_root, records, sk):
    record_count = len(records)
    mroot = merkle_root(records)

    schema_dict = uvarint(len(schema_digests)) + b"".join(schema_digests)
    entity_dict = uvarint(len(entity_delta)) + b"".join(lp_string(e) for e in entity_delta)
    clock_quality = bytes([clock_source]) + u32be(clock_skew_ms)

    preimage = (
        D_BATCH
        + u16be(protocol_version)
        + producer_id
        + u64be(boot_epoch)
        + u64be(first_sequence)
        + u32be(record_count)
        + u64be(base_time)
        + clock_quality
        + sha256(schema_dict)
        + sha256(entity_dict)
        + previous_root
        + mroot
    )
    batch_root = sha256(preimage)
    signature = ed25519_sign(sk, batch_root)
    return {
        "merkle_root": mroot,
        "schema_dict": schema_dict,
        "entity_dict": entity_dict,
        "clock_quality": clock_quality,
        "batch_root_preimage": preimage,
        "batch_root": batch_root,
        "signature": signature,
    }


def h(b):
    return b.hex()


# ---------------------------------------------------------------------------
# The canonical worked example (also ATP conformance vector CV-CORE-001)
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    # Fixed Ed25519 test seed (NEVER use in production).
    sk = bytes.fromhex("9d61b19deffe5a60651c9e0d0e6c1e6b" "f0a1b2c3d4e5f60718293a4b5c6d7e8f")
    pk = ed25519_publickey(sk)

    POD_STATE = ["Pending", "Running", "Succeeded", "Failed", "Unknown"]
    schema = Schema(
        name="k8s.pod.transition",
        version="1.0.0",
        primitive=P_TRANSITION,
        publisher="openkedge.io/k8s",
        fields=[
            Field(0, "old_state", T_ENUM, True, enum_ref="pod_phase"),
            Field(1, "new_state", T_ENUM, True, enum_ref="pod_phase"),
            Field(2, "exit_code", T_I32, False),
            Field(3, "reason", T_STRING, False),
        ],
        enums={"pod_phase": POD_STATE},
    )
    sdig = schema.digest()

    # entity alias space (delta for this genesis batch)
    entities = [
        "k8s:pod:prod-us-east-1/pay-7d9b",
        "k8s:pod:prod-us-east-1/auth-4a2c",
    ]

    RUNNING, FAILED = 1, 3
    rec0 = encode_record(schema, 0, 0, 10, {0: RUNNING, 1: FAILED, 2: 137})
    rec1 = encode_record(schema, 0, 1, 15, {0: RUNNING, 1: FAILED, 2: 137})
    records = [rec0, rec1]

    producer_id = bytes.fromhex("00112233445566778899aabbccddeeff")
    signing_key_id = bytes.fromhex("a1b2c3d4e5f60708")

    batch = build_batch(
        protocol_version=0x0001,
        producer_id=producer_id,
        boot_epoch=7,
        first_sequence=1000,
        base_time=1_760_000_000_000,
        clock_source=1,          # NTP
        clock_skew_ms=50,
        schema_digests=[sdig],
        entity_delta=entities,
        previous_root=b"\x00" * 32,
        records=records,
        sk=sk,
    )

    manifest_cbor = cbor(schema.manifest())

    # Field 11 wire framing: each record self-delimited by uvarint(len)||bytes.
    # (Merkle leaves are the UNFRAMED record bytes, so this does not affect
    #  merkle_root or batch_root.)
    encoded_records = b"".join(uvarint(len(r)) + r for r in records)
    batch_wire = (
        u16be(0x0001)
        + producer_id
        + u64be(7)
        + u64be(1000)
        + u32be(len(records))
        + u64be(1_760_000_000_000)
        + batch["clock_quality"]
        + batch["schema_dict"]
        + batch["entity_dict"]
        + (b"\x00" * 32)
        + encoded_records
        + batch["merkle_root"]
        + signing_key_id
        + batch["signature"]
    )

    vec = {
        "vector_id": "CV-CORE-001",
        "description": "ATP-0001 canonical worked example: genesis batch, "
                       "one k8s.pod.transition schema, two Transition records.",
        "crypto": {"hash": "SHA-256", "signature": "Ed25519"},
        "ed25519_private_seed_test_only": h(sk),
        "schema_manifest_canonical_cbor": h(manifest_cbor),
        "schema_digest_sha256": h(sdig),
        "entity_aliases": entities,
        "records": [
            {"record_index": 0, "sequence": 1000, "bytes": h(rec0)},
            {"record_index": 1, "sequence": 1001, "bytes": h(rec1)},
        ],
        "merkle_root": h(batch["merkle_root"]),
        "batch_header": {
            "protocol_version": "0x0001",
            "producer_id": h(producer_id),
            "boot_epoch": 7,
            "first_sequence": 1000,
            "record_count": 2,
            "base_time_unix_ms": 1_760_000_000_000,
            "clock_quality_bytes": h(batch["clock_quality"]),
            "schema_dictionary_bytes": h(batch["schema_dict"]),
            "entity_dictionary_delta_bytes": h(batch["entity_dict"]),
            "previous_root": h(b"\x00" * 32),
            "encoded_records_bytes": h(encoded_records),
            "signing_key_id": h(signing_key_id),
        },
        "H_schema_dictionary": h(sha256(batch["schema_dict"])),
        "H_entity_dictionary_delta": h(sha256(batch["entity_dict"])),
        "batch_root_preimage": h(batch["batch_root_preimage"]),
        "batch_root": h(batch["batch_root"]),
        "ed25519_public_key": h(pk),
        "signature": h(batch["signature"]),
        "batch_wire": h(batch_wire),
    }
    rendered = json.dumps(vec, indent=2) + "\n"
    outdir = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(outdir, "CV-CORE-001.json")
    if args.check:
        with open(path, encoding="utf-8") as existing:
            if existing.read() != rendered:
                raise SystemExit("CV-CORE-001.json is stale")
        print("CV-CORE-001.json is current")
        return
    with open(path, "w", encoding="utf-8") as output:
        output.write(rendered)
    print(rendered, end="")


if __name__ == "__main__":
    main()
