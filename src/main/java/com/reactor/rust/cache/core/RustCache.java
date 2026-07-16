package com.reactor.rust.cache.core;

import com.reactor.rust.cache.api.RustCacheReader;
import com.reactor.rust.cache.api.RustCacheWriter;
import com.reactor.rust.cache.api.NativeCacheResponseHandle;
import com.reactor.rust.cache.config.RustCacheConfig;
import com.reactor.rust.cache.config.RedisAccessMode;
import com.reactor.rust.cache.internal.nativebridge.NativeRedisBridge;
import com.reactor.rust.cache.lock.RustCacheLocks;
import com.reactor.rust.cache.versioned.VersionedJsonCache;
import com.reactor.rust.cache.versioned.VersionedJsonCacheReader;
import com.reactor.rust.cache.versioned.VersionedJsonCacheWriter;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class RustCache implements RustCacheReader, RustCacheWriter, AutoCloseable {

    private final RustCacheConfig config;
    private final int clientId;
    private volatile RustCacheLocks locks;
    private volatile boolean closed;

    private RustCache(RustCacheConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.clientId = NativeRedisBridge.createClient(config);
    }

    static RustCache create(RustCacheConfig config) {
        return new RustCache(config);
    }

    public RustCacheConfig config() {
        return config;
    }

    public RustCacheReader reader() {
        requireRead();
        return this;
    }

    public RustCacheWriter writer() {
        requireWrite();
        return this;
    }

    public RustCacheLocks locks() {
        requireWrite();
        RustCacheLocks current = locks;
        if (current == null) {
            synchronized (this) {
                current = locks;
                if (current == null) {
                    current = new RustCacheLocks(this);
                    locks = current;
                }
            }
        }
        return current;
    }

    public VersionedJsonCache versionedJson(String namespace) {
        requireRead();
        requireWrite();
        return VersionedJsonCache.create(this, namespace);
    }

    public VersionedJsonCache versionedJson(String namespace, long versionCacheMillis, int batchSize) {
        requireRead();
        requireWrite();
        return VersionedJsonCache.create(this, namespace, versionCacheMillis, batchSize);
    }

    public VersionedJsonCacheReader versionedJsonReader(String namespace) {
        requireRead();
        return VersionedJsonCache.createReader(this, namespace);
    }

    public VersionedJsonCacheReader versionedJsonReader(String namespace, long versionCacheMillis) {
        requireRead();
        return VersionedJsonCache.createReader(this, namespace, versionCacheMillis);
    }

    public VersionedJsonCacheWriter versionedJsonWriter(String namespace) {
        requireWrite();
        return VersionedJsonCache.createWriter(this, namespace);
    }

    public VersionedJsonCacheWriter versionedJsonWriter(String namespace, int batchSize) {
        requireWrite();
        return VersionedJsonCache.createWriter(this, namespace, batchSize);
    }

    @Override
    public byte[] getBytes(String key) {
        ensureOpen();
        requireRead();
        return NativeRedisBridge.get(clientId, requireKey(key));
    }

    public String getString(String key) {
        byte[] value = getBytes(key);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    @Override
    public CompletableFuture<byte[]> getBytesAsync(String key) {
        ensureOpen();
        requireRead();
        return NativeRedisBridge.getAsync(clientId, requireKey(key), config.readTimeoutMs());
    }

    @Override
    public CompletableFuture<NativeCacheResponseHandle> getNativeJsonAsync(String key) {
        ensureOpen();
        requireRead();
        return NativeRedisBridge.getNativeJsonAsync(
                clientId,
                requireKey(key),
                config.readTimeoutMs()
        );
    }

    @Override
    public byte[][] mgetBytes(String... keys) {
        ensureOpen();
        requireRead();
        if (keys == null) {
            throw new IllegalArgumentException("keys must not be null");
        }
        String[] checked = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            checked[i] = requireKey(keys[i]);
        }
        return NativeRedisBridge.mget(clientId, checked);
    }

    @Override
    public boolean exists(String key) {
        ensureOpen();
        requireRead();
        return NativeRedisBridge.exists(clientId, requireKey(key));
    }

    @Override
    public long ttlMillis(String key) {
        ensureOpen();
        requireRead();
        return NativeRedisBridge.ttl(clientId, requireKey(key));
    }

    @Override
    public boolean setBytes(String key, byte[] value) {
        return setBytes(key, value, 0);
    }

    @Override
    public boolean setBytes(String key, byte[] value, long ttlMillis) {
        ensureOpen();
        requireWrite();
        return NativeRedisBridge.set(clientId, requireKey(key), requireValue(value), requireTtl(ttlMillis));
    }

    public boolean setString(String key, String value, long ttlMillis) {
        Objects.requireNonNull(value, "value");
        return setBytes(key, value.getBytes(StandardCharsets.UTF_8), ttlMillis);
    }

    @Override
    public boolean setBytesNx(String key, byte[] value, long ttlMillis) {
        ensureOpen();
        requireWrite();
        return NativeRedisBridge.setNx(clientId, requireKey(key), requireValue(value), requireTtl(ttlMillis));
    }

    @Override
    public int setManyBytes(String[] keys, byte[][] values, long ttlMillis) {
        ensureOpen();
        requireWrite();
        requireTtl(ttlMillis);
        if (keys == null || values == null) {
            throw new IllegalArgumentException("keys and values must not be null");
        }
        if (keys.length != values.length) {
            throw new IllegalArgumentException("keys and values length must match");
        }
        String[] checkedKeys = new String[keys.length];
        byte[][] checkedValues = new byte[values.length][];
        for (int i = 0; i < keys.length; i++) {
            checkedKeys[i] = requireKey(keys[i]);
            checkedValues[i] = requireValue(values[i]);
        }
        return NativeRedisBridge.setMany(clientId, checkedKeys, checkedValues, ttlMillis);
    }

    @Override
    public long delete(String key) {
        ensureOpen();
        requireWrite();
        return NativeRedisBridge.delete(clientId, requireKey(key));
    }

    @Override
    public long increment(String key) {
        return increment(key, 0);
    }

    @Override
    public long increment(String key, long ttlMillis) {
        ensureOpen();
        requireWrite();
        return NativeRedisBridge.increment(clientId, requireKey(key), requireTtl(ttlMillis));
    }

    @Override
    public boolean expire(String key, long ttlMillis) {
        ensureOpen();
        requireWrite();
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive for expire");
        }
        return NativeRedisBridge.expire(clientId, requireKey(key), ttlMillis);
    }

    public String metricsJson() {
        return NativeRedisBridge.metricsJson();
    }

    public void resetMetrics() {
        NativeRedisBridge.resetMetrics();
    }

    public boolean acquireLock(String key, String ownerToken, long ttlMillis) {
        ensureOpen();
        requireWrite();
        return NativeRedisBridge.acquireLock(clientId, requireKey(key), ownerToken, ttlMillis);
    }

    public boolean renewLock(String key, String ownerToken, long ttlMillis) {
        ensureOpen();
        requireWrite();
        return NativeRedisBridge.renewLock(clientId, requireKey(key), ownerToken, ttlMillis);
    }

    public boolean releaseLock(String key, String ownerToken) {
        ensureOpen();
        requireWrite();
        return NativeRedisBridge.releaseLock(clientId, requireKey(key), ownerToken);
    }

    public boolean publishFencedVersion(
            String currentKey,
            long fencingToken,
            String version,
            long ttlMillis) {
        ensureOpen();
        requireWrite();
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        Objects.requireNonNull(version, "version");
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        return NativeRedisBridge.publishFencedVersion(
                clientId,
                requireKey(currentKey),
                fencingToken,
                version,
                ttlMillis
        );
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            try {
                RustCacheLocks currentLocks = locks;
                if (currentLocks != null) {
                    currentLocks.close();
                }
            } finally {
                closed = true;
                NativeRedisBridge.closeClient(clientId);
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new RedisCacheException("RustCache is closed");
        }
    }

    private void requireRead() {
        requireCapability(true, "read");
    }

    private void requireWrite() {
        requireCapability(false, "write");
    }

    private void requireCapability(boolean read, String operation) {
        RedisAccessMode mode = config.accessMode();
        boolean allowed = read ? mode.readable() : mode.writable();
        if (!allowed) {
            throw new RedisCacheException(
                    "Redis " + operation + " operation is disabled by reactor.cache.redis.access-mode="
                            + mode.wireValue());
        }
    }

    private static String requireKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must not be empty");
        }
        return key;
    }

    private static byte[] requireValue(byte[] value) {
        return Objects.requireNonNull(value, "value");
    }

    private static long requireTtl(long ttlMillis) {
        if (ttlMillis < 0) {
            throw new IllegalArgumentException("ttlMillis must not be negative");
        }
        return ttlMillis;
    }
}
