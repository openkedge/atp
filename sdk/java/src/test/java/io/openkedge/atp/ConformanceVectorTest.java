package io.openkedge.atp;

import io.openkedge.atp.Fixtures.PodTransition;
import io.openkedge.atp.SchemaBinder.BoundSchema;
import io.openkedge.atp.internal.Batch;
import io.openkedge.atp.internal.Crypto;
import io.openkedge.atp.internal.Manifest;
import io.openkedge.atp.internal.Merkle;
import io.openkedge.atp.internal.RecordEncoder;
import io.openkedge.atp.internal.RecordEncoder.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reproduces the foundational cross-language vector CV-CORE-001 (ATP-0001
 * Appendix B) byte-for-byte, driven through the reflection schema binder. This
 * is the admission-critical evidence for the atp-java-producer protocol.
 */
public final class ConformanceVectorTest {
    private ConformanceVectorTest() {}

    // The continuity anchor also asserted by the Python and Rust references.
    static final String POD_TRANSITION_DIGEST =
            "b8fafb40cf935d94f1ef933ce411f5d7d881a82413fc463a45db30a37a4103f2";

    private static Map<String, Object> vector() {
        return Vectors.load("CV-CORE-001.json");
    }

    private static BoundSchema schema() {
        return SchemaBinder.bind(PodTransition.class);
    }

    public static void cvCore001ManifestCbor() {
        Manifest m = schema().manifest;
        Check.eqHex(m.canonicalCbor(),
                Json.str(vector().get("schema_manifest_canonical_cbor")),
                "CV-CORE-001 canonical manifest CBOR");
    }

    public static void cvCore001SchemaDigest() {
        Check.eqHex(schema().manifest.digest(),
                Json.str(vector().get("schema_digest_sha256")),
                "CV-CORE-001 schema digest H_S");
    }

    public static void digestMatchesIndependentStacks() {
        // The Java-derived H_S equals the digest the Rust/Python stacks reproduce
        // (ATP-0001 §1.6 cross-language identity).
        Check.eqHex(schema().manifest.digest(), POD_TRANSITION_DIGEST,
                "cross-language digest identity");
    }

    private static List<byte[]> encodeRecords() {
        BoundSchema s = schema();
        // reason is null -> optional absent; exit_code = 137.
        PodTransition e0 = new PodTransition(PodPhase(1), PodPhase(3), 137, null);
        PodTransition e1 = new PodTransition(PodPhase(1), PodPhase(3), 137, null);
        Map<Integer, Value> v0 = s.extract(e0, ConformanceVectorTest::noEntity, null);
        Map<Integer, Value> v1 = s.extract(e1, ConformanceVectorTest::noEntity, null);
        byte[] rec0 = RecordEncoder.encodeRecord(s.manifest, 0, 0, 10, v0);
        byte[] rec1 = RecordEncoder.encodeRecord(s.manifest, 0, 1, 15, v1);
        List<byte[]> records = new ArrayList<>();
        records.add(rec0);
        records.add(rec1);
        return records;
    }

    public static void cvCore001Records() {
        Map<String, Object> v = vector();
        List<Object> recs = Json.arr(v.get("records"));
        List<byte[]> got = encodeRecords();
        Check.eqHex(got.get(0), Json.str(Json.obj(recs.get(0)).get("bytes")), "CV-CORE-001 record[0]");
        Check.eqHex(got.get(1), Json.str(Json.obj(recs.get(1)).get("bytes")), "CV-CORE-001 record[1]");
    }

    public static void cvCore001Merkle() {
        Check.eqHex(Merkle.merkleRoot(encodeRecords()),
                Json.str(vector().get("merkle_root")), "CV-CORE-001 merkle_root");
    }

    private static Batch buildBatch() {
        Map<String, Object> v = vector();
        Map<String, Object> hdr = Json.obj(v.get("batch_header"));
        List<String> entities = new ArrayList<>();
        for (Object a : Json.arr(v.get("entity_aliases"))) {
            entities.add(Json.str(a));
        }
        List<byte[]> digests = new ArrayList<>();
        digests.add(schema().manifest.digest());
        return Batch.build(
                0x0001,
                Check.unhex(Json.str(hdr.get("producer_id"))),
                Json.lng(hdr.get("boot_epoch")),
                Json.lng(hdr.get("first_sequence")),
                Json.lng(hdr.get("base_time_unix_ms")),
                Check.unhex(Json.str(hdr.get("clock_quality_bytes"))),
                digests,
                entities,
                new byte[32],
                encodeRecords(),
                Check.unhex(Json.str(hdr.get("signing_key_id"))),
                Check.unhex(Json.str(v.get("ed25519_private_seed_test_only"))));
    }

    public static void cvCore001BatchRoot() {
        Map<String, Object> v = vector();
        Batch b = buildBatch();
        Check.eqHex(b.batchRootPreimage(), Json.str(v.get("batch_root_preimage")),
                "CV-CORE-001 batch_root preimage (197 bytes)");
        Check.eq(b.batchRootPreimage().length, 197, "batch_root preimage length");
        Check.eqHex(b.batchRoot(), Json.str(v.get("batch_root")), "CV-CORE-001 batch_root");
    }

    public static void cvCore001Signature() {
        Map<String, Object> v = vector();
        Batch b = buildBatch();
        Check.eqHex(b.signature, Json.str(v.get("signature")), "CV-CORE-001 signature");
        byte[] pk = Check.unhex(Json.str(v.get("ed25519_public_key")));
        Check.isTrue(Crypto.ed25519Verify(pk, b.batchRoot(), b.signature),
                "signature verifies under strict RFC 8032 against expected public key");
    }

    public static void cvCore001BatchWire() {
        Map<String, Object> v = vector();
        Check.eqHex(buildBatch().serialize(), Json.str(v.get("batch_wire")), "CV-CORE-001 batch wire");
    }

    private static Fixtures.PodPhase PodPhase(int ordinal) {
        return Fixtures.PodPhase.values()[ordinal];
    }

    private static long noEntity(EntityId e) {
        throw new IllegalStateException("no ENTITY_REF field in this schema");
    }
}
