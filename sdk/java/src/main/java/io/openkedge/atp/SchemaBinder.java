package io.openkedge.atp;

import io.openkedge.atp.annotations.AtpEnum;
import io.openkedge.atp.annotations.AtpObservation;
import io.openkedge.atp.annotations.AtpRelation;
import io.openkedge.atp.annotations.AtpTransition;
import io.openkedge.atp.annotations.AtpType;
import io.openkedge.atp.annotations.Intent;
import io.openkedge.atp.annotations.MaxLen;
import io.openkedge.atp.annotations.Opaque;
import io.openkedge.atp.annotations.Optional;
import io.openkedge.atp.annotations.Unit;
import io.openkedge.atp.internal.Constants;
import io.openkedge.atp.internal.FieldDef;
import io.openkedge.atp.internal.Manifest;
import io.openkedge.atp.internal.RecordEncoder.Value;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Runtime-reflection binder from an annotated Java {@code record} to a canonical
 * ATP-0002 manifest and its content-addressed {@code H_S} (DESIGN §5.5, the
 * fallback path). Record-component declaration order is the JLS-guaranteed slot
 * order; enum members are in ordinal order. Fails closed on any unmappable type.
 *
 * <p>The compile-time annotation processor is the recommended primary path
 * (DESIGN §5.5); this binder MUST produce identical bytes to it.
 */
public final class SchemaBinder {
    private SchemaBinder() {}

    private static final ConcurrentHashMap<Class<?>, BoundSchema> CACHE = new ConcurrentHashMap<>();

    /** A bound schema: its manifest, digest, and how to extract typed values. */
    public static final class BoundSchema {
        public final Class<?> recordClass;
        public final Manifest manifest;
        public final SchemaId schemaId;
        final List<BoundField> boundFields;

        BoundSchema(Class<?> recordClass, Manifest manifest, List<BoundField> boundFields) {
            this.recordClass = recordClass;
            this.manifest = manifest;
            this.boundFields = boundFields;
            this.schemaId = SchemaId.of(manifest.digest());
        }

        /**
         * Extract slot -&gt; Value from a record instance, omitting null optionals.
         * ENTITY_REF values are resolved through {@code aliasResolver}; OPAQUE_REF
         * values through {@code opaqueEncoder} (raw component value -&gt; OpaqueRef bytes).
         */
        public Map<Integer, Value> extract(Object instance,
                                            ToLongFunction<EntityId> aliasResolver,
                                            Function<Object, byte[]> opaqueEncoder) {
            Map<Integer, Value> out = new LinkedHashMap<>();
            for (BoundField bf : boundFields) {
                Object raw;
                try {
                    raw = bf.component.getAccessor().invoke(instance);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("cannot read component " + bf.component.getName(), e);
                }
                if (raw == null) {
                    if (bf.required) {
                        throw new IllegalArgumentException(
                                "required field '" + bf.name + "' is null");
                    }
                    continue; // optional-absent
                }
                out.put((int) bf.slot, toValue(bf, raw, aliasResolver, opaqueEncoder));
            }
            return out;
        }
    }

    static final class BoundField {
        final long slot;
        final String name;
        final int type;
        final boolean required;
        final RecordComponent component;

        BoundField(long slot, String name, int type, boolean required, RecordComponent component) {
            this.slot = slot;
            this.name = name;
            this.type = type;
            this.required = required;
            this.component = component;
        }
    }

    public static BoundSchema bind(Class<?> recordClass) {
        return CACHE.computeIfAbsent(recordClass, SchemaBinder::doBind);
    }

    private static BoundSchema doBind(Class<?> recordClass) {
        if (!recordClass.isRecord()) {
            throw new IllegalArgumentException("not a record: " + recordClass.getName());
        }
        int primitive;
        String name;
        String version;
        String publisher;
        AtpTransition t = recordClass.getAnnotation(AtpTransition.class);
        AtpObservation o = recordClass.getAnnotation(AtpObservation.class);
        AtpRelation r = recordClass.getAnnotation(AtpRelation.class);
        int kinds = (t != null ? 1 : 0) + (o != null ? 1 : 0) + (r != null ? 1 : 0);
        if (kinds != 1) {
            throw new IllegalArgumentException(
                    "record must carry exactly one of @AtpTransition/@AtpObservation/@AtpRelation: "
                            + recordClass.getName());
        }
        if (t != null) {
            primitive = Constants.P_TRANSITION;
            name = t.name();
            version = t.version();
            publisher = t.publisher();
        } else if (o != null) {
            primitive = Constants.P_OBSERVATION;
            name = o.name();
            version = o.version();
            publisher = o.publisher();
        } else {
            primitive = Constants.P_RELATION;
            name = r.name();
            version = r.version();
            publisher = r.publisher();
        }

        RecordComponent[] components = recordClass.getRecordComponents();
        List<FieldDef> fieldDefs = new ArrayList<>();
        List<BoundField> boundFields = new ArrayList<>();
        LinkedHashMap<String, List<String>> enums = new LinkedHashMap<>();

        for (int slot = 0; slot < components.length; slot++) {
            RecordComponent c = components[slot];
            String fieldName = c.isAnnotationPresent(io.openkedge.atp.annotations.Field.class)
                    ? c.getAnnotation(io.openkedge.atp.annotations.Field.class).name()
                    : c.getName();
            boolean required = !c.isAnnotationPresent(Optional.class);
            int type = mapType(recordClass, c);
            if (c.isAnnotationPresent(Intent.class)) {
                fieldName = "intent_ref"; // canonical name enforced by manifest validation
            }

            FieldDef fd = new FieldDef(slot, fieldName, type, required);

            if (c.isAnnotationPresent(Unit.class)) {
                fd.unit(c.getAnnotation(Unit.class).value());
            }
            if (type == Constants.T_ENUM) {
                Class<?> enumType = enumComponentType(c);
                AtpEnum ae = enumType.getAnnotation(AtpEnum.class);
                if (ae == null) {
                    throw new IllegalArgumentException(
                            "ENUM component '" + fieldName + "' requires @AtpEnum on " + enumType.getName());
                }
                fd.enumRef(ae.name());
                enums.putIfAbsent(ae.name(), enumMembers(enumType));
            }
            if (c.isAnnotationPresent(MaxLen.class)) {
                int n = c.getAnnotation(MaxLen.class).value();
                if (type != Constants.T_STRING && type != Constants.T_BYTES) {
                    throw new IllegalArgumentException("@MaxLen on non-length type: " + fieldName);
                }
                if (n < 1 || n > Constants.HARD_MAX_LEN) {
                    throw new IllegalArgumentException("@MaxLen out of range (1..4096): " + fieldName);
                }
                fd.constraint(1, n);
            }
            fieldDefs.add(fd);
            boundFields.add(new BoundField(slot, fieldName, type, required, c));
        }

        Manifest manifest = new Manifest(name, version, primitive, publisher, fieldDefs, enums, null);
        manifest.validate(); // fail closed on any structural violation, incl. intent_ref gating
        return new BoundSchema(recordClass, manifest, boundFields);
    }

