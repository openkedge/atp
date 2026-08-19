//! Batch structure: wire serialize/parse, `batch_root`, signing. ATP-0001 §7.

use crate::constants::D_BATCH;
use crate::constants::{
    MAX_BATCH_BYTES, MAX_ENTITY_DICTIONARY_DELTA_ENTRIES, MAX_ENTITY_ID_BYTES,
    MAX_RECORDS_PER_BATCH, MAX_RECORD_BYTES, MAX_SCHEMA_DICTIONARY_ENTRIES,
};
use crate::crypto::{ed25519_sign, sha256};
use crate::identity::valid_entity_id;
use crate::merkle::merkle_root;
use crate::varint::{uvarint, DecodeError, Reader};
use std::collections::HashSet;

pub struct Batch {
    pub protocol_version: u16,
    pub producer_id: [u8; 16],
    pub boot_epoch: u64,
    pub first_sequence: u64,
    pub record_count: u32,
    pub base_time: u64,
    pub clock_quality: [u8; 5],
    pub schema_digests: Vec<[u8; 32]>,
    pub entity_delta: Vec<String>,
    pub previous_root: [u8; 32],
    pub records: Vec<Vec<u8>>,
    pub merkle_root: [u8; 32],
    pub signing_key_id: [u8; 8],
    pub signature: [u8; 64],
}

impl Batch {
    fn schema_dict(&self) -> Vec<u8> {
        let mut v = uvarint(self.schema_digests.len() as u64);
        for d in &self.schema_digests {
            v.extend_from_slice(d);
        }
        v
    }

    fn entity_dict(&self) -> Vec<u8> {
        let mut v = uvarint(self.entity_delta.len() as u64);
        for e in &self.entity_delta {
            v.extend(uvarint(e.len() as u64));
            v.extend_from_slice(e.as_bytes());
        }
        v
    }

    /// ATP-0001 §7.4. Uses the batch's `record_count` and `merkle_root` fields.
    pub fn batch_root(&self) -> [u8; 32] {
        let mut pre = D_BATCH.to_vec();
        pre.extend_from_slice(&self.protocol_version.to_be_bytes());
        pre.extend_from_slice(&self.producer_id);
        pre.extend_from_slice(&self.boot_epoch.to_be_bytes());
        pre.extend_from_slice(&self.first_sequence.to_be_bytes());
        pre.extend_from_slice(&self.record_count.to_be_bytes());
        pre.extend_from_slice(&self.base_time.to_be_bytes());
        pre.extend_from_slice(&self.clock_quality);
        pre.extend_from_slice(&sha256(&self.schema_dict()));
        pre.extend_from_slice(&sha256(&self.entity_dict()));
        pre.extend_from_slice(&self.previous_root);
        pre.extend_from_slice(&self.merkle_root);
        sha256(&pre)
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut w = Vec::new();
        w.extend_from_slice(&self.protocol_version.to_be_bytes());
        w.extend_from_slice(&self.producer_id);
        w.extend_from_slice(&self.boot_epoch.to_be_bytes());
        w.extend_from_slice(&self.first_sequence.to_be_bytes());
        w.extend_from_slice(&(self.records.len() as u32).to_be_bytes());
        w.extend_from_slice(&self.base_time.to_be_bytes());
        w.extend_from_slice(&self.clock_quality);
        w.extend(self.schema_dict());
        w.extend(self.entity_dict());
        w.extend_from_slice(&self.previous_root);
        for r in &self.records {
            w.extend(uvarint(r.len() as u64));
            w.extend_from_slice(r);
        }
        w.extend_from_slice(&self.merkle_root);
        w.extend_from_slice(&self.signing_key_id);
        w.extend_from_slice(&self.signature);
        w
    }

