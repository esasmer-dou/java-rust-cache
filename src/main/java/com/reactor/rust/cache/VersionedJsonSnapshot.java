package com.reactor.rust.cache;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class VersionedJsonSnapshot {

    private final RustCache cache;
    private final String namespace;
    private final String version;
    private final long dataTtlMillis;
    private final int batchSize;
    private final List<String> keys;
    private final List<byte[]> values;
    private int written;
    private boolean published;

    VersionedJsonSnapshot(
            RustCache cache,
            String namespace,
            String version,
            long dataTtlMillis,
            int batchSize) {
        this.cache = cache;
        this.namespace = namespace;
        this.version = version;
        this.dataTtlMillis = dataTtlMillis;
        this.batchSize = batchSize;
        this.keys = new ArrayList<>(batchSize);
        this.values = new ArrayList<>(batchSize);
    }

    public String version() {
        return version;
    }

    public void putById(long id, byte[] json) {
        putInternal(VersionedJsonCache.idKey(namespace, version, id), json);
    }

    public void putIndex(String indexName, String indexValue, byte[] json) {
        putInternal(VersionedJsonCache.indexKey(namespace, version, indexName, indexValue), json);
    }

    public void putMeta(byte[] json) {
        putInternal(VersionedJsonCache.metaKey(namespace, version), json);
    }

    public void putMeta(String json) {
        Objects.requireNonNull(json, "json");
        putMeta(json.getBytes(StandardCharsets.UTF_8));
    }

    int publish() {
        if (published) {
            throw new RedisCacheException("snapshot already published");
        }
        flush();
        cache.setString(VersionedJsonCache.currentKey(namespace), version, dataTtlMillis);
        published = true;
        return written + 1;
    }

    private void putInternal(String key, byte[] json) {
        Objects.requireNonNull(json, "json");
        if (published) {
            throw new RedisCacheException("snapshot already published");
        }
        keys.add(key);
        values.add(json);
        if (keys.size() >= batchSize) {
            flush();
        }
    }

    private void flush() {
        if (keys.isEmpty()) {
            return;
        }
        String[] keyArray = keys.toArray(String[]::new);
        byte[][] valueArray = values.toArray(byte[][]::new);
        written += cache.setManyBytes(keyArray, valueArray, dataTtlMillis);
        keys.clear();
        values.clear();
    }
}
