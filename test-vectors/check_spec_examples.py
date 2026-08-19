#!/usr/bin/env python3
"""Fail when normative specification examples drift from golden vectors."""

import hashlib
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as source:
        return source.read()


def hex_after_label(text, label):
    match = re.search(
        rf"(?m)^{re.escape(label)}:\n(?:\n```text\n)?((?:[0-9a-f]+\n)+)",
        text,
    )
    if not match:
        raise AssertionError(f"missing hex field: {label}")
    return "".join(match.group(1).split())


def producer_value(text, name):
    match = re.search(rf"(?m)^{re.escape(name)}\s*=\s*(\S+)$", text)
    if not match:
        raise AssertionError(f"missing producer field: {name}")
    return match.group(1)


def main():
    core = json.loads(read("test-vectors/CV-CORE-001.json"))
    core_spec = read("spec/ATP-0001-core-protocol.md")
    appendix = core_spec.split("## Appendix B. Worked Vector CV-CORE-001", 1)[1]
    header = core["batch_header"]

    expected_producer = {
        "producer_id": header["producer_id"],
        "boot_epoch": str(header["boot_epoch"]),
        "first_sequence": str(header["first_sequence"]),
        "base_time": str(header["base_time_unix_ms"]),
        "clock_quality": header["clock_quality_bytes"],
        "signing_key_id": header["signing_key_id"],
    }
    for name, expected in expected_producer.items():
        actual = producer_value(appendix, name)
        assert actual == expected, f"{name}: {actual} != {expected}"

    displayed_hex = {
        "canonical manifest CBOR": core["schema_manifest_canonical_cbor"],
        "H_S": core["schema_digest_sha256"],
        "encoded entity_dictionary_delta": header["entity_dictionary_delta_bytes"],
        "merkle_root": core["merkle_root"],
        "SHA-256(schema_dictionary)": core["H_schema_dictionary"],
        "SHA-256(entity_dictionary_delta)": core["H_entity_dictionary_delta"],
        "Batch-root preimage, 197 bytes": core["batch_root_preimage"],
        "batch_root": core["batch_root"],
        "Ed25519 public key": core["ed25519_public_key"],
        "signature": core["signature"],
    }
    for label, expected in displayed_hex.items():
        actual = hex_after_label(appendix, label)
        assert actual == expected, f"{label}: {actual} != {expected}"

    for index, record in enumerate(core["records"]):
        pattern = rf"(?m)^sequence {record['sequence']}: ([0-9a-f]+)$"
        match = re.search(pattern, appendix)
        assert match, f"missing sequence {record['sequence']}"
        assert match.group(1) == record["bytes"], f"record {index} differs"

    aliases = {
        int(index): entity
        for index, entity in re.findall(r"(?m)^([0-9]+) = (\S+)$", appendix)
    }
    assert [aliases[index] for index in sorted(aliases)] == core["entity_aliases"]

    seed_match = re.search(
        r"Test-only Ed25519 seed:\n\n```text\n([0-9a-f]+)\n```",
        appendix,
    )
    assert seed_match, "missing test seed"
    assert seed_match.group(1) == core["ed25519_private_seed_test_only"]

    preimage = bytes.fromhex(core["batch_root_preimage"])
    assert len(preimage) == 197
    assert hashlib.sha256(preimage).hexdigest() == core["batch_root"]

    schema_vectors = json.loads(read("test-vectors/schema-vectors.json"))
    continuity = next(
        vector for vector in schema_vectors["positive"]
        if vector["vector_id"] == "SV-002"
    )
    schema_spec = read("spec/ATP-0002-schema-manifest-format.md")
    appendix_a = schema_spec.split("## Appendix A. Continuity Manifest", 1)[1]
    cbor_match = re.search(
        r"Canonical CBOR:\n\n```text\n([0-9a-f\n]+)\n```",
        appendix_a,
    )
    assert cbor_match, "missing ATP-0002 Appendix A CBOR"
    assert "".join(cbor_match.group(1).split()) == continuity["canonical_cbor"]
    assert continuity["schema_digest_sha256"] == core["schema_digest_sha256"]

    print("specification examples match golden vectors")


if __name__ == "__main__":
    main()
