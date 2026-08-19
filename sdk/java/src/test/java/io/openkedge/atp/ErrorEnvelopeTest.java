package io.openkedge.atp;

/**
 * Stable error envelope (structural invariant stable-error-envelope): rejections
 * surface an ATP-0001 Appendix C ATP_ERR_* code, never free-form prose in a
 * canonical field.
 */
public final class ErrorEnvelopeTest {
    private ErrorEnvelopeTest() {}

    public static void rejectionsCarryAtpErrCode() {
        // Back-pressure rejection carries a typed code.
        AtpProducer.AtpBackpressureException bp =
                new AtpProducer.AtpBackpressureException("full");
        Check.isTrue(bp.code().startsWith("ATP_ERR_"), "back-pressure code is ATP_ERR_*");

        // Malformed opaque input is rejected with an ATP_ERR_* reason.
        try {
            OpaqueRef.parse(new byte[] {0x7f}); // claims a 127-byte id but is truncated
            throw new AssertionError("expected rejection");
        } catch (IllegalArgumentException ex) {
            Check.isTrue(ex.getMessage().contains("ATP_ERR_"),
                    "opaque parse failure carries ATP_ERR_* code: " + ex.getMessage());
        }
    }
}
