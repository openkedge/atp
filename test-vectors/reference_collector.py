#!/usr/bin/env python3
"""
ATP-0001 reference Collector + read-time Verifier.

This is the executable form of ATP-0001 §7 (wire framing), §9 (collector
acceptance state machine), §10 (coverage classification), and §11 (chain-head
checkpoints). It is what an implementer's collector is checked against by the
conformance vectors in generate_conformance_vectors.py.

Dependency-free; reuses primitives from generate_vectors.py (the ATP-0001
reference generator). Correctness over speed (pure-Python Ed25519).
"""

from functools import lru_cache

import generate_vectors as g

# ---- Error codes (ATP-0001 Appendix C) ------------------------------------
E_MALFORMED_BATCH        = "ATP_ERR_MALFORMED_BATCH"
E_UNSUPPORTED_VERSION    = "ATP_ERR_UNSUPPORTED_VERSION"
E_UNKNOWN_PRODUCER       = "ATP_ERR_UNKNOWN_PRODUCER"
E_UNKNOWN_KEY            = "ATP_ERR_UNKNOWN_KEY"
E_INVALID_SIGNATURE      = "ATP_ERR_INVALID_SIGNATURE"
E_SCHEMA_UNKNOWN         = "ATP_ERR_SCHEMA_UNKNOWN"
E_SCHEMA_UNAUTHORIZED    = "ATP_ERR_SCHEMA_UNAUTHORIZED"
E_PREVIOUS_ROOT_MISMATCH = "ATP_ERR_PREVIOUS_ROOT_MISMATCH"
E_SEQUENCE_GAP           = "ATP_ERR_SEQUENCE_GAP"
E_EPOCH_REUSE            = "ATP_ERR_EPOCH_REUSE"
E_MERKLE_MISMATCH        = "ATP_ERR_MERKLE_MISMATCH"
E_MALFORMED_RECORD       = "ATP_ERR_MALFORMED_RECORD"
E_SCHEMA_VIOLATION       = "ATP_ERR_SCHEMA_VIOLATION"
E_COMMIT_FAILED          = "ATP_ERR_COMMIT_FAILED"
E_OPAQUE_DIGEST_MISMATCH = "ATP_ERR_OPAQUE_DIGEST_MISMATCH"
E_OPAQUE_LENGTH_MISMATCH = "ATP_ERR_OPAQUE_LENGTH_MISMATCH"

ZERO32 = b"\x00" * 32
PROTOCOL_VERSION_V0_1 = 0x0001

MAX_BATCH_BYTES = 16 * 1024 * 1024
MAX_RECORDS_PER_BATCH = 65_535
MAX_RECORD_BYTES = 65_535
MAX_SCHEMA_DICTIONARY_ENTRIES = 65_535
MAX_ENTITY_DICTIONARY_DELTA_ENTRIES = 65_535
MAX_ENTITY_ID_BYTES = 1_024
MAX_OPAQUE_REF_BYTES = 4_096
MAX_OPAQUE_ID_BYTES = 128
MAX_MEDIA_TYPE_BYTES = 127
MAX_STORAGE_URI_BYTES = 2_048

# numeric / length type classes (ATP-0002 §4.2)
NUMERIC = {1, 2, 3, 4, 5, 6, 10, 13}
LEN_TYPES = {8, 9}
DEFAULT_MAX_LEN = 1024
HARD_MAX_LEN = 4096


class MalformedBatch(Exception):
    pass


class SchemaViolation(Exception):
    pass


# ---------------------------------------------------------------------------
# Ed25519 verify (RFC 8032) — reuses field/group ops from generate_vectors.
# ---------------------------------------------------------------------------
@lru_cache(maxsize=512)
def _decodepoint(s):
    if len(s) != 32:
        raise ValueError("bad point length")
    q = g._q
    y = int.from_bytes(s, "little") & ((1 << (g._b - 1)) - 1)
    if y >= q:
        raise ValueError("non-canonical point")
    x = g._xrecover(y)
    if (x & 1) != ((s[-1] >> 7) & 1):
        x = q - x
    P = [x, y]
    if (-x * x + y * y - 1 - g._d * x * x * y * y) % q != 0:
        raise ValueError("point not on curve")
    if g._encodepoint(P) != s:
        raise ValueError("non-canonical point encoding")
    identity = [0, 1]
    if g._scalarmult(P, 8) == identity or g._scalarmult(P, g._L) != identity:
        raise ValueError("point not in prime-order subgroup")
    return tuple(P)


