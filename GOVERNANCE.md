# ATP Governance

## Scope

This document governs the ATP specifications, golden vectors, and reference
implementations in this repository.

The initial maintainers are Jun He and Deying Yu. Maintainers may add or remove
maintainers through the decision process below and must record the change in
this file.

## Roles

- **Contributor**: anyone submitting issues, proposals, text, vectors, or code.
- **Reviewer**: a contributor with demonstrated expertise who regularly reviews
  a defined area.
- **Maintainer**: a steward with merge, release, moderation, and security
  responsibilities.

Roles are earned through sustained, constructive contribution. They are not
permanent entitlements.

## Decisions

Routine editorial and implementation changes use lazy consensus and require one
maintainer approval.

The following high-impact changes require two maintainer approvals and no
unresolved blocking technical objection:

- normative specification behavior;
- wire format, hash preimage, signature, identity, or validation order;
- changes to existing golden bytes or verdicts;
- conformance-class or compatibility changes;
- release promotion or deprecation; and
- governance or licensing changes.

Authors do not count their own approval. When only one non-author maintainer is
available, the change remains open for at least 7 calendar days for public
review. Security fixes may use a private expedited process and receive public
review after coordinated disclosure.

Maintainers seek technical consensus. If consensus cannot be reached, the
maintainers document competing positions and decide by majority. A tie preserves
the current protocol.

## Specification Lifecycle

ATP documents use these states:

1. **Proposed**: a scoped idea, not an implementation target.
2. **Draft**: actively changing and unsuitable for compatibility claims.
3. **Release Candidate**: complete enough for independent implementation;
   changes require compatibility review.
4. **Final**: stable for the identified protocol version.
5. **Deprecated**: retained for historical and compatibility purposes.

Promotion to Final requires:

- complete normative syntax and validation ordering;
- at least two independent implementations of the required conformance surface;
- machine-readable positive and negative vectors;
- a security review;
- no unresolved implementation blocker; and
- a recorded release vote.

## Golden Vectors and Errata

Golden vectors are versioned protocol artifacts. Existing identifiers and bytes
are immutable after a Final release.

Before Final, a correction to an existing vector must identify the governing
text, explain why the old artifact was inconsistent, assess deployed impact,
and update every reference implementation. Additive vectors are preferred.

Editorial errata that do not affect bytes or behavior may merge without a
protocol version change. Normative incompatible changes require a new protocol
or profile version.

## Releases

Maintainers publish signed tags and update `CHANGELOG.md`. A release records:

- document and profile versions;
- vector checksums or immutable source revision;
- supported implementation versions;
- known limitations; and
- any superseded or deprecated behavior.

## Conduct and Security

Maintainers enforce `CODE_OF_CONDUCT.md` and handle vulnerability reports under
`SECURITY.md`. A maintainer with a conflict of interest must recuse from the
relevant conduct, security, or governance decision.
