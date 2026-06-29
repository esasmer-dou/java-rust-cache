package com.reactor.rust.cache;

public final class VersionedJsonCacheReader {

    private final RustCache cache;
    private final String namespace;
    private final long versionCacheMillis;
    private volatile String cachedVersion;
    private volatile long cachedVersionUntilMillis;

    VersionedJsonCacheReader(RustCache cache, String namespace, long versionCacheMillis) {
        this.cache = cache;
        this.namespace = namespace;
        this.versionCacheMillis = Math.max(0L, versionCacheMillis);
    }

    public CacheReadResult getById(long id) {
        String version = currentVersion();
        if (version == null) {
            return CacheReadResult.cacheNotReady();
        }
        byte[] bytes = cache.getBytes(VersionedJsonCache.idKey(namespace, version, id));
        return bytes == null ? CacheReadResult.miss() : CacheReadResult.hit(bytes);
    }

    public CacheReadResult getIndex(String indexName, String indexValue) {
        String version = currentVersion();
        if (version == null) {
            return CacheReadResult.cacheNotReady();
        }
        byte[] bytes = cache.getBytes(VersionedJsonCache.indexKey(namespace, version, indexName, indexValue));
        return bytes == null ? CacheReadResult.miss() : CacheReadResult.hit(bytes);
    }

    public CacheReadResult getMeta() {
        String version = currentVersion();
        if (version == null) {
            return CacheReadResult.cacheNotReady();
        }
        byte[] bytes = cache.getBytes(VersionedJsonCache.metaKey(namespace, version));
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
        version = cache.getString(VersionedJsonCache.currentKey(namespace));
        if (version != null && versionCacheMillis > 0) {
            cachedVersion = version;
            cachedVersionUntilMillis = now + versionCacheMillis;
        }
        return version;
    }
}
