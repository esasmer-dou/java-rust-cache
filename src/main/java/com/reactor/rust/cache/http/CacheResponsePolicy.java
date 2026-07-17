package com.reactor.rust.cache.http;

import com.reactor.rust.http.HttpStatus;

/** Explicit HTTP semantics for cache miss and snapshot-not-ready outcomes. */
public record CacheResponsePolicy(
        String missCode,
        String missMessage,
        HttpStatus missStatus,
        String notReadyCode,
        String notReadyMessage,
        HttpStatus notReadyStatus) {

    public CacheResponsePolicy {
        missCode = requireText(missCode, "missCode");
        missMessage = requireText(missMessage, "missMessage");
        missStatus = requireStatus(missStatus, "missStatus");
        notReadyCode = requireText(notReadyCode, "notReadyCode");
        notReadyMessage = requireText(notReadyMessage, "notReadyMessage");
        notReadyStatus = requireStatus(notReadyStatus, "notReadyStatus");
    }

    /** Recommended API behavior: a real miss is 404, while an unpublished snapshot is 503. */
    public static CacheResponsePolicy production(String missCode) {
        return new CacheResponsePolicy(
                missCode,
                "Cache entry was not found",
                HttpStatus.NOT_FOUND,
                "cache_not_ready",
                "Redis snapshot is not available yet",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static HttpStatus requireStatus(HttpStatus status, String name) {
        if (status == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return status;
    }
}
