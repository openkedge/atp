package io.openkedge.atp;

import java.util.concurrent.CompletableFuture;

/**
 * The evidence coordinate returned by {@code emit}. The application may observe
 * the assigned sequence and await collector commit, but cannot influence the
 * trust path (TCB ownership, DESIGN §2).
 */
public interface RecordHandle {
    /** The assigned per-(producer_id, boot_epoch) sequence. */
    long sequence();

    long bootEpoch();

    /** Resolves on collector commit (ATP-0001 §9.1 stage 13). */
    CompletableFuture<Ack> acknowledged();
}
