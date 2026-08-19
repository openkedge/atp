package io.openkedge.atp;

/** One-to-one signing-key registry (operational invariant key-registry-one-to-one, ATP-0001 §7.7). */
public final class KeyRegistryTest {
    private KeyRegistryTest() {}

    public static void conflictingBindingFailsClosed() {
        byte[] kid1 = Check.unhex("a1b2c3d4e5f60708");
        byte[] kid2 = Check.unhex("0102030405060708");
        byte[] pub1 = Check.unhex("121b96cf6280559ff9e409d9ca18866f42c4724c9a7eab847eb1e3f34428c5bb");
        byte[] pub2 = Check.unhex("0000000000000000000000000000000000000000000000000000000000000001");

        KeyRegistry reg = new KeyRegistry();
        reg.register(kid1, pub1);
        Check.isTrue(reg.isBound(kid1, pub1), "binding present");

        // Same key id, different public key -> conflict.
        Check.throwsAny(() -> reg.register(kid1, pub2), "key id rebinding fails closed");
        // Same public key, different key id -> conflict.
        Check.throwsAny(() -> reg.register(kid2, pub1), "public key rebinding fails closed");
        // Idempotent re-registration of the exact same binding is allowed.
        reg.register(kid1, pub1);
        Check.isTrue(reg.isBound(kid1, pub1), "idempotent re-register ok");
    }
}
