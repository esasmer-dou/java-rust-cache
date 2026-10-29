package com.reactor.rust.cache.internal.nativebridge;

import com.reactor.rust.cache.config.RustCacheConfig;
import com.reactor.rust.cache.api.NativeCacheResponseHandle;
import com.reactor.rust.cache.core.RedisCacheException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class NativeRedisBridge {

    private static final String NATIVE_EXTRACT_DIR_PROPERTY = "reactor.cache.native.extract-dir";
    private static final String NATIVE_EXTRACT_DIR_ENV = "REACTOR_CACHE_NATIVE_EXTRACT_DIR";
    private static final int REDIS_TOPOLOGY_ABI_VERSION = 2;
    private static final int REDIS_SENTINEL_MASTER_CHECK_ABI_VERSION = 3;
    private static final int REDIS_FENCED_PUBLISH_ABI_VERSION = 4;
    private static final int REDIS_ASYNC_GET_ABI_VERSION = 5;
    private static final int REDIS_ACCESS_MODE_ABI_VERSION = 6;
    private static final PendingNativeCacheOperations PENDING = new PendingNativeCacheOperations();

    private NativeRedisBridge() {}

    static {
        loadNativeLibrary();
    }

    public static int createClient(RustCacheConfig config) {
        int abiVersion = nativeAbiVersionOrLegacy();
        int id;
        if (abiVersion >= REDIS_ACCESS_MODE_ABI_VERSION) {
            id = nativeCreateClientWithTopologyV4(
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
                    config.topologyRefreshMs(),
                    config.sentinelMasterCheckMs(),
                    config.accessMode().nativeValue());
        } else if (config.accessMode() != com.reactor.rust.cache.config.RedisAccessMode.READ_WRITE) {
            throw new RedisCacheException(
                    "Redis access-mode=" + config.accessMode().wireValue()
                            + " requires rust_hyper Redis ABI " + REDIS_ACCESS_MODE_ABI_VERSION
                            + " or newer. Loaded Redis ABI version: " + abiVersion);
        } else if (abiVersion >= REDIS_SENTINEL_MASTER_CHECK_ABI_VERSION) {
            id = nativeCreateClientWithTopologyV3(
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
                    config.topologyRefreshMs(),
                    config.sentinelMasterCheckMs());
        } else if (abiVersion >= REDIS_TOPOLOGY_ABI_VERSION) {
            if (config.topology() == com.reactor.rust.cache.config.RedisTopology.SENTINEL) {
                throw new RedisCacheException(
                        "Redis Sentinel topology requires rust_hyper native ABI "
                                + REDIS_SENTINEL_MASTER_CHECK_ABI_VERSION
                                + " or newer for configurable master refresh. Loaded ABI version: " + abiVersion);
            }
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

    public static CompletableFuture<byte[]> getAsync(
            int clientId,
            String key,
            int timeoutMs) {
        requireAsyncAbi();
        PendingNativeCacheOperations.PendingBytes pending = PENDING.beginBytes(clientId);
        try {
            boolean accepted = nativeGetAsync(clientId, key, pending.callbackId());
            if (accepted) {
                PENDING.accepted(pending.callbackId(), pending.future(), timeoutMs);
            } else {
                PENDING.rejected(
                        pending.callbackId(),
                        pending.future(),
                        "Native Redis async read admission rejected the call"
                );
            }
        } catch (RuntimeException | LinkageError error) {
            PENDING.rejected(pending.callbackId(), pending.future(), error);
        }
        return pending.future();
    }

    public static CompletableFuture<NativeCacheResponseHandle> getNativeJsonAsync(
            int clientId,
            String key,
            int timeoutMs) {
        requireAsyncAbi();
        PendingNativeCacheOperations.PendingNativeJson pending = PENDING.beginNativeJson(clientId);
        try {
            boolean accepted = nativeGetNativeJsonAsync(clientId, key, pending.callbackId());
            if (accepted) {
                PENDING.accepted(pending.callbackId(), pending.future(), timeoutMs);
            } else {
                PENDING.rejected(
                        pending.callbackId(),
                        pending.future(),
                        "Native Redis async read admission rejected the call"
                );
            }
        } catch (RuntimeException | LinkageError error) {
            PENDING.rejected(pending.callbackId(), pending.future(), error);
        }
        return pending.future();
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

    public static boolean supportsSnapshotBatch() {
        return SnapshotBatchSupport.AVAILABLE;
    }

    private static final class SnapshotBatchSupport {
        static final boolean AVAILABLE = probe();
        private static boolean probe() {
            try { return nativeSnapshotBatchVersion() == 1; }
            catch (UnsatisfiedLinkError legacy) { return false; }
        }
    }

    public static long beginSnapshot(int clientId, String key, String owner, String fence, long ttl) {
        return nativeBeginSnapshot(clientId, key, owner, fence, ttl);
    }

    public static int commitSnapshot(int clientId, String key, String owner, String current, long fence,
                                     String version, long ttl, String[] keys, byte[][] values) {
        return nativeCommitSnapshot(clientId, key, owner, current, fence, version, ttl, keys, values);
    }

    public static boolean renewLock(int clientId, String key, String ownerToken, long ttlMillis) {
        return nativeRenewLock(clientId, key, ownerToken, ttlMillis);
    }

    public static boolean releaseLock(int clientId, String key, String ownerToken) {
        return nativeReleaseLock(clientId, key, ownerToken);
    }

    public static boolean publishFencedVersion(
            int clientId,
            String currentKey,
            long fencingToken,
            String version,
            long ttlMillis) {
        int abiVersion = nativeAbiVersionOrLegacy();
        if (abiVersion < REDIS_FENCED_PUBLISH_ABI_VERSION) {
            throw new RedisCacheException(
                    "Fenced snapshot publishing requires rust_hyper Redis ABI "
                            + REDIS_FENCED_PUBLISH_ABI_VERSION
                            + " or newer. Loaded Redis ABI version: " + abiVersion
            );
        }
        return nativePublishFencedVersion(clientId, currentKey, fencingToken, version, ttlMillis);
    }

    public static void closeClient(int clientId) {
        try {
            nativeCloseClient(clientId);
        } finally {
            PENDING.closeClient(clientId);
        }
    }

    public static void releaseResponseHandle(int nativeId) {
        if (nativeId > 0) {
            nativeReleaseResponseHandle(nativeId);
        }
    }

    public static String metricsJson() {
        return nativeMetricsJson();
    }

    public static void resetMetrics() {
        nativeResetMetrics();
    }

    private static void requireAsyncAbi() {
        int abiVersion = nativeAbiVersionOrLegacy();
        if (abiVersion < REDIS_ASYNC_GET_ABI_VERSION) {
            throw new RedisCacheException(
                    "Async Redis reads require rust_hyper Redis ABI "
                            + REDIS_ASYNC_GET_ABI_VERSION
                            + " or newer. Loaded Redis ABI version: " + abiVersion
            );
        }
    }

    private static boolean completeGetAsync(
            long callbackId,
            byte[] value,
            String errorMessage) {
        return PENDING.completeBytes(callbackId, value, errorMessage);
    }

    private static boolean completeNativeJsonGetAsync(
            long callbackId,
            int nativeId,
            String errorMessage) {
        return PENDING.completeNativeJson(callbackId, nativeId, errorMessage);
    }

    static int pendingCountForTest() {
        return PENDING.size();
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

        java.net.URL binaryResource = NativeRedisBridge.class.getClassLoader().getResource(resource);
        if (binaryResource == null) {
                return new UnsatisfiedLinkError("Missing packaged native resource: " + resource);
        }
        try (InputStream input = binaryResource.openStream()) {
            Path library = NativeCacheArtifactStager.stage(
                    binaryResource,
                    platformName(resource),
                    input,
                    nativeExtractRoot(),
                    resource.substring(resource.lastIndexOf('/') + 1),
                    REDIS_ACCESS_MODE_ABI_VERSION
            );
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

    private static String platformResource() {
        return platformResource(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static String platformResource(String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.contains("win") && x64) {
            return "native/windows-x64/rust_hyper-windows-x64.dll";
        }
        if (os.contains("linux") && x64) {
            return "native/linux-x64/librust_hyper-linux-x64.so";
        }
        if ((os.contains("mac") || os.contains("darwin")) && arm64) {
            return "native/macos-arm64/librust_hyper-macos-arm64.dylib";
        }
        if (os.contains("linux") && arm64) {
            return "native/linux-arm64/librust_hyper-linux-arm64.so";
        }
        return null;
    }

    static String platformName(String resource) {
        if (resource == null || !resource.startsWith("native/")) {
            throw new IllegalArgumentException("Invalid packaged native resource: " + resource);
        }
        int separator = resource.indexOf('/', "native/".length());
        if (separator < 0) {
            throw new IllegalArgumentException("Invalid packaged native resource: " + resource);
        }
        String platform = resource.substring("native/".length(), separator);
        return switch (platform) {
            case "windows-x64", "linux-x64", "macos-arm64", "linux-arm64" -> platform;
            default -> throw new IllegalArgumentException("Unsupported packaged native platform: " + platform);
        };
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

    private static native int nativeCreateClientWithTopologyV3(
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
            int topologyRefreshMs,
            int sentinelMasterCheckMs);

    private static native int nativeCreateClientWithTopologyV4(
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
            int topologyRefreshMs,
            int sentinelMasterCheckMs,
            int accessMode);

    private static native byte[] nativeGet(int clientId, String key);

    private static native boolean nativeGetAsync(int clientId, String key, long callbackId);

    private static native boolean nativeGetNativeJsonAsync(int clientId, String key, long callbackId);

    private static native void nativeReleaseResponseHandle(int nativeId);

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

    private static native int nativeSnapshotBatchVersion();
    private static native long nativeBeginSnapshot(int clientId, String key, String owner, String fence, long ttl);
    private static native int nativeCommitSnapshot(int clientId, String key, String owner, String current,
            long fence, String version, long ttl, String[] keys, byte[][] values);

    private static native boolean nativeRenewLock(int clientId, String key, String ownerToken, long ttlMillis);

    private static native boolean nativeReleaseLock(int clientId, String key, String ownerToken);

    private static native boolean nativePublishFencedVersion(
            int clientId,
            String currentKey,
            long fencingToken,
            String version,
            long ttlMillis);

    private static native void nativeCloseClient(int clientId);

    private static native String nativeMetricsJson();

    private static native void nativeResetMetrics();
}
