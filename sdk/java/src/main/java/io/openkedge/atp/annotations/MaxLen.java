package io.openkedge.atp.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an explicit {@code max_len} constraint (1..4096) for a STRING/BYTES
 * component. Absent means the decode-time default (1024) applies and NO
 * constraints key is materialized into the manifest (ATP-0002 §4.2).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface MaxLen {
    int value();
}
