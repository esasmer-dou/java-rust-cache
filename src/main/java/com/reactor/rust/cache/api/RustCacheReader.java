package com.reactor.rust.cache.api;

public interface RustCacheReader {

    byte[] getBytes(String key);

    byte[][] mgetBytes(String... keys);

    boolean exists(String key);

    long ttlMillis(String key);
}
