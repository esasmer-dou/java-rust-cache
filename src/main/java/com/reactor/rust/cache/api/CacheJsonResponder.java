package com.reactor.rust.cache.api;

import com.reactor.rust.cache.http.CacheResponsePolicy;
import com.reactor.rust.cache.http.CacheResponses;
import com.reactor.rust.cache.projection.ProjectionPropertySource;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;
import java.util.Objects;

/** Optional REST responder selected explicitly at startup; legacy CacheResponses methods are unchanged. */
@FunctionalInterface
public interface CacheJsonResponder {
    String MARK_STALE_HIT = "reactor.cache.responses.mark-stale-hit";

    ResponseEntity<RawResponse> json(CacheReadResult result);

    /** Captures the immutable policy and selects the implementation once, not on each response. */
    static CacheJsonResponder create(CacheResponsePolicy policy, boolean markStaleHits) {
        Objects.requireNonNull(policy, "policy");
        return markStaleHits ? result -> markedJson(result, policy) : result -> CacheResponses.json(result, policy);
    }

    /** Reads only the explicit source, once. Absent/blank is false; otherwise true or false is required. */
    static CacheJsonResponder fromProperties(ProjectionPropertySource properties, CacheResponsePolicy policy) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(policy, "policy");
        String value = properties.getOptional(MARK_STALE_HIT);
        if (value == null || value.isBlank()) return create(policy, false);
        value = value.trim();
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(MARK_STALE_HIT + " must be true or false");
        }
        return create(policy, Boolean.parseBoolean(value));
    }

    private static ResponseEntity<RawResponse> markedJson(CacheReadResult result, CacheResponsePolicy policy) {
        ResponseEntity<RawResponse> response = CacheResponses.json(result, policy);
        return result.status() == CacheReadResult.Status.STALE_HIT ? response.header("X-Cache-Stale", "true") : response;
    }
}
