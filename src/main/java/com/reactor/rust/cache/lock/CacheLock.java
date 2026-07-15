package com.reactor.rust.cache.lock;

import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.core.RedisCacheException;

import java.util.Objects;
import java.util.function.Consumer;

public final class CacheLock implements AutoCloseable {

    private final RustCache cache;
    private final String key;
    private final String ownerToken;
    private final Consumer<CacheLock> closeListener;
    private volatile boolean closed;
    private volatile boolean lost;

    CacheLock(RustCache cache, String key, String ownerToken, Consumer<CacheLock> closeListener) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.key = Objects.requireNonNull(key, "key");
        this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        this.closeListener = Objects.requireNonNull(closeListener, "closeListener");
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
    public synchronized void close() {
        if (!closed) {
            closed = true;
            try {
                cache.releaseLock(key, ownerToken);
            } finally {
                closeListener.accept(this);
            }
        }
    }
}
