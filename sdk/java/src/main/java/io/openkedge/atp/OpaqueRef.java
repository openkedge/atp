package io.openkedge.atp;

import io.openkedge.atp.internal.Constants;
import io.openkedge.atp.internal.Crypto;
import io.openkedge.atp.internal.Varint;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Canonical out-of-band evidence reference (ATP-0001 §12.2):
 *
 * <pre>
 * OpaqueRef = lp_string(opaque_id) || lp_string(media_type) || uvarint(byte_length)
 *          || payload_digest(32) || lp_string(storage_uri) || uvarint(retention_class)
 * </pre>
 *
 * <p>Only bounded metadata plus a SHA-256 integrity digest live inline; the
 * payload itself is stored elsewhere. This is how untrusted diagnostics (log
 * prose, stack traces) enter ATP without polluting canonical fields (§12.4).
 */
public final class OpaqueRef {
    private OpaqueRef() {}

    /** Parsed view of an OpaqueRef inner value. */
    public record Parsed(String opaqueId, String mediaType, long byteLength,
                         byte[] payloadDigest, String storageUri, long retentionClass) {}

    /** Build a canonical OpaqueRef, computing byte_length and payload_digest from {@code payload}. */
    public static byte[] build(String opaqueId, String mediaType, byte[] payload,
                               String storageUri, long retentionClass) {
        byte[] digest = Crypto.sha256(payload);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        lpString(out, opaqueId);
        lpString(out, mediaType);
        out.writeBytes(Varint.uvarint(payload.length));
        out.writeBytes(digest);
        lpString(out, storageUri);
        out.writeBytes(Varint.uvarint(retentionClass));
        byte[] bytes = out.toByteArray();
        if (bytes.length < 1 || bytes.length > Constants.MAX_OPAQUE_REF_BYTES) {
            throw new IllegalArgumentException("OpaqueRef out of size bounds: " + bytes.length);
        }
        // Validate by round-tripping through the strict parser.
        parse(bytes);
        return bytes;
    }

    /** Parse and canonically validate an OpaqueRef inner value; throws on malformed input. */
    public static Parsed parse(byte[] raw) {
        if (raw.length < 1 || raw.length > Constants.MAX_OPAQUE_REF_BYTES) {
            throw new IllegalArgumentException(Constants.E_SCHEMA_VIOLATION + ": opaque size");
        }
        try {
            Varint.Reader r = new Varint.Reader(raw);
            String opaqueId = readText(r, 1, Constants.MAX_OPAQUE_ID_BYTES);
            if (!allVchar(opaqueId)) {
                throw bad("opaque_id charset");
            }
            String mediaType = readText(r, 3, Constants.MAX_MEDIA_TYPE_BYTES);
            validateMediaType(mediaType);
            long byteLength = r.uvarint();
            byte[] digest = r.take(32);
            String storageUri = readText(r, 1, Constants.MAX_STORAGE_URI_BYTES);
            validateUri(storageUri);
            long retention = r.uvarint();
            if (Long.compareUnsigned(retention, 0xffff_ffffL) > 0 || !r.eof()) {
                throw bad("retention/trailing");
            }
            return new Parsed(opaqueId, mediaType, byteLength, digest, storageUri, retention);
        } catch (Varint.DecodeException e) {
            throw new IllegalArgumentException(Constants.E_MALFORMED_RECORD, e);
        }
    }

    /**
     * Verify fetched bytes against an OpaqueRef's declared length and digest,
     * checking length before digest so implementations agree on the first failure.
     */
    public static String verifyPayload(long expectedLength, byte[] expectedDigest, byte[] payload) {
        if (Integer.toUnsignedLong(payload.length) != expectedLength) {
            return Constants.E_OPAQUE_LENGTH_MISMATCH;
        }
        if (!java.util.Arrays.equals(Crypto.sha256(payload), expectedDigest)) {
            return Constants.E_OPAQUE_DIGEST_MISMATCH;
        }
        return "OK";
    }

    private static void lpString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(Varint.uvarint(b.length));
        out.writeBytes(b);
    }

    private static String readText(Varint.Reader r, int min, int max) throws Varint.DecodeException {
        long len = r.uvarint();
        if (len < min || len > max) {
            throw bad("text length");
        }
        byte[] raw = r.take((int) len);
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static void validateMediaType(String mediaType) {
        String[] parts = mediaType.split("/", -1);
        if (parts.length != 2 || !validToken(parts[0]) || !validToken(parts[1])) {
            throw bad("media_type shape");
        }
        for (int i = 0; i < mediaType.length(); i++) {
            if (Character.isUpperCase(mediaType.charAt(i))) {
                throw bad("media_type uppercase");
            }
        }
    }

    private static boolean validToken(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$&^_.+-".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static void validateUri(String uri) {
        int colon = uri.indexOf(':');
        if (colon <= 0) {
            throw bad("uri scheme");
        }
        char first = uri.charAt(0);
        if (!(first >= 'a' && first <= 'z')) {
            throw bad("uri scheme start");
        }
        for (int i = 1; i < colon; i++) {
            char c = uri.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.')) {
                throw bad("uri scheme char");
            }
        }
        if (!allVchar(uri)) {
            throw bad("uri charset");
        }
    }

    private static boolean allVchar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    private static IllegalArgumentException bad(String why) {
        return new IllegalArgumentException(Constants.E_SCHEMA_VIOLATION + ": " + why);
    }
}
