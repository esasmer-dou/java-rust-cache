package com.reactor.rust.cache.lock;

import com.reactor.rust.cache.config.RustCacheConfig;
import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.core.RustCaches;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "reactor.cache.redis.integration", matches = "true")
class SharedLeaseRenewalLiveTest {
    private RustCache cache() {
        return RustCaches.create(RustCacheConfig.builder().host("127.0.0.1")
                .port(Integer.getInteger("reactor.cache.redis.integration.port", 6379))
                .snapshotBatchEnabled(true).build());
    }

    @Test void shortSnapshotJobsShareOneRenewalTask() {
        try (var cache = cache()) {
            String namespace = "shared-renew-" + UUID.randomUUID();
            for (int i = 0; i < 100; i++) {
                assertTrue(cache.versionedJsonWriter(namespace).refreshSnapshotWithLock(
                        namespace, 30_000, 60_000, snapshot -> snapshot.putMeta("{}")).published());
            }
            assertEquals(1, cache.locks().renewalSchedulesForTest());
            assertNull(cache.getString("lock:" + namespace));
        }
    }

    @Test void sharedTickNeverRenewsANewLeaseBeforeItsOwnDueTime() {
        try (var cache = cache(); var lease = cache.locks().tryAcquire("due-" + UUID.randomUUID(), 30_000)) {
            assertNotNull(lease);
            long before = System.nanoTime();
            lease.scheduleRenewal(30_000);
            assertFalse(lease.renewIfDue(before + java.util.concurrent.TimeUnit.SECONDS.toNanos(9)),
                    "A tick must not consume the next tick's 10-second lead time");
            assertTrue(lease.renewIfDue(System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(11)));
            assertFalse(lease.renewIfDue(System.nanoTime()), "Renewal must move its deadline forward");
        }
    }

    @Test void longProtectedWorkStillRenewsAndReleasesItsLease() {
        try (var cache = cache(); var competitor = cache()) {
            String name = "long-renew-" + UUID.randomUUID();
            assertTrue(cache.locks().runOnceChecked(name, 600, lease -> {
                Thread.sleep(1_500);
                lease.ensureValid();
                try (var stolen = competitor.locks().tryAcquire(name, 600)) {
                    assertNull(stolen, "The protected job must still own the renewed lease");
                }
            }));
            assertNull(cache.getString("lock:" + name));
        }
    }

    @Test void shorterLeaseDoesNotWaitForAnEarlierLongLeaseInterval() {
        try (var cache = cache(); var competitor = cache()) {
            String prefix = "mixed-renew-" + UUID.randomUUID();
            assertTrue(cache.locks().runOnce(prefix + "-long", 30_000, () -> {}));
            assertTrue(cache.locks().runOnceChecked(prefix + "-short", 600, lease -> {
                Thread.sleep(1_500);
                lease.ensureValid();
                try (var stolen = competitor.locks().tryAcquire(prefix + "-short", 600)) {
                    assertNull(stolen);
                }
            }));
            assertEquals(2, cache.locks().renewalSchedulesForTest());
        }
    }
}
