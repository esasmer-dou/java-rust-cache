package com.reactor.rust.cache.api;

import java.util.concurrent.CompletableFuture;

public interface RustCacheReader {

    byte[] getBytes(String key);

    CompletableFuture<byte[]> getBytesAsync(String key);

    CompletableFuture<NativeCacheResponseHandle> getNativeJsonAsync(String key);

    byte[][] mgetBytes(String... keys);

    boolean exists(String key);

    long ttlMillis(String key);
}
