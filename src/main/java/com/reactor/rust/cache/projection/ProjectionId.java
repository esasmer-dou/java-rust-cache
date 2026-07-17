package com.reactor.rust.cache.projection;

/**
 * Stable, allocation-free projection identifier implemented by application enums.
 */
@FunctionalInterface
public interface ProjectionId {

    String value();

    static ProjectionId of(String value) {
        String validated = requireText(value);
        return () -> validated;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("projection value must not be blank");
        }
        return value;
    }
}
