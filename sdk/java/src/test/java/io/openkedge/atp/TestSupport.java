package io.openkedge.atp;

import java.util.ArrayList;
import java.util.List;

/** Shared test doubles and helpers. */
public final class TestSupport {
    private TestSupport() {}

    // A test producer id and Ed25519 seed/keyid (test-only, from CV-CORE-001).
    static final byte[] PRODUCER_ID = Check.unhex("00112233445566778899aabbccddeeff");
    static final byte[] SEED = Check.unhex("9d61b19deffe5a60651c9e0d0e6c1e6bf0a1b2c3d4e5f60718293a4b5c6d7e8f");
    static final byte[] KEY_ID = Check.unhex("a1b2c3d4e5f60708");
    static final byte[] CLOCK_QUALITY = Check.unhex("0100000032");

    /** A transport that records every transmitted byte string and replays a scripted ack pattern. */
    static final class RecordingTransport implements Transport {
        enum Mode { ALWAYS_ACK, NEVER_ACK, ACK_AFTER }

        final List<byte[]> transmitted = new ArrayList<>();
        private final Mode mode;
        private final int timeoutsBeforeAck;
        private int calls;

        RecordingTransport(Mode mode) {
            this(mode, 0);
        }

        RecordingTransport(Mode mode, int timeoutsBeforeAck) {
            this.mode = mode;
            this.timeoutsBeforeAck = timeoutsBeforeAck;
        }

        @Override
        public Result transmit(byte[] sealedBatchBytes) {
            transmitted.add(sealedBatchBytes.clone());
            int n = calls++;
            return switch (mode) {
                case ALWAYS_ACK -> Result.ack();
                case NEVER_ACK -> Result.timeout();
                case ACK_AFTER -> n < timeoutsBeforeAck ? Result.timeout() : Result.ack();
            };
        }
    }

    static AtpProducer.Builder baseProducer(Transport transport) {
        return AtpProducer.builder()
                .producerId(PRODUCER_ID)
                .signingKey(SEED, KEY_ID)
                .clockQuality(CLOCK_QUALITY)
                .transport(transport);
    }
}
