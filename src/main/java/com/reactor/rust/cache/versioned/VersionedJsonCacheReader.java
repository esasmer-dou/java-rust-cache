package com.reactor.rust.cache.versioned;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.cache.core.RustCache;

import java.util.Objects;

public final class VersionedJsonCacheReader {

    private final RustCache cache;
    private final String namespace;
    private final long versionCacheMillis;
    private final CacheFreshness freshness;
    private volatile Version cached;
    private record Version(String value, long untilMillis, long freshUntilMillis) {}

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
        this(cache, namespace, versionCacheMillis, CacheFreshness.DISABLED);
    }

    public static VersionedJsonCacheReader create(RustCache cache, String namespace,
                                                  long versionCacheMillis, CacheFreshness freshness) {
        return new VersionedJsonCacheReader(Objects.requireNonNull(cache),
                VersionedJsonKeys.normalizeNamespace(namespace), versionCacheMillis, Objects.requireNonNull(freshness));
    }

    private VersionedJsonCacheReader(RustCache cache, String namespace, long versionCacheMillis, CacheFreshness freshness) {
        this.cache = cache;
        this.namespace = namespace;
        this.versionCacheMillis = VersionedJsonKeys.requireVersionCacheMillis(versionCacheMillis);
        this.freshness = freshness;
    }

    public CacheReadResult getById(long id) {
        Version version = currentVersion();
        if (version == null) {
            return CacheReadResult.cacheNotReady();
        }
        if (stale(version) && !freshness.serveStale()) return CacheReadResult.stale();
        byte[] bytes = cache.getBytes(VersionedJsonKeys.idKey(namespace, version.value(), id));
        return result(bytes, version);
    }

    public CacheReadResult getIndex(String indexName, String indexValue) {
        Version version = currentVersion();
        if (version == null) {
            return CacheReadResult.cacheNotReady();
        }
        if (stale(version) && !freshness.serveStale()) return CacheReadResult.stale();
        byte[] bytes = cache.getBytes(VersionedJsonKeys.indexKey(namespace, version.value(), indexName, indexValue));
        return result(bytes, version);
    }

    public CacheReadResult getMeta() {
        Version version = currentVersion();
        if (version == null) {
            return CacheReadResult.cacheNotReady();
        }
        if (stale(version) && !freshness.serveStale()) return CacheReadResult.stale();
        byte[] bytes = cache.getBytes(VersionedJsonKeys.metaKey(namespace, version.value()));
        return result(bytes, version);
    }

    public void invalidateVersionCache() {
        cached = null;
    }

    private CacheReadResult result(byte[] bytes, Version version) {
        if (bytes == null) return CacheReadResult.miss();
        if (!stale(version)) return CacheReadResult.hit(bytes);
        return freshness.serveStale() ? CacheReadResult.staleHit(bytes) : CacheReadResult.stale();
    }

    private boolean stale(Version version) {
        return freshness.maxAgeMillis() != 0 && System.currentTimeMillis() > version.freshUntilMillis();
    }

    private Version currentVersion() {
        long now = System.currentTimeMillis();
        Version version = cached;
        if (version != null && now < version.untilMillis()) {
            return version;
        }
        String value = VersionedJsonKeys.decodeCurrentVersion(
                cache.getString(VersionedJsonKeys.currentKey(namespace))
        );
        if (value == null) return null;
        long freshUntil = 0;
        if (freshness.maxAgeMillis() > 0 && !freshness.stale(value, now)) {
            long created = Long.parseLong(value.substring(0, value.indexOf('-')));
            freshUntil = created > Long.MAX_VALUE - freshness.maxAgeMillis()
                    ? Long.MAX_VALUE : created + freshness.maxAgeMillis();
        }
        version = new Version(value, now + versionCacheMillis, freshUntil);
        if (versionCacheMillis > 0) cached = version;
        return version;
    }
}
