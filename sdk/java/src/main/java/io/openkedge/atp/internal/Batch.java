package io.openkedge.atp.internal;

import static io.openkedge.atp.internal.Constants.D_BATCH;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Canonical batch: wire serialization, {@code batch_root}, and Ed25519 signing.
 * ATP-0001 §7. TCB-internal.
 */
public final class Batch {
    public final int protocolVersion;      // u16
    public final byte[] producerId;        // 16 bytes
    public final long bootEpoch;           // u64
    public final long firstSequence;       // u64
    public final int recordCount;          // u32
    public final long baseTime;            // u64
    public final byte[] clockQuality;      // 5 bytes
    public final List<byte[]> schemaDigests;   // each 32 bytes
    public final List<String> entityDelta;
    public final byte[] previousRoot;      // 32 bytes
    public final List<byte[]> records;     // unframed records
    public final byte[] merkleRoot;        // 32 bytes
    public final byte[] signingKeyId;      // 8 bytes
    public byte[] signature;               // 64 bytes

    private Batch(int protocolVersion, byte[] producerId, long bootEpoch, long firstSequence,
                  int recordCount, long baseTime, byte[] clockQuality, List<byte[]> schemaDigests,
                  List<String> entityDelta, byte[] previousRoot, List<byte[]> records,
                  byte[] merkleRoot, byte[] signingKeyId, byte[] signature) {
        this.protocolVersion = protocolVersion;
        this.producerId = producerId;
        this.bootEpoch = bootEpoch;
        this.firstSequence = firstSequence;
        this.recordCount = recordCount;
        this.baseTime = baseTime;
        this.clockQuality = clockQuality;
        this.schemaDigests = schemaDigests;
        this.entityDelta = entityDelta;
        this.previousRoot = previousRoot;
        this.records = records;
        this.merkleRoot = merkleRoot;
        this.signingKeyId = signingKeyId;
        this.signature = signature;
    }

    private byte[] schemaDict() {
        ByteArrayOutputStream v = new ByteArrayOutputStream();
        v.writeBytes(Varint.uvarint(schemaDigests.size()));
        for (byte[] d : schemaDigests) {
            v.writeBytes(d);
        }
        return v.toByteArray();
    }

    private byte[] entityDict() {
        ByteArrayOutputStream v = new ByteArrayOutputStream();
        v.writeBytes(Varint.uvarint(entityDelta.size()));
        for (String e : entityDelta) {
            byte[] bytes = e.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            v.writeBytes(Varint.uvarint(bytes.length));
            v.writeBytes(bytes);
        }
        return v.toByteArray();
    }

    /** ATP-0001 §7.4 batch_root preimage and hash (197-byte preimage in v0.1). */
    public byte[] batchRoot() {
        ByteArrayOutputStream pre = new ByteArrayOutputStream();
        pre.writeBytes(D_BATCH);
        pre.writeBytes(be(protocolVersion & 0xffffL, 2));
        pre.writeBytes(producerId);
        pre.writeBytes(be(bootEpoch, 8));
        pre.writeBytes(be(firstSequence, 8));
        pre.writeBytes(be(recordCount & 0xffff_ffffL, 4));
        pre.writeBytes(be(baseTime, 8));
        pre.writeBytes(clockQuality);
        pre.writeBytes(Crypto.sha256(schemaDict()));
        pre.writeBytes(Crypto.sha256(entityDict()));
        pre.writeBytes(previousRoot);
        pre.writeBytes(merkleRoot);
        return Crypto.sha256(pre.toByteArray());
    }

    /** The exact 197-byte v0.1 preimage (test/evidence use). */
    public byte[] batchRootPreimage() {
        ByteArrayOutputStream pre = new ByteArrayOutputStream();
        pre.writeBytes(D_BATCH);
        pre.writeBytes(be(protocolVersion & 0xffffL, 2));
        pre.writeBytes(producerId);
        pre.writeBytes(be(bootEpoch, 8));
        pre.writeBytes(be(firstSequence, 8));
        pre.writeBytes(be(recordCount & 0xffff_ffffL, 4));
        pre.writeBytes(be(baseTime, 8));
        pre.writeBytes(clockQuality);
        pre.writeBytes(Crypto.sha256(schemaDict()));
        pre.writeBytes(Crypto.sha256(entityDict()));
        pre.writeBytes(previousRoot);
        pre.writeBytes(merkleRoot);
        return pre.toByteArray();
    }

    public byte[] serialize() {
        ByteArrayOutputStream w = new ByteArrayOutputStream();
        w.writeBytes(be(protocolVersion & 0xffffL, 2));
        w.writeBytes(producerId);
        w.writeBytes(be(bootEpoch, 8));
        w.writeBytes(be(firstSequence, 8));
        w.writeBytes(be(recordCount & 0xffff_ffffL, 4));
        w.writeBytes(be(baseTime, 8));
        w.writeBytes(clockQuality);
        w.writeBytes(schemaDict());
        w.writeBytes(entityDict());
        w.writeBytes(previousRoot);
        for (byte[] r : records) {
            w.writeBytes(Varint.uvarint(r.length));
            w.writeBytes(r);
        }
        w.writeBytes(merkleRoot);
        w.writeBytes(signingKeyId);
        w.writeBytes(signature);
        return w.toByteArray();
    }

    /** Producer-side constructor: computes merkle_root, batch_root, and signs. */
    public static Batch build(int protocolVersion, byte[] producerId, long bootEpoch,
                              long firstSequence, long baseTime, byte[] clockQuality,
                              List<byte[]> schemaDigests, List<String> entityDelta,
                              byte[] previousRoot, List<byte[]> records, byte[] signingKeyId,
                              byte[] seed32) {
        byte[] mroot = Merkle.merkleRoot(records);
        Batch b = new Batch(protocolVersion, producerId, bootEpoch, firstSequence,
                records.size(), baseTime, clockQuality, schemaDigests, entityDelta,
                previousRoot, records, mroot, signingKeyId, new byte[64]);
        byte[] broot = b.batchRoot();
        b.signature = Crypto.ed25519Sign(seed32, broot);
        return b;
    }

    private static byte[] be(long n, int bytes) {
        byte[] out = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            out[i] = (byte) (n >>> (8 * (bytes - 1 - i)));
        }
        return out;
    }
}
