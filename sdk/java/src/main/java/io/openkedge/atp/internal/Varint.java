package io.openkedge.atp.internal;

import java.io.ByteArrayOutputStream;

/**
 * LEB128 varints (minimal/canonical) and ZigZag, plus a bounds-checked reader.
 * ATP-0001 §6.2.
 *
 * <p>{@code uvarint} is unsigned LEB128 over an unsigned 64-bit value using the
 * shortest encoding (at most 10 bytes). Signed integers use ZigZag then uvarint.
 */
public final class Varint {
    private Varint() {}

    /** Unsigned LEB128, minimal form. {@code n} is treated as unsigned 64-bit. */
    public static byte[] uvarint(long n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int b = (int) (n & 0x7f);
            n >>>= 7;                    // unsigned shift
            if (n != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                break;
            }
        }
        return out.toByteArray();
    }

    /** ZigZag map: (n << 1) XOR (n >> 63), arithmetic shift for the sign. */
    public static long zigzag(long n) {
        return (n << 1) ^ (n >> 63);
    }

    public static long unzigzag(long u) {
        return (u >>> 1) ^ -(u & 1);
    }

    /** Signed varint: ZigZag followed by uvarint. */
    public static byte[] svarint(long n) {
        return uvarint(zigzag(n));
    }

    /** Non-canonical or truncated ATP encoding. */
    public static final class DecodeException extends Exception {
        private static final long serialVersionUID = 1L;

        public DecodeException() {
            super("non-canonical or truncated ATP encoding");
        }
    }

    /** A cursor over a byte array with bounds-checked, canonicality-enforcing reads. */
    public static final class Reader {
        private final byte[] buf;
        private int pos;

        public Reader(byte[] buf) {
            this.buf = buf;
            this.pos = 0;
        }

        public int position() {
            return pos;
        }

        public byte[] take(int n) throws DecodeException {
            if (n < 0) {
                throw new DecodeException();
            }
            long end = (long) pos + n;
            if (end > buf.length) {
                throw new DecodeException();
            }
            byte[] s = new byte[n];
            System.arraycopy(buf, pos, s, 0, n);
            pos = (int) end;
            return s;
        }

        public int u8() throws DecodeException {
            return take(1)[0] & 0xff;
        }

        /** Read a minimal uvarint; reject overflow and non-canonical encodings. */
        public long uvarint() throws DecodeException {
            int start = pos;
            long val = 0;
            int nbytes = 0;
            while (true) {
                int b = u8();
                // Guard against shift overflow past 64 bits.
                if (nbytes == 9 && (b & 0x7f) > 1) {
                    throw new DecodeException();
                }
                val |= (long) (b & 0x7f) << (7 * nbytes);
                nbytes++;
                if ((b & 0x80) == 0) {
                    break;
                }
                if (nbytes >= 10) {
                    throw new DecodeException();
                }
            }
            // Minimality: the canonical encoding must equal what we consumed.
            byte[] canonical = Varint.uvarint(val);
            if (canonical.length != pos - start) {
                throw new DecodeException();
            }
            for (int i = 0; i < canonical.length; i++) {
                if (canonical[i] != buf[start + i]) {
                    throw new DecodeException();
                }
            }
            return val;
        }

        public boolean eof() {
            return pos == buf.length;
        }
    }
}
