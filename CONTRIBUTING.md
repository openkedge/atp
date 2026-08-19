# Contributing to ATP

ATP welcomes specification, conformance, implementation, security, and
editorial contributions.

By participating, you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).
Report vulnerabilities through [SECURITY.md](SECURITY.md), not a public issue.

## Before Opening a Change

- Search existing issues and proposals.
- For editorial fixes, open a focused change directly.
- For wire behavior, validation order, security properties, schema formats,
  golden bytes, or compatibility, open a design issue first.
- Keep the paper informative. Normative behavior belongs in an ATP document and
  executable vectors.

## Development Checks

Python 3.9+ and Rust 1.85+ are required.

```bash
python3 test-vectors/generate_vectors.py --check
python3 test-vectors/generate_schema_vectors.py --check
python3 test-vectors/generate_conformance_vectors.py --check
python3 test-vectors/generate_otel_mapping_vectors.py --check
python3 test-vectors/check_spec_examples.py

cd reference/rust
cargo fmt --all -- --check
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked --all-targets
cargo run --locked --example end_to_end
cargo run --locked --example otel_bridge
```

Do not hand-edit generated JSON. Change its generator, regenerate, and review
the resulting semantic and byte-level diff.

## Specification Changes

A normative change must state:

- which conformance class and protocol version it affects;
- whether existing conformant bytes remain valid;
- validation ordering and stable error behavior;
- security, privacy, resource-bound, and downgrade implications;
- how two independent implementations can test interoperability; and
- which vectors and references are added or changed.

Changing an existing golden byte or expected verdict is a breaking action unless
the current artifact is demonstrably inconsistent with its governing text.
Corrections require an erratum entry, compatibility analysis, regenerated
vectors, and maintainer approval.

Use RFC 2119/8174 key words only for normative requirements. Define every wire
integer's width, byte order, range, canonical form, and failure stage.

## Pull Requests

- Keep changes focused and explain the interoperability impact.
- Add or update tests for every behavior change.
- Update `CHANGELOG.md` for user-visible changes.
- Ensure links and section references resolve.
- Obtain the approvals required by [GOVERNANCE.md](GOVERNANCE.md).

## Developer Certificate of Origin

This project uses the Developer Certificate of Origin 1.1 rather than a
contributor license agreement. Sign each commit:

```text
Signed-off-by: Your Name <you@example.com>
```

The sign-off certifies that you have the right to submit the contribution under
the repository's Apache-2.0 license. See
https://developercertificate.org/ for the full certificate.
