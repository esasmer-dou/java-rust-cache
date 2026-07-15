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

public final class RustCacheLocks implements AutoCloseable {

    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final RustCache cache;
    private final ScheduledThreadPoolExecutor renewalExecutor;
    private final Set<CacheLock> activeLocks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

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
        ensureOpen();
        String lockKey = lockKey(name);
        String ownerToken = ownerToken();
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        if (!cache.acquireLock(lockKey, ownerToken, ttlMillis)) {
            return null;
        }
        CacheLock lock = new CacheLock(cache, lockKey, ownerToken, activeLocks::remove);
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
        Objects.requireNonNull(task, "task");
        CacheLock lock = tryAcquire(name, ttlMillis);
        if (lock == null) {
            return false;
        }
        ScheduledFuture<?> renewal = null;
        try (lock) {
            ensureOpen();
            lock.ensureValid();
            renewal = startRenewer(lock, ttlMillis);
            task.run(lock);
            lock.ensureValid();
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

    private static String ownerToken() {
        byte[] bytes = new byte[16];
        TokenRandomHolder.INSTANCE.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static final class TokenRandomHolder {
        private static final SecureRandom INSTANCE = new SecureRandom();
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
