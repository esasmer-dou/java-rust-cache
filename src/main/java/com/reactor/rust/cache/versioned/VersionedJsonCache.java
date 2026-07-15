package com.reactor.rust.cache.versioned;

import com.reactor.rust.cache.core.RustCache;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class VersionedJsonCache {

    public static final long DEFAULT_VERSION_CACHE_MILLIS = 1_000L;
    public static final int DEFAULT_BATCH_SIZE = 256;
    public static final int MAX_BATCH_SIZE = 4_096;

    private static final int MAX_KEY_PART_BYTES = 256;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final RustCache cache;
    private final String namespace;
    private final VersionedJsonCacheReader reader;
    private final VersionedJsonCacheWriter writer;

    private VersionedJsonCache(RustCache cache, String namespace, long versionCacheMillis, int batchSize) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.namespace = normalizeNamespace(namespace);
        this.reader = new VersionedJsonCacheReader(cache, this.namespace, versionCacheMillis);
        this.writer = new VersionedJsonCacheWriter(cache, this.namespace, batchSize);
    }

    public static VersionedJsonCache create(RustCache cache, String namespace) {
        return new VersionedJsonCache(cache, namespace, DEFAULT_VERSION_CACHE_MILLIS, DEFAULT_BATCH_SIZE);
    }

    public static VersionedJsonCache create(
            RustCache cache,
            String namespace,
            long versionCacheMillis,
            int batchSize) {
        return new VersionedJsonCache(cache, namespace, versionCacheMillis, batchSize);
    }

    public static VersionedJsonCacheReader createReader(RustCache cache, String namespace) {
        return createReader(cache, namespace, DEFAULT_VERSION_CACHE_MILLIS);
    }

    public static VersionedJsonCacheReader createReader(RustCache cache, String namespace, long versionCacheMillis) {
        return new VersionedJsonCacheReader(
                Objects.requireNonNull(cache, "cache"),
                normalizeNamespace(namespace),
                versionCacheMillis);
    }

    public static VersionedJsonCacheWriter createWriter(RustCache cache, String namespace) {
        return createWriter(cache, namespace, DEFAULT_BATCH_SIZE);
    }

    public static VersionedJsonCacheWriter createWriter(RustCache cache, String namespace, int batchSize) {
        return new VersionedJsonCacheWriter(
                Objects.requireNonNull(cache, "cache"),
                normalizeNamespace(namespace),
                batchSize);
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

    static String fenceKey(String namespace) {
        return namespace + ":fence-sequence";
    }

    static String encodeCurrentVersion(long fencingToken, String version) {
        return fencingToken + "|" + version;
    }

    static String decodeCurrentVersion(String encoded) {
        if (encoded == null) {
            return null;
        }
        int separator = encoded.indexOf('|');
        if (separator <= 0 || separator == encoded.length() - 1) {
            return encoded;
        }
        for (int i = 0; i < separator; i++) {
            if (!Character.isDigit(encoded.charAt(i))) {
                return encoded;
            }
        }
        return encoded.substring(separator + 1);
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
        String trimmed = value.trim();
        byte[] bytes = trimmed.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_KEY_PART_BYTES) {
            throw new IllegalArgumentException("cache key part is too long: " + bytes.length + " bytes");
        }

        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte raw : bytes) {
            int valueByte = raw & 0xFF;
            if (isUnreserved(valueByte)) {
                encoded.append((char) valueByte);
            } else {
                encoded.append('%');
                encoded.append(HEX[(valueByte >>> 4) & 0x0F]);
                encoded.append(HEX[valueByte & 0x0F]);
            }
        }
        return encoded.toString();
    }

    static long requireVersionCacheMillis(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("versionCacheMillis must not be negative");
        }
        return value;
    }

    static int requireBatchSize(int value) {
        if (value < 1 || value > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_BATCH_SIZE);
        }
        return value;
    }

    private static boolean isUnreserved(int value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '.'
                || value == '_'
                || value == '-';
    }
}
