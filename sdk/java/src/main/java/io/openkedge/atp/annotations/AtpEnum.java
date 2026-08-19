package io.openkedge.atp.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@code enum} as an ATP ENUM type. Members are encoded in declaration
 * (ordinal) order; {@code name} is the manifest {@code enum_ref}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AtpEnum {
    String name();
}
