package com.reactor.rust.cache;

import java.util.Objects;

public final class VersionedJsonCache {

    private final RustCache cache;
    private final String namespace;
    private final VersionedJsonCacheReader reader;
    private final VersionedJsonCacheWriter writer;

    private VersionedJsonCache(RustCache cache, String namespace) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.namespace = normalizeNamespace(namespace);
        this.reader = new VersionedJsonCacheReader(cache, this.namespace, 1_000L);
        this.writer = new VersionedJsonCacheWriter(cache, this.namespace, 256);
    }

    static VersionedJsonCache create(RustCache cache, String namespace) {
        return new VersionedJsonCache(cache, namespace);
    }

    public String namespace() {
        return namespace;
    }

    public VersionedJsonCacheReader reader() {
        return reader;
    }

    public VersionedJsonCacheWriter writer() {
        return writer;
    }

    static String currentKey(String namespace) {
        return namespace + ":current";
    }

    static String idKey(String namespace, String version, long id) {
        return namespace + ":v:" + version + ":id:" + id;
    }

    static String indexKey(String namespace, String version, String indexName, String indexValue) {
        return namespace + ":v:" + version + ":idx:" + sanitize(indexName) + ":" + sanitize(indexValue);
    }

    static String metaKey(String namespace, String version) {
        return namespace + ":v:" + version + ":meta";
    }

    static String normalizeNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        return sanitize(namespace.trim());
    }

    static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("cache key part must not be blank");
        }
        return value.trim().replace(' ', '_');
    }
}
