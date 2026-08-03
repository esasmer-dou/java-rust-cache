package com.reactor.rust.cache.projection;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Binds a no-argument reader method to projection metadata. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface ProjectionMetaRead {
    String projection();
}
