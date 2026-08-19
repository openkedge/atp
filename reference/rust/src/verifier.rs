//! Read-time coverage verifier and chain-head checkpoints. ATP-0001 §10/§11.

use std::collections::{HashMap, HashSet};

use crate::batch::Batch;
use crate::constants::{D_CHECKPOINT, PROTOCOL_VERSION_V0_1, ZERO32};
use crate::crypto::{ed25519_verify, sha256};
use crate::manifest::Manifest;
use crate::merkle::merkle_root;
use crate::record::validate_record;
use crate::varint::{DecodeError, Reader};

pub const CHECKPOINT_WIRE_BYTES: usize = 152;

pub struct Checkpoint {
    pub producer_id: [u8; 16],
    pub boot_epoch: u64,
    pub highest_sequence: u64,
    pub batch_root: [u8; 32],
    pub checkpoint_time: u64,
    pub checkpoint_sequence: u64,
    pub signing_key_id: [u8; 8],
    pub signature: [u8; 64],
}

pub struct CoverageRequest {
    pub producer_id: [u8; 16],
    pub boot_epoch: u64,
    pub first_sequence: u64,
    pub last_sequence: u64,
}

/// Historical trust material required to certify a stream range.
///
/// `producer_keys` and `authorized_schemas` contain positive append-time
/// authorization evidence for every represented use in the requested history.
/// Callers must omit uncertain or distrusted bindings, which makes coverage a
/// gap. Current key rotation or schema revocation must not silently rewrite
/// historical verification policy.
pub struct VerificationContext<'a> {
    pub producer_keys: &'a HashMap<[u8; 8], [u8; 32]>,
    pub checkpoint_keys: &'a HashMap<[u8; 8], [u8; 32]>,
    pub schemas: &'a HashMap<[u8; 32], Manifest>,
    pub authorized_schemas: &'a HashSet<[u8; 32]>,
}

impl Checkpoint {
    pub fn serialize(&self) -> [u8; CHECKPOINT_WIRE_BYTES] {
        let mut wire = [0u8; CHECKPOINT_WIRE_BYTES];
        let mut offset = 0usize;
        let mut write = |field: &[u8]| {
            wire[offset..offset + field.len()].copy_from_slice(field);
            offset += field.len();
        };
        write(&self.producer_id);
        write(&self.boot_epoch.to_be_bytes());
        write(&self.highest_sequence.to_be_bytes());
        write(&self.batch_root);
        write(&self.checkpoint_time.to_be_bytes());
        write(&self.checkpoint_sequence.to_be_bytes());
        write(&self.signing_key_id);
        write(&self.signature);
        debug_assert_eq!(offset, CHECKPOINT_WIRE_BYTES);
        wire
    }

    pub fn parse(wire: &[u8]) -> Result<Self, DecodeError> {
        if wire.len() != CHECKPOINT_WIRE_BYTES {
            return Err(DecodeError);
        }
        let mut reader = Reader::new(wire);
        let checkpoint = Checkpoint {
            producer_id: reader.take(16)?.try_into().unwrap(),
            boot_epoch: u64::from_be_bytes(reader.take(8)?.try_into().unwrap()),
            highest_sequence: u64::from_be_bytes(reader.take(8)?.try_into().unwrap()),
            batch_root: reader.take(32)?.try_into().unwrap(),
            checkpoint_time: u64::from_be_bytes(reader.take(8)?.try_into().unwrap()),
            checkpoint_sequence: u64::from_be_bytes(reader.take(8)?.try_into().unwrap()),
            signing_key_id: reader.take(8)?.try_into().unwrap(),
            signature: reader.take(64)?.try_into().unwrap(),
        };
        if !reader.eof() {
            return Err(DecodeError);
        }
        Ok(checkpoint)
    }

    pub fn root(&self) -> [u8; 32] {
        let mut pre = D_CHECKPOINT.to_vec();
        pre.extend_from_slice(&self.producer_id);
        pre.extend_from_slice(&self.boot_epoch.to_be_bytes());
        pre.extend_from_slice(&self.highest_sequence.to_be_bytes());
        pre.extend_from_slice(&self.batch_root);
        pre.extend_from_slice(&self.checkpoint_time.to_be_bytes());
        pre.extend_from_slice(&self.checkpoint_sequence.to_be_bytes());
        sha256(&pre)
    }

    pub fn verify(&self, ck_pk: &[u8; 32]) -> bool {
        ed25519_verify(ck_pk, &self.root(), &self.signature)
    }
}

