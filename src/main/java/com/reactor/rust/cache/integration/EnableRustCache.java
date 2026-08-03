package com.reactor.rust.cache.integration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Generates the minimal Rust cache lifecycle configuration for a REST application. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface EnableRustCache {

    String generatedConfigurationName() default "";
}
