package com.reactor.rust.cache.api;

import com.reactor.rust.cache.http.CacheResponsePolicy;
import com.reactor.rust.cache.http.CacheResponses;
import com.reactor.rust.cache.projection.ProjectionPropertySource;
import com.reactor.rust.http.HttpStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheJsonResponderTest {
    private static final CacheResponsePolicy POLICY = CacheResponsePolicy.production("missing");

    @Test
    void disabledResponderPreservesEveryExistingStatusBodyAndHeader() {
        var responder = CacheJsonResponder.create(POLICY, false);
        for (var result : results()) {
            var expected = CacheResponses.json(result, POLICY);
            var actual = responder.json(result);
            assertEquals(expected.getStatus(), actual.getStatus());
            assertEquals(expected.readOnlyHeaders(), actual.readOnlyHeaders());
            assertEquals(expected.getBody().getHeaders(), actual.getBody().getHeaders());
            assertArrayEquals(expected.getBody().getBody(), actual.getBody().getBody());
        }
    }

    @Test
    void enabledResponderMarksOnlyServedStaleHitsWithoutCopyingTheirBodies() {
        var responder = CacheJsonResponder.create(POLICY, true);
        for (var result : results()) {
            var expected = CacheResponses.json(result, POLICY);
            var actual = responder.json(result);
            assertEquals(expected.getStatus(), actual.getStatus());
            assertArrayEquals(expected.getBody().getBody(), actual.getBody().getBody());
            if (result.status() == CacheReadResult.Status.STALE_HIT) {
                assertEquals("true", actual.readOnlyHeaders().get("X-Cache-Stale"));
                assertEquals(1, actual.readOnlyHeaders().size());
            } else assertTrue(actual.readOnlyHeaders().isEmpty());
            if (result.hit()) assertSame(result.bytes(), actual.getBody().getBody());
        }
    }

    @Test
    void selectingMarkedResponderDoesNotChangeLegacyOverloads() {
        var stale = CacheReadResult.staleHit(new byte[]{42});
        assertEquals("true", CacheJsonResponder.create(POLICY, true).json(stale).readOnlyHeaders().get("X-Cache-Stale"));
        assertTrue(CacheResponses.json(stale, POLICY).readOnlyHeaders().isEmpty());
        assertTrue(CacheResponses.json(stale, "missing", "not_ready").readOnlyHeaders().isEmpty());
    }

    @Test
    void propertiesAreResolvedOnlyOnceAtExplicitStartupAndNotCapturedByResponder() {
        var properties = new MutableProperties("true");
        var responder = CacheJsonResponder.fromProperties(properties, POLICY);
        assertEquals(1, properties.reads);
        properties.value = "false";
        for (int i = 0; i < 100; i++) {
            assertEquals("true", responder.json(CacheReadResult.staleHit(new byte[]{42})).readOnlyHeaders().get("X-Cache-Stale"));
        }
        assertEquals(1, properties.reads);
        var disabled = CacheJsonResponder.fromProperties(properties, POLICY);
        assertTrue(disabled.json(CacheReadResult.staleHit(new byte[]{42})).readOnlyHeaders().isEmpty());
        assertEquals(2, properties.reads);
    }

    @Test
    void absentBlankAndFalseSettingsSelectTheExistingUnmarkedMethod() {
        for (String value : new String[]{null, "", " ", "false", " FALSE "}) {
            var responder = CacheJsonResponder.fromProperties(new MutableProperties(value), POLICY);
            assertTrue(responder.json(CacheReadResult.staleHit(new byte[]{42})).readOnlyHeaders().isEmpty());
        }
        var enabled = CacheJsonResponder.fromProperties(new MutableProperties(" TRUE "), POLICY);
        assertEquals("true", enabled.json(CacheReadResult.staleHit(new byte[]{42})).readOnlyHeaders().get("X-Cache-Stale"));
    }

    @Test
    void malformedConfigurationAndNullStartupArgumentsFailBeforeServing() {
        for (String value : new String[]{"yes", "1", "tru", "false,true"}) {
            assertThrows(IllegalArgumentException.class, () -> CacheJsonResponder.fromProperties(new MutableProperties(value), POLICY));
        }
        assertThrows(NullPointerException.class, () -> CacheJsonResponder.fromProperties(null, POLICY));
        assertThrows(NullPointerException.class, () -> CacheJsonResponder.fromProperties(new MutableProperties("true"), null));
        assertThrows(NullPointerException.class, () -> CacheJsonResponder.create(null, false));
        assertThrows(NullPointerException.class, () -> CacheJsonResponder.create(null, true));
    }

    @Test
    void selectedResponderPreservesCustomFailurePolicyAndNullResultValidation() {
        var policy = new CacheResponsePolicy("custom_miss", "missing item", HttpStatus.BAD_REQUEST,
                "custom_ready", "not ready", HttpStatus.INTERNAL_SERVER_ERROR);
        for (boolean enabled : new boolean[]{false, true}) {
            var responder = CacheJsonResponder.create(policy, enabled);
            for (var result : results()) {
                var expected = CacheResponses.json(result, policy);
                var actual = responder.json(result);
                assertEquals(expected.getStatus(), actual.getStatus());
                assertArrayEquals(expected.getBody().getBody(), actual.getBody().getBody());
            }
            assertThrows(NullPointerException.class, () -> responder.json(null));
        }
    }

    private static CacheReadResult[] results() {
        return new CacheReadResult[]{CacheReadResult.hit(new byte[]{42}), CacheReadResult.staleHit(new byte[]{42}),
                CacheReadResult.miss(), CacheReadResult.cacheNotReady(), CacheReadResult.stale()};
    }

    private static final class MutableProperties implements ProjectionPropertySource {
        private String value;
        private int reads;
        private MutableProperties(String value) { this.value = value; }
        @Override public String getOptional(String key) {
            assertEquals(CacheJsonResponder.MARK_STALE_HIT, key); reads++; return value;
        }
        @Override public String get(String key) { throw new AssertionError("Unexpected lookup"); }
        @Override public String getRuntimeOverride(String key) { throw new AssertionError("Unexpected lookup"); }
        @Override public String getFileOptional(String key) { throw new AssertionError("Unexpected lookup"); }
    }
}
