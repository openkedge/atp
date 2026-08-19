# ATP Conformance Vectors

These machine-readable suites pin ATP to exact bytes and deterministic verdicts.
The checked-in JSON is normative where the applicable specification identifies
it as a golden vector.

## Suites

| Artifact | Coverage |
|---|---|
| `CV-CORE-001.json` | Foundational ATP-0001 genesis batch: canonical manifest, two records, Merkle root, 197-byte batch-root preimage, signature, and complete wire bytes. Mirrored in ATP-0001 Appendix B. |
| `schema-vectors.json` | ATP-0002: 6 positive canonical-CBOR/digest vectors and 25 executable negative canonical-CBOR inputs with ordered rejection reasons. |
| `conformance-vectors.json` | ATP-0001: 48 collector scenarios, 17 coverage scenarios, 3 opaque dereference scenarios, and 3 RFC 6962 Merkle vectors including odd leaf counts. |
| `otel-mapping-vectors.json` | ATP-0004: 3 standard schema manifests, all canonical `AnyValue` forms, 4 Resource identities, and deterministic Span, LogRecord, and numeric Metric mappings. |
| `check_spec_examples.py` | Publication guard: requires ATP-0001 Appendix B and ATP-0002 Appendix A to match the golden JSON. |

The Python references use only the standard library. The Ed25519 implementation
in `generate_vectors.py` is deliberately simple and slow; it exists for
reproducibility, not production use.

## Verify Without Rewriting

Run from the repository root:

```bash
python3 test-vectors/generate_vectors.py --check
python3 test-vectors/generate_schema_vectors.py --check
python3 test-vectors/generate_conformance_vectors.py --check
python3 test-vectors/generate_otel_mapping_vectors.py --check
python3 test-vectors/check_spec_examples.py
```

Regenerate a suite by omitting `--check`. Review generated JSON changes as
protocol changes, not formatting updates.

The schema generator asserts the continuity digest:

```text
b8fafb40cf935d94f1ef933ce411f5d7d881a82413fc463a45db30a37a4103f2
```

The core generator asserts:

```text
len(batch_root_preimage)
  = len(D_BATCH) + 2+16+8+8+4+8+5+32+32+32+32
  = 18 + 179
  = 197
```

## Implementer Workflow

1. Reproduce every canonical manifest byte string and schema digest.
2. Reproduce each record, Merkle root, batch root, signature, and wire batch in
   `CV-CORE-001.json`.
3. Return each expected collector `(status, error_code)` in the specified
   validation order.
4. Return each expected coverage status using the supplied registry context and
   checkpoint.
5. Reject each ATP-0002 negative input for its listed first reason.
6. Match all ATP-0004 identities, logical fields, canonical attribute payloads,
   opaque references, and standard schema digests.

The Rust reference consumes all four JSON files in
`reference/rust/tests/vectors.rs`.

## Change Control

Any change to existing golden bytes or expected verdicts requires:

- a normative specification change;
- an interoperability and compatibility explanation;
- regenerated vectors;
- matching updates to both references; and
- maintainer approval under `GOVERNANCE.md`.

Add new vectors instead of rewriting existing identifiers when the old behavior
remains valid.
