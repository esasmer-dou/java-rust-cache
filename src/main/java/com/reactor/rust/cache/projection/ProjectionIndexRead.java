package com.reactor.rust.cache.projection;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Binds a reader method to a named projection index. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface ProjectionIndexRead {
    String projection();
    String index();
    String defaultValue() default "";
    boolean trim() default true;
}
