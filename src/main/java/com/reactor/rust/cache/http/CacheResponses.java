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
        String checkedMissCode = CacheResponsePolicy.requireText(missCode, "missCode");
        String checkedMessage = CacheResponsePolicy.requireText(unavailableMessage, "missMessage");
        String checkedNotReadyCode = CacheResponsePolicy.requireText(notReadyCode, "notReadyCode");
        Objects.requireNonNull(result, "result");
        if (result.hit()) {
            return ResponseEntity.ok(RawResponse.json(result.bytes()));
        }
        boolean notReady = result.isCacheNotReady() || result.isStale();
        return ResponseEntity.status(com.reactor.rust.http.HttpStatus.NOT_FOUND,
                JsonResponses.error(notReady ? checkedNotReadyCode : checkedMissCode, checkedMessage));
    }

    public static ResponseEntity<RawResponse> json(
            CacheReadResult result,
            CacheResponsePolicy policy) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(policy, "policy");
        if (result.hit()) {
            return ResponseEntity.ok(RawResponse.json(result.bytes()));
        }
        boolean notReady = result.isCacheNotReady() || result.isStale();
        return ResponseEntity.status(
                notReady ? policy.notReadyStatus() : policy.missStatus(),
                JsonResponses.error(
                        notReady ? policy.notReadyCode() : policy.missCode(),
                        notReady ? policy.notReadyMessage() : policy.missMessage()));
    }

    /** Opt-in common error contract. Existing json overloads retain their wire contract. */
    public static ResponseEntity<RawResponse> problem(CacheReadResult result) {
        if (result.hit()) {
            ResponseEntity<RawResponse> response = ResponseEntity.ok(RawResponse.json(result.bytes()));
            return result.isStale() ? response.header("X-Cache-Stale", "true") : response;
        }
        int status = result.isMiss() ? 404 : 503;
        String code = result.isMiss() ? "not_found" : result.isStale() ? "cache_stale" : "cache_not_ready";
        return com.reactor.rust.http.ErrorResponses.from(new com.reactor.rust.exception.HttpErrorMapper.MappedError(
                status, code, result.isMiss() ? "Resource not found" : "Cache snapshot is not ready or is too old"));
    }

}