def ed25519_verify(pk, msg, sig):
    if len(sig) != 64 or len(pk) != 32:
        return False
    try:
        R = _decodepoint(sig[:32])
        A = _decodepoint(pk)
    except ValueError:
        return False
    S = int.from_bytes(sig[32:], "little")
    if S >= g._L:
        return False
    hh = int.from_bytes(g._H(sig[:32] + pk + msg), "little")
    v1 = g._scalarmult(g._B, S)
    v2 = g._edwards(R, g._scalarmult(A, hh))
    q = g._q
    return v1[0] % q == v2[0] % q and v1[1] % q == v2[1] % q


# ---------------------------------------------------------------------------
# Wire serialization / parsing (ATP-0001 §7.1)
# ---------------------------------------------------------------------------
def serialize_batch(b):
    """b: dict with keys protocol_version, producer_id, boot_epoch,
    first_sequence, base_time, clock_quality(5 bytes), schema_digests(list),
    entity_delta(list[str]), previous_root(32), records(list[bytes]),
    merkle_root(32), signing_key_id(8), signature(64)."""
    schema_dict = g.uvarint(len(b["schema_digests"])) + b"".join(b["schema_digests"])
    entity_dict = g.uvarint(len(b["entity_delta"])) + b"".join(g.lp_string(e) for e in b["entity_delta"])
    enc_records = b"".join(g.uvarint(len(r)) + r for r in b["records"])
    return (
        g.u16be(b["protocol_version"])
        + b["producer_id"]
        + g.u64be(b["boot_epoch"])
        + g.u64be(b["first_sequence"])
        + g.u32be(len(b["records"]))
        + g.u64be(b["base_time"])
        + b["clock_quality"]
        + schema_dict
        + entity_dict
        + b["previous_root"]
        + enc_records
        + b["merkle_root"]
        + b["signing_key_id"]
        + b["signature"]
    )


class _Reader:
    def __init__(self, buf):
        self.b, self.i = buf, 0

    def take(self, n):
        if n < 0 or self.i > len(self.b) - n:
            raise MalformedBatch("truncated")
        s = self.b[self.i:self.i + n]
        self.i += n
        return s

    def uvarint(self):
        val = 0
        start = self.i
        for index in range(10):
            byte = self.take(1)[0]
            if index == 9 and byte > 1:
                raise MalformedBatch("uvarint overflow")
            val |= (byte & 0x7F) << (7 * index)
            if not (byte & 0x80):
                break
        else:
            raise MalformedBatch("uvarint too long")
        consumed = self.b[start:self.i]
        if g.uvarint(val) != consumed:          # minimality check (ATP-0001 §6.2)
            raise MalformedBatch("non-minimal uvarint")
        return val

    def eof(self):
        return self.i == len(self.b)


