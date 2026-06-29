package com.reactor.rust.cache;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class RustCache implements RustCacheReader, RustCacheWriter, AutoCloseable {

    private final RustCacheConfig config;
    private final int clientId;
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
        return this;
    }

    public RustCacheWriter writer() {
        return this;
    }

    public RustCacheLocks locks() {
        return new RustCacheLocks(this);
    }

    public VersionedJsonCache versionedJson(String namespace) {
        return VersionedJsonCache.create(this, namespace);
    }

    @Override
    public byte[] getBytes(String key) {
        ensureOpen();
        return NativeRedisBridge.get(clientId, requireKey(key));
    }

    public String getString(String key) {
        byte[] value = getBytes(key);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    @Override
    public byte[][] mgetBytes(String... keys) {
        ensureOpen();
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
        return NativeRedisBridge.exists(clientId, requireKey(key));
    }

    @Override
    public long ttlMillis(String key) {
        ensureOpen();
        return NativeRedisBridge.ttl(clientId, requireKey(key));
    }

    @Override
    public boolean setBytes(String key, byte[] value) {
        return setBytes(key, value, 0);
    }

    @Override
    public boolean setBytes(String key, byte[] value, long ttlMillis) {
        ensureOpen();
        return NativeRedisBridge.set(clientId, requireKey(key), requireValue(value), requireTtl(ttlMillis));
    }

    public boolean setString(String key, String value, long ttlMillis) {
        Objects.requireNonNull(value, "value");
        return setBytes(key, value.getBytes(StandardCharsets.UTF_8), ttlMillis);
    }

    @Override
    public boolean setBytesNx(String key, byte[] value, long ttlMillis) {
        ensureOpen();
        return NativeRedisBridge.setNx(clientId, requireKey(key), requireValue(value), requireTtl(ttlMillis));
    }

    @Override
    public int setManyBytes(String[] keys, byte[][] values, long ttlMillis) {
        ensureOpen();
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
        return NativeRedisBridge.delete(clientId, requireKey(key));
    }

    @Override
    public long increment(String key) {
        return increment(key, 0);
    }

    @Override
    public long increment(String key, long ttlMillis) {
        ensureOpen();
        return NativeRedisBridge.increment(clientId, requireKey(key), requireTtl(ttlMillis));
    }

    @Override
    public boolean expire(String key, long ttlMillis) {
        ensureOpen();
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

    boolean acquireLock(String key, String ownerToken, long ttlMillis) {
        ensureOpen();
        return NativeRedisBridge.acquireLock(clientId, requireKey(key), ownerToken, ttlMillis);
    }

    boolean renewLock(String key, String ownerToken, long ttlMillis) {
        ensureOpen();
        return NativeRedisBridge.renewLock(clientId, requireKey(key), ownerToken, ttlMillis);
    }

    boolean releaseLock(String key, String ownerToken) {
        ensureOpen();
        return NativeRedisBridge.releaseLock(clientId, requireKey(key), ownerToken);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            NativeRedisBridge.closeClient(clientId);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new RedisCacheException("RustCache is closed");
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
