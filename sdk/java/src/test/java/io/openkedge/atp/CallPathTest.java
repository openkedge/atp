package io.openkedge.atp;

import io.openkedge.atp.Fixtures.PodPhase;
import io.openkedge.atp.Fixtures.PodTransition;
import io.openkedge.atp.TestSupport.RecordingTransport;
import io.openkedge.atp.TestSupport.RecordingTransport.Mode;

/**
 * No network on the emit call path (operational invariant no-network-on-call-path):
 * emit() buffers durably and returns; transmission happens only on the
 * background flush path.
 */
public final class CallPathTest {
    private CallPathTest() {}

    public static void emitDoesNoNetworkIo() {
        RecordingTransport t = new RecordingTransport(Mode.ALWAYS_ACK);
        AtpProducer p = TestSupport.baseProducer(t).onFull(AtpProducer.OnFull.BLOCK).build();
        AtpEmitter<PodTransition> e = p.emitter(PodTransition.class);

        e.emit(EntityId.of("k8s", "pod", "x"),
                new PodTransition(PodPhase.Running, PodPhase.Failed, 1, null));
        Check.eq(t.transmitted.size(), 0, "emit performs no transmission (off the call path)");

        p.flush();
        Check.eq(t.transmitted.size(), 1, "transmission happens on flush");
    }
}
