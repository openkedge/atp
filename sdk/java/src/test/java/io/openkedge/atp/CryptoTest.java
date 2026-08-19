package io.openkedge.atp;

import io.openkedge.atp.internal.Crypto;

/** Ed25519 sign/verify roundtrip via the JDK provider (behavioral invariant signature-over-batch-root). */
public final class CryptoTest {
    private CryptoTest() {}

    public static void signVerifyRoundtrip() {
        byte[] seed = Check.unhex("9d61b19deffe5a60651c9e0d0e6c1e6bf0a1b2c3d4e5f60718293a4b5c6d7e8f");
        byte[] pub = Check.unhex("121b96cf6280559ff9e409d9ca18866f42c4724c9a7eab847eb1e3f34428c5bb");
        byte[] msg = Check.unhex("fdc55b73af58d0d213b82129273b078a988634f27639a7a91f9ff6de97b98805");

        byte[] sig = Crypto.ed25519Sign(seed, msg);
        Check.isTrue(Crypto.ed25519Verify(pub, msg, sig), "own signature verifies");

        byte[] tampered = msg.clone();
        tampered[0] ^= 0x01;
        Check.isFalse(Crypto.ed25519Verify(pub, tampered, sig), "tampered message fails verification");

        byte[] tamperedSig = sig.clone();
        tamperedSig[10] ^= 0x01;
        Check.isFalse(Crypto.ed25519Verify(pub, msg, tamperedSig), "tampered signature fails verification");

        // SHA-256 sanity: known empty-string digest.
        Check.eqHex(Crypto.sha256(new byte[0]),
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "SHA-256 of empty input");
    }
}
