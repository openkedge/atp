//! End-to-end ATP integration walkthrough.
//!
//!     cargo run --example end_to_end
//!
//! Shows the whole lifecycle wiring against the `atp-ref` public API:
//!   1. Producer  — define a schema, encode records, build + sign chained batches.
//!   2. Transport — serialize each batch to the wire (bytes you'd send anywhere).
//!   3. Collector — register the producer + schema, run the §9 acceptance machine.
//!   4. Verifier  — publish a chain-head checkpoint, classify coverage (§10/§11),
//!      then demonstrate that a single flipped byte flips the verdict.
//!   5. Decoder   — render verified records as compact ATP-TAB rows (§13).

use std::collections::{HashMap, HashSet};

use atp_ref::batch::Batch;
use atp_ref::collector::{Collector, Status};
use atp_ref::constants::*;
use atp_ref::crypto::{ed25519_pubkey, ed25519_sign};
use atp_ref::manifest::{Field, Manifest};
use atp_ref::record::{encode_record, Value};
use atp_ref::tab::render_batches;
use atp_ref::verifier::{classify_coverage, Checkpoint, CoverageRequest, VerificationContext};

fn hexs(b: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(b.len() * 2);
    for byte in b {
        output.push(HEX[(byte >> 4) as usize] as char);
        output.push(HEX[(byte & 0x0f) as usize] as char);
    }
    output
}

