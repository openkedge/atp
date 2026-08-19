package io.openkedge.atp.annotations;

import io.openkedge.atp.ValueType;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Pins a record component to an explicit ATP {@link ValueType}, overriding the
 * Java-type default (e.g. {@code long} -&gt; I64 becomes U64/DURATION_MS/TIMESTAMP_MS).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface AtpType {
    ValueType value();
}
