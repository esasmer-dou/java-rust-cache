package com.reactor.rust.cache.projection;

/**
 * Stable, allocation-free projection index identifier implemented by application enums.
 */
@FunctionalInterface
public interface ProjectionIndex {

    String value();

    static ProjectionIndex of(String value) {
        String validated = requireText(value);
        return () -> validated;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("projection index value must not be blank");
        }
        return value;
    }
}
