package io.openkedge.atp.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a 32-byte component as the {@code intent_ref}. Valid only on Transition
 * and Relation primitives (ATP-0001 §3.3); enforced at bind time.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Intent {
}
