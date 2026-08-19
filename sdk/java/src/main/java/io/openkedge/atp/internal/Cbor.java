package io.openkedge.atp.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic CBOR (RFC 8949 §4.2.1) — the minimal subset needed for ATP
 * schema manifests. ATP-0002 §6.
 *
 * <p>Integers use shortest form; maps are emitted with entries sorted by the
 * bytewise-lexicographic order of the encoded key; there are no indefinite-length
 * items. Text is UTF-8 (major type 3); byte strings are major type 2.
 */
public sealed interface Cbor
        permits Cbor.U, Cbor.I, Cbor.Bool, Cbor.Text, Cbor.Bytes, Cbor.Array, Cbor.MapC {

    /** Unsigned integer (major type 0). {@code v} is treated as unsigned 64-bit. */
    record U(long v) implements Cbor {}

    /** Signed integer; negative uses major type 1, non-negative uses major type 0. */
    record I(long v) implements Cbor {}

    record Bool(boolean v) implements Cbor {}

    record Text(String v) implements Cbor {}

    record Bytes(byte[] v) implements Cbor {}

    record Array(List<Cbor> items) implements Cbor {}

    /** A CBOR map; encoding sorts entries by encoded-key bytes. */
    record MapC(List<Map.Entry<Cbor, Cbor>> entries) implements Cbor {}

    static Cbor u(long v) {
        return new U(v);
    }

    static Cbor i(long v) {
        return new I(v);
    }

    static Cbor bool(boolean v) {
        return new Bool(v);
    }

    static Cbor text(String v) {
        return new Text(v);
    }

    static Cbor bytes(byte[] v) {
        return new Bytes(v);
    }

    static byte[] encode(Cbor c) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeInto(c, out);
        return out.toByteArray();
    }

    private static void head(int major, long n, ByteArrayOutputStream out) {
        int mt = major << 5;
        if (Long.compareUnsigned(n, 24) < 0) {
            out.write(mt | (int) n);
        } else if (Long.compareUnsigned(n, 0x100) < 0) {
            out.write(mt | 24);
            out.write((int) (n & 0xff));
        } else if (Long.compareUnsigned(n, 0x10000) < 0) {
            out.write(mt | 25);
            writeBe(out, n, 2);
        } else if (Long.compareUnsigned(n, 0x1_0000_0000L) < 0) {
            out.write(mt | 26);
            writeBe(out, n, 4);
        } else {
            out.write(mt | 27);
            writeBe(out, n, 8);
        }
    }

    private static void writeBe(ByteArrayOutputStream out, long n, int bytes) {
        for (int i = bytes - 1; i >= 0; i--) {
            out.write((int) ((n >>> (8 * i)) & 0xff));
        }
    }

    private static void encodeInto(Cbor c, ByteArrayOutputStream out) {
        // Java 17: use instanceof chains (type-pattern switch is a 17 preview).
        if (c instanceof U u) {
            head(0, u.v(), out);
        } else if (c instanceof I ivar) {
            long n = ivar.v();
            if (n < 0) {
                head(1, -1 - n, out);
            } else {
                head(0, n, out);
            }
        } else if (c instanceof Bool b) {
            out.write(b.v() ? 0xf5 : 0xf4);
        } else if (c instanceof Text t) {
            byte[] utf8 = t.v().getBytes(StandardCharsets.UTF_8);
            head(3, utf8.length, out);
            out.writeBytes(utf8);
        } else if (c instanceof Bytes by) {
            head(2, by.v().length, out);
            out.writeBytes(by.v());
        } else if (c instanceof Array a) {
            head(4, a.items().size(), out);
            for (Cbor it : a.items()) {
                encodeInto(it, out);
            }
        } else if (c instanceof MapC m) {
            // RFC 8949 §4.2.1: sort by bytewise-lexicographic order of encoded key.
            List<byte[][]> enc = new ArrayList<>();
            for (Map.Entry<Cbor, Cbor> e : m.entries()) {
                enc.add(new byte[][] {encode(e.getKey()), encode(e.getValue())});
            }
            enc.sort((x, y) -> compareLex(x[0], y[0]));
            head(5, enc.size(), out);
            for (byte[][] kv : enc) {
                out.writeBytes(kv[0]);
                out.writeBytes(kv[1]);
            }
        } else {
            throw new IllegalStateException("unknown Cbor node");
        }
    }

    private static int compareLex(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int d = (a[i] & 0xff) - (b[i] & 0xff);
            if (d != 0) {
                return d;
            }
        }
        return a.length - b.length;
    }
}