/// Coverage classification. Precedence (ATP-0001 §10.2): tampered > truncated >
/// gap > complete.
pub fn classify_coverage(
    segment: &[Vec<u8>],
    checkpoint: Option<&Checkpoint>,
    context: &VerificationContext<'_>,
    request: &CoverageRequest,
) -> &'static str {
    // tampered (highest precedence): any cryptographic failure on any batch.
    let mut parsed: Vec<(Batch, [u8; 32])> = Vec::new();
    let mut incomplete = request.first_sequence > request.last_sequence;
    let mut entity_count = Some(0u64);
    let mut seen_entities = HashSet::new();
    for (index, wire) in segment.iter().enumerate() {
        let pb = match Batch::parse(wire) {
            Ok(b) => b,
            Err(_) => return "tampered",
        };
        let broot = pb.batch_root();
        if pb.protocol_version != PROTOCOL_VERSION_V0_1
            || pb.producer_id != request.producer_id
            || pb.boot_epoch != request.boot_epoch
        {
            return "tampered";
        }
        if merkle_root(&pb.records) != pb.merkle_root {
            return "tampered";
        }
        match context.producer_keys.get(&pb.signing_key_id) {
            Some(pk) if !ed25519_verify(pk, &broot, &pb.signature) => return "tampered",
            Some(_) => {}
            None => incomplete = true,
        }

        if index == 0 && pb.previous_root != ZERO32 {
            // Core v0.1 has no authenticated alias snapshot. Without epoch
            // genesis, semantic validation cannot be complete.
            entity_count = None;
            incomplete = true;
        }
        if let Some((previous, _)) = parsed.last() {
            let previous_end = previous.first_sequence + previous.record_count as u64;
            if pb.first_sequence < previous_end {
                return "tampered";
            }
            if pb.first_sequence > previous_end {
                entity_count = None;
                incomplete = true;
            }
        }
        if pb
            .entity_delta
            .iter()
            .any(|id| !seen_entities.insert(id.clone()))
        {
            return "tampered";
        }
        if let Some(count) = entity_count {
            entity_count = count.checked_add(pb.entity_delta.len() as u64);
            if entity_count.is_none() {
                return "tampered";
            }
        }

        let mut manifests = Vec::with_capacity(pb.schema_digests.len());
        for digest in &pb.schema_digests {
            if !context.authorized_schemas.contains(digest) {
                incomplete = true;
            }
            match context.schemas.get(digest) {
                Some(manifest) => manifests.push(Some(manifest)),
                None => {
                    manifests.push(None);
                    incomplete = true;
                }
            }
        }
        for record in &pb.records {
            let mut reader = Reader::new(record);
            let schema_ref = match reader.uvarint() {
                Ok(value) => match usize::try_from(value) {
                    Ok(index) => index,
                    Err(_) => return "tampered",
                },
                Err(_) => return "tampered",
            };
            if reader.uvarint().is_err() {
                return "tampered";
            }
            let time_delta = match reader.uvarint() {
                Ok(value) => value,
                Err(_) => return "tampered",
            };
            if pb.base_time.checked_add(time_delta).is_none() || schema_ref >= manifests.len() {
                return "tampered";
            }
            if let Some(manifest) = manifests[schema_ref] {
                let alias_limit = entity_count.unwrap_or(u64::MAX);
                if validate_record(manifest, record, alias_limit).is_err() {
                    return "tampered";
                }
            }
        }
        parsed.push((pb, broot));
    }

    // Reordering is tampering. A broken previous_root is tampering only between
    // sequence-adjacent batches; a missing middle batch is classified as a gap.
    for i in 1..parsed.len() {
        let (prev_pb, prev_root) = (&parsed[i - 1].0, parsed[i - 1].1);
        let cur_pb = &parsed[i].0;
        if cur_pb.first_sequence <= prev_pb.first_sequence {
            return "tampered";
        }
        let adjacent =
            cur_pb.first_sequence == prev_pb.first_sequence + prev_pb.record_count as u64;
        if adjacent && cur_pb.previous_root != prev_root {
            return "tampered";
        }
    }

    let Some(cp) = checkpoint else {
        return "gap";
    };
    let Some(checkpoint_key) = context.checkpoint_keys.get(&cp.signing_key_id) else {
        return "gap";
    };
    if !cp.verify(checkpoint_key)
        || cp.producer_id != request.producer_id
        || cp.boot_epoch != request.boot_epoch
    {
        return "tampered";
    }

    // A valid checkpoint above the retained tail proves suffix truncation.
    let highest_retained = parsed
        .last()
        .map(|(pb, _)| pb.first_sequence + pb.record_count as u64 - 1);
    if highest_retained
        .map(|s| s < cp.highest_sequence)
        .unwrap_or(true)
    {
        return "truncated";
    }

    if cp.highest_sequence < request.last_sequence {
        return "gap"; // the requested suffix is not externally anchored
    }

    // The checkpoint must identify the batch that ends at highest_sequence.
    let cp_index = parsed.iter().position(|(pb, root)| {
        pb.first_sequence + pb.record_count as u64 - 1 == cp.highest_sequence
            && *root == cp.batch_root
    });
    let Some(cp_index) = cp_index else {
        let checkpoint_sequence_is_present = parsed.iter().any(|(pb, _)| {
            let last = pb.first_sequence + pb.record_count as u64 - 1;
            pb.first_sequence <= cp.highest_sequence && cp.highest_sequence <= last
        });
        return if checkpoint_sequence_is_present {
            "tampered"
        } else {
            "gap"
        };
    };

    let start_index = parsed.iter().position(|(pb, _)| {
        let last = pb.first_sequence + pb.record_count as u64 - 1;
        pb.first_sequence <= request.first_sequence && request.first_sequence <= last
    });
    let Some(start_index) = start_index else {
        return "gap";
    };
    if start_index > cp_index {
        return "gap";
    }

    let mut expected = parsed[start_index].0.first_sequence;
    for (pb, _) in &parsed[start_index..=cp_index] {
        if pb.first_sequence != expected {
            return "gap";
        }
        expected += pb.record_count as u64;
    }
    if incomplete {
        "gap"
    } else {
        "complete"
    }
}
