package io.openkedge.atp.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record component as optional ({@code required = false}); it is gated by
 * the presence bitmap (ATP-0001 §6.4). Optional components should be a
 * boxed/reference type so {@code null} means absent.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Optional {
}
