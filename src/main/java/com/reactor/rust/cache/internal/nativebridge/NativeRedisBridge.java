package com.reactor.rust.cache.internal.nativebridge;

import com.reactor.rust.cache.config.RustCacheConfig;
import com.reactor.rust.cache.core.RedisCacheException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class NativeRedisBridge {

    private static final String NATIVE_EXTRACT_DIR_PROPERTY = "reactor.cache.native.extract-dir";
    private static final String NATIVE_EXTRACT_DIR_ENV = "REACTOR_CACHE_NATIVE_EXTRACT_DIR";
    private static final int REDIS_TOPOLOGY_ABI_VERSION = 2;

    private NativeRedisBridge() {}

    static {
        loadNativeLibrary();
    }

    public static int createClient(RustCacheConfig config) {
        int abiVersion = nativeAbiVersionOrLegacy();
        int id;
        if (abiVersion >= REDIS_TOPOLOGY_ABI_VERSION) {
            id = nativeCreateClientWithTopology(
                    config.topology().wireValue(),
                    config.effectiveNodes(),
                    config.host(),
                    config.port(),
                    config.sentinelMasterName(),
                    config.sentinelUsername(),
                    config.sentinelPassword(),
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
                    config.maxResponseBytes(),
                    config.clusterMaxRedirects(),
                    config.topologyRefreshMs());
        } else {
            if (config.topology() != com.reactor.rust.cache.config.RedisTopology.STANDALONE) {
                throw new RedisCacheException(
                        "Redis " + config.topology().wireValue()
                                + " topology requires rust_hyper native ABI "
                                + REDIS_TOPOLOGY_ABI_VERSION
                                + " or newer. Loaded ABI version: " + abiVersion);
            }
            id = nativeCreateClient(
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
        }
        if (id <= 0) {
            throw new RedisCacheException("Failed to create native Redis cache client");
        }
        return id;
    }

    private static int nativeAbiVersionOrLegacy() {
        try {
            return nativeAbiVersion();
        } catch (UnsatisfiedLinkError ignored) {
            return 1;
        }
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
        UnsatisfiedLinkError resourceLoadError = loadPackagedNativeLibrary();
        if (resourceLoadError == null) {
            return;
        }

        try {
            System.loadLibrary("rust_hyper");
        } catch (UnsatisfiedLinkError e) {
            e.addSuppressed(resourceLoadError);
            throw new RedisCacheException(
                    "Failed to load native Redis cache bridge. Expected rust_hyper native library from rust-java-rest "
                            + "or java-rust-cache packaged native resources, or a platform library named rust_hyper on java.library.path="
                            + System.getProperty("java.library.path"),
                    e);
        }
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
        } catch (LinkageError e) {
            throw new RedisCacheException(
                    "rust-java-rest NativeBridge was found but failed to initialize. "
                            + "Use a rust-java-rest/native binary version that exports java-rust-cache JNI symbols.",
                    e);
        }
    }

    private static UnsatisfiedLinkError loadPackagedNativeLibrary() {
        String resource = platformResource();
        if (resource == null) {
            return new UnsatisfiedLinkError("Unsupported platform for packaged rust_hyper native library: "
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        }

        try (InputStream input = NativeRedisBridge.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                return new UnsatisfiedLinkError("Missing packaged native resource: " + resource);
            }
            byte[] bytes = input.readAllBytes();
            Path library = extractNativeLibrary(bytes, resource);
            System.load(library.toAbsolutePath().toString());
            return null;
        } catch (UnsatisfiedLinkError e) {
            return e;
        } catch (IOException | RuntimeException e) {
            UnsatisfiedLinkError error = new UnsatisfiedLinkError("Failed to extract packaged native resource: " + resource);
            error.initCause(e);
            return error;
        }
    }

    private static Path extractNativeLibrary(byte[] bytes, String resource) throws IOException {
        String hash = sha256(bytes);
        String fileName = resource.substring(resource.lastIndexOf('/') + 1);
        Path directory = nativeExtractRoot().resolve(hash.substring(0, 16));
        Files.createDirectories(directory);
        Path library = directory.resolve(fileName);
        if (Files.exists(library)) {
            return library;
        }

        Path temp = Files.createTempFile(directory, fileName, ".tmp");
        Files.write(temp, bytes);
        try {
            Files.move(temp, library, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException ignored) {
            Files.deleteIfExists(temp);
        }
        return library;
    }

    private static Path nativeExtractRoot() {
        String configured = System.getProperty(NATIVE_EXTRACT_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(NATIVE_EXTRACT_DIR_ENV);
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }

        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new RedisCacheException("user.home is not set; configure "
                    + NATIVE_EXTRACT_DIR_PROPERTY + " or " + NATIVE_EXTRACT_DIR_ENV);
        }
        return Path.of(home, ".java-rust-cache", "native");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RedisCacheException("SHA-256 digest is not available", e);
        }
    }

    private static String platformResource() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (!x64) {
            return null;
        }
        if (os.contains("win")) {
            return "native/windows-x64/rust_hyper-windows-x64.dll";
        }
        if (os.contains("linux")) {
            return "native/linux-x64/librust_hyper-linux-x64.so";
        }
        return null;
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

    private static native int nativeAbiVersion();

    private static native int nativeCreateClientWithTopology(
            String topology,
            String nodes,
            String host,
            int port,
            String sentinelMasterName,
            String sentinelUsername,
            String sentinelPassword,
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
            int maxResponseBytes,
            int clusterMaxRedirects,
            int topologyRefreshMs);

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
