package com.reactor.rust.cache.http;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

}
