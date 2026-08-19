package io.openkedge.atp.internal;

import static io.openkedge.atp.internal.Constants.D_MANIFEST;
import static io.openkedge.atp.internal.Constants.HARD_MAX_LEN;
import static io.openkedge.atp.internal.Constants.MAX_MANIFEST_BYTES;
import static io.openkedge.atp.internal.Constants.MAX_SCHEMA_ENUMS;
import static io.openkedge.atp.internal.Constants.MAX_SCHEMA_FIELDS;
import static io.openkedge.atp.internal.Constants.T_BYTES;
import static io.openkedge.atp.internal.Constants.T_ENUM;
import static io.openkedge.atp.internal.Constants.T_STRING;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A schema manifest and its content-addressed identity. ATP-0002.
 *
 * <p>{@code H_S = SHA-256(D_MANIFEST || CanonicalCBOR(manifest))}. The manifest
 * bytes are canonical; the Java {@code record} is only a binding to them, which
 * is why a Java-derived {@code H_S} equals a Go/Rust/Python sibling's.
 */
public final class Manifest {
    public final String name;
    public final String version;
    public final int primitive;
    public final String publisher;
    public final List<FieldDef> fields;
    /** name -> members (ordinal order). Insertion order is irrelevant: the CBOR map sorts keys. */
    public final LinkedHashMap<String, List<String>> enums;
    /** optional (compatibilityVersion, mode); null if absent. */
    public final Object[] compatibility;

    public Manifest(String name, String version, int primitive, String publisher,
                    List<FieldDef> fields, LinkedHashMap<String, List<String>> enums,
                    Object[] compatibility) {
        this.name = name;
        this.version = version;
        this.primitive = primitive;
        this.publisher = publisher;
        this.fields = fields;
        this.enums = enums;
        this.compatibility = compatibility;
    }

    Cbor toCbor() {
        List<Map.Entry<Cbor, Cbor>> m = new ArrayList<>();
        m.add(Map.entry(Cbor.u(1), Cbor.text(name)));
        m.add(Map.entry(Cbor.u(2), Cbor.text(version)));
        m.add(Map.entry(Cbor.u(3), Cbor.u(primitive)));
        m.add(Map.entry(Cbor.u(4), Cbor.text(publisher)));

        List<Cbor> fieldArr = new ArrayList<>();
        for (FieldDef f : fields) {
            fieldArr.add(f.toCbor());
        }
        m.add(Map.entry(Cbor.u(5), new Cbor.Array(fieldArr)));

        List<Map.Entry<Cbor, Cbor>> enumMap = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : enums.entrySet()) {
            List<Cbor> members = new ArrayList<>();
            for (String member : e.getValue()) {
                members.add(Cbor.text(member));
            }
            enumMap.add(Map.entry(Cbor.text(e.getKey()), new Cbor.Array(members)));
        }
        m.add(Map.entry(Cbor.u(6), new Cbor.MapC(enumMap)));

