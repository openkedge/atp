package io.openkedge.atp.internal;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;

/**
 * Cryptographic primitives from the JDK platform provider (java.security):
 * SHA-256 and strict RFC 8032 Ed25519. No third-party cryptography (PDD
 * invariant {@code jdk-only-crypto}).
 *
 * <p>The JDK Ed25519 signature over a 32-byte {@code batch_root} is byte-identical
 * to the {@code ed25519-dalek} reference stack; this is the interop-critical
 * property that lets a Java producer sign conformant batches.
 */
public final class Crypto {
    private Crypto() {}

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Sign {@code msg} (for ATP, the 32-byte {@code batch_root}) with a 32-byte seed. */
    public static byte[] ed25519Sign(byte[] seed32, byte[] msg) {
        if (seed32.length != 32) {
            throw new IllegalArgumentException("Ed25519 seed must be 32 bytes");
        }
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PrivateKey sk = kf.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed32));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(sk);
            sig.update(msg);
            return sig.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    /** Strict RFC 8032 verification against a 32-byte compressed public key. */
    public static boolean ed25519Verify(byte[] pub32, byte[] msg, byte[] sig64) {
        try {
            PublicKey pk = publicKeyFromBytes(pub32);
            Signature v = Signature.getInstance("Ed25519");
            v.initVerify(pk);
            v.update(msg);
            return v.verify(sig64);
        } catch (Exception e) {
            return false;
        }
    }

    /** Decode a 32-byte little-endian compressed Ed25519 point into a JDK public key. */
    public static PublicKey publicKeyFromBytes(byte[] pub32) {
        if (pub32.length != 32) {
            throw new IllegalArgumentException("Ed25519 public key must be 32 bytes");
        }
        try {
            byte[] le = pub32.clone();
            boolean xOdd = (le[31] & 0x80) != 0;
            le[31] &= 0x7f;
            byte[] be = new byte[32];
            for (int i = 0; i < 32; i++) {
                be[i] = le[31 - i];
            }
            BigInteger y = new BigInteger(1, be);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            return kf.generatePublic(
                    new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, y)));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid Ed25519 public key", e);
        }
    }
}
