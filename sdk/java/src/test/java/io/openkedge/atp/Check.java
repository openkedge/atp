package io.openkedge.atp;

import java.util.Arrays;
import java.util.HexFormat;

/** Minimal assertion helpers for the dependency-free test harness. */
public final class Check {
    private Check() {}

    public static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    public static byte[] unhex(String s) {
        return HexFormat.of().parseHex(s);
    }

    public static void eqHex(byte[] got, String wantHex, String label) {
        String g = hex(got);
        if (!g.equals(wantHex)) {
            throw new AssertionError(label + "\n  got : " + g + "\n  want: " + wantHex);
        }
    }

    public static void eqBytes(byte[] got, byte[] want, String label) {
        if (!Arrays.equals(got, want)) {
            throw new AssertionError(label + "\n  got : " + hex(got) + "\n  want: " + hex(want));
        }
    }

    public static void eq(Object got, Object want, String label) {
        if (!java.util.Objects.equals(got, want)) {
            throw new AssertionError(label + "\n  got : " + got + "\n  want: " + want);
        }
    }

    public static void isTrue(boolean cond, String label) {
        if (!cond) {
            throw new AssertionError(label);
        }
    }

    public static void isFalse(boolean cond, String label) {
        if (cond) {
            throw new AssertionError(label);
        }
    }

    /** Assert that {@code body} throws (any exception) — used for fail-closed invariants. */
    public static void throwsAny(Runnable body, String label) {
        try {
            body.run();
        } catch (Throwable t) {
            return;
        }
        throw new AssertionError(label + " (expected an exception, none thrown)");
    }
}