    pub fn parse(wire: &[u8]) -> Result<Batch, DecodeError> {
        if wire.len() > MAX_BATCH_BYTES {
            return Err(DecodeError);
        }
        let mut r = Reader::new(wire);
        let protocol_version = u16::from_be_bytes(r.take(2)?.try_into().unwrap());
        let producer_id: [u8; 16] = r.take(16)?.try_into().unwrap();
        let boot_epoch = u64::from_be_bytes(r.take(8)?.try_into().unwrap());
        let first_sequence = u64::from_be_bytes(r.take(8)?.try_into().unwrap());
        let record_count = u32::from_be_bytes(r.take(4)?.try_into().unwrap());
        if record_count == 0 || record_count > MAX_RECORDS_PER_BATCH {
            return Err(DecodeError);
        }
        first_sequence
            .checked_add(record_count as u64)
            .ok_or(DecodeError)?;
        let base_time = u64::from_be_bytes(r.take(8)?.try_into().unwrap());
        let clock_quality: [u8; 5] = r.take(5)?.try_into().unwrap();
        let clock_source = clock_quality[0];
        let clock_skew = u32::from_be_bytes(clock_quality[1..].try_into().unwrap());
        if clock_source > 4 || (clock_source == 0 && clock_skew != u32::MAX) {
            return Err(DecodeError);
        }
        let n = r.uvarint()?;
        if n == 0 || n > MAX_SCHEMA_DICTIONARY_ENTRIES {
            return Err(DecodeError);
        }
        let mut schema_digests = Vec::new();
        let mut seen_schemas = HashSet::new();
        for _ in 0..n {
            let digest: [u8; 32] = r.take(32)?.try_into().unwrap();
            if !seen_schemas.insert(digest) {
                return Err(DecodeError);
            }
            schema_digests.push(digest);
        }
        let m = r.uvarint()?;
        if m > MAX_ENTITY_DICTIONARY_DELTA_ENTRIES {
            return Err(DecodeError);
        }
        let mut entity_delta = Vec::new();
        let mut seen_entities = HashSet::new();
        for _ in 0..m {
            let ln = usize::try_from(r.uvarint()?).map_err(|_| DecodeError)?;
            if ln == 0 || ln > MAX_ENTITY_ID_BYTES {
                return Err(DecodeError);
            }
            let s = std::str::from_utf8(r.take(ln)?)
                .map_err(|_| DecodeError)?
                .to_string();
            if !valid_entity_id(&s) || !seen_entities.insert(s.clone()) {
                return Err(DecodeError);
            }
            entity_delta.push(s);
        }
        let previous_root: [u8; 32] = r.take(32)?.try_into().unwrap();
        let mut records = Vec::new();
        for _ in 0..record_count {
            let ln = usize::try_from(r.uvarint()?).map_err(|_| DecodeError)?;
            if ln == 0 || ln > MAX_RECORD_BYTES {
                return Err(DecodeError);
            }
            records.push(r.take(ln)?.to_vec());
        }
        let merkle_root: [u8; 32] = r.take(32)?.try_into().unwrap();
        let signing_key_id: [u8; 8] = r.take(8)?.try_into().unwrap();
        let signature: [u8; 64] = r.take(64)?.try_into().unwrap();
        if !r.eof() {
            return Err(DecodeError);
        }
        Ok(Batch {
            protocol_version,
            producer_id,
            boot_epoch,
            first_sequence,
            record_count,
            base_time,
            clock_quality,
            schema_digests,
            entity_delta,
            previous_root,
            records,
            merkle_root,
            signing_key_id,
            signature,
        })
    }

    /// Producer-side constructor: computes `merkle_root`, `batch_root`, and signs.
    #[allow(clippy::too_many_arguments)]
    pub fn build(
        protocol_version: u16,
        producer_id: [u8; 16],
        boot_epoch: u64,
        first_sequence: u64,
        base_time: u64,
        clock_quality: [u8; 5],
        schema_digests: Vec<[u8; 32]>,
        entity_delta: Vec<String>,
        previous_root: [u8; 32],
        records: Vec<Vec<u8>>,
        signing_key_id: [u8; 8],
        seed: &[u8; 32],
    ) -> Batch {
        let mroot = merkle_root(&records);
        let mut b = Batch {
            protocol_version,
            producer_id,
            boot_epoch,
            first_sequence,
            record_count: records.len() as u32,
            base_time,
            clock_quality,
            schema_digests,
            entity_delta,
            previous_root,
            records,
            merkle_root: mroot,
            signing_key_id,
            signature: [0u8; 64],
        };
        let broot = b.batch_root();
        b.signature = ed25519_sign(seed, &broot);
        b
    }
}
