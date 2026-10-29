package com.reactor.rust.cache.api;

/**
 * Caller-owned, reusable, thread-confined read-only result. Close before reuse. No underlying
 * ByteBuffer escapes: close detaches this view, and bytes() is always a defensive copy.
 */
public interface CacheReadLease extends AutoCloseable {
    CacheReadResult.Status status();
    String version();
    int length();
    byte get(int index);
    void copyTo(byte[] destination, int offset);
    byte[] bytes();
    @Override void close();
}
