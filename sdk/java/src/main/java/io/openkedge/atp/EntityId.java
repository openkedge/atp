package io.openkedge.atp;

import java.nio.charset.StandardCharsets;

/**
 * A canonical entity identifier, {@code namespace:resource_type:canonical_identifier}
 * (ATP-0001 §4.1).
 *
 * <p>The canonical form is ASCII. Non-ASCII source identifiers are represented by
 * uppercase {@code %HH} escapes of their UTF-8 bytes; a literal percent is
 * {@code %25}. The display alias is presentation-only and is never part of the
 * identity.
 */
public final class EntityId {
    private final String canonical;
    private final String displayAlias; // nullable, display-only

    private EntityId(String canonical, String displayAlias) {
        this.canonical = canonical;
        this.displayAlias = displayAlias;
    }

    /**
     * Build and validate an EntityId from already-canonical components. The
     * {@code canonicalId} may contain {@code %HH} escapes for non-ASCII bytes.
     */
    public static EntityId of(String namespace, String resourceType, String canonicalId) {
        String id = namespace + ":" + resourceType + ":" + canonicalId;
        if (!isValid(id)) {
            throw new IllegalArgumentException("invalid EntityId: " + id);
        }
        return new EntityId(id, null);
    }

    /** Parse and validate a full canonical EntityId string. */
    public static EntityId parse(String canonical) {
        if (!isValid(canonical)) {
            throw new IllegalArgumentException("invalid EntityId: " + canonical);
        }
        return new EntityId(canonical, null);
    }

    /** Escape a raw identifier component to canonical ASCII with uppercase %HH escapes. */
    public static String escapeIdentifier(String raw) {
        StringBuilder sb = new StringBuilder();
        for (byte b : raw.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xff;
            if (v == '%') {
                sb.append("%25");
            } else if (v >= 0x21 && v <= 0x7e) {
                sb.append((char) v);
            } else {
                sb.append('%').append(String.format("%02X", v));
            }
        }
        return sb.toString();
    }

    public EntityId withDisplayAlias(String shortForm) {
        return new EntityId(canonical, shortForm);
    }

    public String canonical() {
        return canonical;
    }

    public String displayAlias() {
        return displayAlias;
    }

    @Override
    public String toString() {
        return canonical;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EntityId e && e.canonical.equals(canonical);
    }

    @Override
    public int hashCode() {
        return canonical.hashCode();
    }

    /** Validate {@code namespace:resource_type:canonical_identifier} per ATP-0001 §4.1. */
    public static boolean isValid(String id) {
        if (id.isEmpty() || id.length() > 1024 || !isAscii(id)) {
            return false;
        }
        int c1 = id.indexOf(':');
        if (c1 < 0) {
            return false;
        }
        int c2 = id.indexOf(':', c1 + 1);
        if (c2 < 0) {
            return false;
        }
        String namespace = id.substring(0, c1);
        String resourceType = id.substring(c1 + 1, c2);
        String identifier = id.substring(c2 + 1);
        if (!validComponent(namespace) || !validComponent(resourceType) || identifier.isEmpty()) {
            return false;
        }
        byte[] bytes = identifier.getBytes(StandardCharsets.US_ASCII);
        java.io.ByteArrayOutputStream decoded = new java.io.ByteArrayOutputStream();
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xff;
            if (b < 0x21 || b > 0x7e) {
                return false;
            }
            if (b == '%') {
                if (i + 2 >= bytes.length) {
                    return false;
                }
                int hi = bytes[i + 1] & 0xff;
                int lo = bytes[i + 2] & 0xff;
                if (!isUpperHex(hi) || !isUpperHex(lo)) {
                    return false;
                }
                int value = (hexVal(hi) << 4) | hexVal(lo);
                if (value < 0x80 && value != '%') {
                    return false;
                }
                decoded.write(value);
                i += 3;
            } else {
                decoded.write(b);
                i += 1;
            }
        }
        // Decoded identifier must be valid UTF-8.
        byte[] db = decoded.toByteArray();
        String round = new String(db, StandardCharsets.UTF_8);
        return round.getBytes(StandardCharsets.UTF_8).length == db.length;
    }

    private static boolean validComponent(String s) {
        if (s.isEmpty()) {
            return false;
        }
        char first = s.charAt(0);
        if (!isAlnum(first)) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(isAlnum(c) || c == '.' || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7f) {
                return false;
            }
        }
        return true;
    }

    // Reject lowercase hex: escapes must be uppercase (ATP-0001 §4.1).
    private static boolean isUpperHex(int b) {
        return (b >= '0' && b <= '9') || (b >= 'A' && b <= 'F');
    }

    private static int hexVal(int b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        return b - 'A' + 10;
    }
}