def parse_batch(wire):
    if len(wire) > MAX_BATCH_BYTES:
        raise MalformedBatch("batch too large")
    r = _Reader(wire)
    pb = {}
    pb["protocol_version"] = int.from_bytes(r.take(2), "big")
    pb["producer_id"] = r.take(16)
    pb["boot_epoch"] = int.from_bytes(r.take(8), "big")
    pb["first_sequence"] = int.from_bytes(r.take(8), "big")
    pb["record_count"] = int.from_bytes(r.take(4), "big")
    if not (1 <= pb["record_count"] <= MAX_RECORDS_PER_BATCH):
        raise MalformedBatch("record count out of range")
    if pb["first_sequence"] + pb["record_count"] > (1 << 64) - 1:
        raise MalformedBatch("sequence range overflow")
    pb["base_time"] = int.from_bytes(r.take(8), "big")
    pb["clock_quality"] = r.take(5)
    source = pb["clock_quality"][0]
    max_skew = int.from_bytes(pb["clock_quality"][1:], "big")
    if source > 4 or (source == 0 and max_skew != 0xFFFFFFFF):
        raise MalformedBatch("invalid clock quality")
    # field 8 schema_dictionary
    sd_start = r.i
    n = r.uvarint()
    if not (1 <= n <= MAX_SCHEMA_DICTIONARY_ENTRIES):
        raise MalformedBatch("schema dictionary count out of range")
    pb["schema_digests"] = [r.take(32) for _ in range(n)]
    if len(set(pb["schema_digests"])) != len(pb["schema_digests"]):
        raise MalformedBatch("duplicate schema digest")
    pb["schema_dict_raw"] = wire[sd_start:r.i]
    # field 9 entity_dictionary_delta
    ed_start = r.i
    m = r.uvarint()
    if m > MAX_ENTITY_DICTIONARY_DELTA_ENTRIES:
        raise MalformedBatch("entity dictionary count out of range")
    ents = []
    for _ in range(m):
        ln = r.uvarint()
        if not (1 <= ln <= MAX_ENTITY_ID_BYTES):
            raise MalformedBatch("entity id length out of range")
        entity_id = r.take(ln).decode("ascii", "strict")
        if not valid_entity_id(entity_id):
            raise MalformedBatch("invalid entity id")
        ents.append(entity_id)
    if len(set(ents)) != len(ents):
        raise MalformedBatch("duplicate entity id")
    pb["entity_delta"] = ents
    pb["entity_dict_raw"] = wire[ed_start:r.i]
    pb["previous_root"] = r.take(32)
    # field 11 encoded_records
    recs = []
    for _ in range(pb["record_count"]):
        ln = r.uvarint()
        if not (1 <= ln <= MAX_RECORD_BYTES):
            raise MalformedBatch("record length out of range")
        recs.append(r.take(ln))
    pb["records"] = recs
    pb["merkle_root"] = r.take(32)
    pb["signing_key_id"] = r.take(8)
    pb["signature"] = r.take(64)
    if not r.eof():
        raise MalformedBatch("trailing bytes")
    return pb


def valid_entity_id(entity_id):
    if not entity_id or len(entity_id.encode("ascii", "ignore")) != len(entity_id):
        return False
    if len(entity_id) > MAX_ENTITY_ID_BYTES:
        return False
    parts = entity_id.split(":", 2)
    if len(parts) != 3:
        return False
    namespace, resource_type, identifier = parts

    def component(s):
        return bool(s) and s[0].isalnum() and all(c.isalnum() or c in "._-" for c in s)

    if not component(namespace) or not component(resource_type) or not identifier:
        return False
    decoded = bytearray()
    i = 0
    while i < len(identifier):
        c = identifier[i]
        if not (0x21 <= ord(c) <= 0x7E):
            return False
        if c == "%":
            if i + 2 >= len(identifier):
                return False
            pair = identifier[i + 1:i + 3]
            if any(ch not in "0123456789ABCDEF" for ch in pair):
                return False
            value = int(pair, 16)
            if value < 0x80 and value != ord("%"):
                return False
            decoded.append(value)
            i += 3
        else:
            decoded.append(ord(c))
            i += 1
    try:
        decoded.decode("utf-8", "strict")
    except UnicodeDecodeError:
        return False
    return True


def compute_batch_root(pb):
    """ATP-0001 §7.4, over PARSED/claimed fields (incl. claimed merkle_root)."""
    pre = (
        g.D_BATCH
        + g.u16be(pb["protocol_version"])
        + pb["producer_id"]
        + g.u64be(pb["boot_epoch"])
        + g.u64be(pb["first_sequence"])
        + g.u32be(pb["record_count"])
        + g.u64be(pb["base_time"])
        + pb["clock_quality"]
        + g.sha256(pb["schema_dict_raw"])
        + g.sha256(pb["entity_dict_raw"])
        + pb["previous_root"]
        + pb["merkle_root"]
    )
    return g.sha256(pre)


