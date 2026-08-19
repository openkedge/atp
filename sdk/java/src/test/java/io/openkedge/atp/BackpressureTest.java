package io.openkedge.atp;

import io.openkedge.atp.Fixtures.PodPhase;
import io.openkedge.atp.Fixtures.PodTransition;
import io.openkedge.atp.TestSupport.RecordingTransport;
import io.openkedge.atp.TestSupport.RecordingTransport.Mode;

/**
 * Never-drop back-pressure (behavioral invariant never-drop-fail-closed): a full
 * durable buffer blocks or fails the emit closed with a typed error, but never
 * silently discards evidence (ATP-0001 §8.4).
 */
public final class BackpressureTest {
    private BackpressureTest() {}

    private static PodTransition ev() {
        return new PodTransition(PodPhase.Running, PodPhase.Failed, 1, null);
    }

    public static void fullBufferBlocksOrFailsClosedNeverDrops() {
        // Transport never acknowledges, so sealed batches stay in the durable buffer.
        RecordingTransport t = new RecordingTransport(Mode.NEVER_ACK);
        AtpProducer p = TestSupport.baseProducer(t)
                .capacity(2)
                .onFull(AtpProducer.OnFull.FAIL_CLOSED)
                .maxTransmitAttempts(2)
                .build();
        AtpEmitter<PodTransition> e = p.emitter(PodTransition.class);
        EntityId entity = EntityId.of("k8s", "pod", "x");

        e.emit(entity, ev());   // buffered 1
        e.emit(entity, ev());   // buffered 2 (at capacity)
        p.flush();              // seals 2 records; never acked => remain durably buffered

        // The 3rd emit must fail closed, not drop.
        boolean failed = false;
        try {
            e.emit(entity, ev());
        } catch (AtpProducer.AtpBackpressureException ex) {
            failed = true;
            Check.eq(ex.code(), "ATP_ERR_BUFFER_FULL", "typed back-pressure error code");
        }
        Check.isTrue(failed, "emit fails closed on a full buffer");

        // Nothing was committed (never acked) and nothing was silently dropped:
        Check.eq(p.committedBatches().size(), 0, "no batch committed while unacked");
        Check.isTrue(t.transmitted.size() >= 1, "sealed bytes were (re)transmitted, not discarded");
    }
}
