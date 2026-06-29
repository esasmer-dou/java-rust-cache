package com.reactor.rust.cache;

import java.util.Objects;

public final class CacheLock implements AutoCloseable {

    private final RustCache cache;
    private final String key;
    private final String ownerToken;
    private volatile boolean closed;

    CacheLock(RustCache cache, String key, String ownerToken) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.key = Objects.requireNonNull(key, "key");
        this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
    }

    public boolean renew(long ttlMillis) {
        if (closed) {
            return false;
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        return cache.renewLock(key, ownerToken, ttlMillis);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            cache.releaseLock(key, ownerToken);
        }
    }
}
