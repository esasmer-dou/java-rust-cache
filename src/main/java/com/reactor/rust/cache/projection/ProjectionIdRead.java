package com.reactor.rust.cache.projection;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Binds a reader method to an ID lookup in a named projection. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface ProjectionIdRead {
    String projection();
}