    private static int mapType(Class<?> recordClass, RecordComponent c) {
        if (c.isAnnotationPresent(Opaque.class)) {
            return Constants.T_OPAQUE_REF;
        }
        if (c.isAnnotationPresent(Intent.class)) {
            return Constants.T_BYTES; // intent_ref is a BYTES(32)
        }
        AtpType at = c.getAnnotation(AtpType.class);
        if (at != null && at.value() != ValueType.DEFAULT) {
            return at.value().code();
        }
        Class<?> jt = c.getType();
        if (jt == boolean.class || jt == Boolean.class) {
            return Constants.T_BOOL;
        }
        if (jt == int.class || jt == Integer.class) {
            return Constants.T_I32;
        }
        if (jt == long.class || jt == Long.class) {
            return Constants.T_I64;
        }
        if (jt == float.class || jt == Float.class) {
            return Constants.T_F32;
        }
        if (jt == double.class || jt == Double.class) {
            return Constants.T_F64;
        }
        if (jt == String.class) {
            return Constants.T_STRING;
        }
        if (jt == byte[].class) {
            return Constants.T_BYTES;
        }
        if (jt.isEnum()) {
            return Constants.T_ENUM;
        }
        if (jt == Instant.class) {
            return Constants.T_TIMESTAMP_MS;
        }
        if (jt == EntityId.class) {
            return Constants.T_ENTITY_REF;
        }
        if (jt == Duration.class) {
            return Constants.T_DURATION_MS;
        }
        throw new IllegalArgumentException(
                "no ATP type mapping for component '" + c.getName() + "' of type " + jt.getName()
                        + " in " + recordClass.getName() + " (annotate with @AtpType or @Opaque)");
    }

    private static Class<?> enumComponentType(RecordComponent c) {
        Class<?> jt = c.getType();
        if (jt.isEnum()) {
            return jt;
        }
        AtpType at = c.getAnnotation(AtpType.class);
        if (at != null && at.value() == ValueType.ENUM) {
            throw new IllegalArgumentException(
                    "@AtpType(ENUM) requires the component to be a Java enum: " + c.getName());
        }
        throw new IllegalArgumentException("ENUM mapping requires a Java enum: " + c.getName());
    }

    private static List<String> enumMembers(Class<?> enumType) {
        List<String> members = new ArrayList<>();
        for (Object e : enumType.getEnumConstants()) {
            members.add(((Enum<?>) e).name());
        }
        return members;
    }

    private static Value toValue(BoundField bf, Object raw,
                                 ToLongFunction<EntityId> aliasResolver,
                                 Function<Object, byte[]> opaqueEncoder) {
        return switch (bf.type) {
            case Constants.T_BOOL -> new Value.BoolV((Boolean) raw);
            case Constants.T_U32, Constants.T_U64 -> new Value.UV(((Number) raw).longValue());
            case Constants.T_I32, Constants.T_I64 -> new Value.IV(((Number) raw).longValue());
            case Constants.T_F32 -> new Value.F32V((Float) raw);
            case Constants.T_F64 -> new Value.F64V((Double) raw);
            case Constants.T_ENUM -> new Value.EnumV(((Enum<?>) raw).ordinal());
            case Constants.T_STRING -> new Value.StrV((String) raw);
            case Constants.T_BYTES -> new Value.BytesV((byte[]) raw);
            case Constants.T_TIMESTAMP_MS -> new Value.IV(((Instant) raw).toEpochMilli());
            case Constants.T_DURATION_MS -> new Value.UV(((Duration) raw).toMillis());
            case Constants.T_ENTITY_REF -> new Value.EntityV(aliasResolver.applyAsLong((EntityId) raw));
            case Constants.T_OPAQUE_REF -> {
                if (opaqueEncoder == null) {
                    throw new IllegalStateException("no opaque encoder configured for @Opaque field");
                }
                yield new Value.OpaqueV(opaqueEncoder.apply(raw));
            }
            default -> throw new IllegalStateException("unmapped type " + bf.type);
        };
    }
}
