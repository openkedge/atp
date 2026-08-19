package io.openkedge.atp;

import io.openkedge.atp.annotations.AtpEnum;
import io.openkedge.atp.annotations.AtpObservation;
import io.openkedge.atp.annotations.AtpTransition;
import io.openkedge.atp.annotations.Field;
import io.openkedge.atp.annotations.Intent;
import io.openkedge.atp.annotations.Optional;

/** Annotated schema fixtures used across the conformance tests. */
public final class Fixtures {
    private Fixtures() {}

    /** ATP-0001 Appendix B / CV-CORE-001 running example enum. */
    @AtpEnum(name = "pod_phase")
    public enum PodPhase { Pending, Running, Succeeded, Failed, Unknown }

    /** The Java binding of the spec's running-example schema (DESIGN §5.6). */
    @AtpTransition(name = "k8s.pod.transition", version = "1.0.0", publisher = "openkedge.io/k8s")
    public record PodTransition(
            @Field(name = "old_state") PodPhase oldState,       // slot 0, ENUM(pod_phase), required
            @Field(name = "new_state") PodPhase newState,       // slot 1, ENUM(pod_phase), required
            @Optional @Field(name = "exit_code") Integer exitCode,  // slot 2, I32, optional
            @Optional @Field(name = "reason") String reason) {}     // slot 3, STRING, optional, no constraints

    /** Negative fixture: intent_ref on an Observation must be rejected at bind time. */
    @AtpObservation(name = "bad.observation.intent", version = "1.0.0", publisher = "openkedge.io/test")
    public record BadObservationWithIntent(
            @Intent byte[] intentRef,
            @Field(name = "value") double value) {}

    /** Negative fixture: a component with no ATP type mapping must fail closed. */
    @AtpObservation(name = "bad.unmappable.type", version = "1.0.0", publisher = "openkedge.io/test")
    public record UnmappableComponent(
            @Field(name = "when") java.util.Date when) {}   // java.util.Date has no ATP mapping
}
