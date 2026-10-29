package com.reactor.rust.cache.core;

import com.reactor.rust.cache.config.RustCacheConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "reactor.cache.redis.integration", matches = "true")
class SnapshotBatchLiveTest {
    private RustCache cache() {
        return RustCaches.create(RustCacheConfig.builder().host("127.0.0.1")
                .port(Integer.getInteger("reactor.cache.redis.integration.port", 6379)).snapshotBatchEnabled(true).build());
    }

    @Test void featureIsOffUnlessExplicitlySelected() {
        assertFalse(RustCacheConfig.builder().build().snapshotBatchEnabled());
        var values = new java.util.Properties();
        values.setProperty("reactor.cache.redis.snapshot-batch-enabled", "true");
        assertTrue(RustCacheConfig.fromProperties(values).snapshotBatchEnabled());
        values.setProperty("reactor.cache.redis.snapshot-batch-enabled", "perhaps");
        assertThrows(IllegalArgumentException.class, () -> RustCacheConfig.fromProperties(values));
    }

    @Test void publishesInTwoCallsAndPreservesWireKeysAndTtl() {
        try (RustCache cache = cache()) {
            assertTrue(cache.supportsSnapshotBatch());
            String ns = "snap-" + UUID.randomUUID();
            cache.resetMetrics();
            var result = cache.versionedJsonWriter(ns).refreshSnapshotWithLock(ns, 30000, 60000,
                    snapshot -> snapshot.putMeta("{\"name\":\"Çağrı\"}"));
            assertTrue(result.published());
            assertEquals(2, result.writtenKeys());
            assertTrue(cache.metricsJson().contains("\"write_calls\":2"), cache.metricsJson());
            assertEquals("1|" + result.version(), cache.getString(ns + ":current"));
            assertEquals("{\"name\":\"Çağrı\"}", cache.getString(ns + ":v:" + result.version() + ":meta"));
            assertTrue(cache.ttlMillis(ns + ":current") > 0);
            assertNull(cache.getString("lock:" + ns));
        }
    }

    @Test void refusesLostLeaseAndDoesNotDeleteAnotherOwner() {
        try (RustCache cache = cache()) {
            String ns = "snap-" + UUID.randomUUID();
            assertThrows(RedisCacheException.class, () -> cache.versionedJsonWriter(ns)
                    .refreshSnapshotWithLock(ns, 30000, 60000, snapshot -> {
                        snapshot.putMeta("{}");
                        cache.setString("lock:" + ns, "new-owner", 30000);
                    }));
            assertNull(cache.getString(ns + ":current"));
            assertEquals("new-owner", cache.getString("lock:" + ns));
        }
    }

    @Test void checksFencesAboveDoublePrecisionAndRetainsOldPointerOnFailure() {
        try (RustCache cache = cache()) {
            String ns = "snap-" + UUID.randomUUID();
            cache.setString(ns + ":fence-sequence", "9007199254740992", 0);
            cache.setString(ns + ":current", "9007199254740994|newer", 60000);
            assertThrows(RedisCacheException.class, () -> cache.versionedJsonWriter(ns)
                    .refreshSnapshotWithLock(ns, 30000, 60000, snapshot -> snapshot.putMeta("{}")));
            assertEquals("9007199254740994|newer", cache.getString(ns + ":current"));
            assertNull(cache.getString("lock:" + ns));
        }
    }

    @Test void boundedFullBatchesAndCallbackFailureReleaseTheLease() {
        try (RustCache cache = cache()) {
            String ns = "snap-" + UUID.randomUUID();
            var writer = cache.versionedJsonWriter(ns, 2);
            var result = writer.refreshSnapshotWithLock(ns, 30000, 60000, snapshot -> {
                for (int i = 0; i < 5; i++) snapshot.putById(i, "{}".getBytes(StandardCharsets.UTF_8));
            });
            assertEquals(6, result.writtenKeys());
            assertThrows(RedisCacheException.class, () -> writer.refreshSnapshotWithLock(ns, 30000, 60000,
                    snapshot -> { throw new IllegalStateException("DB failed"); }));
            assertEquals("1|" + result.version(), cache.getString(ns + ":current"));
            assertNull(cache.getString("lock:" + ns));
            try (var held = cache.locks().tryAcquire(ns, 30000)) {
                assertNotNull(held);
                assertTrue(writer.refreshSnapshotWithLock(ns, 30000, 60000,
                        snapshot -> fail("must not run while another replica owns lease")).skippedLocked());
            }
        }
    }
}