fn main() {
    // ---- keys & identity (test values; never use in production) -------------
    let producer_seed: [u8; 32] =
        hex_seed("9d61b19deffe5a60651c9e0d0e6c1e6bf0a1b2c3d4e5f60718293a4b5c6d7e8f");
    let producer_pk = ed25519_pubkey(&producer_seed);
    let producer_id: [u8; 16] =
        *b"\x00\x11\x22\x33\x44\x55\x66\x77\x88\x99\xaa\xbb\xcc\xdd\xee\xff";
    let signing_key_id: [u8; 8] = *b"\xa1\xb2\xc3\xd4\xe5\xf6\x07\x08";

    let checkpoint_seed: [u8; 32] = [0xAA; 32];
    let checkpoint_pk = ed25519_pubkey(&checkpoint_seed);

    // =========================================================================
    // 1. PRODUCER: define the schema.
    // =========================================================================
    let schema = Manifest {
        name: "k8s.pod.transition".into(),
        version: "1.0.0".into(),
        primitive: 0, // Transition
        publisher: "openkedge.io/k8s".into(),
        fields: vec![
            Field::new(0, "old_state", T_ENUM, true).enum_ref("pod_phase"),
            Field::new(1, "new_state", T_ENUM, true).enum_ref("pod_phase"),
            Field::new(2, "exit_code", T_I32, false),
            Field::new(3, "reason", T_STRING, false),
        ],
        enums: vec![(
            "pod_phase".into(),
            ["Pending", "Running", "Succeeded", "Failed", "Unknown"]
                .iter()
                .map(|s| s.to_string())
                .collect(),
        )],
        compatibility: None,
    };
    let sdig = schema.digest();
    println!("── 1. Producer ───────────────────────────────────────────────");
    println!(
        "schema  {}:{}  H_S={}",
        schema.name,
        schema.version,
        &hexs(&sdig)[..16]
    );

    // Pod phase ordinals: Pending=0 Running=1 Succeeded=2 Failed=3 Unknown=4.
    let (running, failed) = (1u64, 3u64);
    let base_time = 1_760_000_000_000u64; // Unix ms
    let clock_quality = {
        let mut cq = [0u8; 5];
        cq[0] = 1; // NTP
        cq[1..].copy_from_slice(&50u32.to_be_bytes()); // Δ_clk = 50ms
        cq
    };

    // Genesis batch: two pods crash-loop. entity aliases 0,1 introduced here.
    let entities = vec![
        "k8s:pod:prod-us-east-1/pay-7d9b".to_string(),
        "k8s:pod:prod-us-east-1/auth-4a2c".to_string(),
    ];
    let b0 = Batch::build(
        0x0001,
        producer_id,
        7,    // boot_epoch
        1000, // first_sequence
        base_time,
        clock_quality,
        vec![sdig],
        entities.clone(),
        ZERO32, // genesis: previous_root = 0
        vec![
            encode_record(
                &schema,
                0,
                0,
                10,
                &[
                    (0, Value::Enum(running)),
                    (1, Value::Enum(failed)),
                    (2, Value::I(137)),
                ],
            ),
            encode_record(
                &schema,
                0,
                1,
                15,
                &[
                    (0, Value::Enum(running)),
                    (1, Value::Enum(failed)),
                    (2, Value::I(137)),
                ],
            ),
        ],
        signing_key_id,
        &producer_seed,
    );

    // Second batch: chains onto b0 via previous_root = b0.batch_root().
    let b1 = Batch::build(
        0x0001,
        producer_id,
        7,
        1002, // continues the sequence (b0 had 2 records: 1000,1001)
        base_time,
        clock_quality,
        vec![sdig],
        vec![], // no new entities; reuses aliases 0,1
        b0.batch_root(),
        vec![encode_record(
            &schema,
            0,
            0,
            40,
            &[(0, Value::Enum(failed)), (1, Value::Enum(running))],
        )],
        signing_key_id,
        &producer_seed,
    );
    println!(
        "built 2 chained batches; b0.batch_root={}…",
        &hexs(&b0.batch_root())[..16]
    );

    // =========================================================================
    // 2. TRANSPORT: serialize to wire bytes.
    // =========================================================================
    let wire0 = b0.serialize();
    let wire1 = b1.serialize();
    println!("\n── 2. Transport ──────────────────────────────────────────────");
    println!(
        "wire b0 = {} bytes, wire b1 = {} bytes (send these anywhere)",
        wire0.len(),
        wire1.len()
    );

    // =========================================================================
    // 3. COLLECTOR: register trust, run the §9 acceptance state machine.
    // =========================================================================
    let mut collector = Collector::new();
    collector
        .register_producer(producer_id, signing_key_id, producer_pk, &[sdig])
        .unwrap();
    collector.register_schema(schema.clone()).unwrap();
    println!("\n── 3. Collector ──────────────────────────────────────────────");
    for (name, wire) in [("b0", &wire0), ("b1", &wire1)] {
        let out = collector.accept(wire);
        println!(
            "accept({name}) -> {:?} {}",
            out.status,
            out.code.unwrap_or("")
        );
        assert_eq!(out.status, Status::Accept);
    }

    // =========================================================================
    // 4. VERIFIER: publish a chain-head checkpoint, classify coverage.
    // =========================================================================
    let highest_seq = b1.first_sequence + b1.record_count as u64 - 1; // 1002
    let mut checkpoint = Checkpoint {
        producer_id,
        boot_epoch: 7,
        highest_sequence: highest_seq,
        batch_root: b1.batch_root(),
        checkpoint_time: base_time + 1,
        checkpoint_sequence: 1,
        signing_key_id: *b"\xc0\xff\xee\x00\xc0\xff\xee\x00",
        signature: [0u8; 64],
    };
    checkpoint.signature = ed25519_sign(&checkpoint_seed, &checkpoint.root());

    println!("\n── 4. Verifier (coverage) ────────────────────────────────────");
    let segment = vec![wire0.clone(), wire1.clone()];
    let request = CoverageRequest {
        producer_id,
        boot_epoch: 7,
        first_sequence: 1000,
        last_sequence: highest_seq,
    };
    let producer_keys = HashMap::from([(signing_key_id, producer_pk)]);
    let checkpoint_keys = HashMap::from([(checkpoint.signing_key_id, checkpoint_pk)]);
    let schemas = HashMap::from([(sdig, schema.clone())]);
    let authorized_schemas = HashSet::from([sdig]);
    let context = VerificationContext {
        producer_keys: &producer_keys,
        checkpoint_keys: &checkpoint_keys,
        schemas: &schemas,
        authorized_schemas: &authorized_schemas,
    };
    let status = classify_coverage(&segment, Some(&checkpoint), &context, &request);
    println!("coverage(intact, anchored)      -> {status}");
    assert_eq!(status, "complete");

    // One flipped byte in stored batch b0 -> tampered.
    let mut tampered = wire0.clone();
    let last = tampered.len() - 1;
    tampered[last] ^= 0x01; // flip a signature byte
    let status_t = classify_coverage(
        &[tampered, wire1.clone()],
        Some(&checkpoint),
        &context,
        &request,
    );
    println!("coverage(1 byte flipped in b0)  -> {status_t}");
    assert_eq!(status_t, "tampered");

    // Drop the tail below the checkpoint -> truncated.
    let status_x = classify_coverage(
        std::slice::from_ref(&wire0),
        Some(&checkpoint),
        &context,
        &request,
    );
    println!("coverage(tail below checkpoint) -> {status_x}");
    assert_eq!(status_x, "truncated");

    // =========================================================================
    // 5. DECODER: render verified records as compact ATP-TAB rows (§13).
    // =========================================================================
    println!("\n── 5. Stateless decoder (ATP-TAB) ────────────────────────────");
    print!(
        "{}",
        render_batches(&[&b0, &b1], &schemas, &entities).unwrap()
    );

    println!("\n✓ end-to-end lifecycle OK");
}

fn hex_seed(s: &str) -> [u8; 32] {
    let mut out = [0u8; 32];
    for i in 0..32 {
        out[i] = u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).unwrap();
    }
    out
}
