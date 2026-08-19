package io.openkedge.atp;

import io.openkedge.atp.internal.Manifest;

/** Encode determinism (behavioral invariant encode-determinism): same inputs, same bytes, every time. */
public final class DeterminismTest {
    private DeterminismTest() {}

    public static void repeatedEncodeIsByteIdentical() {
        // Two independent core manifests of the same logical schema hash identically.
        Manifest a = SchemaVectorTest.podTransition();
        Manifest b = SchemaVectorTest.podTransition();
        Check.eqBytes(a.canonicalCbor(), b.canonicalCbor(), "independent manifests, identical CBOR");
        Check.eqBytes(a.digest(), b.digest(), "independent manifests, identical digest");

        // Repeated digest computation is stable.
        byte[] first = a.digest();
        for (int i = 0; i < 100; i++) {
            Check.eqBytes(a.digest(), first, "digest stable across " + i + " runs");
        }
    }
}
