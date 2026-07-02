package com.reactor.rust.cache.config;

import java.util.Locale;

public enum RedisTopology {
    STANDALONE,
    SENTINEL,
    CLUSTER;

    public static RedisTopology parse(String value) {
        if (value == null || value.isBlank()) {
            return STANDALONE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "STANDALONE", "SINGLE" -> STANDALONE;
            case "SENTINEL" -> SENTINEL;
            case "CLUSTER" -> CLUSTER;
            default -> throw new IllegalArgumentException("Unsupported Redis topology: " + value);
        };
    }

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
