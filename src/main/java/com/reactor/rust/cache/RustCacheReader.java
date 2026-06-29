package com.reactor.rust.cache;

public interface RustCacheReader {

    byte[] getBytes(String key);

    byte[][] mgetBytes(String... keys);

    boolean exists(String key);

    long ttlMillis(String key);
}
