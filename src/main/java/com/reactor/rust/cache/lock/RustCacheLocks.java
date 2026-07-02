package com.reactor.rust.cache.lock;

import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.core.RedisCacheException;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RustCacheLocks {

    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

    private final RustCache cache;

    public RustCacheLocks(RustCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    public CacheLock tryAcquire(String name, long ttlMillis) {
        String lockKey = lockKey(name);
        String ownerToken = ownerToken();
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        return cache.acquireLock(lockKey, ownerToken, ttlMillis)
                ? new CacheLock(cache, lockKey, ownerToken)
                : null;
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
        AtomicBoolean running = new AtomicBoolean(true);
        Thread renewer = startRenewer(lock, ttlMillis, running);
        try (lock) {
            task.run(lock);
            lock.ensureValid();
            return true;
        } catch (Exception e) {
            throw new RedisCacheException("Locked cache task failed: " + name, e);
        } finally {
            running.set(false);
            if (renewer != null) {
                renewer.interrupt();
            }
        }
    }

    private static Thread startRenewer(CacheLock lock, long ttlMillis, AtomicBoolean running) {
        long sleepMillis = Math.max(250L, ttlMillis / 3L);
        Thread thread = new Thread(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    if (running.get() && !lock.renew(ttlMillis)) {
                        lock.markLost();
                        return;
                    }
                } catch (RuntimeException e) {
                    lock.markLost();
                    return;
                }
            }
        }, "java-rust-cache-lock-renewer");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String lockKey(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("lock name must not be blank");
        }
        return "lock:" + name.trim();
    }

    private static String ownerToken() {
        byte[] bytes = new byte[16];
        TOKEN_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
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
