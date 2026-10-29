package com.reactor.rust.cache.http;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.cache.api.CacheReadSnapshot;
import com.reactor.rust.http.HttpStatus;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.json.ManagedJsonBodyProducer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CacheResponsesTest {

    @Test
    void backendSelectedRemoteHitUsesTheOriginalRawBodyWithoutSnapshotOwnership() {
        byte[] body = "{\"name\":\"\u0130pek\"}".getBytes(StandardCharsets.UTF_8);
        var value = CacheReadResult.hit(body);
        assertSame(body, assertInstanceOf(RawResponse.class,
                CacheResponses.valueJson(value, "missing", "not_ready").getBody()).getBody());
        assertSame(body, assertInstanceOf(RawResponse.class,
                CacheResponses.valueProblem(value).getBody()).getBody());
    }

    @Test
    void backendSelectedResultsPreserveEveryLegacyStatusBodyAndHeader() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        for (var value : new CacheReadResult[]{CacheReadResult.hit(body), CacheReadResult.staleHit(body),
                CacheReadResult.miss(), CacheReadResult.stale(), CacheReadResult.cacheNotReady()}) {
            var expected = CacheResponses.json(value, "missing", "not_ready");
            var actual = CacheResponses.valueJson(value, "missing", "not_ready");
            assertEquals(expected.getStatus(), actual.getStatus());
            assertArrayEquals(expected.getBody().getBody(), ((RawResponse) actual.getBody()).getBody());
            expected = CacheResponses.problem(value);
            actual = CacheResponses.valueProblem(value);
            assertEquals(expected.getStatus(), actual.getStatus());
            assertEquals(expected.getHeaders(), actual.getHeaders());
            assertArrayEquals(expected.getBody().getBody(), ((RawResponse) actual.getBody()).getBody());
        }
    }

    @Test
    void backendSelectedSnapshotKeepsOwnershipOnOverflowAndClosesAfterSuccessfulWrite() {
        var snapshot = CacheReadSnapshot.takeOwnership(CacheReadResult.hit(new byte[]{42, 7}));
        var producer = assertInstanceOf(ManagedJsonBodyProducer.class,
                CacheResponses.valueJson(snapshot, "missing", "not_ready").getBody());
        assertEquals(-2, producer.write(ByteBuffer.allocate(1), 0));
        var out = ByteBuffer.allocate(4);
        assertEquals(2, producer.write(out, 2));
        assertEquals(42, out.get(2));
        producer.close();
        assertThrows(IllegalStateException.class, () -> snapshot.writeTo(out, 0));
    }

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