        if (compatibility != null) {
            String mcv = (String) compatibility[0];
            long mode = ((Number) compatibility[1]).longValue();
            List<Map.Entry<Cbor, Cbor>> cm = new ArrayList<>();
            cm.add(Map.entry(Cbor.u(1), Cbor.text(mcv)));
            cm.add(Map.entry(Cbor.u(2), Cbor.u(mode)));
            m.add(Map.entry(Cbor.u(7), new Cbor.MapC(cm)));
        }
        return new Cbor.MapC(m);
    }

    public byte[] canonicalCbor() {
        return Cbor.encode(toCbor());
    }

    public byte[] digest() {
        ByteArrayOutputStream pre = new ByteArrayOutputStream();
        pre.writeBytes(D_MANIFEST);
        pre.writeBytes(canonicalCbor());
        return Crypto.sha256(pre.toByteArray());
    }

    public List<String> enumMembers(String enumName) {
        return enums.get(enumName);
    }

    /**
     * Validate the manifest (a subset of ATP-0002 §5 sufficient for bind-time
     * fail-closed). Throws {@link IllegalArgumentException} with a stable reason.
     */
    public void validate() {
        if (name.length() > 128 || !validDottedName(name)) {
            throw err("BAD_SCHEMA_NAME");
        }
        if (!validSemver(version)) {
            throw err("BAD_SCHEMA_VERSION");
        }
        if (primitive > 3 || primitive < 0) {
            throw err("BAD_PRIMITIVE");
        }
        if (publisher.isEmpty() || publisher.length() > 128 || !allVchar(publisher)) {
            throw err("BAD_PUBLISHER");
        }
        if (fields.isEmpty() || fields.size() > MAX_SCHEMA_FIELDS) {
            throw err("EMPTY_FIELDS");
        }
        java.util.HashSet<String> fieldNames = new java.util.HashSet<>();
        for (int index = 0; index < fields.size(); index++) {
            FieldDef f = fields.get(index);
            if (f.slot != index) {
                throw err("SLOT_NOT_DENSE:expected=" + index + ":got=" + f.slot);
            }
            if (f.name.length() > 64 || !validFieldName(f.name)) {
                throw err("BAD_FIELD_NAME:" + index);
            }
            if (!fieldNames.add(f.name)) {
                throw err("DUP_FIELD_NAME:" + f.name);
            }
            if (f.type < 0 || f.type > 13) {
                throw err("UNKNOWN_TYPE:" + f.type);
            }
            if (f.unit != null && (f.unit.isEmpty() || f.unit.length() > 64 || !allVchar(f.unit))) {
                throw err("BAD_UNIT:" + f.name);
            }
            if (f.type == T_ENUM) {
                if (f.enumRef == null) {
                    throw err("ENUM_MISSING_ENUM_REF");
                }
                if (enumMembers(f.enumRef) == null) {
                    throw err("ENUM_REF_UNRESOLVED:" + f.enumRef);
                }
            } else if (f.enumRef != null) {
                throw err("ENUM_REF_ON_NONENUM:" + f.name);
            }
            if (f.name.equals("intent_ref")
                    && (!(primitive == 0 || primitive == 2) || f.type != T_BYTES)) {
                throw err("BAD_INTENT_REF");
            }
            validateConstraints(f);
        }
        if (enums.size() > MAX_SCHEMA_ENUMS) {
            throw err("TOO_MANY_ENUMS");
        }
        for (Map.Entry<String, List<String>> e : enums.entrySet()) {
            String enumName = e.getKey();
            List<String> members = e.getValue();
            if (enumName.length() > 64 || !validFieldName(enumName)) {
                throw err("BAD_ENUM_NAME");
            }
            if (members.isEmpty() || members.size() > 65_535) {
                throw err("EMPTY_ENUM:" + enumName);
            }
            java.util.HashSet<String> unique = new java.util.HashSet<>();
            for (String member : members) {
                if (member.isEmpty() || member.length() > 128 || !allVchar(member)) {
                    throw err("BAD_ENUM_MEMBER:" + enumName);
                }
                if (!unique.add(member)) {
                    throw err("DUP_ENUM_MEMBER:" + enumName);
                }
            }
        }
        if (canonicalCbor().length > MAX_MANIFEST_BYTES) {
            throw err("MANIFEST_TOO_LARGE");
        }
    }

    private void validateConstraints(FieldDef f) {
        java.util.HashSet<Long> keys = new java.util.HashSet<>();
        for (long[] kv : f.constraints) {
            if (!keys.add(kv[0])) {
                throw err("DUP_CONSTRAINT:" + f.name);
            }
            if (kv[0] == 1 && f.type != T_STRING && f.type != T_BYTES) {
                throw err("MAX_LEN_ON_NONLEN_TYPE:" + f.name);
            }
            if (kv[0] < 1 || kv[0] > 3) {
                throw err("UNKNOWN_CONSTRAINT:" + kv[0]);
            }
        }
        for (long[] kv : f.constraints) {
            if (kv[0] == 1 && (kv[1] < 1 || kv[1] > HARD_MAX_LEN)) {
                throw err("BAD_MAX_LEN:" + f.name);
            }
        }
    }

    private static IllegalArgumentException err(String reason) {
        return new IllegalArgumentException(reason);
    }

    private static boolean allVchar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    static boolean validDottedName(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        if (!(first >= 'a' && first <= 'z')) {
            return false;
        }
        boolean prevSep = false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                prevSep = false;
            } else if ((c == '.' || c == '_' || c == '-') && !prevSep) {
                prevSep = true;
            } else {
                return false;
            }
        }
        return !prevSep;
    }

    static boolean validFieldName(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        if (!(first >= 'a' && first <= 'z')) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** Strict SemVer core (major.minor.patch) with optional pre-release/build. */
    static boolean validSemver(String value) {
        if (value.isEmpty() || value.length() > 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7f) {
                return false;
            }
        }
        String withoutBuild = value;
        int plus = value.indexOf('+');
        if (plus >= 0) {
            String build = value.substring(plus + 1);
            withoutBuild = value.substring(0, plus);
            if (!validSemverIdentifiers(build, false)) {
                return false;
            }
        }
        String core = withoutBuild;
        int dash = withoutBuild.indexOf('-');
        if (dash >= 0) {
            String pre = withoutBuild.substring(dash + 1);
            core = withoutBuild.substring(0, dash);
            if (!validSemverIdentifiers(pre, true)) {
                return false;
            }
        }
        String[] parts = core.split("\\.", -1);
        if (parts.length != 3) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || !allDigits(part) || (!part.equals("0") && part.startsWith("0"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean validSemverIdentifiers(String value, boolean rejectNumericLeadingZero) {
        if (value.isEmpty()) {
            return false;
        }
        for (String part : value.split("\\.", -1)) {
            if (part.isEmpty()) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '-')) {
                    return false;
                }
            }
            if (rejectNumericLeadingZero && allDigits(part) && !part.equals("0") && part.startsWith("0")) {
                return false;
            }
        }
        return true;
    }

    private static boolean allDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
