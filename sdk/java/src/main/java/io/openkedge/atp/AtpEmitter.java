package io.openkedge.atp;

import java.time.Instant;

/**
 * The app-facing emit surface (DESIGN §3). The application deals only with a
 * typed event and a canonical entity; the SDK owns the entire trust path. The
 * two-argument floor is mandatory: there is no method that accepts free-form
 * prose as canonical evidence (ATP-0001 §3, §12.4).
 *
 * @param <E> an ATP event record type (annotated with @AtpTransition/@AtpObservation/@AtpRelation)
 */
public interface AtpEmitter<E> {
    /** Emit a typed event about a canonical entity. */
    RecordHandle emit(EntityId entity, E event);

    /** Emit a typed event using the entity bound by {@link #forEntity(EntityId)}. */
    RecordHandle emit(E event);

    /** Bind a fixed entity for self-reporting producers (true one-argument emit). */
    AtpEmitter<E> forEntity(EntityId bound);

    /** Set the event time for the next emit; default is call time (SDK derives time_delta). */
    AtpEmitter<E> at(Instant when);

    /** The content-addressed schema identity H_S for this emitter's event type. */
    SchemaId schemaId();
}
