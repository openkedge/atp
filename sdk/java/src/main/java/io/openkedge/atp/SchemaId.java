package io.openkedge.atp;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * The content-addressed schema identity {@code H_S}: a 32-byte SHA-256 digest of
 * the canonical manifest bytes (ATP-0002 §6.4). Stable across languages.
 */
public final class SchemaId {
    private final byte[] digest; // 32 bytes

    private SchemaId(byte[] digest) {
        if (digest.length != 32) {
            throw new IllegalArgumentException("H_S must be 32 bytes");
        }
        this.digest = digest;
    }

    public static SchemaId of(byte[] digest32) {
        return new SchemaId(digest32.clone());
    }

    public static SchemaId fromHex(String hex) {
        return new SchemaId(HexFormat.of().parseHex(hex));
    }

    public byte[] bytes() {
        return digest.clone();
    }

    public String hex() {
        return HexFormat.of().formatHex(digest);
    }

    @Override
    public String toString() {
        return hex();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SchemaId s && Arrays.equals(s.digest, digest);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(digest);
    }
}
