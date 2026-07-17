package com.reactor.rust.cache.versioned;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.cache.core.RustCache;

import java.util.Objects;

public final class VersionedJsonCacheReader {

    private final RustCache cache;
    private final String namespace;
    private final long versionCacheMillis;
    private volatile String cachedVersion;
    private volatile long cachedVersionUntilMillis;

    public static VersionedJsonCacheReader create(RustCache cache, String namespace) {
        return create(cache, namespace, VersionedJsonKeys.DEFAULT_VERSION_CACHE_MILLIS);
    }

    public static VersionedJsonCacheReader create(
            RustCache cache,
            String namespace,
            long versionCacheMillis) {
        return new VersionedJsonCacheReader(
                Objects.requireNonNull(cache, "cache"),
                VersionedJsonKeys.normalizeNamespace(namespace),
                versionCacheMillis);
    }

    VersionedJsonCacheReader(RustCache cache, String namespace, long versionCacheMillis) {
        this.cache = cache;
        this.namespace = namespace;
        this.versionCacheMillis = VersionedJsonKeys.requireVersionCacheMillis(versionCacheMillis);
    }

    public CacheReadResult getById(long id) {
        String version = currentVersion();
        if (version == null) {
            return CacheReadResult.cacheNotReady();
        }
        byte[] bytes = cache.getBytes(VersionedJsonKeys.idKey(namespace, version, id));
        return bytes == null ? CacheReadResult.miss() : CacheReadResult.hit(bytes);
    }

    public CacheReadResult getIndex(String indexName, String indexValue) {
        String version = currentVersion();
        if (version == null) {
            return CacheReadResult.cacheNotReady();
        }
        byte[] bytes = cache.getBytes(VersionedJsonKeys.indexKey(namespace, version, indexName, indexValue));
        return bytes == null ? CacheReadResult.miss() : CacheReadResult.hit(bytes);
    }

    public CacheReadResult getMeta() {
        String version = currentVersion();
        if (version == null) {
            return CacheReadResult.cacheNotReady();
        }
        byte[] bytes = cache.getBytes(VersionedJsonKeys.metaKey(namespace, version));
        return bytes == null ? CacheReadResult.miss() : CacheReadResult.hit(bytes);
    }

    public void invalidateVersionCache() {
        cachedVersion = null;
        cachedVersionUntilMillis = 0L;
    }

    private String currentVersion() {
        long now = System.currentTimeMillis();
        String version = cachedVersion;
        if (version != null && now < cachedVersionUntilMillis) {
            return version;
        }
        version = VersionedJsonKeys.decodeCurrentVersion(
                cache.getString(VersionedJsonKeys.currentKey(namespace))
        );
        if (version != null && versionCacheMillis > 0) {
            cachedVersion = version;
            cachedVersionUntilMillis = now + versionCacheMillis;
        }
        return version;
    }
}
