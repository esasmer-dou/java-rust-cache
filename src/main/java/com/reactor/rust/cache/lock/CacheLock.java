package com.reactor.rust.cache.lock;

import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.core.RedisCacheException;

import java.util.Objects;

public final class CacheLock implements AutoCloseable {

    private final RustCache cache;
    private final String key;
    private final String ownerToken;
    private volatile boolean closed;
    private volatile boolean lost;

    CacheLock(RustCache cache, String key, String ownerToken) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.key = Objects.requireNonNull(key, "key");
        this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
    }

    public boolean renew(long ttlMillis) {
        if (closed) {
            return false;
        }
        if (lost) {
            return false;
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        boolean renewed = cache.renewLock(key, ownerToken, ttlMillis);
        if (!renewed) {
            markLost();
        }
        return renewed;
    }

    public boolean isValid() {
        return !closed && !lost;
    }

    public void ensureValid() {
        if (lost) {
            throw new RedisCacheException("Redis lock lease was lost before the protected task completed: " + key);
        }
        if (closed) {
            throw new RedisCacheException("Redis lock is already closed: " + key);
        }
    }

    void markLost() {
        lost = true;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            cache.releaseLock(key, ownerToken);
        }
    }
}
