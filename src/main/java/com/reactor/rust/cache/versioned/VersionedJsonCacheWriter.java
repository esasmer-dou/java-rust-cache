package com.reactor.rust.cache.versioned;

import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.lock.CacheLock;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class VersionedJsonCacheWriter {

    private static final AtomicLong VERSION_SEQUENCE = new AtomicLong();

    private final RustCache cache;
    private final String namespace;
    private final int batchSize;

    VersionedJsonCacheWriter(RustCache cache, String namespace, int batchSize) {
        this.cache = cache;
        this.namespace = namespace;
        this.batchSize = VersionedJsonCache.requireBatchSize(batchSize);
    }

    public SnapshotResult refreshSnapshotWithLock(
            String lockName,
            long lockTtlMillis,
            long dataTtlMillis,
            SnapshotCallback callback) {
        Objects.requireNonNull(callback, "callback");
        SnapshotResult[] result = new SnapshotResult[1];
        boolean ran = cache.locks().runOnceChecked(
                lockName,
                lockTtlMillis,
                lock -> result[0] = refreshSnapshot(dataTtlMillis, callback, lock, lockTtlMillis));
        return ran ? result[0] : SnapshotResult.skippedResult();
    }

    public SnapshotResult refreshSnapshot(long dataTtlMillis, SnapshotCallback callback) throws Exception {
        return refreshSnapshot(dataTtlMillis, callback, null, 0);
    }

    private SnapshotResult refreshSnapshot(
            long dataTtlMillis,
            SnapshotCallback callback,
            CacheLock lock,
            long lockTtlMillis) throws Exception {
        Objects.requireNonNull(callback, "callback");
        if (dataTtlMillis <= 0) {
            throw new IllegalArgumentException("dataTtlMillis must be positive");
        }
        String version = newVersion();
        VersionedJsonSnapshot snapshot = new VersionedJsonSnapshot(cache, namespace, version, dataTtlMillis, batchSize);
        callback.write(snapshot);
        if (lock != null) {
            lock.ensureValid();
            if (!lock.renew(lockTtlMillis)) {
                throw new IllegalStateException("Redis lock lease could not be renewed before publishing snapshot");
            }
            lock.ensureValid();
        }
        int written = snapshot.publish();
        return SnapshotResult.publishedResult(version, written);
    }

    private static String newVersion() {
        return Instant.now().toEpochMilli() + "-" + VERSION_SEQUENCE.incrementAndGet();
    }

    @FunctionalInterface
    public interface SnapshotCallback {
        void write(VersionedJsonSnapshot snapshot) throws Exception;
    }

    public record SnapshotResult(boolean published, boolean skippedLocked, String version, int writtenKeys) {

        static SnapshotResult skippedResult() {
            return new SnapshotResult(false, true, "", 0);
        }

        static SnapshotResult publishedResult(String version, int writtenKeys) {
            return new SnapshotResult(true, false, version, writtenKeys);
        }
    }
}
