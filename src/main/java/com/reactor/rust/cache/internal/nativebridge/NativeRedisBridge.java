package com.reactor.rust.cache.internal.nativebridge;

import com.reactor.rust.cache.config.RustCacheConfig;
import com.reactor.rust.cache.core.RedisCacheException;

public final class NativeRedisBridge {

    private NativeRedisBridge() {}

    static {
        loadNativeLibrary();
    }

    public static int createClient(RustCacheConfig config) {
        int id = nativeCreateClient(
                config.host(),
                config.port(),
                config.username(),
                config.password(),
                config.database(),
                config.connectTimeoutMs(),
                config.readTimeoutMs(),
                config.writeTimeoutMs(),
                config.readConnections(),
                config.writeConnections(),
                config.maxReadInflight(),
                config.maxWriteInflight(),
                config.maxResponseBytes());
        if (id <= 0) {
            throw new RedisCacheException("Failed to create native Redis cache client");
        }
        return id;
    }

    public static byte[] get(int clientId, String key) {
        return nativeGet(clientId, key);
    }

    public static byte[][] mget(int clientId, String[] keys) {
        return nativeMget(clientId, keys);
    }

    public static boolean exists(int clientId, String key) {
        return nativeExists(clientId, key);
    }

    public static long ttl(int clientId, String key) {
        return nativeTtl(clientId, key);
    }

    public static boolean set(int clientId, String key, byte[] value, long ttlMillis) {
        return nativeSet(clientId, key, value, ttlMillis);
    }

    public static boolean setNx(int clientId, String key, byte[] value, long ttlMillis) {
        return nativeSetNx(clientId, key, value, ttlMillis);
    }

    public static int setMany(int clientId, String[] keys, byte[][] values, long ttlMillis) {
        return nativeSetMany(clientId, keys, values, ttlMillis);
    }

    public static long delete(int clientId, String key) {
        return nativeDel(clientId, key);
    }

    public static long increment(int clientId, String key, long ttlMillis) {
        return nativeIncr(clientId, key, ttlMillis);
    }

    public static boolean expire(int clientId, String key, long ttlMillis) {
        return nativeExpire(clientId, key, ttlMillis);
    }

    public static boolean acquireLock(int clientId, String key, String ownerToken, long ttlMillis) {
        return nativeAcquireLock(clientId, key, ownerToken, ttlMillis);
    }

    public static boolean renewLock(int clientId, String key, String ownerToken, long ttlMillis) {
        return nativeRenewLock(clientId, key, ownerToken, ttlMillis);
    }

    public static boolean releaseLock(int clientId, String key, String ownerToken) {
        return nativeReleaseLock(clientId, key, ownerToken);
    }

    public static void closeClient(int clientId) {
        nativeCloseClient(clientId);
    }

    public static String metricsJson() {
        return nativeMetricsJson();
    }

    public static void resetMetrics() {
        nativeResetMetrics();
    }

    private static void loadNativeLibrary() {
        ClassLoader ownLoader = NativeRedisBridge.class.getClassLoader();
        if (loadFrameworkNativeBridge(ownLoader)) {
            return;
        }

        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        if (systemLoader != ownLoader && loadFrameworkNativeBridge(systemLoader)) {
            return;
        }

        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != ownLoader
                && contextLoader != systemLoader
                && loadFrameworkNativeBridge(contextLoader)) {
            return;
        }
        System.loadLibrary("rust_hyper");
    }

    private static boolean loadFrameworkNativeBridge(ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        try {
            Class.forName("com.reactor.rust.bridge.NativeBridge", true, loader);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static native int nativeCreateClient(
            String host,
            int port,
            String username,
            String password,
            int database,
            int connectTimeoutMs,
            int readTimeoutMs,
            int writeTimeoutMs,
            int readConnections,
            int writeConnections,
            int maxReadInflight,
            int maxWriteInflight,
            int maxResponseBytes);

    private static native byte[] nativeGet(int clientId, String key);

    private static native byte[][] nativeMget(int clientId, String[] keys);

    private static native boolean nativeExists(int clientId, String key);

    private static native long nativeTtl(int clientId, String key);

    private static native boolean nativeSet(int clientId, String key, byte[] value, long ttlMillis);

    private static native boolean nativeSetNx(int clientId, String key, byte[] value, long ttlMillis);

    private static native int nativeSetMany(int clientId, String[] keys, byte[][] values, long ttlMillis);

    private static native long nativeDel(int clientId, String key);

    private static native long nativeIncr(int clientId, String key, long ttlMillis);

    private static native boolean nativeExpire(int clientId, String key, long ttlMillis);

    private static native boolean nativeAcquireLock(int clientId, String key, String ownerToken, long ttlMillis);

    private static native boolean nativeRenewLock(int clientId, String key, String ownerToken, long ttlMillis);

    private static native boolean nativeReleaseLock(int clientId, String key, String ownerToken);

    private static native void nativeCloseClient(int clientId);

    private static native String nativeMetricsJson();

    private static native void nativeResetMetrics();
}
