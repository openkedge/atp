package io.openkedge.atp;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The producer's one-to-one signing-key registry (ATP-0001 §7.7): each 8-byte
 * {@code signing_key_id} maps to exactly one Ed25519 public key and each public
 * key to exactly one key id. A conflicting binding MUST fail closed.
 */
public final class KeyRegistry {
    private final Map<String, String> keyIdToPub = new LinkedHashMap<>();
    private final Map<String, String> pubToKeyId = new LinkedHashMap<>();

    /** Register a binding; throws {@link IllegalStateException} on any conflict. */
    public void register(byte[] signingKeyId, byte[] publicKey) {
        if (signingKeyId.length != 8) {
            throw new IllegalArgumentException("signing_key_id must be 8 bytes");
        }
        if (publicKey.length != 32) {
            throw new IllegalArgumentException("public key must be 32 bytes");
        }
        String kid = HexFormat.of().formatHex(signingKeyId);
        String pub = HexFormat.of().formatHex(publicKey);
        String existingPub = keyIdToPub.get(kid);
        if (existingPub != null && !existingPub.equals(pub)) {
            throw new IllegalStateException("key id " + kid + " already bound to a different key");
        }
        String existingKid = pubToKeyId.get(pub);
        if (existingKid != null && !existingKid.equals(kid)) {
            throw new IllegalStateException("public key already bound to a different key id");
        }
        keyIdToPub.put(kid, pub);
        pubToKeyId.put(pub, kid);
    }

    public boolean isBound(byte[] signingKeyId, byte[] publicKey) {
        String kid = HexFormat.of().formatHex(signingKeyId);
        return HexFormat.of().formatHex(publicKey).equals(keyIdToPub.get(kid));
    }
}
