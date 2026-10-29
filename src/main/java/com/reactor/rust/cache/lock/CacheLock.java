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
    private long snapshotFence;
    private boolean snapshotCommitted;
    private long renewalTtlMillis;
    private long nextRenewalNanos;

    CacheLock(RustCache cache, String key, String ownerToken, Consumer<CacheLock> closeListener) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.key = Objects.requireNonNull(key, "key");
        this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        this.closeListener = Objects.requireNonNull(closeListener, "closeListener");
    }

    public synchronized boolean renew(long ttlMillis) {
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

    synchronized void scheduleRenewal(long ttlMillis) {
        renewalTtlMillis = ttlMillis;
        nextRenewalNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(ttlMillis / 3);
    }

    synchronized boolean renewIfDue(long nowNanos) {
        if (renewalTtlMillis == 0 || closed || lost || nowNanos - nextRenewalNanos < 0) return false;
        try {
            if (renew(renewalTtlMillis)) {
                nextRenewalNanos = System.nanoTime()
                        + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(renewalTtlMillis / 3);
                return true;
            }
        } catch (RuntimeException error) {
            markLost();
        }
        return false;
    }

    void snapshotFence(long fence) { snapshotFence = fence; }

    public long snapshotFence() { return snapshotFence; }

    boolean snapshotCommitted() { return snapshotCommitted; }

    /** Serializes commit with renewal/close; a successful commit releases the remote lease atomically. */
    public synchronized int commitSnapshot(String current, long fence, String version, long ttl,
                                            String[] keys, byte[][] values) {
        ensureValid();
        if (snapshotFence <= 0 || snapshotFence != fence) {
            throw new IllegalStateException("Snapshot lease does not own this fencing token");
        }
        int written = cache.commitSnapshot(key, ownerToken, current, fence, version, ttl, keys, values);
        if (written <= 0) {
            markLost();
            throw new RedisCacheException("Snapshot commit rejected: expired lease or newer fenced writer");
        }
        snapshotCommitted = true;
        closed = true;
        closeListener.accept(this);
        return written;
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
