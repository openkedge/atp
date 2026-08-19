package io.openkedge.atp;

/**
 * The off-call-path transport. Implementations transmit sealed batch bytes and
 * return an acknowledgement or signal a timeout. The producer retransmits the
 * exact same bytes on timeout (ATP-0001 §8.1); the transport never re-encodes.
 */
public interface Transport {
    /** Result of a single transmission attempt. */
    record Result(boolean acknowledged) {
        public static Result ack() {
            return new Result(true);
        }

        public static Result timeout() {
            return new Result(false);
        }
    }

    /**
     * Transmit exact sealed batch bytes. The producer calls this only from its
     * background flush/retransmit path, never from {@code emit}.
     */
    Result transmit(byte[] sealedBatchBytes);
}
