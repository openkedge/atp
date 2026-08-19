# Security Policy

ATP is security-sensitive protocol work. Please do not disclose a suspected
vulnerability in a public issue, discussion, or pull request.

## Supported Versions

Until ATP reaches 1.0, security fixes are made on the latest release-candidate
line and the default development branch. Older snapshots are not maintained.

| Version | Supported |
|---|:---:|
| latest `0.1.0-rc.x` / default branch | yes |
| older snapshots | no |

## Reporting

Use the repository's private security-advisory interface when available, or
email `security@openkedge.io`.

Include:

- affected specification section, vector, or implementation;
- impact and threat model;
- minimal reproducer or malformed input;
- whether the issue is remotely reachable;
- any proposed mitigation; and
- whether you plan coordinated publication.

Do not include production secrets, private keys, customer data, or unsafe
payloads that are not necessary to reproduce the issue.

Maintainers aim to acknowledge a report within 3 business days and provide an
initial assessment within 7 business days. Resolution time depends on severity,
interoperability impact, and whether coordinated specification changes are
required.

## Disclosure

The reporter and maintainers should agree on a disclosure date after a fix and
updated vectors are available. Security fixes may be developed privately.
Credit is offered unless the reporter requests anonymity.

## Deployment Boundary

The reference implementations are not production SDKs. Deployments remain
responsible for key custody, durable storage, authenticated checkpoint
discovery, registry authorization, opaque-fetch SSRF controls, resource limits,
and transport security described by the specifications.
