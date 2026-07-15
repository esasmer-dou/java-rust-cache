package com.reactor.rust.cache.versioned;

import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.lock.CacheLock;

import java.time.Instant;
import java.util.Objects;

public final class VersionedJsonCacheWriter {

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
        long fencingToken = cache.increment(VersionedJsonCache.fenceKey(namespace));
        if (fencingToken <= 0) {
            throw new IllegalStateException("Redis returned an invalid snapshot fencing token");
        }
        String version = newVersion(fencingToken);
        VersionedJsonSnapshot snapshot = new VersionedJsonSnapshot(
                cache,
                namespace,
                version,
                fencingToken,
                dataTtlMillis,
                batchSize
        );
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

    private static String newVersion(long fencingToken) {
        return Instant.now().toEpochMilli() + "-" + fencingToken;
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
