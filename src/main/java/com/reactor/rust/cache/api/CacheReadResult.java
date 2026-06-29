package com.reactor.rust.cache.api;

import java.util.Arrays;

public final class CacheReadResult {

    public enum Status {
        HIT,
        MISS,
        CACHE_NOT_READY
    }

    private static final CacheReadResult MISS = new CacheReadResult(Status.MISS, null);
    private static final CacheReadResult CACHE_NOT_READY = new CacheReadResult(Status.CACHE_NOT_READY, null);

    private final Status status;
    private final byte[] bytes;

    private CacheReadResult(Status status, byte[] bytes) {
        this.status = status;
        this.bytes = bytes;
    }

    public static CacheReadResult hit(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null for hit result");
        }
        return new CacheReadResult(Status.HIT, bytes);
    }

    public static CacheReadResult miss() {
        return MISS;
    }

    public static CacheReadResult cacheNotReady() {
        return CACHE_NOT_READY;
    }

    public Status status() {
        return status;
    }

    public boolean hit() {
        return status == Status.HIT;
    }

    public boolean isMiss() {
        return status == Status.MISS;
    }

    public boolean isCacheNotReady() {
        return status == Status.CACHE_NOT_READY;
    }

    public byte[] bytes() {
        return bytes;
    }

    public byte[] copyBytes() {
        return bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
    }
}
