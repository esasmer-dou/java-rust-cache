package com.reactor.rust.cache;

import java.util.Properties;

public final class RustCaches {

    private RustCaches() {}

    public static RustCache create() {
        return RustCache.create(RustCacheConfig.fromProperties());
    }

    public static RustCache create(Properties properties) {
        return RustCache.create(RustCacheConfig.fromProperties(properties));
    }

    public static RustCache create(RustCacheConfig config) {
        return RustCache.create(config);
    }
}
