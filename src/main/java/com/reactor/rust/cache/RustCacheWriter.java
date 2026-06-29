package com.reactor.rust.cache;

public interface RustCacheWriter {

    boolean setBytes(String key, byte[] value);

    boolean setBytes(String key, byte[] value, long ttlMillis);

    boolean setBytesNx(String key, byte[] value, long ttlMillis);

    int setManyBytes(String[] keys, byte[][] values, long ttlMillis);

    long delete(String key);

    long increment(String key);

    long increment(String key, long ttlMillis);

    boolean expire(String key, long ttlMillis);
}
