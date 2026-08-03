package com.reactor.rust.cache.projection;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Generates a bound, allocation-conscious cache projection reader implementation. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface GenerateProjectionReader {
    String rootPrefix();
    String generatedName() default "";

    /** Registers the generated reader as a REST DI bean at build time. */
    boolean restBean() default false;
}
