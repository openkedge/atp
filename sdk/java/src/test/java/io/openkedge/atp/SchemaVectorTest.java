package io.openkedge.atp;

import io.openkedge.atp.internal.Constants;
import io.openkedge.atp.internal.FieldDef;
import io.openkedge.atp.internal.Manifest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ATP-0002 positive schema vectors (SV-001..SV-006 except SV-004, matching the
 * Rust reference set): every canonical CBOR byte string and schema digest across
 * all 14 value types, constraints, compatibility, and enum key-ordering edge
 * cases. Built at the core Manifest level (the CBOR/digest engine).
 */
public final class SchemaVectorTest {
    private SchemaVectorTest() {}

    static Manifest podTransition() {
        List<FieldDef> f = new ArrayList<>();
        f.add(new FieldDef(0, "old_state", Constants.T_ENUM, true).enumRef("pod_phase"));
        f.add(new FieldDef(1, "new_state", Constants.T_ENUM, true).enumRef("pod_phase"));
        f.add(new FieldDef(2, "exit_code", Constants.T_I32, false));
        f.add(new FieldDef(3, "reason", Constants.T_STRING, false));
        LinkedHashMap<String, List<String>> enums = new LinkedHashMap<>();
        enums.put("pod_phase", List.of("Pending", "Running", "Succeeded", "Failed", "Unknown"));
        return new Manifest("k8s.pod.transition", "1.0.0", 0, "openkedge.io/k8s", f, enums, null);
    }

    private static Manifest sv001() {
        List<FieldDef> f = new ArrayList<>();
        f.add(new FieldDef(0, "cpu_pct", Constants.T_F64, true).unit("1"));
        return new Manifest("host.cpu.utilization", "1.0.0", 1, "openkedge.io/host",
                f, new LinkedHashMap<>(), null);
    }

    private static Manifest sv003() {
        String[] names = {"bool", "u32", "u64", "i32", "i64", "f32", "f64", "enum",
                "string", "bytes", "timestamp_ms", "entity_ref", "opaque_ref", "duration_ms"};
        List<FieldDef> f = new ArrayList<>();
        for (int t = 0; t < names.length; t++) {
            FieldDef fd = new FieldDef(t, "f_" + names[t], t, true);
            if (t == Constants.T_ENUM) {
                fd.enumRef("color");
            }
            if (t == Constants.T_STRING || t == Constants.T_BYTES) {
                fd.constraint(1, 256);
            }
            f.add(fd);
        }
        LinkedHashMap<String, List<String>> enums = new LinkedHashMap<>();
        enums.put("color", List.of("red", "green", "blue"));
        return new Manifest("test.all_types.observation", "1.0.0", 1, "openkedge.io/test",
                f, enums, null);
    }

    private static Manifest sv005() {
        List<FieldDef> f = new ArrayList<>();
        f.add(new FieldDef(0, "status_code", Constants.T_U32, true).constraint(2, 100).constraint(3, 599));
        f.add(new FieldDef(1, "method", Constants.T_STRING, true).constraint(1, 64));
        f.add(new FieldDef(2, "latency_ms", Constants.T_DURATION_MS, false));
        return new Manifest("svc.rpc.result", "2.1.0", 0, "openkedge.io/rpc",
                f, new LinkedHashMap<>(), new Object[] {"2.0.0", 1L});
    }

    private static Manifest sv006() {
        List<FieldDef> f = new ArrayList<>();
        f.add(new FieldDef(0, "short_key", Constants.T_ENUM, true).enumRef("z"));
        f.add(new FieldDef(1, "long_key", Constants.T_ENUM, true).enumRef("aa"));
        LinkedHashMap<String, List<String>> enums = new LinkedHashMap<>();
        enums.put("aa", List.of("A"));   // inserted in lexical order; encoding sorts "z" before "aa"
        enums.put("z", List.of("Z"));
        return new Manifest("test.enum.order", "1.0.0", 1, "openkedge.io/test", f, enums, null);
    }

    private static Map<String, Object> positive(String id) {
        for (Object e : Json.arr(Vectors.load("schema-vectors.json").get("positive"))) {
            Map<String, Object> m = Json.obj(e);
            if (id.equals(Json.str(m.get("vector_id")))) {
                return m;
            }
        }
        throw new AssertionError("no schema vector " + id);
    }

    private static void assertVector(String id, Manifest m) {
        Map<String, Object> e = positive(id);
        m.validate();
        Check.eqHex(m.canonicalCbor(), Json.str(e.get("canonical_cbor")), id + " canonical CBOR");
        Check.eqHex(m.digest(), Json.str(e.get("schema_digest_sha256")), id + " schema digest");
    }

    public static void canonicalCbor() {
        assertVector("SV-001", sv001());
        assertVector("SV-002", podTransition());
        assertVector("SV-003", sv003());
        assertVector("SV-005", sv005());
        assertVector("SV-006", sv006());
    }

    public static void schemaDigest() {
        // continuity anchor
        Check.eqHex(podTransition().digest(), ConformanceVectorTest.POD_TRANSITION_DIGEST,
                "SV-002 continuity digest");
    }
}
