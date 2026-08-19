package io.openkedge.atp;

import io.openkedge.atp.Fixtures.PodPhase;
import io.openkedge.atp.Fixtures.PodTransition;
import io.openkedge.atp.SchemaBinder.BoundSchema;
import io.openkedge.atp.internal.Batch;
import io.openkedge.atp.internal.Manifest;
import io.openkedge.atp.internal.RecordEncoder;
import io.openkedge.atp.internal.RecordEncoder.Value;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Dual-path equivalence (behavioral invariant dual-path-equivalence): the
 * app-facing emitter path and the low-level core path produce identical manifest
 * and record bytes for the same logical inputs.
 */
public final class DualPathTest {
    private DualPathTest() {}

    public static void emitterEqualsCore() {
        // 1. Manifest bytes: reflection binder == core-constructed manifest.
        BoundSchema bound = SchemaBinder.bind(PodTransition.class);
        Manifest core = SchemaVectorTest.podTransition();
        Check.eqBytes(bound.manifest.canonicalCbor(), core.canonicalCbor(),
                "binder manifest == core manifest CBOR");

        // 2. Record + batch bytes: drive the emitter, then reproduce via the core encoder.
        TestSupport.RecordingTransport transport =
                new TestSupport.RecordingTransport(TestSupport.RecordingTransport.Mode.ALWAYS_ACK);
        AtpProducer producer = TestSupport.baseProducer(transport)
                .onFull(AtpProducer.OnFull.BLOCK)
                .build();
        AtpEmitter<PodTransition> emitter = producer.emitter(PodTransition.class);

        EntityId a = EntityId.of("k8s", "pod", "a");
        EntityId b = EntityId.of("k8s", "pod", "b");
        PodTransition e0 = new PodTransition(PodPhase.Running, PodPhase.Failed, 137, null);
        PodTransition e1 = new PodTransition(PodPhase.Pending, PodPhase.Succeeded, null, "boot");
        emitter.at(Instant.ofEpochMilli(1000)).emit(a, e0);
        emitter.at(Instant.ofEpochMilli(1005)).emit(b, e1);
        producer.flush();

        List<Batch> committed = producer.committedBatches();
        Check.eq(committed.size(), 1, "one batch committed");
        Batch batch = committed.get(0);

        // Core reproduction with the same derived (schemaRef, entityRef, timeDelta) values.
        long baseTime = 1000;
        Map<Integer, Value> v0 = bound.extract(e0, x -> {
            throw new IllegalStateException();
        }, null);
        Map<Integer, Value> v1 = bound.extract(e1, x -> {
            throw new IllegalStateException();
        }, null);
        byte[] coreRec0 = RecordEncoder.encodeRecord(bound.manifest, 0, 0, 1000 - baseTime, v0);
        byte[] coreRec1 = RecordEncoder.encodeRecord(bound.manifest, 0, 1, 1005 - baseTime, v1);

        Check.eqBytes(batch.records.get(0), coreRec0, "emitter record[0] == core record[0]");
        Check.eqBytes(batch.records.get(1), coreRec1, "emitter record[1] == core record[1]");

        // The emitter's signed batch verifies (self-consistency).
        Check.isTrue(io.openkedge.atp.internal.Crypto.ed25519Verify(
                        Check.unhex("121b96cf6280559ff9e409d9ca18866f42c4724c9a7eab847eb1e3f34428c5bb"),
                        batch.batchRoot(), batch.signature),
                "emitter batch signature verifies");
    }
}
