package com.reactor.rust.cache.http;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CacheResponsesTest {

    @Test
    void productionPolicySeparatesMissFromSnapshotReadiness() {
        CacheResponsePolicy policy = CacheResponsePolicy.production("customer_not_found");

        assertEquals(HttpStatus.NOT_FOUND, CacheResponses.json(CacheReadResult.miss(), policy).getStatus());
        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                CacheResponses.json(CacheReadResult.cacheNotReady(), policy).getStatus());
    }

    @Test
    void hitBodyIsForwardedWithoutCopying() {
        byte[] body = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);

        var response = CacheResponses.json(
                CacheReadResult.hit(body),
                CacheResponsePolicy.production("customer_not_found"));

        assertEquals(HttpStatus.OK, response.getStatus());
        assertArrayEquals(body, response.getBody().getBody());
    }

    @Test
    void legacyOverloadPreservesValidationAndWireContract() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        var hit = CacheReadResult.hit(body);
        assertThrows(IllegalArgumentException.class, () -> CacheResponses.json(hit, " ", "not_ready"));
        assertThrows(IllegalArgumentException.class, () -> CacheResponses.json(hit, "missing", "not_ready", " "));
        assertThrows(IllegalArgumentException.class, () -> CacheResponses.json(hit, "missing", " "));
        assertEquals(HttpStatus.OK, CacheResponses.json(hit, "missing", "not_ready").getStatus());
        CacheResponsePolicy oldPolicy = new CacheResponsePolicy("missing", "unavailable", HttpStatus.NOT_FOUND,
                "not_ready", "unavailable", HttpStatus.NOT_FOUND);
        for (var result : new CacheReadResult[]{CacheReadResult.miss(), CacheReadResult.cacheNotReady(),
                CacheReadResult.stale(), CacheReadResult.staleHit(body), hit}) {
            var expected = CacheResponses.json(result, oldPolicy);
            var actual = CacheResponses.json(result, " missing ", " not_ready ", " unavailable ");
            assertEquals(expected.getStatus(), actual.getStatus());
            assertArrayEquals(expected.getBody().getBody(), actual.getBody().getBody());
            assertEquals(expected.getBody().getHeaders(), actual.getBody().getHeaders());
        }
    }

}
