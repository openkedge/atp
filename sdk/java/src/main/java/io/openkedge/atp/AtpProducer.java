package io.openkedge.atp;

import io.openkedge.atp.SchemaBinder.BoundSchema;
import io.openkedge.atp.internal.Batch;
import io.openkedge.atp.internal.Constants;
import io.openkedge.atp.internal.RecordEncoder;
import io.openkedge.atp.internal.RecordEncoder.Value;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The TCB producer: it owns sequence, {@code boot_epoch}, {@code previous_root},
 * batching, Merkle, {@code batch_root}, and Ed25519 signing. Application code
 * touches only {@link AtpEmitter}. This class encodes the ATP-0001 §7-§8 producer
 * invariants: monotonic epoch, contiguous sequence, previous_root chaining,
 * exact-byte retransmission, and never-silent-drop back-pressure.
 *
 * <p>This reference producer is synchronous (flush transmits and awaits ack
 * inline) so its state machine is deterministically testable; a production build
 * would run the flush/ack/retransmit loop on a background thread.
 */
public final class AtpProducer {

    /** Back-pressure policy on a full durable buffer. Neither policy ever drops. */
    public enum OnFull { BLOCK, FAIL_CLOSED }

    /** Signals a full durable buffer under {@link OnFull#FAIL_CLOSED} (never a drop). */
    public static final class AtpBackpressureException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public AtpBackpressureException(String message) {
            super(message);
        }

