//! Collector acceptance state machine. ATP-0001 §9.

use std::collections::hash_map::Entry;
use std::collections::{HashMap, HashSet};

use crate::batch::Batch;
use crate::constants::*;
use crate::crypto::ed25519_verify;
use crate::manifest::Manifest;
use crate::merkle::merkle_root;
use crate::record::validate_record;
use crate::varint::Reader;

#[derive(Debug, PartialEq, Eq)]
pub enum Status {
    Accept,
    AcceptIdempotent,
    Reject,
}

#[derive(Debug)]
pub struct Outcome {
    pub status: Status,
    pub code: Option<&'static str>,
}

fn reject(code: &'static str) -> Outcome {
    Outcome {
        status: Status::Reject,
        code: Some(code),
    }
}

struct Producer {
    keys: HashMap<[u8; 8], [u8; 32]>,
    authorized: HashSet<[u8; 32]>,
}

struct EpochState {
    next_seq: u64,
    head_root: [u8; 32],
    entity_count: u64,
    entities: HashSet<String>,
    committed: HashMap<u64, ([u8; 32], u32, Vec<u8>)>,
}

struct ProducerState {
    highest_epoch: i128,
    epochs: HashMap<u64, EpochState>,
}

#[derive(Default)]
pub struct Collector {
    producers: HashMap<[u8; 16], Producer>,
    schemas: HashMap<[u8; 32], Manifest>,
    state: HashMap<[u8; 16], ProducerState>,
}

impl Collector {
    pub fn new() -> Self {
        Collector::default()
    }

