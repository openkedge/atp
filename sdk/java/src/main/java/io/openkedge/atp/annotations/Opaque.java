package io.openkedge.atp.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a component as OPAQUE_REF: the SDK stores the payload out of band and the
 * manifest sees only {@code OPAQUE_REF} (ATP-0001 §12).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Opaque {
}
