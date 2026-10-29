package com.reactor.rust.cache.lock;

import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.core.RedisCacheException;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class RustCacheLocks implements AutoCloseable {

    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final RustCache cache;
    private final ScheduledThreadPoolExecutor renewalExecutor;
    private final Set<CacheLock> activeLocks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong renewalSchedules = new AtomicLong();
    private ScheduledFuture<?> sharedRenewal;
    private long sharedIntervalMillis = Long.MAX_VALUE;

    public RustCacheLocks(RustCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
        ThreadFactory factory = task -> {
            Thread thread = new Thread(
                    null,
                    task,
                    "java-rust-cache-lock-renewer-" + THREAD_SEQUENCE.incrementAndGet(),
                    256L * 1024L);
            thread.setDaemon(true);
            return thread;
        };
        this.renewalExecutor = new ScheduledThreadPoolExecutor(1, factory);
        this.renewalExecutor.setRemoveOnCancelPolicy(true);
        this.renewalExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.renewalExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public CacheLock tryAcquire(String name, long ttlMillis) {
        return tryAcquire(name, ttlMillis, null);
    }

    private CacheLock tryAcquire(String name, long ttlMillis, String fenceKey) {
        ensureOpen();
        String lockKey = lockKey(name);
        String ownerToken = ownerToken();
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        long fence = 0;
        boolean acquired;
        if (fenceKey != null && cache.supportsSnapshotBatch()) {
            fence = cache.beginSnapshot(lockKey, ownerToken, fenceKey, ttlMillis);
            acquired = fence > 0;
        } else {
            acquired = cache.acquireLock(lockKey, ownerToken, ttlMillis);
        }
        if (!acquired) {
            return null;
        }
        CacheLock lock = new CacheLock(cache, lockKey, ownerToken, activeLocks::remove);
        lock.snapshotFence(fence);
        activeLocks.add(lock);
        if (closed.get()) {
            lock.close();
            throw new RedisCacheException("Redis lock manager is closed");
        }
        return lock;
    }

    public boolean runOnce(String name, long ttlMillis, ThrowingRunnable task) {
        Objects.requireNonNull(task, "task");
        return runOnceChecked(name, ttlMillis, lock -> {
            task.run();
            lock.ensureValid();
        });
    }

    public boolean runOnceChecked(String name, long ttlMillis, ThrowingLockRunnable task) {
        return runOnceChecked(name, ttlMillis, null, task);
    }

    public boolean runSnapshotChecked(String name, long ttlMillis, String fenceKey, ThrowingLockRunnable task) {
        return runOnceChecked(name, ttlMillis, Objects.requireNonNull(fenceKey, "fenceKey"), task);
    }

    private boolean runOnceChecked(String name, long ttlMillis, String fenceKey, ThrowingLockRunnable task) {
        Objects.requireNonNull(task, "task");
        CacheLock lock = tryAcquire(name, ttlMillis, fenceKey);
        if (lock == null) {
            return false;
        }
        ScheduledFuture<?> renewal = null;
        try (lock) {
            ensureOpen();
            lock.ensureValid();
            renewal = startRenewer(lock, ttlMillis);
            task.run(lock);
            if (!lock.snapshotCommitted()) lock.ensureValid();
            return true;
        } catch (Exception e) {
            throw new RedisCacheException("Locked cache task failed: " + name, e);
        } finally {
            if (renewal != null) {
                renewal.cancel(true);
            }
        }
    }

    private ScheduledFuture<?> startRenewer(CacheLock lock, long ttlMillis) {
        long intervalMillis = Math.max(1L, ttlMillis / 3L);
        if (ttlMillis >= 300) {
            lock.scheduleRenewal(ttlMillis);
            startSharedRenewer(intervalMillis);
            return null;
        }
        // Tiny leases keep their original per-lock cadence and do not force
        // a manager-wide millisecond wakeup after the short task has ended.
        renewalSchedules.incrementAndGet();
        return renewalExecutor.scheduleWithFixedDelay(() -> {
            try {
                if (!lock.renew(ttlMillis)) {
                    lock.markLost();
                }
            } catch (RuntimeException e) {
                lock.markLost();
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private synchronized void startSharedRenewer(long intervalMillis) {
        ensureOpen();
        if (intervalMillis >= sharedIntervalMillis) return;
        if (sharedRenewal != null) sharedRenewal.cancel(false);
        sharedIntervalMillis = intervalMillis;
        renewalSchedules.incrementAndGet();
        sharedRenewal = renewalExecutor.scheduleWithFixedDelay(
                this::renewActiveLocks, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void renewActiveLocks() {
        if (activeLocks.isEmpty()) return;
        for (CacheLock lock : activeLocks) {
            // A shared tick can fall just after acquisition. Never renew that
            // fresh lease or block its commit on an unnecessary Redis command.
            // Tick <= ttl/3 leaves at least ttl/3 before expiry for a due lease.
            lock.renewIfDue(System.nanoTime());
        }
    }

    long renewalSchedulesForTest() { return renewalSchedules.get(); }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            renewalExecutor.shutdownNow();
            for (CacheLock lock : activeLocks) {
                try {
                    lock.close();
                } catch (RuntimeException ignored) {
                    // The lease still expires by TTL if Redis is unavailable during shutdown.
                }
            }
            activeLocks.clear();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new RedisCacheException("Redis lock manager is closed");
        }
    }

    private static String lockKey(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("lock name must not be blank");
        }
        return "lock:" + name.trim();
    }

    static String ownerToken() {
        long sequence = TokenRandomHolder.SEQUENCE.getAndIncrement();
        if (sequence < 0) {
            throw new IllegalStateException("Redis lease owner sequence exhausted");
        }
        return TokenRandomHolder.PREFIX + ':' + Long.toHexString(sequence);
    }

    private static final class TokenRandomHolder {
        // Independent JVMs get 128 random bits; the counter prevents reuse within
        // this JVM without a shared PRNG lock or an entropy read on every refresh.
        private static final String PREFIX = prefix();
        private static final AtomicLong SEQUENCE = new AtomicLong();

        private static String prefix() {
            byte[] bytes = new byte[16];
            new SecureRandom().nextBytes(bytes);
            return HexFormat.of().formatHex(bytes);
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingLockRunnable {
        void run(CacheLock lock) throws Exception;
    }
}
