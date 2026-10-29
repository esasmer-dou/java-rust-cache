package com.reactor.rust.cache.projection;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.cache.api.CacheReadSnapshot;
import com.reactor.rust.cache.api.CacheReadValue;
import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.versioned.L1VersionedJsonCacheReader;
import com.reactor.rust.cache.versioned.VersionedJsonCacheReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Opt-in registry: selects Redis-only or bounded L1 once, during startup. */
public final class ConfiguredJsonProjectionReaders implements AutoCloseable {
    private interface Reader {
        CacheReadResult getById(long id);
        CacheReadResult getIndex(String name, String value);
        CacheReadResult getMeta();
        default CacheReadValue readById(long id) { return getById(id); }
        default CacheReadValue readIndex(String name, String value) { return getIndex(name, value); }
        default CacheReadValue readMeta() { return getMeta(); }
        default CacheReadSnapshot snapshotById(long id) { return CacheReadSnapshot.takeOwnership(getById(id)); }
        default CacheReadSnapshot snapshotIndex(String name, String value) { return CacheReadSnapshot.takeOwnership(getIndex(name, value)); }
        default CacheReadSnapshot snapshotMeta() { return CacheReadSnapshot.takeOwnership(getMeta()); }
        default void close() {}
        default long hits() { return 0; }
        default long misses() { return 0; }
        default long slabBytes() { return 0; }
        default long activeBorrows() { return 0; }
    }

    private record Remote(VersionedJsonCacheReader reader) implements Reader {
        public CacheReadResult getById(long id) { return reader.getById(id); }
        public CacheReadResult getIndex(String name, String value) { return reader.getIndex(name, value); }
        public CacheReadResult getMeta() { return reader.getMeta(); }
    }

    private record Local(L1VersionedJsonCacheReader reader) implements Reader {
        public CacheReadValue readById(long id) { return reader.snapshotById(id); }
        public CacheReadValue readIndex(String name, String value) { return reader.snapshotIndex(name, value); }
        public CacheReadValue readMeta() { return reader.snapshotMeta(); }
        public CacheReadResult getById(long id) { return reader.getById(id); }
        public CacheReadResult getIndex(String name, String value) { return reader.getIndex(name, value); }
        public CacheReadResult getMeta() { return reader.getMeta(); }
        public void close() { reader.close(); }
        public long hits() { return reader.l1Hits(); }
        public long misses() { return reader.l1Misses(); }
        public long slabBytes() { return reader.options().slabBytes(); }
        public long activeBorrows() { return reader.l1ActiveBorrows(); }
        public CacheReadSnapshot snapshotById(long id) {
            return reader.snapshotById(id);
        }
        public CacheReadSnapshot snapshotIndex(String name, String value) {
            return reader.snapshotIndex(name, value);
        }
        public CacheReadSnapshot snapshotMeta() {
            return reader.snapshotMeta();
        }
    }

    private static final Reader MISSING = new Reader() {
        public CacheReadResult getById(long id) { return CacheReadResult.cacheNotReady(); }
        public CacheReadResult getIndex(String name, String value) { return CacheReadResult.cacheNotReady(); }
        public CacheReadResult getMeta() { return CacheReadResult.cacheNotReady(); }
    };

    private final Map<String, Reader> readers;

    private ConfiguredJsonProjectionReaders(Map<String, Reader> readers) {
        this.readers = Map.copyOf(readers);
    }

    public static ConfiguredJsonProjectionReaders create(RustCache cache, List<L1ProjectionSettings> settings,
                                                        long versionCacheMillis) {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(settings, "settings");
        if (versionCacheMillis < 0) throw new IllegalArgumentException("versionCacheMillis must not be negative");
        Map<String, Reader> readers = new LinkedHashMap<>();
        try {
            for (L1ProjectionSettings setting : settings) {
                var base = setting.base();
                if (readers.containsKey(base.name())) throw new IllegalArgumentException("Duplicate projection name: " + base.name());
                Reader reader = setting.options() == null
                        ? new Remote(VersionedJsonCacheReader.create(cache, base.namespace(), versionCacheMillis, base.freshness()))
                        : new Local(VersionedJsonProjectionReaders.createL1Reader(cache, setting, versionCacheMillis));
                readers.put(base.name(), reader);
            }
            return new ConfiguredJsonProjectionReaders(readers);
        } catch (RuntimeException | Error failure) {
            for (Reader reader : readers.values()) reader.close();
            throw failure;
        }
    }

    public BoundProjection bind(Supplier<String> projection) {
        String name = requireText(projection);
        return new BoundProjection(readers.getOrDefault(name, MISSING));
    }

    /** Metrics are assembled only when the metrics endpoint is called, not on data reads. */
    public String metricsJson(RustCache cache) {
        String nativeMetrics = cache.metricsJson();
        long hits = 0, misses = 0, slabBytes = 0, activeBorrows = 0;
        int enabled = 0;
        for (Reader reader : readers.values()) {
            hits += reader.hits(); misses += reader.misses(); slabBytes += reader.slabBytes();
            activeBorrows += reader.activeBorrows();
            if (reader instanceof Local) enabled++;
        }
        if (!nativeMetrics.endsWith("}")) throw new IllegalStateException("Invalid native cache metrics");
        return nativeMetrics.substring(0, nativeMetrics.length() - 1)
                + ",\"l1\":{\"enabled_projections\":" + enabled + ",\"hits\":" + hits
                + ",\"misses\":" + misses + ",\"slab_bytes\":" + slabBytes
                + ",\"active_borrows\":" + activeBorrows + "}}";
    }

    /** Releases L1 ownership only; the application's Redis client has its own lifecycle. */
    @Override public void close() {
        for (Reader reader : readers.values()) reader.close();
    }

    public static final class BoundProjection {
        private final Reader reader;
        private BoundProjection(Reader reader) { this.reader = reader; }
        public CacheReadResult getById(long id) { return reader.getById(id); }
        public CacheReadResult getMeta() { return reader.getMeta(); }
        public CacheReadValue readById(long id) { return reader.readById(id); }
        public CacheReadValue readMeta() { return reader.readMeta(); }
        public CacheReadSnapshot snapshotById(long id) { return reader.snapshotById(id); }
        public CacheReadSnapshot snapshotMeta() { return reader.snapshotMeta(); }
        public BoundIndex bind(Supplier<String> index) { return new BoundIndex(reader, requireText(index)); }
    }

    public static final class BoundIndex {
        private final Reader reader;
        private final String name;
        private BoundIndex(Reader reader, String name) { this.reader = reader; this.name = name; }
        public CacheReadResult get(String value) { return reader.getIndex(name, value); }
        public CacheReadValue read(String value) { return reader.readIndex(name, value); }
        public CacheReadSnapshot snapshot(String value) { return reader.snapshotIndex(name, value); }
    }

    private static String requireText(Supplier<String> value) {
        String text = Objects.requireNonNull(value, "name").get();
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Projection/index name must not be blank");
        return text;
    }
}
