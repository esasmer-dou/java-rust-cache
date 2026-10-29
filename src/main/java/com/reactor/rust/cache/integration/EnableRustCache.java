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

    /**
     * Controllers whose generated edge policies are installed before routes.
     * Listing cache policies explicitly consents to public, uniform data: a hit skips
     * Java handlers and guards. Never cache private or per-user output. Rate-only
     * policies do not bypass Java authentication or business logic.
     * Empty by default: no edge registration, native edge loading or additional startup branch.
     */
    Class<?>[] publicEdgePolicies() default {};

    /** Optional startup-only switch for the listed policies; false when unset. */
    String publicEdgeEnabledProperty() default "";
}
