package io.openkedge.atp;

/**
 * A collector acknowledgement for a committed batch (ATP-0001 §9.1 stage 13).
 *
 * @param producerId  hex producer id
 * @param bootEpoch   the batch's epoch
 * @param firstSequence the batch's first sequence
 * @param recordCount number of records acknowledged
 */
public record Ack(String producerId, long bootEpoch, long firstSequence, int recordCount) {}
