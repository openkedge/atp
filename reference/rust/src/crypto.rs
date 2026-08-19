//! Cryptographic primitives: SHA-256 and Ed25519 (RFC 8032).

use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use sha2::{Digest, Sha256};

pub fn sha256(data: &[u8]) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(data);
    h.finalize().into()
}

/// Ed25519 public key from a 32-byte seed.
pub fn ed25519_pubkey(seed: &[u8; 32]) -> [u8; 32] {
    SigningKey::from_bytes(seed).verifying_key().to_bytes()
}

/// Sign a message (for ATP, the 32-byte `batch_root`) with a 32-byte seed.
pub fn ed25519_sign(seed: &[u8; 32], msg: &[u8]) -> [u8; 64] {
    SigningKey::from_bytes(seed).sign(msg).to_bytes()
}

pub fn ed25519_verify(pk: &[u8; 32], msg: &[u8], sig: &[u8; 64]) -> bool {
    match VerifyingKey::from_bytes(pk) {
        Ok(vk) => vk.verify_strict(msg, &Signature::from_bytes(sig)).is_ok(),
        Err(_) => false,
    }
}
