package io.openkedge.atp;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A durable, monotonic allocator of {@code boot_epoch} (ATP-0001 §8.2). Every
 * process start MUST obtain an epoch strictly greater than any previously
 * created. A real implementation persists atomically; the in-memory variant here
 * models the durable counter for tests.
 */
public interface EpochStore {
    /** Return a fresh epoch strictly greater than every previously returned value. */
    long allocateEpoch();

    /** In-memory monotonic allocator shared across simulated restarts (test/dev). */
    final class InMemory implements EpochStore {
        private final AtomicLong last;

        public InMemory() {
            this(-1);
        }

        public InMemory(long startAfter) {
            this.last = new AtomicLong(startAfter);
        }

        @Override
        public long allocateEpoch() {
            return last.incrementAndGet();
        }
    }
}
