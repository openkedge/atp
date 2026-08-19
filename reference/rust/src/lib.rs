//! ATP-0001 / ATP-0002 reference implementation.
//!
//! An independent, byte-exact implementation of the Agent Telemetry Protocol
//! core: canonical encoding, deterministic-CBOR schema digests, RFC 6962 Merkle
//! commitment, `batch_root`, Ed25519 signing, the collector acceptance state
//! machine (§9), and the read-time coverage verifier (§10/§11).
//!
//! It is validated against the language-independent conformance vectors in
//! `test-vectors/` (see `tests/vectors.rs`). Two independent stacks — this crate
//! and the Python reference generator — must agree on every hash and signature.

pub mod batch;
pub mod cbor;
pub mod collector;
pub mod constants;
pub mod crypto;
pub mod identity;
pub mod manifest;
pub mod merkle;
pub mod otel;
pub mod record;
pub mod tab;
pub mod varint;
pub mod verifier;

pub use crypto::{ed25519_pubkey, ed25519_sign, ed25519_verify, sha256};