# ---------------------------------------------------------------------------
# Record validation (ATP-0001 §9 stage 12)
# ---------------------------------------------------------------------------
def _read_value(r, typ, field, enums):
    if typ == 0:      # BOOL
        v = r.take(1)[0]
        if v not in (0, 1):
            raise MalformedBatch("bad bool")
        return v
    if typ in (1, 2, 13):   # U32/U64/DURATION_MS
        return r.uvarint()
    if typ in (3, 4, 10):   # I32/I64/TIMESTAMP_MS (zigzag)
        u = r.uvarint()
        return (u >> 1) ^ -(u & 1)
    if typ == 5:            # F32
        import struct
        raw = r.take(4)
        value = struct.unpack(">f", raw)[0]
        if value != value and raw != bytes.fromhex("7fc00000"):
            raise MalformedBatch("non-canonical f32 NaN")
        return value
    if typ == 6:            # F64
        import struct
        raw = r.take(8)
        value = struct.unpack(">d", raw)[0]
        if value != value and raw != bytes.fromhex("7ff8000000000000"):
            raise MalformedBatch("non-canonical f64 NaN")
        return value
    if typ == 7:            # ENUM
        return ("enum", r.uvarint())
    if typ in (8, 9):       # STRING / BYTES
        ln = r.uvarint()
        raw = r.take(ln)
        if typ == 8:
            raw.decode("utf-8", "strict")
        return ("len", ln, raw)
    if typ == 11:           # ENTITY_REF
        return ("entity", r.uvarint())
    if typ == 12:           # OPAQUE_REF
        ln = r.uvarint()
        raw = r.take(ln)
        validate_opaque_ref(raw)
        return ("opaque", raw)
    raise MalformedBatch(f"unknown type {typ}")


