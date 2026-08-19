//! RFC 6962 Merkle Tree Hash over record leaves. ATP-0001 §7.3.

use crate::crypto::sha256;

pub fn merkle_root(leaves: &[Vec<u8>]) -> [u8; 32] {
    let n = leaves.len();
    assert!(n >= 1, "ATP batches MUST contain >= 1 record");
    if n == 1 {
        let mut pre = vec![0u8];
        pre.extend_from_slice(&leaves[0]);
        return sha256(&pre);
    }
    // largest power of two strictly less than n
    let mut k = 1usize;
    while k < n {
        k <<= 1;
    }
    k >>= 1;
    let l = merkle_root(&leaves[..k]);
    let r = merkle_root(&leaves[k..]);
    let mut pre = vec![1u8];
    pre.extend_from_slice(&l);
    pre.extend_from_slice(&r);
    sha256(&pre)
}
