package com.reactor.rust.cache.http;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.http.JsonResponses;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;

import java.util.Objects;

/**
 * Optional REST adapter for the common cache hit, miss and not-ready response flow.
 *
 * <p>The {@code rust-java-rest} dependency is optional. Cache-only processes do not
 * load this class or pull the REST framework transitively.</p>
 */
public final class CacheResponses {

    private static final String DEFAULT_MISS_MESSAGE = "Redis snapshot is not available yet";

    private CacheResponses() {}

    public static ResponseEntity<RawResponse> json(
            CacheReadResult result,
            String missCode,
            String notReadyCode) {
        return json(result, missCode, notReadyCode, DEFAULT_MISS_MESSAGE);
    }

    public static ResponseEntity<RawResponse> json(
            CacheReadResult result,
            String missCode,
            String notReadyCode,
            String unavailableMessage) {
        return json(result, new CacheResponsePolicy(
                missCode,
                unavailableMessage,
                com.reactor.rust.http.HttpStatus.NOT_FOUND,
                notReadyCode,
                unavailableMessage,
                com.reactor.rust.http.HttpStatus.NOT_FOUND));
    }

    public static ResponseEntity<RawResponse> json(
            CacheReadResult result,
            CacheResponsePolicy policy) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(policy, "policy");
        if (result.hit()) {
            return ResponseEntity.ok(RawResponse.json(result.bytes()));
        }
        boolean notReady = result.isCacheNotReady();
        return ResponseEntity.status(
                notReady ? policy.notReadyStatus() : policy.missStatus(),
                JsonResponses.error(
                        notReady ? policy.notReadyCode() : policy.missCode(),
                        notReady ? policy.notReadyMessage() : policy.missMessage()));
    }

}
