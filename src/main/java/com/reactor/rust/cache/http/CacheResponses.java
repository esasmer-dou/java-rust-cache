package com.reactor.rust.cache.http;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.cache.api.CacheReadSnapshot;
import com.reactor.rust.cache.api.CacheReadValue;
import com.reactor.rust.json.ManagedJsonBodyProducer;
import java.nio.ByteBuffer;
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
    private static final boolean PROBLEM_DETAILS = com.reactor.rust.config.PropertiesLoader.getBoolean(
            "reactor.cache.http.problem-details", false);

    private CacheResponses() {}

    /** Applies the startup-selected error policy without copying borrowed L1 bodies. */
    public static ResponseEntity<?> reply(CacheReadValue value, String missCode, String notReadyCode) {
        return reply(value, missCode, notReadyCode, PROBLEM_DETAILS);
    }

    public static ResponseEntity<?> reply(
            CacheReadValue value, String missCode, String notReadyCode, boolean problemDetails) {
        return problemDetails ? valueProblem(value) : valueJson(value, missCode, notReadyCode);
    }

    public static ResponseEntity<RawResponse> reply(
            CacheReadResult value, String missCode, String notReadyCode) {
        return reply(value, missCode, notReadyCode, PROBLEM_DETAILS);
    }

    public static ResponseEntity<RawResponse> reply(
            CacheReadResult value, String missCode, String notReadyCode, boolean problemDetails) {
        return problemDetails ? problem(value) : json(value, missCode, notReadyCode);
    }

    /** Remote results use the raw-body path; borrowed L1 results retain managed ownership. */
    public static ResponseEntity<?> valueJson(CacheReadValue value, String missCode, String notReadyCode) {
        Objects.requireNonNull(value, "value");
        if (value instanceof CacheReadResult result) return json(result, missCode, notReadyCode);
        return ownedJson((CacheReadSnapshot) value, missCode, notReadyCode);
    }

    public static ResponseEntity<?> valueProblem(CacheReadValue value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof CacheReadResult result) return problem(result);
        return ownedProblem((CacheReadSnapshot) value);
    }

    /** Transfers ownership to the response writer; no intermediate body array on L1 hits. */
    public static ResponseEntity<?> ownedJson(CacheReadSnapshot snapshot, String missCode, String notReadyCode) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            CacheResponsePolicy.requireText(missCode, "missCode");
            CacheResponsePolicy.requireText(notReadyCode, "notReadyCode");
            if (snapshot.hit()) return ResponseEntity.ok(new SnapshotProducer(snapshot));
            snapshot.close();
            return json(emptyResult(snapshot), missCode, notReadyCode);
        } catch (RuntimeException | Error failure) { snapshot.close(); throw failure; }
    }

    public static ResponseEntity<?> ownedProblem(CacheReadSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            if (snapshot.hit()) {
                ResponseEntity<?> response = ResponseEntity.ok(new SnapshotProducer(snapshot));
                return snapshot.status() == CacheReadResult.Status.STALE_HIT ? response.header("X-Cache-Stale", "true") : response;
            }
            snapshot.close();
            return problem(emptyResult(snapshot));
        } catch (RuntimeException | Error failure) { snapshot.close(); throw failure; }
    }

    private static CacheReadResult emptyResult(CacheReadSnapshot snapshot) {
        return switch (snapshot.status()) {
            case MISS -> CacheReadResult.miss();
            case STALE -> CacheReadResult.stale();
            case CACHE_NOT_READY -> CacheReadResult.cacheNotReady();
            default -> throw new IllegalArgumentException("Expected a non-hit snapshot");
        };
    }

    private record SnapshotProducer(CacheReadSnapshot snapshot) implements ManagedJsonBodyProducer {
        public int write(ByteBuffer out, int offset) {
            try {
                // Header-only overflow must retain even an empty body's ownership for retry.
                if (offset > out.capacity()) return -snapshot.length();
                int written = snapshot.writeTo(out, offset);
                if (written >= 0) snapshot.close();
                return written;
            } catch (RuntimeException | Error failure) { snapshot.close(); throw failure; }
        }
        public void close() { snapshot.close(); }
    }

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