        public String code() {
            return Constants.E_BUFFER_FULL;
        }
    }

    private final byte[] producerId;   // 16 bytes
    private final byte[] seed;         // 32 bytes (TCB secret; never exposed)
    private final byte[] signingKeyId; // 8 bytes
    private final byte[] clockQuality; // 5 bytes
    private final Transport transport;
    private final int capacity;
    private final OnFull onFull;
    private final int maxTransmitAttempts;
    private final Function<Object, byte[]> opaqueEncoder;

    private final long bootEpoch;
    private long nextSequence;
    private byte[] previousRoot = Constants.ZERO32.clone();
    private final LinkedHashMap<String, Long> epochEntityAliases = new LinkedHashMap<>();

    private final List<Pending> pending = new ArrayList<>();
    private final List<Sealed> sealedUnacked = new ArrayList<>();

    // Inspection (evidence / tests) — read-only views of committed output.
    private final List<Batch> committedBatches = new ArrayList<>();
    private final List<byte[]> committedWires = new ArrayList<>();

    private AtpProducer(Builder b) {
        this.producerId = b.producerId.clone();
        this.seed = b.seed.clone();
        this.signingKeyId = b.signingKeyId.clone();
        this.clockQuality = b.clockQuality.clone();
        this.transport = b.transport;
        this.capacity = b.capacity;
        this.onFull = b.onFull;
        this.maxTransmitAttempts = b.maxTransmitAttempts;
        this.opaqueEncoder = b.opaqueEncoder != null ? b.opaqueEncoder : AtpProducer::defaultOpaque;
        this.nextSequence = b.initialSequence;
        // Monotonic epoch allocation happens exactly once, at construction (ATP-0001 §8.2).
        this.bootEpoch = b.epochStore.allocateEpoch();
    }

    public static Builder builder() {
        return new Builder();
    }

    public <E> AtpEmitter<E> emitter(Class<E> eventType) {
        return new DefaultEmitter<>(this, SchemaBinder.bind(eventType));
    }

    public long bootEpoch() {
        return bootEpoch;
    }

    // ---- inspection accessors (evidence/tests; not part of the app trust path) ----

    public List<Batch> committedBatches() {
        return List.copyOf(committedBatches);
    }

    public List<byte[]> committedWires() {
        return List.copyOf(committedWires);
    }

    public byte[] currentPreviousRoot() {
        return previousRoot.clone();
    }

    // ---- the emit/flush state machine ----

    private synchronized RecordHandle enqueue(BoundSchema schema, EntityId subject,
                                              Object event, long eventTimeMs) {
        int buffered = pending.size() + unackedRecordCount();
        if (buffered >= capacity) {
            if (onFull == OnFull.FAIL_CLOSED) {
                throw new AtpBackpressureException(
                        Constants.E_BUFFER_FULL + ": durable buffer full (" + capacity + ")");
            }
            // BLOCK: try to drain first; if still full, fail closed rather than drop.
            drainUnacked();
            if (pending.size() + unackedRecordCount() >= capacity) {
                throw new AtpBackpressureException(
                        Constants.E_BUFFER_FULL + ": durable buffer full after drain");
            }
        }
        long assignedSeq = nextSequence + pending.size();
        DefaultHandle handle = new DefaultHandle(assignedSeq, bootEpoch);
        pending.add(new Pending(schema, subject, event, eventTimeMs, handle));
        return handle;
    }

    /** Seal buffered records into a batch, transmit, and await ack (retransmit on timeout). */
    public synchronized void flush() {
        // Crash-recovery order: retransmit any sealed-but-unacked batch first (§8.3).
        drainUnacked();
        if (!sealedUnacked.isEmpty()) {
            return; // cannot advance until the prior batch is acknowledged
        }
        if (pending.isEmpty()) {
            return;
        }
        sealedUnacked.add(seal(new ArrayList<>(pending)));
        pending.clear();
        drainUnacked();
    }

    private int unackedRecordCount() {
        int n = 0;
        for (Sealed s : sealedUnacked) {
            n += s.count;
        }
        return n;
    }

    private Sealed seal(List<Pending> batch) {
        long baseTime = Long.MAX_VALUE;
        for (Pending p : batch) {
            baseTime = Math.min(baseTime, p.eventTimeMs);
        }

        // Per-batch schema dictionary (first-seen order).
        LinkedHashMap<String, Integer> schemaIndex = new LinkedHashMap<>();
        List<byte[]> schemaDigests = new ArrayList<>();
        // Per-batch new-entity delta (first seen this epoch, appended in order).
        List<String> batchNewAliases = new ArrayList<>();
        java.util.function.ToLongFunction<EntityId> aliasResolver = e -> {
            String key = e.canonical();
            Long existing = epochEntityAliases.get(key);
            if (existing != null) {
                return existing;
            }
            long alias = epochEntityAliases.size();
            epochEntityAliases.put(key, alias);
            batchNewAliases.add(key);
            return alias;
        };

        List<byte[]> records = new ArrayList<>();
        for (Pending p : batch) {
            byte[] digest = p.schema.manifest.digest();
            String dhex = java.util.HexFormat.of().formatHex(digest);
            int schemaRef = schemaIndex.computeIfAbsent(dhex, k -> {
                schemaDigests.add(digest);
                return schemaIndex.size();
            });
            long entityRef = aliasResolver.applyAsLong(p.subject); // subject alias first
            Map<Integer, Value> values = p.schema.extract(p.event, aliasResolver, opaqueEncoder);
            long timeDelta = p.eventTimeMs - baseTime;
            records.add(RecordEncoder.encodeRecord(p.schema.manifest, schemaRef, entityRef,
                    timeDelta, values));
        }

        Batch b = Batch.build(Constants.PROTOCOL_VERSION_V0_1, producerId, bootEpoch,
                nextSequence, baseTime, clockQuality, schemaDigests, batchNewAliases,
                previousRoot.clone(), records, signingKeyId, seed);
        byte[] wire = b.serialize(); // persist exact sealed bytes (durable-before-transmit)
        List<DefaultHandle> handles = new ArrayList<>();
        for (Pending p : batch) {
            handles.add(p.handle);
        }
        return new Sealed(b, wire, nextSequence, records.size(), handles);
    }

    private void drainUnacked() {
        while (!sealedUnacked.isEmpty()) {
            Sealed s = sealedUnacked.get(0);
            boolean acked = false;
            for (int attempt = 0; attempt < maxTransmitAttempts; attempt++) {
                // Retransmit the EXACT sealed bytes every attempt (§8.1).
                Transport.Result r = transport.transmit(s.wire);
                if (r.acknowledged()) {
                    acked = true;
                    break;
                }
            }
            if (!acked) {
                return; // keep the sealed bytes durably buffered; never drop
            }
            // Commit: advance sequence and previous_root only on acknowledgement (§8.1.5).
            nextSequence = s.firstSequence + s.count;
            previousRoot = s.batch.batchRoot();
            committedBatches.add(s.batch);
            committedWires.add(s.wire);
            Ack ack = new Ack(java.util.HexFormat.of().formatHex(producerId), bootEpoch,
                    s.firstSequence, s.count);
            for (DefaultHandle h : s.handles) {
                h.future.complete(ack);
            }
            sealedUnacked.remove(0);
        }
    }

    private static byte[] defaultOpaque(Object raw) {
        String mediaType;
        byte[] payload;
        if (raw instanceof byte[] b) {
            mediaType = "application/octet-stream";
            payload = b;
        } else if (raw instanceof Throwable t) {
            mediaType = "text/plain";
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            payload = sw.toString().getBytes(StandardCharsets.UTF_8);
        } else {
            mediaType = "text/plain";
            payload = String.valueOf(raw).getBytes(StandardCharsets.UTF_8);
        }
        String opaqueId = "op-" + java.util.HexFormat.of()
                .formatHex(io.openkedge.atp.internal.Crypto.sha256(payload)).substring(0, 24);
        return OpaqueRef.build(opaqueId, mediaType, payload, "atp-opaque:" + opaqueId, 0);
    }

    // ---- internal record holders ----

    private record Pending(BoundSchema schema, EntityId subject, Object event,
                           long eventTimeMs, DefaultHandle handle) {}

    private record Sealed(Batch batch, byte[] wire, long firstSequence, int count,
                          List<DefaultHandle> handles) {}

    private static final class DefaultHandle implements RecordHandle {
        private final long sequence;
        private final long bootEpoch;
        private final CompletableFuture<Ack> future = new CompletableFuture<>();

        DefaultHandle(long sequence, long bootEpoch) {
            this.sequence = sequence;
            this.bootEpoch = bootEpoch;
        }

        @Override
        public long sequence() {
            return sequence;
        }

        @Override
        public long bootEpoch() {
            return bootEpoch;
        }

        @Override
        public CompletableFuture<Ack> acknowledged() {
            return future;
        }
    }

    private static final class DefaultEmitter<E> implements AtpEmitter<E> {
        private final AtpProducer producer;
        private final BoundSchema schema;
        private final EntityId bound;      // nullable
        private final Instant when;        // nullable => call time

        DefaultEmitter(AtpProducer producer, BoundSchema schema) {
            this(producer, schema, null, null);
        }

        private DefaultEmitter(AtpProducer producer, BoundSchema schema, EntityId bound, Instant when) {
            this.producer = producer;
            this.schema = schema;
            this.bound = bound;
            this.when = when;
        }

        @Override
        public RecordHandle emit(EntityId entity, E event) {
            long t = (when != null ? when : Instant.now()).toEpochMilli();
            return producer.enqueue(schema, entity, event, t);
        }

        @Override
        public RecordHandle emit(E event) {
            if (bound == null) {
                throw new IllegalStateException("emit(event) requires forEntity(...) binding");
            }
            return emit(bound, event);
        }

        @Override
        public AtpEmitter<E> forEntity(EntityId boundEntity) {
            return new DefaultEmitter<>(producer, schema, boundEntity, when);
        }

        @Override
        public AtpEmitter<E> at(Instant instant) {
            return new DefaultEmitter<>(producer, schema, bound, instant);
        }

        @Override
        public SchemaId schemaId() {
            return schema.schemaId;
        }
    }

    /** TCB configuration builder (off the call path, DESIGN §3.3). */
    public static final class Builder {
        private byte[] producerId;
        private byte[] seed;
        private byte[] signingKeyId;
        private byte[] clockQuality = new byte[] {0, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        private Transport transport;
        private EpochStore epochStore = new EpochStore.InMemory();
        private int capacity = Integer.MAX_VALUE;
        private OnFull onFull = OnFull.BLOCK;
        private int maxTransmitAttempts = 5;
        private long initialSequence = 0;
        private Function<Object, byte[]> opaqueEncoder;

        public Builder producerId(byte[] id) {
            this.producerId = id;
            return this;
        }

        /** Ed25519 32-byte signing seed and its 8-byte key id (TCB secret, never exposed). */
        public Builder signingKey(byte[] seed32, byte[] signingKeyId8) {
            this.seed = seed32;
            this.signingKeyId = signingKeyId8;
            return this;
        }

        public Builder clockQuality(byte[] fiveBytes) {
            this.clockQuality = fiveBytes;
            return this;
        }

        public Builder transport(Transport t) {
            this.transport = t;
            return this;
        }

        public Builder epochStore(EpochStore s) {
            this.epochStore = s;
            return this;
        }

        public Builder capacity(int c) {
            this.capacity = c;
            return this;
        }

        public Builder onFull(OnFull policy) {
            this.onFull = policy;
            return this;
        }

        public Builder maxTransmitAttempts(int n) {
            this.maxTransmitAttempts = n;
            return this;
        }

        public Builder initialSequence(long seq) {
            this.initialSequence = seq;
            return this;
        }

        public Builder opaqueEncoder(Function<Object, byte[]> enc) {
            this.opaqueEncoder = enc;
            return this;
        }

        public AtpProducer build() {
            if (producerId == null || producerId.length != 16) {
                throw new IllegalArgumentException("producerId must be 16 bytes");
            }
            if (seed == null || seed.length != 32) {
                throw new IllegalArgumentException("signing seed must be 32 bytes");
            }
            if (signingKeyId == null || signingKeyId.length != 8) {
                throw new IllegalArgumentException("signingKeyId must be 8 bytes");
            }
            if (clockQuality.length != 5) {
                throw new IllegalArgumentException("clockQuality must be 5 bytes");
            }
            if (transport == null) {
                throw new IllegalArgumentException("transport is required");
            }
            return new AtpProducer(this);
        }
    }
}
