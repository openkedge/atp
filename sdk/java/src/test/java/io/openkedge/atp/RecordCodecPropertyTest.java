package io.openkedge.atp;

import io.openkedge.atp.internal.Constants;
import io.openkedge.atp.internal.FieldDef;
import io.openkedge.atp.internal.Manifest;
import io.openkedge.atp.internal.RecordEncoder;
import io.openkedge.atp.internal.RecordEncoder.Value;
import io.openkedge.atp.internal.Varint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical integer/float/bitmap properties (structural invariant
 * record-canonical-layout, behavioral invariant nan-and-float-canonicalization).
 */
public final class RecordCodecPropertyTest {
    private RecordCodecPropertyTest() {}

    public static void minimalVarints() throws Exception {
        long[] values = {0, 1, 23, 24, 127, 128, 255, 256, 300, 16383, 16384, 65535,
                1_000_000, Long.MAX_VALUE, -1L /* unsigned max */};
        for (long v : values) {
            byte[] enc = Varint.uvarint(v);
            Varint.Reader r = new Varint.Reader(enc);
            long got = r.uvarint();
            Check.isTrue(r.eof(), "uvarint consumed exactly for " + v);
            Check.eq(got, v, "uvarint roundtrip for " + v);
        }
        // Non-minimal encoding of 0 (0x80 0x00) MUST be rejected.
        Check.throwsAny(() -> {
            try {
                new Varint.Reader(new byte[] {(byte) 0x80, 0x00}).uvarint();
            } catch (Varint.DecodeException e) {
                throw new RuntimeException(e);
            }
        }, "non-minimal uvarint rejected");

        long[] signed = {0, 1, -1, 137, -137, 1000, -1000, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long v : signed) {
            Check.eq(Varint.unzigzag(Varint.zigzag(v)), v, "zigzag roundtrip for " + v);
        }
    }

    private static Manifest floats() {
        List<FieldDef> f = new ArrayList<>();
        f.add(new FieldDef(0, "a", Constants.T_F32, true));
        f.add(new FieldDef(1, "b", Constants.T_F64, true));
        return new Manifest("t.floats.o", "1.0.0", 1, "openkedge.io/test", f, new LinkedHashMap<>(), null);
    }

    public static void nanCanonicalization() {
        Manifest m = floats();
        Map<Integer, Value> v = new LinkedHashMap<>();
        v.put(0, new Value.F32V(Float.NaN));
        v.put(1, new Value.F64V(Double.NaN));
        byte[] rec = RecordEncoder.encodeRecord(m, 0, 0, 0, v);
        // header 00 00 00, no optional bitmap; then canonical NaN patterns.
        Check.eqHex(rec, "0000007fc000007ff8000000000000", "canonical NaN F32/F64");

        // Non-NaN bit patterns preserved: -0.0f and +Inf f64.
        Map<Integer, Value> v2 = new LinkedHashMap<>();
        v2.put(0, new Value.F32V(-0.0f));
        v2.put(1, new Value.F64V(Double.POSITIVE_INFINITY));
        Check.eqHex(RecordEncoder.encodeRecord(m, 0, 0, 0, v2),
                "000000800000007ff0000000000000", "negative zero and +Inf preserved");
    }

    private static Manifest twoOptional() {
        List<FieldDef> f = new ArrayList<>();
        f.add(new FieldDef(0, "x", Constants.T_U32, false));
        f.add(new FieldDef(1, "y", Constants.T_U32, false));
        return new Manifest("t.opt.o", "1.0.0", 1, "openkedge.io/test", f, new LinkedHashMap<>(), null);
    }

    public static void presenceBitmap() {
        Manifest m = twoOptional();
        // Only slot 0 present -> bitmap byte 0x01 (bit0 set, unused high bits zero).
        Map<Integer, Value> only0 = new LinkedHashMap<>();
        only0.put(0, new Value.UV(7));
        Check.eqHex(RecordEncoder.encodeRecord(m, 0, 0, 0, only0), "00000001" + "07",
                "presence bitmap slot0 only");
        // Neither present -> bitmap 0x00, no values.
        Check.eqHex(RecordEncoder.encodeRecord(m, 0, 0, 0, new LinkedHashMap<>()), "00000000",
                "presence bitmap none present");
    }
}
