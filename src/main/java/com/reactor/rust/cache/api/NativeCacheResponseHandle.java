package com.reactor.rust.cache.api;

import com.reactor.rust.cache.internal.nativebridge.NativeRedisBridge;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns a one-shot JSON response body that remains in native memory.
 * Pass {@link #nativeId()} to rust-java-rest RawResponse.nativeJson(int).
 */
public final class NativeCacheResponseHandle implements AutoCloseable {

    private final int nativeId;
    private final AtomicBoolean released = new AtomicBoolean();

    public NativeCacheResponseHandle(int nativeId) {
        if (nativeId <= 0) {
            throw new IllegalArgumentException("nativeId must be positive");
        }
        this.nativeId = nativeId;
    }

    public int nativeId() {
        return nativeId;
    }

    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            NativeRedisBridge.releaseResponseHandle(nativeId);
        }
    }
}