    pub fn register_producer(
        &mut self,
        producer_id: [u8; 16],
        key_id: [u8; 8],
        pubkey: [u8; 32],
        authorized: &[[u8; 32]],
    ) -> Result<(), &'static str> {
        if self.producers.contains_key(&producer_id) {
            return Err("producer already registered");
        }
        let mut keys = HashMap::new();
        keys.insert(key_id, pubkey);
        self.producers.insert(
            producer_id,
            Producer {
                keys,
                authorized: authorized.iter().copied().collect(),
            },
        );
        self.state.entry(producer_id).or_insert(ProducerState {
            highest_epoch: -1,
            epochs: HashMap::new(),
        });
        Ok(())
    }

    /// Add a key without replacing the Producer's retained key history.
    ///
    /// Re-registering the same binding is idempotent. Reusing a key ID with
    /// different key bytes fails closed as required by ATP-0001 Section 7.7.
    pub fn register_producer_key(
        &mut self,
        producer_id: [u8; 16],
        key_id: [u8; 8],
        pubkey: [u8; 32],
    ) -> Result<(), &'static str> {
        let producer = self
            .producers
            .get_mut(&producer_id)
            .ok_or("unknown producer")?;
        if producer
            .keys
            .iter()
            .any(|(existing_id, existing_key)| existing_id != &key_id && existing_key == &pubkey)
        {
            return Err("producer public key already has a different key id");
        }
        match producer.keys.entry(key_id) {
            Entry::Vacant(entry) => {
                entry.insert(pubkey);
                Ok(())
            }
            Entry::Occupied(entry) if entry.get() == &pubkey => Ok(()),
            Entry::Occupied(_) => Err("signing key id collision"),
        }
    }

    pub fn authorize_schema(
        &mut self,
        producer_id: [u8; 16],
        schema_digest: [u8; 32],
    ) -> Result<(), &'static str> {
        let producer = self
            .producers
            .get_mut(&producer_id)
            .ok_or("unknown producer")?;
        producer.authorized.insert(schema_digest);
        Ok(())
    }

    pub fn register_schema(&mut self, manifest: Manifest) -> Result<(), String> {
        manifest.validate()?;
        self.schemas.insert(manifest.digest(), manifest);
        Ok(())
    }

    pub fn accept(&mut self, wire: &[u8]) -> Outcome {
        // Step 1: framing
        let pb = match Batch::parse(wire) {
            Ok(b) => b,
            Err(_) => return reject(E_MALFORMED_BATCH),
        };
        // Step 2: this implementation supports exactly ATP v0.1.
        if pb.protocol_version != PROTOCOL_VERSION_V0_1 {
            return reject(E_UNSUPPORTED_VERSION);
        }
        // Steps 3 & 4: producer + key (scoped so the immutable borrow ends here)
        let pk = {
            let prod = match self.producers.get(&pb.producer_id) {
                Some(p) => p,
                None => return reject(E_UNKNOWN_PRODUCER),
            };
            match prod.keys.get(&pb.signing_key_id) {
                Some(k) => *k,
                None => return reject(E_UNKNOWN_KEY),
            }
        };
        // Step 5: signature over recomputed batch_root
        let broot = pb.batch_root();
        if !ed25519_verify(&pk, &broot, &pb.signature) {
            return reject(E_INVALID_SIGNATURE);
        }
        let st = self.state.get_mut(&pb.producer_id).expect("state exists");
        let epoch = pb.boot_epoch;

        // Step 6: byte-identical idempotent retransmission.
        if let Some(ep) = st.epochs.get(&epoch) {
            if let Some((root, _, prior_wire)) = ep.committed.get(&pb.first_sequence) {
                if *root == broot && prior_wire.as_slice() == wire {
                    return Outcome {
                        status: Status::AcceptIdempotent,
                        code: None,
                    };
                }
                return reject(E_EPOCH_REUSE);
            }
        }

        // Step 7: schema digests resolvable + authorized
        for d in &pb.schema_digests {
            if !self.schemas.contains_key(d) {
                return reject(E_SCHEMA_UNKNOWN);
            }
        }
        {
            let prod = self.producers.get(&pb.producer_id).unwrap();
            for d in &pb.schema_digests {
                if !prod.authorized.contains(d) {
                    return reject(E_SCHEMA_UNAUTHORIZED);
                }
            }
        }

        // Step 8: previous_root continuity
        let is_genesis = !st.epochs.contains_key(&epoch);
        if is_genesis {
            if pb.previous_root != ZERO32 {
                return reject(E_PREVIOUS_ROOT_MISMATCH);
            }
        } else if pb.previous_root != st.epochs[&epoch].head_root {
            return reject(E_PREVIOUS_ROOT_MISMATCH);
        }

        // Step 9: epoch & sequence continuity
        if (epoch as i128) < st.highest_epoch {
            return reject(E_EPOCH_REUSE);
        }
        if !is_genesis {
            let ep = &st.epochs[&epoch];
            let fs = pb.first_sequence;
            if fs > ep.next_seq {
                return reject(E_SEQUENCE_GAP);
            }
            if fs < ep.next_seq {
                return reject(E_EPOCH_REUSE);
            }
        }

        // Steps 10 and 11: dictionary continuity, then Merkle root.
        let (prior_entity_count, prior_entities) = if is_genesis {
            (0, None)
        } else {
            let ep = &st.epochs[&epoch];
            (ep.entity_count, Some(&ep.entities))
        };
        if let Some(existing) = prior_entities {
            if pb.entity_delta.iter().any(|id| existing.contains(id)) {
                return reject(E_MALFORMED_BATCH);
            }
        }
        let alias_count = match prior_entity_count.checked_add(pb.entity_delta.len() as u64) {
            Some(v) => v,
            None => return reject(E_MALFORMED_BATCH),
        };
        if pb.record_count as usize != pb.records.len() || pb.records.is_empty() {
            return reject(E_MALFORMED_BATCH);
        }
        if merkle_root(&pb.records) != pb.merkle_root {
            return reject(E_MERKLE_MISMATCH);
        }

        // Step 12: per-record validation.
        for rec in &pb.records {
            let mut rr = Reader::new(rec);
            let sr = match rr.uvarint() {
                Ok(v) => match usize::try_from(v) {
                    Ok(value) => value,
                    Err(_) => return reject(E_MALFORMED_RECORD),
                },
                Err(_) => return reject(E_MALFORMED_RECORD),
            };
            if rr.uvarint().is_err() {
                return reject(E_MALFORMED_RECORD);
            }
            let time_delta = match rr.uvarint() {
                Ok(v) => v,
                Err(_) => return reject(E_MALFORMED_RECORD),
            };
            if pb.base_time.checked_add(time_delta).is_none() {
                return reject(E_MALFORMED_RECORD);
            }
            if sr >= pb.schema_digests.len() {
                return reject(E_MALFORMED_RECORD);
            }
            let manifest = self.schemas.get(&pb.schema_digests[sr]).unwrap();
            if let Err(code) = validate_record(manifest, rec, alias_count) {
                return reject(code);
            }
        }

        // Step 13: atomic commit.
        if is_genesis {
            let entities = pb.entity_delta.iter().cloned().collect();
            let mut committed = HashMap::new();
            committed.insert(pb.first_sequence, (broot, pb.record_count, wire.to_vec()));
            st.epochs.insert(
                epoch,
                EpochState {
                    next_seq: pb.first_sequence + pb.record_count as u64,
                    head_root: broot,
                    entity_count: alias_count,
                    entities,
                    committed,
                },
            );
        } else {
            let ep = st.epochs.get_mut(&epoch).unwrap();
            ep.committed
                .insert(pb.first_sequence, (broot, pb.record_count, wire.to_vec()));
            ep.next_seq = pb.first_sequence + pb.record_count as u64;
            ep.head_root = broot;
            ep.entity_count = alias_count;
            ep.entities.extend(pb.entity_delta.iter().cloned());
        }
        if (epoch as i128) > st.highest_epoch {
            st.highest_epoch = epoch as i128;
        }

        // Acknowledge only after commit.
        Outcome {
            status: Status::Accept,
            code: None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::Collector;

    #[test]
    fn producer_registration_preserves_key_bindings() {
        let producer_id = [1u8; 16];
        let key_id = [2u8; 8];
        let first_key = [3u8; 32];
        let second_key = [4u8; 32];
        let rotated_key_id = [5u8; 8];

        let mut collector = Collector::new();
        collector
            .register_producer(producer_id, key_id, first_key, &[])
            .unwrap();
        collector
            .register_producer_key(producer_id, key_id, first_key)
            .unwrap();
        assert_eq!(
            collector.register_producer_key(producer_id, rotated_key_id, first_key),
            Err("producer public key already has a different key id")
        );
        assert_eq!(
            collector.register_producer_key(producer_id, key_id, second_key),
            Err("signing key id collision")
        );
        collector
            .register_producer_key(producer_id, rotated_key_id, second_key)
            .unwrap();
        assert_eq!(
            collector.register_producer(producer_id, key_id, first_key, &[]),
            Err("producer already registered")
        );
    }
}
