package io.openkedge.atp.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One field definition inside a schema manifest (ATP-0002 §2.2).
 *
 * <p>Encodes to a CBOR map with keys 1=slot, 2=name, 3=type, 4=required, and
 * optionally 5=unit, 6=enum_ref, 7=constraints. Optional keys are omitted when
 * absent; decode-time defaults are never materialized.
 */
public final class FieldDef {
    public final long slot;
    public final String name;
    public final int type;
    public final boolean required;
    public String unit;                 // optional
    public String enumRef;              // present iff type == ENUM
    /** (constraint-key, value): 1=max_len, 2=min, 3=max (ATP-0002 §4.2). */
    public final List<long[]> constraints = new ArrayList<>();

    public FieldDef(long slot, String name, int type, boolean required) {
        this.slot = slot;
        this.name = name;
        this.type = type;
        this.required = required;
    }

    public FieldDef unit(String u) {
        this.unit = u;
        return this;
    }

    public FieldDef enumRef(String e) {
        this.enumRef = e;
        return this;
    }

    public FieldDef constraint(long key, long value) {
        this.constraints.add(new long[] {key, value});
        return this;
    }

    Cbor toCbor() {
        List<Map.Entry<Cbor, Cbor>> m = new ArrayList<>();
        m.add(Map.entry(Cbor.u(1), Cbor.u(slot)));
        m.add(Map.entry(Cbor.u(2), Cbor.text(name)));
        m.add(Map.entry(Cbor.u(3), Cbor.u(type)));
        m.add(Map.entry(Cbor.u(4), Cbor.bool(required)));
        if (unit != null) {
            m.add(Map.entry(Cbor.u(5), Cbor.text(unit)));
        }
        if (enumRef != null) {
            m.add(Map.entry(Cbor.u(6), Cbor.text(enumRef)));
        }
        if (!constraints.isEmpty()) {
            List<Map.Entry<Cbor, Cbor>> cm = new ArrayList<>();
            for (long[] kv : constraints) {
                cm.add(Map.entry(Cbor.u(kv[0]), Cbor.i(kv[1])));
            }
            m.add(Map.entry(Cbor.u(7), new Cbor.MapC(cm)));
        }
        return new Cbor.MapC(m);
    }
}
