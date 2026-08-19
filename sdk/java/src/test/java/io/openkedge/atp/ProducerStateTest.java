package io.openkedge.atp;

import io.openkedge.atp.Fixtures.PodPhase;
import io.openkedge.atp.Fixtures.PodTransition;
import io.openkedge.atp.TestSupport.RecordingTransport;
import io.openkedge.atp.TestSupport.RecordingTransport.Mode;
import io.openkedge.atp.internal.Batch;
import io.openkedge.atp.internal.Constants;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Producer state-machine invariants (ATP-0001 §7.6, §8): monotonic epoch,
 * contiguous sequence + triple uniqueness, previous_root chaining, and
 * exact-byte retransmission.
 */
public final class ProducerStateTest {
    private ProducerStateTest() {}

    private static AtpProducer producer(RecordingTransport t, EpochStore store) {
        return TestSupport.baseProducer(t).epochStore(store)
                .onFull(AtpProducer.OnFull.BLOCK).build();
    }

    private static PodTransition ev() {
        return new PodTransition(PodPhase.Running, PodPhase.Failed, 1, null);
    }

    public static void bootEpochStrictlyIncreasesAcrossRestart() {
        EpochStore store = new EpochStore.InMemory();
        long prev = Long.MIN_VALUE;
        for (int restart = 0; restart < 5; restart++) {
            AtpProducer p = producer(new RecordingTransport(Mode.ALWAYS_ACK), store);
            Check.isTrue(p.bootEpoch() > prev, "boot_epoch strictly increases across restart " + restart);
            prev = p.bootEpoch();
        }
    }

    public static void sequenceContiguousNoGaps() {
        AtpProducer p = producer(new RecordingTransport(Mode.ALWAYS_ACK), new EpochStore.InMemory());
        AtpEmitter<PodTransition> e = p.emitter(PodTransition.class);
        EntityId entity = EntityId.of("k8s", "pod", "x");

        RecordHandle h0 = e.emit(entity, ev());
        RecordHandle h1 = e.emit(entity, ev());
        RecordHandle h2 = e.emit(entity, ev());
        p.flush();
        RecordHandle h3 = e.emit(entity, ev());
        RecordHandle h4 = e.emit(entity, ev());
        p.flush();

        Check.eq(h0.sequence(), 0L, "seq0");
        Check.eq(h1.sequence(), 1L, "seq1");
        Check.eq(h2.sequence(), 2L, "seq2");
        Check.eq(h3.sequence(), 3L, "seq3");
        Check.eq(h4.sequence(), 4L, "seq4");

        List<Batch> batches = p.committedBatches();
        Check.eq(batches.size(), 2, "two batches");
        Check.eq(batches.get(0).firstSequence, 0L, "batch0 first_sequence");
        Check.eq(batches.get(0).recordCount, 3, "batch0 record_count");
        Check.eq(batches.get(1).firstSequence, 3L, "batch1 first_sequence = prior exclusive end");
        Check.eq(batches.get(1).recordCount, 2, "batch1 record_count");
    }

    public static void tripleUniqueForAllTime() {
        AtpProducer p = producer(new RecordingTransport(Mode.ALWAYS_ACK), new EpochStore.InMemory());
        AtpEmitter<PodTransition> e = p.emitter(PodTransition.class);
        EntityId entity = EntityId.of("k8s", "pod", "x");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            RecordHandle h = e.emit(entity, ev());
            Check.isTrue(seen.add(h.bootEpoch() + ":" + h.sequence()),
                    "(boot_epoch, sequence) unique for record " + i);
            if (i % 4 == 3) {
                p.flush();
            }
        }
    }

    public static void previousRootChainsAcceptedRoots() {
        AtpProducer p = producer(new RecordingTransport(Mode.ALWAYS_ACK), new EpochStore.InMemory());
        AtpEmitter<PodTransition> e = p.emitter(PodTransition.class);
        EntityId entity = EntityId.of("k8s", "pod", "x");
        e.emit(entity, ev());
        p.flush();
        e.emit(entity, ev());
        p.flush();

        List<Batch> batches = p.committedBatches();
        Check.eqBytes(batches.get(0).previousRoot, Constants.ZERO32, "genesis previous_root is 32*0x00");
        Check.eqBytes(batches.get(1).previousRoot, batches.get(0).batchRoot(),
                "batch1 previous_root chains batch0 batch_root");
    }

    public static void retransmitIsByteIdentical() {
        // Time out once, then acknowledge; the retransmitted bytes MUST be identical.
        RecordingTransport t = new RecordingTransport(Mode.ACK_AFTER, 1);
        AtpProducer p = TestSupport.baseProducer(t).maxTransmitAttempts(3)
                .onFull(AtpProducer.OnFull.BLOCK).build();
        AtpEmitter<PodTransition> e = p.emitter(PodTransition.class);
        e.emit(EntityId.of("k8s", "pod", "x"), ev());
        p.flush();

        Check.eq(t.transmitted.size(), 2, "one timeout then one ack => two transmissions");
        Check.eqBytes(t.transmitted.get(0), t.transmitted.get(1),
                "retransmission is byte-identical to the first attempt");
        Check.eq(p.committedBatches().size(), 1, "batch committed after ack");
    }
}
