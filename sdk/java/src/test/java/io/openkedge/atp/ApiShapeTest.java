package io.openkedge.atp;

import java.lang.reflect.Method;

/**
 * API-surface invariants: the two-argument floor (behavioral invariant
 * two-argument-floor) and TCB ownership of the trust path (operational invariant
 * tcb-owns-trust-path). Enforced by reflecting the app-facing types.
 */
public final class ApiShapeTest {
    private ApiShapeTest() {}

    public static void noFreeFormCanonicalEmit() {
        // No emit(...) overload accepts a String/CharSequence as canonical evidence.
        for (Method m : AtpEmitter.class.getDeclaredMethods()) {
            if (!m.getName().equals("emit")) {
                continue;
            }
            for (Class<?> p : m.getParameterTypes()) {
                Check.isFalse(p == String.class || CharSequence.class.isAssignableFrom(p),
                        "emit must not accept free-form prose: " + m);
            }
        }
        // The generic event parameter erases to Object, and entity is a typed EntityId.
    }

    public static void trustPathNotAppSettable() {
        String[] forbidden = {
                "batchroot", "merkleroot", "previousroot", "signature", "sign", "seed",
                "setsequence", "setepoch", "bootepochset"
        };
        checkNoForbidden(AtpEmitter.class, forbidden);
        checkNoForbidden(RecordHandle.class, forbidden);
        checkNoForbidden(Ack.class, forbidden);
        // RecordHandle exposes read-only sequence()/bootEpoch() and acknowledged() — no setters.
        for (Method m : RecordHandle.class.getDeclaredMethods()) {
            Check.eq(m.getParameterCount(), 0, "RecordHandle method takes no args: " + m.getName());
        }
    }

    private static void checkNoForbidden(Class<?> type, String[] forbidden) {
        for (Method m : type.getMethods()) {
            String n = m.getName().toLowerCase();
            for (String f : forbidden) {
                Check.isFalse(n.contains(f),
                        type.getSimpleName() + " must not expose trust-path method: " + m.getName());
            }
        }
    }
}
