package com.reactor.rust.cache.projection;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.versioned.VersionedJsonCacheReader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

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
                    VersionedJsonCacheReader.create(cache, setting.namespace(), versionCacheMillis, setting.freshness())) != null) {
                throw new IllegalArgumentException("Duplicate projection name: " + setting.name());
            }
        }
        return new VersionedJsonProjectionReaders(readers);
    }

    /** Explicit startup-only opt-in for one selected projection; create() and existing bound readers stay unchanged. */
    public static com.reactor.rust.cache.versioned.L1VersionedJsonCacheReader createL1Reader(
            RustCache cache, L1ProjectionSettings setting, long versionCacheMillis) {
        Objects.requireNonNull(setting, "setting");
        if (setting.options() == null) throw new IllegalArgumentException("Projection L1 is not explicitly configured");
        return com.reactor.rust.cache.versioned.L1VersionedJsonCacheReader.create(cache, setting.base().namespace(),
                versionCacheMillis, setting.base().freshness(), setting.options());
    }

    public CacheReadResult getById(String projection, long id) {
        VersionedJsonCacheReader reader = readers.get(projection);
        return reader == null ? CacheReadResult.cacheNotReady() : reader.getById(id);
    }

    public CacheReadResult getById(ProjectionId projection, long id) {
        return getById(requireValue(projection, "projection"), id);
    }

    /**
     * Resolves a projection once during application startup.
     *
     * <p>The returned reader avoids the registry lookup and identifier dispatch on
     * every cache request. A missing optional projection returns
     * {@code cacheNotReady} instead of failing application startup.</p>
     */
    public BoundProjection bind(ProjectionId projection) {
        String projectionName = requireValue(projection, "projection");
        return new BoundProjection(projectionName, readers.get(projectionName));
    }

    public BoundProjection bind(Supplier<String> projection) {
        String projectionName = requireValue(projection, "projection");
        return new BoundProjection(projectionName, readers.get(projectionName));
    }

    public CacheReadResult getIndex(String projection, String indexName, String value) {
        VersionedJsonCacheReader reader = readers.get(projection);
        return reader == null ? CacheReadResult.cacheNotReady() : reader.getIndex(indexName, value);
    }

    public CacheReadResult getIndex(ProjectionId projection, ProjectionIndex index, String value) {
        return getIndex(
                requireValue(projection, "projection"),
                requireValue(index, "index"),
                value
        );
    }

    public CacheReadResult getMeta(String projection) {
        VersionedJsonCacheReader reader = readers.get(projection);
        return reader == null ? CacheReadResult.cacheNotReady() : reader.getMeta();
    }

    public CacheReadResult getMeta(ProjectionId projection) {
        return getMeta(requireValue(projection, "projection"));
    }

    public Set<String> projectionNames() {
        return readers.keySet();
    }

    public static final class BoundProjection {

        private final String name;
        private final VersionedJsonCacheReader reader;

        private BoundProjection(String name, VersionedJsonCacheReader reader) {
            this.name = name;
            this.reader = reader;
        }

        public String name() {
            return name;
        }

        public CacheReadResult getById(long id) {
            return reader == null ? CacheReadResult.cacheNotReady() : reader.getById(id);
        }

        public CacheReadResult getMeta() {
            return reader == null ? CacheReadResult.cacheNotReady() : reader.getMeta();
        }

        public BoundIndex bind(ProjectionIndex index) {
            return new BoundIndex(reader, requireValue(index, "index"));
        }

        public BoundIndex bind(Supplier<String> index) {
            return new BoundIndex(reader, requireValue(index, "index"));
        }
    }

    public static final class BoundIndex {

        private final VersionedJsonCacheReader reader;
        private final String name;

        private BoundIndex(VersionedJsonCacheReader reader, String name) {
            this.reader = reader;
            this.name = name;
        }

        public String name() {
            return name;
        }

        public CacheReadResult get(String value) {
            return reader == null ? CacheReadResult.cacheNotReady() : reader.getIndex(name, value);
        }
    }

    private static String requireValue(ProjectionId projection, String name) {
        Objects.requireNonNull(projection, name);
        return requireText(projection.value(), name);
    }

    private static String requireValue(ProjectionIndex index, String name) {
        Objects.requireNonNull(index, name);
        return requireText(index.value(), name);
    }

    private static String requireValue(Supplier<String> value, String name) {
        Objects.requireNonNull(value, name);
        return requireText(value.get(), name);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " value must not be blank");
        }
        return value;
    }
}
