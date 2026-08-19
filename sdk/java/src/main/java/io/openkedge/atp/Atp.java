package io.openkedge.atp;

/**
 * Producer bootstrap facade (DESIGN §3.3). Configuration is one-time ops wiring
 * off the call path; application code touches only {@link AtpEmitter}.
 */
public final class Atp {
    private static volatile AtpProducer processProducer;

    private Atp() {}

    /** Start configuring the process-wide producer (TCB config). */
    public static AtpProducer.Builder producer() {
        return AtpProducer.builder();
    }

    /** Install the process-wide producer used by {@link #emitter(Class)}. */
    public static void useProducer(AtpProducer producer) {
        processProducer = producer;
    }

    /** An emitter for {@code eventType}, bound to the process-wide producer. */
    public static <E> AtpEmitter<E> emitter(Class<E> eventType) {
        AtpProducer p = processProducer;
        if (p == null) {
            throw new IllegalStateException(
                    "no process-wide producer installed; call Atp.useProducer(...) first");
        }
        return p.emitter(eventType);
    }
}
