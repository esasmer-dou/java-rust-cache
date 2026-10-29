package com.reactor.rust.cache.versioned;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.cache.core.RustCache;
import java.util.Optional;

/** Test seam for the opt-in reader only. dataKeyPrefix already contains the observed snapshot version. */
interface VersionedRedisSource {
    Optional<String> getString(String key);
    CacheReadResult getVersionedJson(String versionKey, String dataKeyPrefix, String id);

    static VersionedRedisSource nativeSource(RustCache cache) {
        return new VersionedRedisSource() {
            @Override public Optional<String> getString(String key) { return Optional.ofNullable(cache.getString(key)); }
            @Override public CacheReadResult getVersionedJson(String versionKey, String dataKeyPrefix, String id) {
                byte[] value = cache.getBytes(dataKeyPrefix + id);
                return value == null ? CacheReadResult.miss() : CacheReadResult.hit(value);
            }
        };
    }
}
