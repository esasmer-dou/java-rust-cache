package com.reactor.rust.cache.config;

import java.util.Locale;

public enum RedisAccessMode {
    READ_WRITE("read-write", 0, true, true),
    READ_ONLY("read-only", 1, true, false),
    WRITE_ONLY("write-only", 2, false, true);

    private final String wireValue;
    private final int nativeValue;
    private final boolean readable;
    private final boolean writable;

    RedisAccessMode(String wireValue, int nativeValue, boolean readable, boolean writable) {
        this.wireValue = wireValue;
        this.nativeValue = nativeValue;
        this.readable = readable;
        this.writable = writable;
    }

    public String wireValue() {
        return wireValue;
    }

    public int nativeValue() {
        return nativeValue;
    }

    public boolean readable() {
        return readable;
    }

    public boolean writable() {
        return writable;
    }

    public static RedisAccessMode parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", "read-write", "readwrite", "rw" -> READ_WRITE;
            case "read-only", "readonly", "read", "ro" -> READ_ONLY;
            case "write-only", "writeonly", "write", "wo" -> WRITE_ONLY;
            default -> throw new IllegalArgumentException(
                    "access-mode must be read-write, read-only, or write-only: " + value);
        };
    }
}
