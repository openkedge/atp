package io.openkedge.atp.internal;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * RFC 6962 Merkle Tree Hash over unframed record leaves. ATP-0001 §7.3.
 *
 * <pre>
 * MTH([x])   = SHA-256(0x00 || x)
 * MTH(recs)  = SHA-256(0x01 || MTH(recs[0:k]) || MTH(recs[k:n])), n &gt; 1
 *              where k is the largest power of two strictly less than n.
 * </pre>
 */
public final class Merkle {
    private Merkle() {}

    public static byte[] merkleRoot(List<byte[]> leaves) {
        int n = leaves.size();
        if (n < 1) {
            throw new IllegalArgumentException("ATP batches MUST contain >= 1 record");
        }
        return root(leaves, 0, n);
    }

    private static byte[] root(List<byte[]> leaves, int from, int to) {
        int n = to - from;
        if (n == 1) {
            ByteArrayOutputStream pre = new ByteArrayOutputStream();
            pre.write(0x00);
            pre.writeBytes(leaves.get(from));
            return Crypto.sha256(pre.toByteArray());
        }
        int k = 1;
        while (k < n) {
            k <<= 1;
        }
        k >>= 1;
        byte[] left = root(leaves, from, from + k);
        byte[] right = root(leaves, from + k, to);
        ByteArrayOutputStream pre = new ByteArrayOutputStream();
        pre.write(0x01);
        pre.writeBytes(left);
        pre.writeBytes(right);
        return Crypto.sha256(pre.toByteArray());
    }
}
