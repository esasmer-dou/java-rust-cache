package com.reactor.rust.cache.core;

public final class RedisCacheException extends RuntimeException {

    public RedisCacheException(String message) {
        super(message);
    }

    public RedisCacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
