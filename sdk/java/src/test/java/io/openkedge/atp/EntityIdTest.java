package io.openkedge.atp;

/** EntityId canonical syntax (ATP-0001 §4.1) — structural invariant entity-id-syntax. */
public final class EntityIdTest {
    private EntityIdTest() {}

    public static void acceptsCanonical() {
        Check.isTrue(EntityId.isValid("k8s:pod:prod-us-east-1/pay-7d9b"), "CV-CORE-001 alias 0");
        Check.isTrue(EntityId.isValid("k8s:pod:prod-us-east-1/auth-4a2c"), "CV-CORE-001 alias 1");
        Check.isTrue(EntityId.isValid("service:host:payments-worker"), "simple id");
        // uppercase %HH escape of a non-ASCII byte is valid
        Check.isTrue(EntityId.isValid("k8s:pod:caf%C3%A9"), "escaped non-ASCII");
        Check.isTrue(EntityId.isValid("a:b:%25"), "literal percent as %25");
        Check.eq(EntityId.of("k8s", "pod", "prod-us-east-1/pay-7d9b").canonical(),
                "k8s:pod:prod-us-east-1/pay-7d9b", "of() assembles canonical");
        Check.eq(EntityId.escapeIdentifier("café"), "caf%C3%A9", "escapeIdentifier");
    }

    public static void rejectsMalformed() {
        Check.isFalse(EntityId.isValid("only:two"), "missing identifier");
        Check.isFalse(EntityId.isValid(":pod:x"), "empty namespace");
        Check.isFalse(EntityId.isValid("k8s::x"), "empty resource_type");
        Check.isFalse(EntityId.isValid("k8s:pod:"), "empty identifier");
        Check.isFalse(EntityId.isValid("k8s:pod:caf%c3%a9"), "lowercase hex escape rejected");
        Check.isFalse(EntityId.isValid("k8s:pod:naïve"), "non-ASCII byte rejected");
        Check.isFalse(EntityId.isValid("k8s:pod:x y"), "space in identifier rejected");
        Check.isFalse(EntityId.isValid("k8s:pod:%2"), "truncated escape rejected");
        Check.throwsAny(() -> EntityId.of("k8s", "pod", ""), "of() fails closed on invalid");
    }
}
