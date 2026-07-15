package com.reactor.rust.cache.projection;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.versioned.VersionedJsonCacheReader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Named reader registry for independently versioned JSON projections.
 */
public final class VersionedJsonProjectionReaders {

    private final Map<String, VersionedJsonCacheReader> readers;

    private VersionedJsonProjectionReaders(Map<String, VersionedJsonCacheReader> readers) {
        this.readers = Map.copyOf(readers);
    }

    public static VersionedJsonProjectionReaders create(
            RustCache cache,
            List<CacheReaderProjectionSettings> settings,
            long versionCacheMillis) {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(settings, "settings");
        if (versionCacheMillis < 0) {
            throw new IllegalArgumentException("versionCacheMillis must not be negative");
        }
        Map<String, VersionedJsonCacheReader> readers = new LinkedHashMap<>();
        for (CacheReaderProjectionSettings setting : settings) {
            if (readers.putIfAbsent(
                    setting.name(),
                    cache.versionedJsonReader(setting.namespace(), versionCacheMillis)) != null) {
                throw new IllegalArgumentException("Duplicate projection name: " + setting.name());
            }
        }
        return new VersionedJsonProjectionReaders(readers);
    }

    public CacheReadResult getById(String projection, long id) {
        VersionedJsonCacheReader reader = readers.get(projection);
        return reader == null ? CacheReadResult.cacheNotReady() : reader.getById(id);
    }

    public CacheReadResult getIndex(String projection, String indexName, String value) {
        VersionedJsonCacheReader reader = readers.get(projection);
        return reader == null ? CacheReadResult.cacheNotReady() : reader.getIndex(indexName, value);
    }

    public CacheReadResult getMeta(String projection) {
        VersionedJsonCacheReader reader = readers.get(projection);
        return reader == null ? CacheReadResult.cacheNotReady() : reader.getMeta();
    }

    public Set<String> projectionNames() {
        return readers.keySet();
    }
}
