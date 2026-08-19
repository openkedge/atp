package io.openkedge.atp;

import io.openkedge.atp.Fixtures.BadObservationWithIntent;
import io.openkedge.atp.Fixtures.PodTransition;
import io.openkedge.atp.Fixtures.UnmappableComponent;

/**
 * Bind-time fail-closed rules (structural invariant type-mapping-total,
 * behavioral invariant intent-ref-primitive-gating).
 */
public final class SchemaBinderTest {
    private SchemaBinderTest() {}

    public static void bindsPodTransition() {
        Check.eqHex(SchemaBinder.bind(PodTransition.class).manifest.digest(),
                ConformanceVectorTest.POD_TRANSITION_DIGEST, "binder reproduces the anchor digest");
    }

    public static void rejectsUnmappableComponent() {
        Check.throwsAny(() -> SchemaBinder.bind(UnmappableComponent.class),
                "component with no ATP type mapping must fail closed");
    }

    public static void rejectsIntentOnObservation() {
        Check.throwsAny(() -> SchemaBinder.bind(BadObservationWithIntent.class),
                "intent_ref on an Observation must be rejected at bind time");
    }
}