def validate_record(manifest, rec_bytes, entity_alias_count):
    """Returns (ok, error_code_or_None)."""
    if not (1 <= len(rec_bytes) <= MAX_RECORD_BYTES):
        return False, E_MALFORMED_RECORD
    fields = manifest[5]
    enums = manifest.get(6, {})
    optional = [f for f in fields if not f[4]]
    r = _Reader(rec_bytes)
    try:
        _schema_ref = r.uvarint()
        entity_ref = r.uvarint()
        _time_delta = r.uvarint()
        nbytes = (len(optional) + 7) // 8
        bitmap = r.take(nbytes)
        # trailing padding bits must be zero (ATP-0001 §6.5)
        if len(optional) % 8 != 0:
            used = len(optional) - (len(optional) // 8) * 8
            if bitmap[-1] >> used:
                return False, E_MALFORMED_RECORD
    except MalformedBatch:
        return False, E_MALFORMED_RECORD

    if entity_ref >= entity_alias_count:
        return False, E_MALFORMED_RECORD

    def present(j):
        return bool(bitmap[j >> 3] >> (j & 7) & 1)

    opt_index = {f[1]: k for k, f in enumerate(optional)}
    try:
        for f in fields:                       # ascending slot order
            slot, name, typ, req = f[1], f[2], f[3], f[4]
            if not req and not present(opt_index[slot]):
                continue
            val = _read_value(r, typ, f, enums)
            # constraint / semantic checks
            if typ == 1 and val > (1 << 32) - 1:
                return False, E_SCHEMA_VIOLATION
            if typ == 3 and not (-(1 << 31) <= val <= (1 << 31) - 1):
                return False, E_SCHEMA_VIOLATION
            if typ == 7:  # ENUM ordinal range
                _, ordv = val
                members = enums.get(f.get(6), [])
                if ordv >= len(members):
                    return False, E_SCHEMA_VIOLATION
            if typ in LEN_TYPES:
                _, ln, _raw = val
                cons = f.get(7, {})
                maxlen = cons.get(1, DEFAULT_MAX_LEN)
                if ln > min(maxlen, HARD_MAX_LEN):
                    return False, E_SCHEMA_VIOLATION
                if name == "intent_ref" and (typ != 9 or ln != 32):
                    return False, E_SCHEMA_VIOLATION
            if typ == 11:
                _, alias = val
                if alias >= entity_alias_count:
                    return False, E_MALFORMED_RECORD
            if typ in NUMERIC and isinstance(val, (int, float)):
                cons = f.get(7, {})
                if isinstance(val, float) and val != val and (2 in cons or 3 in cons):
                    return False, E_SCHEMA_VIOLATION
                if 2 in cons and val < cons[2]:
                    return False, E_SCHEMA_VIOLATION
                if 3 in cons and val > cons[3]:
                    return False, E_SCHEMA_VIOLATION
    except SchemaViolation:
        return False, E_SCHEMA_VIOLATION
    except (MalformedBatch, UnicodeDecodeError):
        return False, E_MALFORMED_RECORD
    if not r.eof():
        return False, E_MALFORMED_RECORD
    return True, None


def _bounded_text(r, minimum, maximum):
    ln = r.uvarint()
    if not (minimum <= ln <= maximum):
        raise SchemaViolation("bounded text length")
    return r.take(ln).decode("utf-8", "strict")


def validate_opaque_ref(raw):
    if not (1 <= len(raw) <= MAX_OPAQUE_REF_BYTES):
        raise SchemaViolation("opaque ref length")
    r = _Reader(raw)
    try:
        opaque_id = _bounded_text(r, 1, MAX_OPAQUE_ID_BYTES)
        if not opaque_id.isascii() or any(not (0x21 <= ord(c) <= 0x7E) for c in opaque_id):
            raise SchemaViolation("opaque id")

        media_type = _bounded_text(r, 3, MAX_MEDIA_TYPE_BYTES)
        parts = media_type.split("/")
        token_chars = set("!#$&^_.+-")

        def token(s):
            return bool(s) and all(
                c.isascii() and (c.isalnum() or c in token_chars) for c in s)

        if len(parts) != 2 or not all(token(p) for p in parts) or media_type.lower() != media_type:
            raise SchemaViolation("media type")

        r.uvarint()  # byte_length
        r.take(32)   # payload_digest
        storage_uri = _bounded_text(r, 1, MAX_STORAGE_URI_BYTES)
        if not storage_uri.isascii() or any(not (0x21 <= ord(c) <= 0x7E) for c in storage_uri):
            raise SchemaViolation("storage uri")
        if ":" not in storage_uri:
            raise SchemaViolation("storage uri scheme")
        scheme = storage_uri.split(":", 1)[0]
        if not scheme or not scheme[0].islower() or not scheme[0].isalpha() \
                or any(not (c.islower() or c.isdigit() or c in "+-.") for c in scheme):
            raise SchemaViolation("storage uri scheme")
        if r.uvarint() > (1 << 32) - 1 or not r.eof():
            raise SchemaViolation("retention class")
    except (MalformedBatch, UnicodeDecodeError) as exc:
        raise MalformedBatch("malformed opaque ref") from exc
    return True


# ---------------------------------------------------------------------------
# Collector (ATP-0001 §9)
# ---------------------------------------------------------------------------
class Collector:
    def __init__(self):
        self.producers = {}    # producer_id -> {keys:{kid:pk}, authorized:set(digests)}
        self.schemas = {}      # H_S -> manifest
        # chain state: producer_id -> {"highest_epoch":int,
        #   epochs:{epoch:{"next_seq":int, "head_root":bytes, "entity_count":int,
        #                  "entities":set[str],
        #                  "committed":{first_sequence:(root,count,wire_bytes)}}}}
        self.state = {}
        self.ledger = {}       # (producer_id, epoch) -> list[wire bytes] in seq order

    def register_producer(self, pid, kid, pk, authorized):
        if pid in self.producers:
            raise ValueError("producer already registered")
        self.producers[pid] = {"keys": {kid: pk}, "authorized": set(authorized)}
        self.state.setdefault(pid, {"highest_epoch": -1, "epochs": {}})

    def register_producer_key(self, pid, kid, pk):
        producer = self.producers.get(pid)
        if producer is None:
            raise ValueError("unknown producer")
        existing = producer["keys"].get(kid)
        if existing is not None and existing != pk:
            raise ValueError("signing key id collision")
        if any(existing_kid != kid and existing_pk == pk
               for existing_kid, existing_pk in producer["keys"].items()):
            raise ValueError("producer public key already has a different key id")
        producer["keys"][kid] = pk

    def authorize_schema(self, pid, schema_digest):
        producer = self.producers.get(pid)
        if producer is None:
            raise ValueError("unknown producer")
        producer["authorized"].add(schema_digest)

    def register_schema(self, manifest):
        self.schemas[g.schema_digest(manifest)] = manifest

    def accept(self, wire):
        """Returns (status, code, info). status in ACCEPT / ACCEPT_IDEMPOTENT / REJECT."""
        # Step 1: framing
        try:
            pb = parse_batch(wire)
        except (MalformedBatch, UnicodeDecodeError):
            return ("REJECT", E_MALFORMED_BATCH, None)
        # Step 2: this reference implementation supports exactly ATP v0.1.
        if pb["protocol_version"] != PROTOCOL_VERSION_V0_1:
            return ("REJECT", E_UNSUPPORTED_VERSION, None)
        # Step 3: producer identity
        prod = self.producers.get(pb["producer_id"])
        if prod is None:
            return ("REJECT", E_UNKNOWN_PRODUCER, None)
        # Step 4: signing key
        pk = prod["keys"].get(pb["signing_key_id"])
        if pk is None:
            return ("REJECT", E_UNKNOWN_KEY, None)
        # Step 5: signature over recomputed batch_root
        broot = compute_batch_root(pb)
        if not ed25519_verify(pk, broot, pb["signature"]):
            return ("REJECT", E_INVALID_SIGNATURE, None)

        st = self.state[pb["producer_id"]]
        epoch = pb["boot_epoch"]
        ep = st["epochs"].get(epoch)

        # Step 6: only byte-identical retransmissions are idempotent.
        if ep is not None:
            prior = ep["committed"].get(pb["first_sequence"])
            if prior is not None:
                if prior[0] == broot and prior[2] == wire:
                    return ("ACCEPT_IDEMPOTENT", None,
                            {"highest_sequence": ep["next_seq"] - 1,
                             "batch_root": broot})
                return ("REJECT", E_EPOCH_REUSE, None)

        # Step 7: schema digests resolvable + authorized
        for dig in pb["schema_digests"]:
            if dig not in self.schemas:
                return ("REJECT", E_SCHEMA_UNKNOWN, None)
            if dig not in prod["authorized"]:
                return ("REJECT", E_SCHEMA_UNAUTHORIZED, None)

        # Step 8: previous_root continuity
        if ep is None:
            if pb["previous_root"] != ZERO32:
                return ("REJECT", E_PREVIOUS_ROOT_MISMATCH, None)  # genesis must be zero
        else:
            if pb["previous_root"] != ep["head_root"]:
                return ("REJECT", E_PREVIOUS_ROOT_MISMATCH, None)

        # Step 9: epoch & sequence continuity
        # ATP-0001 §9.3: any batch on an epoch below the highest accepted is reuse
        # (the producer has since restarted into a newer epoch).
        if epoch < st["highest_epoch"]:
            return ("REJECT", E_EPOCH_REUSE, None)
        if ep is not None:
            fs = pb["first_sequence"]
            if fs > ep["next_seq"]:
                return ("REJECT", E_SEQUENCE_GAP, None)
            if fs < ep["next_seq"]:
                return ("REJECT", E_EPOCH_REUSE, None)  # rewrite of committed history

        # Steps 10 and 11: entity dictionary continuity, then Merkle root.
        prior_entity_count = 0 if ep is None else ep["entity_count"]
        if ep is not None and any(entity in ep["entities"] for entity in pb["entity_delta"]):
            return ("REJECT", E_MALFORMED_BATCH, None)
        alias_count = prior_entity_count + len(pb["entity_delta"])
        if alias_count > (1 << 64) - 1:
            return ("REJECT", E_MALFORMED_BATCH, None)
        if pb["record_count"] != len(pb["records"]) or pb["record_count"] == 0:
            return ("REJECT", E_MALFORMED_BATCH, None)
        if g.merkle_root(pb["records"]) != pb["merkle_root"]:
            return ("REJECT", E_MERKLE_MISMATCH, None)

        # Step 12: per-record validation
        # map schema_ref alias -> manifest
        for rec in pb["records"]:
            try:
                rr = _Reader(rec)
                sr = rr.uvarint()
                rr.uvarint()  # envelope entity_ref; validate_record checks its range
                time_delta = rr.uvarint()
            except MalformedBatch:
                return ("REJECT", E_MALFORMED_RECORD, None)
            if pb["base_time"] + time_delta > (1 << 64) - 1:
                return ("REJECT", E_MALFORMED_RECORD, None)
            if sr >= len(pb["schema_digests"]):
                return ("REJECT", E_MALFORMED_RECORD, None)
            manifest = self.schemas[pb["schema_digests"][sr]]
            ok, code = validate_record(manifest, rec, alias_count)
            if not ok:
                return ("REJECT", code, None)

        # Step 13: atomic commit. No epoch state is created before this point.
        if ep is None:
            ep = {"next_seq": pb["first_sequence"] + pb["record_count"],
                  "head_root": broot, "entity_count": alias_count,
                  "entities": set(pb["entity_delta"]),
                  "committed": {
                      pb["first_sequence"]: (
                          broot, pb["record_count"], bytes(wire))
                  }}
            st["epochs"][epoch] = ep
        else:
            ep["committed"][pb["first_sequence"]] = (
                broot, pb["record_count"], bytes(wire))
            ep["next_seq"] = pb["first_sequence"] + pb["record_count"]
            ep["head_root"] = broot
            ep["entity_count"] = alias_count
            ep["entities"].update(pb["entity_delta"])
        if epoch > st["highest_epoch"]:
            st["highest_epoch"] = epoch
        self.ledger.setdefault((pb["producer_id"], epoch), []).append(wire)

        # Acknowledge only after commit.
        return ("ACCEPT", None,
                {"highest_sequence": ep["next_seq"] - 1, "batch_root": broot})


# ---------------------------------------------------------------------------
# Chain-head checkpoints (ATP-0001 §11)
# ---------------------------------------------------------------------------
def build_checkpoint(pid, epoch, highest_seq, batch_root, ck_time, ck_seq, ck_kid, ck_sk):
    pre = (g.D_CHECKPOINT + pid + g.u64be(epoch) + g.u64be(highest_seq)
           + batch_root + g.u64be(ck_time) + g.u64be(ck_seq))
    root = g.sha256(pre)
    return {
        "producer_id": pid, "boot_epoch": epoch, "highest_sequence": highest_seq,
        "batch_root": batch_root, "checkpoint_time": ck_time,
        "checkpoint_sequence": ck_seq, "signing_key_id": ck_kid,
        "checkpoint_root": root, "signature": g.ed25519_sign(ck_sk, root),
    }


def serialize_checkpoint(ck):
    """ATP-0001 §11.2 exact 152-byte checkpoint wire form."""
    wire = (
        ck["producer_id"]
        + g.u64be(ck["boot_epoch"])
        + g.u64be(ck["highest_sequence"])
        + ck["batch_root"]
        + g.u64be(ck["checkpoint_time"])
        + g.u64be(ck["checkpoint_sequence"])
        + ck["signing_key_id"]
        + ck["signature"]
    )
    if len(wire) != 152:
        raise ValueError("checkpoint fields have invalid width")
    return wire


def verify_checkpoint(ck, ck_pk):
    pre = (g.D_CHECKPOINT + ck["producer_id"] + g.u64be(ck["boot_epoch"])
           + g.u64be(ck["highest_sequence"]) + ck["batch_root"]
           + g.u64be(ck["checkpoint_time"]) + g.u64be(ck["checkpoint_sequence"]))
    return g.sha256(pre) == ck["checkpoint_root"] and \
        ed25519_verify(ck_pk, ck["checkpoint_root"], ck["signature"])


# ---------------------------------------------------------------------------
# Read-time range Verifier (ATP-0001 §10)
# ---------------------------------------------------------------------------
def classify_coverage(segment, checkpoint, checkpoint_keys, producer_keys,
                      schemas, authorized_schemas, request):
    """
    segment: ordered list of stored wire batches for one (producer, epoch),
             as retrieved from (untrusted) ledger storage.
    checkpoint: a chain-head checkpoint dict, or None.
    checkpoint_keys / producer_keys: historical key-id -> public-key maps.
    schemas: schema-digest -> validated manifest map.
    producer_keys / authorized_schemas: positive append-time authorization
             evidence for every represented use. Omit uncertain or distrusted
             bindings so the affected range classifies as gap.
    request: {producer_id, boot_epoch, first_sequence, last_sequence}.
    Returns status in {complete, truncated, tampered, gap}. Precedence per
    ATP-0001 §10.2: tampered > truncated > gap > complete.
    """
    parsed = []
    incomplete = request["first_sequence"] > request["last_sequence"]
    entity_count = 0
    seen_entities = set()
    # --- tampered (highest precedence): cryptographic failure on any batch ---
    for index, wire in enumerate(segment):
        try:
            pb = parse_batch(wire)
        except (MalformedBatch, UnicodeDecodeError):
            return "tampered"
        broot = compute_batch_root(pb)
        if pb["protocol_version"] != 0x0001 \
                or pb["producer_id"] != request["producer_id"] \
                or pb["boot_epoch"] != request["boot_epoch"]:
            return "tampered"
        if g.merkle_root(pb["records"]) != pb["merkle_root"]:
            return "tampered"          # record mutation
        producer_pk = producer_keys.get(pb["signing_key_id"])
        if producer_pk is None:
            incomplete = True
        elif not ed25519_verify(producer_pk, broot, pb["signature"]):
            return "tampered"          # header/merkle/signature mutation

        if index == 0 and pb["previous_root"] != ZERO32:
            entity_count = None
            incomplete = True
        if parsed:
            previous = parsed[-1][0]
            previous_end = previous["first_sequence"] + previous["record_count"]
            if pb["first_sequence"] < previous_end:
                return "tampered"
            if pb["first_sequence"] > previous_end:
                entity_count = None
                incomplete = True
        if any(entity in seen_entities for entity in pb["entity_delta"]):
            return "tampered"
        seen_entities.update(pb["entity_delta"])
        if entity_count is not None:
            entity_count += len(pb["entity_delta"])
            if entity_count > (1 << 64) - 1:
                return "tampered"

        manifests = []
        for digest in pb["schema_digests"]:
            if digest not in authorized_schemas:
                incomplete = True
            manifest = schemas.get(digest)
            if manifest is None:
                incomplete = True
            manifests.append(manifest)
        for record in pb["records"]:
            rr = _Reader(record)
            try:
                schema_ref = rr.uvarint()
                rr.uvarint()
                time_delta = rr.uvarint()
            except MalformedBatch:
                return "tampered"
            if pb["base_time"] + time_delta > (1 << 64) - 1 \
                    or schema_ref >= len(manifests):
                return "tampered"
            manifest = manifests[schema_ref]
            if manifest is not None:
                ok, _ = validate_record(
                    manifest, record,
                    (1 << 64) - 1 if entity_count is None else entity_count)
                if not ok:
                    return "tampered"
        parsed.append((pb, broot))

    # Reordering is tampering. A broken previous_root link is tampering only
    # between sequence-adjacent batches; a missing middle batch is a gap.
    for i in range(1, len(parsed)):
        prev_pb, prev_root = parsed[i - 1]
        cur_pb, _ = parsed[i]
        if cur_pb["first_sequence"] <= prev_pb["first_sequence"]:
            return "tampered"
        adjacent = cur_pb["first_sequence"] == prev_pb["first_sequence"] + prev_pb["record_count"]
        if adjacent and cur_pb["previous_root"] != prev_root:
            return "tampered"

    if checkpoint is None:
        return "gap"
    ck_pk = checkpoint_keys.get(checkpoint["signing_key_id"])
    if ck_pk is None:
        return "gap"
    if not verify_checkpoint(checkpoint, ck_pk) \
            or checkpoint["producer_id"] != request["producer_id"] \
            or checkpoint["boot_epoch"] != request["boot_epoch"]:
        return "tampered"

    # A valid checkpoint above the retained tail proves suffix truncation.
    highest_retained = None if not parsed else \
        parsed[-1][0]["first_sequence"] + parsed[-1][0]["record_count"] - 1
    if highest_retained is None or highest_retained < checkpoint["highest_sequence"]:
        return "truncated"

    if checkpoint["highest_sequence"] < request["last_sequence"]:
        return "gap"

    cp_index = next((
        i for i, (pb, root) in enumerate(parsed)
        if pb["first_sequence"] + pb["record_count"] - 1
        == checkpoint["highest_sequence"] and root == checkpoint["batch_root"]
    ), None)
    if cp_index is None:
        checkpoint_sequence_is_present = any(
            pb["first_sequence"] <= checkpoint["highest_sequence"]
            <= pb["first_sequence"] + pb["record_count"] - 1
            for pb, _ in parsed)
        return "tampered" if checkpoint_sequence_is_present else "gap"

    start_index = next((
        i for i, (pb, _) in enumerate(parsed)
        if pb["first_sequence"] <= request["first_sequence"]
        <= pb["first_sequence"] + pb["record_count"] - 1
    ), None)
    if start_index is None or start_index > cp_index:
        return "gap"

    expected = parsed[start_index][0]["first_sequence"]
    for pb, _ in parsed[start_index:cp_index + 1]:
        if pb["first_sequence"] != expected:
            return "gap"
        expected += pb["record_count"]
    return "gap" if incomplete else "complete"
