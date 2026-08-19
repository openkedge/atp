package io.openkedge.atp.internal;

import static io.openkedge.atp.internal.Constants.T_BOOL;
import static io.openkedge.atp.internal.Constants.T_BYTES;
import static io.openkedge.atp.internal.Constants.T_DURATION_MS;
import static io.openkedge.atp.internal.Constants.T_ENTITY_REF;
import static io.openkedge.atp.internal.Constants.T_ENUM;
import static io.openkedge.atp.internal.Constants.T_F32;
import static io.openkedge.atp.internal.Constants.T_F64;
import static io.openkedge.atp.internal.Constants.T_I32;
import static io.openkedge.atp.internal.Constants.T_I64;
import static io.openkedge.atp.internal.Constants.T_OPAQUE_REF;
import static io.openkedge.atp.internal.Constants.T_STRING;
import static io.openkedge.atp.internal.Constants.T_TIMESTAMP_MS;
import static io.openkedge.atp.internal.Constants.T_U32;
import static io.openkedge.atp.internal.Constants.T_U64;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Canonical record encoding. ATP-0001 §6.
 *
 * <pre>
 * record = uvarint(schema_ref) || uvarint(entity_ref) || uvarint(time_delta)
 *       || presence_bitmap || positional_values
 * </pre>
 */
public final class RecordEncoder {
    private RecordEncoder() {}

    /** A typed value destined for a record's positional area. */
    public sealed interface Value
            permits Value.BoolV, Value.UV, Value.IV, Value.F32V, Value.F64V,
                    Value.EnumV, Value.StrV, Value.BytesV, Value.EntityV, Value.OpaqueV {
        record BoolV(boolean v) implements Value {}
        record UV(long v) implements Value {}
        record IV(long v) implements Value {}
        record F32V(float v) implements Value {}
        record F64V(double v) implements Value {}
        record EnumV(long ordinal) implements Value {}
        record StrV(String v) implements Value {}
        record BytesV(byte[] v) implements Value {}
        record EntityV(long alias) implements Value {}
        record OpaqueV(byte[] v) implements Value {}
    }

    static byte[] encodeValue(int type, Value v) {
        switch (type) {
            case T_BOOL -> {
                return new byte[] {(byte) (((Value.BoolV) v).v() ? 1 : 0)};
            }
            case T_U32 -> {
                long n = ((Value.UV) v).v();
                if (Long.compareUnsigned(n, 0xffff_ffffL) > 0) {
                    throw new IllegalArgumentException("U32 value out of range");
                }
                return Varint.uvarint(n);
            }
            case T_U64, T_DURATION_MS -> {
                return Varint.uvarint(((Value.UV) v).v());
            }
            case T_I32 -> {
                long n = ((Value.IV) v).v();
                if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("I32 value out of range");
                }
                return Varint.svarint(n);
            }
            case T_I64, T_TIMESTAMP_MS -> {
                return Varint.svarint(((Value.IV) v).v());
            }
            case T_F32 -> {
                float f = ((Value.F32V) v).v();
                int bits = Float.isNaN(f) ? 0x7fc0_0000 : Float.floatToRawIntBits(f);
                return new byte[] {
                        (byte) (bits >>> 24), (byte) (bits >>> 16),
                        (byte) (bits >>> 8), (byte) bits
                };
            }
            case T_F64 -> {
                double d = ((Value.F64V) v).v();
                long bits = Double.isNaN(d) ? 0x7ff8_0000_0000_0000L : Double.doubleToRawLongBits(d);
                byte[] out = new byte[8];
                for (int i = 0; i < 8; i++) {
                    out[i] = (byte) (bits >>> (8 * (7 - i)));
                }
                return out;
            }
            case T_ENUM -> {
                return Varint.uvarint(((Value.EnumV) v).ordinal());
            }
            case T_ENTITY_REF -> {
                return Varint.uvarint(((Value.EntityV) v).alias());
            }
            case T_STRING -> {
                byte[] utf8 = ((Value.StrV) v).v().getBytes(StandardCharsets.UTF_8);
                return lp(utf8);
            }
            case T_BYTES -> {
                return lp(((Value.BytesV) v).v());
            }
            case T_OPAQUE_REF -> {
                return lp(((Value.OpaqueV) v).v());
            }
            default -> throw new IllegalArgumentException("value/type mismatch for type " + type);
        }
    }

    private static byte[] lp(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Varint.uvarint(payload.length));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /**
     * Encode a canonical record. {@code values} maps slot -&gt; Value; it must
     * contain all required slots and any present optional slots.
     */
    public static byte[] encodeRecord(Manifest schema, long schemaRef, long entityRef,
                                      long timeDelta, Map<Integer, Value> values) {
        List<FieldDef> optional = schema.fields.stream().filter(f -> !f.required).toList();
        int nbytes = (optional.size() + 7) / 8;
        byte[] bitmap = new byte[nbytes];
        for (int j = 0; j < optional.size(); j++) {
            if (values.containsKey((int) optional.get(j).slot)) {
                bitmap[j >> 3] |= (byte) (1 << (j & 7));
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (FieldDef f : schema.fields) {
            boolean present = f.required || values.containsKey((int) f.slot);
            if (!present) {
                continue;
            }
            Value v = values.get((int) f.slot);
            if (v == null) {
                throw new IllegalStateException("present value missing for slot " + f.slot);
            }
            body.writeBytes(encodeValue(f.type, v));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Varint.uvarint(schemaRef));
        out.writeBytes(Varint.uvarint(entityRef));
        out.writeBytes(Varint.uvarint(timeDelta));
        out.writeBytes(bitmap);
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }
}
