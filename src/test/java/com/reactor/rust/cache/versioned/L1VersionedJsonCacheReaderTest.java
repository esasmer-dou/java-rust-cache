package com.reactor.rust.cache.versioned;

import com.reactor.rust.cache.api.CacheReadResult;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import static com.reactor.rust.cache.versioned.VersionedL1CacheTest.bytes;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class L1VersionedJsonCacheReaderTest {
    private static volatile Object allocationControl;
    private static volatile int checksum;

    @Test
    void tenThousandReusableReadsFetchDataOnceAndLegacyBytesAreDefensive() {
        FakeSource source = new FakeSource(); var clock = new MutableClock(1000);
        try (var reader = reader(source, clock, 1000, CacheFreshness.DISABLED, 64, 0); var lease = reader.newLease()) {
            for (int i = 0; i < 10000; i++) {
                reader.getById(Long.MIN_VALUE, lease);
                assertEquals(CacheReadResult.Status.HIT, lease.status()); assertEquals(42, lease.get(0));
                assertEquals("1000-1", lease.version()); lease.close();
            }
            assertEquals(1, source.fetches.get()); assertEquals(1, source.polls.get());
            assertEquals(9999, reader.l1Hits());
            assertEquals("ns:v:1000-1:id:" + Long.MIN_VALUE, source.lastKey);
            byte[] result = reader.getById(Long.MIN_VALUE).bytes(); result[0] = 99;
            assertEquals(42, reader.getById(Long.MIN_VALUE).bytes()[0]);
            assertEquals(1, source.fetches.get());
        }
    }

    @Test
    void pointerChangeAndExplicitInvalidationNeverReuseOldEpochAndOldLeaseRemainsASnapshot() {
        FakeSource source = new FakeSource(); var clock = new MutableClock(1000);
        source.data = (prefix, id) -> CacheReadResult.hit(bytes(prefix.contains("2000-2") ? "new" : "old"));
        try (var reader = reader(source, clock, 0, CacheFreshness.DISABLED, 64, 0);
             var old = reader.newLease(); var next = reader.newLease()) {
            reader.getById(1, old); long epoch = reader.epoch();
            source.version = "2|2000-2"; clock.set(2000);
            reader.getById(1, next);
            assertArrayEquals(bytes("new"), next.bytes()); assertTrue(reader.epoch() > epoch);
            assertArrayEquals(bytes("old"), old.bytes());
            next.close(); old.close();
            reader.getById(1, next); next.close(); assertEquals(2, source.fetches.get());
            epoch = reader.epoch(); reader.invalidateVersionCache(); assertEquals(epoch + 1, reader.epoch());
            reader.getById(1, next); assertEquals(3, source.fetches.get());
        }
    }

    @Test
    void pointerPollingCadenceIsExplicitAndAbaVersionNamesNeverReviveOldPayloads() {
        FakeSource source = new FakeSource(); var clock = new MutableClock(1000);
        source.data = (prefix, id) -> CacheReadResult.hit(bytes("first"));
        try (var reader = reader(source, clock, 1000, CacheFreshness.DISABLED, 64, 0)) {
            assertArrayEquals(bytes("first"), reader.getById(1).bytes());
            long firstEpoch = reader.epoch();
            source.version = "2000-2"; source.data = (prefix, id) -> CacheReadResult.hit(bytes("second"));
            clock.set(1999); assertArrayEquals(bytes("first"), reader.getById(1).bytes());
            assertEquals(1, source.polls.get());
            clock.set(2000); assertArrayEquals(bytes("second"), reader.getById(1).bytes());
            assertEquals(firstEpoch + 1, reader.epoch());
            source.version = "1000-1"; source.data = (prefix, id) -> CacheReadResult.hit(bytes("third"));
            clock.set(3000); assertArrayEquals(bytes("third"), reader.getById(1).bytes());
            assertEquals(firstEpoch + 2, reader.epoch());
            assertEquals(3, source.fetches.get());
        }
    }

    @Test
    void freshnessUsesClockAfterPointerIoRatherThanMisclassifyingNewlyPublishedVersionAsFuture() {
        var clock = new MutableClock(1000);
        VersionedRedisSource source = new VersionedRedisSource() {
            @Override public Optional<String> getString(String key) { clock.set(2000); return Optional.of("1500-1"); }
            @Override public CacheReadResult getVersionedJson(String key, String prefix, String id) {
                return CacheReadResult.hit(new byte[]{42});
            }
        };
        try (var reader = new L1VersionedJsonCacheReader(source, "ns", 10000, new CacheFreshness(1000, false),
                VersionedL1CacheTest.options(64, 0), clock); var lease = reader.newLease()) {
            reader.getById(1, lease); assertEquals(CacheReadResult.Status.HIT, lease.status()); lease.close();
            clock.set(2500); reader.getById(1, lease); assertEquals(CacheReadResult.Status.HIT, lease.status()); lease.close();
            clock.set(2501); reader.getById(1, lease); assertEquals(CacheReadResult.Status.STALE, lease.status());
        }
    }

    @Test
    void concurrentWrapperMissesHitsAndEvictionsReturnOnlyTheRequestedFullIdentity() throws Exception {
        FakeSource source = new FakeSource(); source.data = (prefix, id) -> CacheReadResult.hit(bytes(id));
        try (var reader = reader(source, new MutableClock(1000), 10000, CacheFreshness.DISABLED, 64, 0);
             var executor = Executors.newFixedThreadPool(8)) {
            reader.getById(0); // Warm pointer before concurrent admission; this is not a single-flight miss claim.
            CountDownLatch start = new CountDownLatch(1);
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 8; t++) {
                int id = t;
                tasks.add(executor.submit(() -> {
                    byte[] expected = bytes(Integer.toString(id));
                    try (var lease = reader.newLease()) {
                        start.await();
                        for (int i = 0; i < 300; i++) {
                            assertArrayEquals(expected, reader.getById(id).bytes());
                            reader.getById(id, lease); assertArrayEquals(expected, lease.bytes()); lease.close();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
            assertEquals(1, source.polls.get());
            assertTrue(source.fetches.get() >= 8);
        }
    }

    @Test
    void missingPointerAndMissingPayloadAreDistinctAndNeverPositiveCached() {
        FakeSource source = new FakeSource(); var clock = new MutableClock(1000); source.version = null;
        try (var reader = reader(source, clock, 0, CacheFreshness.DISABLED, 64, 0); var lease = reader.newLease()) {
            reader.getById(1, lease); assertEquals(CacheReadResult.Status.CACHE_NOT_READY, lease.status());
            assertNull(lease.bytes()); lease.close(); assertEquals(0, source.fetches.get());
            source.version = "1000-1"; source.data = (prefix, id) -> CacheReadResult.miss();
            for (int i = 0; i < 2; i++) {
                reader.getById(1, lease); assertEquals(CacheReadResult.Status.MISS, lease.status()); lease.close();
            }
            assertEquals(2, source.fetches.get());
            source.version = null; reader.getById(1, lease);
            assertEquals(CacheReadResult.Status.CACHE_NOT_READY, lease.status());
        }
    }

    @Test
    void staleRefusalDoesNotTouchL1OrRedisDataAndServeStaleCanHitWithoutRedis() {
        FakeSource source = new FakeSource(); var clock = new MutableClock(1000);
        try (var reader = reader(source, clock, 10000, new CacheFreshness(10, false), 64, 0); var lease = reader.newLease()) {
            reader.getById(1, lease); lease.close();
            clock.set(1010); reader.getById(1, lease); assertEquals(CacheReadResult.Status.HIT, lease.status()); lease.close();
            long hits = reader.l1Hits(); clock.set(1011);
            reader.getById(1, lease); assertEquals(CacheReadResult.Status.STALE, lease.status()); assertNull(lease.bytes());
            assertEquals(1, source.fetches.get()); assertEquals(hits, reader.l1Hits());
        }
        source = new FakeSource(); clock.set(1000);
        try (var reader = reader(source, clock, 10000, new CacheFreshness(10, true), 64, 0); var lease = reader.newLease()) {
            reader.getById(1, lease); lease.close(); clock.set(1011);
            reader.getById(1, lease); assertEquals(CacheReadResult.Status.STALE_HIT, lease.status());
            assertEquals(1, source.fetches.get()); assertEquals(1, reader.l1Hits());
        }
    }

    @Test
    void invalidNegativeAndFutureVersionsFailFreshnessClosedAndDatesAreNotParsedOnEveryHit() {
        for (String version : new String[]{"invalid", "-1-x", "0-x", "2000-future", "9223372036854775808-x"}) {
            FakeSource source = new FakeSource(); source.version = version;
            try (var reader = reader(source, new MutableClock(1000), 1000, new CacheFreshness(10, false), 64, 0);
                 var lease = reader.newLease()) {
                reader.getById(1, lease); assertEquals(CacheReadResult.Status.STALE, lease.status());
                assertEquals(0, source.fetches.get());
            }
        }
        FakeSource source = new FakeSource(); var clock = new MutableClock(1000);
        try (var reader = reader(source, clock, 1000, CacheFreshness.DISABLED, 64, 10); var lease = reader.newLease()) {
            reader.getById(1, lease); lease.close(); clock.set(1010);
            reader.getById(1, lease); lease.close(); assertEquals(2, source.fetches.get());
            clock.set(999); reader.getById(1, lease); assertEquals(3, source.fetches.get());
            assertEquals(2, source.polls.get());
        }
    }

    @Test
    void indexMetaNumericIdentityAndOverlongKeysDoNotCrossContaminate() {
        FakeSource source = new FakeSource();
        source.data = (prefix, id) -> CacheReadResult.hit(bytes(prefix + id));
        try (var reader = reader(source, new MutableClock(1000), 1000, CacheFreshness.DISABLED, 128, 0);
             var lease = reader.newLease()) {
            for (int i = 0; i < 2; i++) {
                reader.getIndex("kind", "\u0130\ud83d\ude80", lease);
                assertArrayEquals(bytes("ns:v:1000-1:idx:kind:%C4%B0%F0%9F%9A%80"), lease.bytes()); lease.close();
            }
            for (int i = 0; i < 2; i++) {
                reader.getMeta(lease); assertArrayEquals(bytes("ns:v:1000-1:meta"), lease.bytes()); lease.close();
            }
            assertEquals(2, source.fetches.get());
            String longPart = "x".repeat(100);
            reader.getIndex(longPart, longPart, lease); lease.close();
            reader.getIndex(longPart, longPart, lease); lease.close();
            assertEquals(4, source.fetches.get()); // Key exceeds the configured 32-character identity admission bound.
            assertThrows(IllegalArgumentException.class, () -> reader.getIndex(" ", "value", lease));
        }
    }

    @Test
    void indexHashCollisionsKeepFullIdentityAndEqualStringsReuseTheSameEntry() {
        FakeSource source = new FakeSource();
        source.data = (prefix, id) -> CacheReadResult.hit(bytes(id));
        assertEquals("kind:Aa".hashCode(), "kind:BB".hashCode());
        try (var reader = reader(source, new MutableClock(1000), 1000, CacheFreshness.DISABLED, 64, 0);
             var lease = reader.newLease()) {
            for (int i = 0; i < 10; i++) {
                reader.getIndex(new String("kind"), new String("Aa"), lease);
                assertArrayEquals(bytes("kind:Aa"), lease.bytes()); lease.close();
                reader.getIndex(new String("kind"), new String("BB"), lease);
                assertArrayEquals(bytes("kind:BB"), lease.bytes()); lease.close();
            }
            assertEquals(2, source.fetches.get());
            assertEquals(18, reader.l1Hits());
        }
    }

    @Test
    void indexSeparatorsAreEscapedAndVersionChangesCannotReviveOldEntries() {
        FakeSource source = new FakeSource();
        source.data = (prefix, id) -> CacheReadResult.hit(bytes(prefix + id));
        try (var reader = reader(source, new MutableClock(1000), 0, CacheFreshness.DISABLED, 64, 0);
             var lease = reader.newLease()) {
            for (int i = 0; i < 2; i++) {
                reader.getIndex("a:b", "c", lease);
                assertArrayEquals(bytes("ns:v:1000-1:idx:a%3Ab:c"), lease.bytes()); lease.close();
                reader.getIndex("a", "b:c", lease);
                assertArrayEquals(bytes("ns:v:1000-1:idx:a:b%3Ac"), lease.bytes()); lease.close();
            }
            assertEquals(2, source.fetches.get());
            long epoch = reader.epoch();
            source.version = "2000-2";
            reader.getIndex("a:b", "c", lease);
            assertArrayEquals(bytes("ns:v:2000-2:idx:a%3Ab:c"), lease.bytes()); lease.close();
            assertTrue(reader.epoch() > epoch);
            reader.getIndex("a:b", "c", lease); lease.close();
            assertEquals(3, source.fetches.get());
        }
    }

    @Test
    void oversizedAndFullyPinnedAdmissionBypassStillReturnsCopiedValidPayloads() {
        FakeSource source = new FakeSource(); byte[] large = new byte[65]; large[64] = 12;
        source.data = (prefix, id) -> CacheReadResult.hit(large);
        try (var reader = reader(source, new MutableClock(1000), 1000, CacheFreshness.DISABLED, 64, 0);
             var lease = reader.newLease()) {
            reader.getById(1, lease); assertEquals(65, lease.length());
            large[64] = 99; assertEquals(12, lease.get(64)); lease.close();
            reader.getById(1, lease); lease.close(); assertEquals(2, source.fetches.get());
        }
        source = new FakeSource(); source.data = (prefix, id) -> CacheReadResult.hit(bytes(id));
        try (var reader = reader(source, new MutableClock(1000), 1000, CacheFreshness.DISABLED, 64, 0);
             var a = reader.newLease(); var b = reader.newLease(); var c = reader.newLease()) {
            reader.getById(1, a); reader.getById(2, b); reader.getById(3, c);
            assertArrayEquals(bytes("1"), a.bytes()); assertArrayEquals(bytes("2"), b.bytes()); assertArrayEquals(bytes("3"), c.bytes());
            c.close(); a.close(); reader.getById(3, c);
            assertArrayEquals(bytes("2"), b.bytes()); assertEquals(4, source.fetches.get());
        }
    }

    @Test
    void sourceFailuresKeepLeaseDetachedForRetry() {
        FakeSource source = new FakeSource(); source.pollFailure = true;
        try (var reader = reader(source, new MutableClock(1000), 1000, CacheFreshness.DISABLED, 64, 0);
             var lease = reader.newLease()) {
            assertThrows(IllegalStateException.class, () -> reader.getById(1, lease));
            assertThrows(IllegalStateException.class, lease::status);
            source.pollFailure = false; source.data = (prefix, id) -> { throw new IllegalStateException("data failure"); };
            assertThrows(IllegalStateException.class, () -> reader.getById(1, lease));
            assertThrows(IllegalStateException.class, lease::status);
            source.data = (prefix, id) -> CacheReadResult.hit(new byte[]{42});
            reader.getById(1, lease); assertEquals(42, lease.get(0));
        }
    }

    @Test
    void ownedCopiesPreserveOversizedFallbackOwnershipAndDoNotLeaveSlotsPinned() {
        FakeSource source = new FakeSource(); byte[] large = new byte[65]; large[64] = 42;
        source.data = (prefix, id) -> CacheReadResult.hit(large);
        try (var reader = reader(source, new MutableClock(1000), 1000, CacheFreshness.DISABLED, 64, 0)) {
            byte[] first = reader.getById(1).bytes(); first[64] = 99;
            assertEquals(42, large[64]);
            assertEquals(42, reader.getById(1).bytes()[64]);
            assertEquals(2, source.fetches.get());
            source.data = (prefix, id) -> CacheReadResult.hit(bytes(id));
            for (int i = 0; i < 100; i++) {
                assertArrayEquals(bytes("1"), reader.getById(1).bytes());
                assertArrayEquals(bytes("2"), reader.getById(2).bytes());
            }
            assertEquals(4, source.fetches.get());
            assertThrows(NullPointerException.class, () -> reader.getById(1, null));
            assertThrows(NullPointerException.class, () -> reader.getIndex("segment", "standard", null));
            assertThrows(NullPointerException.class, () -> reader.getMeta(null));
        }
    }

    @Test
    void staleSourceResultsRemainMarkedOnCachedReadsAndRefusedWhenNotPermitted() {
        FakeSource source = new FakeSource(); source.data = (prefix, id) -> CacheReadResult.staleHit(new byte[]{42});
        try (var reader = reader(source, new MutableClock(1000), 1000, CacheFreshness.DISABLED, 64, 0); var lease = reader.newLease()) {
            reader.getById(1, lease); assertEquals(CacheReadResult.Status.STALE, lease.status()); assertNull(lease.bytes());
        }
        try (var reader = reader(source, new MutableClock(1000), 1000, new CacheFreshness(0, true), 64, 0); var lease = reader.newLease()) {
            reader.getById(1, lease); lease.close();
            reader.getById(1, lease); assertEquals(CacheReadResult.Status.STALE_HIT, lease.status());
        }
    }

    @Test
    void futureTimestampBecomingValidCannotReuseThePreviouslyDerivedStaleMarker() {
        FakeSource source = new FakeSource(); source.version = "2000-1"; var clock = new MutableClock(1000);
        try (var reader = reader(source, clock, 10, new CacheFreshness(1000, true), 64, 0);
             var lease = reader.newLease()) {
            reader.getById(1, lease); assertEquals(CacheReadResult.Status.STALE_HIT, lease.status()); lease.close();
            long oldEpoch = reader.epoch(); clock.set(2000);
            reader.getById(1, lease); assertEquals(CacheReadResult.Status.HIT, lease.status()); lease.close();
            assertEquals(oldEpoch + 1, reader.epoch()); assertEquals(2, source.fetches.get());
            reader.getById(1, lease); assertEquals(CacheReadResult.Status.HIT, lease.status());
            assertEquals(2, source.fetches.get());
        }
    }

    @Test
    void versionRotationDuringDataFetchDiscardsOldResponseWithoutLeakingPins() throws Exception {
        FakeSource source = new FakeSource(); var clock = new MutableClock(1000);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        source.data = (prefix, id) -> { entered.countDown(); await(release); return CacheReadResult.hit(bytes("old")); };
        try (var reader = reader(source, clock, 1000, CacheFreshness.DISABLED, 64, 0);
             var executor = Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> {
                try (var lease = reader.newLease()) {
                    reader.getById(1, lease); return lease.status();
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            reader.invalidateVersionCache(); release.countDown();
            assertEquals(CacheReadResult.Status.CACHE_NOT_READY, task.get(10, TimeUnit.SECONDS));
            source.version = "2000-2"; source.data = (prefix, id) -> CacheReadResult.hit(bytes("new"));
            assertArrayEquals(bytes("new"), reader.getById(1).bytes());
        } finally { release.countDown(); }
    }

    @Test
    void closeDuringPointerPollCannotRepopulateReaderOwnership() throws Exception {
        FakeSource source = new FakeSource();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        source.pollEntered = entered; source.pollRelease = release;
        var reader = reader(source, new MutableClock(1000), 0, CacheFreshness.DISABLED, 64, 0);
        try (var executor = Executors.newSingleThreadExecutor(); var local = reader.newLease()) {
            var task = executor.submit(() -> {
                try (var lease = reader.newLease()) { assertThrows(IllegalStateException.class, () -> reader.getById(1, lease)); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            reader.close(); release.countDown(); task.get(10, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> reader.getById(1, local));
            assertEquals(0, source.fetches.get());
        } finally { release.countDown(); reader.close(); }
    }

    @Test
    void sixtyFourConcurrentExpiredAndZeroIntervalPollsProduceHitsWithoutRefreshRejections() throws Exception {
        for (long interval : new long[]{10, 0}) {
            var clock = new MutableClock(1000);
            var polls = new AtomicInteger(); var fetches = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(64), release = new CountDownLatch(1);
            VersionedRedisSource source = new VersionedRedisSource() {
                @Override public Optional<String> getString(String key) {
                    if (polls.incrementAndGet() > 1) { entered.countDown(); await(release); }
                    return Optional.of("1000-1");
                }
                @Override public CacheReadResult getVersionedJson(String key, String prefix, String id) {
                    fetches.incrementAndGet(); return CacheReadResult.hit(new byte[]{42});
                }
            };
            try (var reader = new L1VersionedJsonCacheReader(source, "ns", interval, CacheFreshness.DISABLED,
                    VersionedL1CacheTest.options(64, 0), clock); var executor = Executors.newFixedThreadPool(64)) {
                reader.getById(1); long epoch = reader.epoch(); clock.set(1010);
                var tasks = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 64; i++) tasks.add(executor.submit(() -> {
                    try (var lease = reader.newLease()) {
                        reader.getById(1, lease); assertEquals(CacheReadResult.Status.HIT, lease.status());
                        assertEquals(42, lease.get(0));
                    }
                }));
                try { assertTrue(entered.await(10, TimeUnit.SECONDS)); }
                finally { release.countDown(); }
                for (var task : tasks) task.get(10, TimeUnit.SECONDS);
                assertEquals(65, polls.get()); assertTrue(fetches.get() >= 1 && fetches.get() <= 65);
                assertEquals(epoch, reader.epoch());
            } finally { release.countDown(); }
        }
    }

    @Test
    void slowOldPointerPollCannotOverwriteNewWinningObservationOrAdvanceItsEpochAgain() throws Exception {
        var source = new CapturedPollSource();
        try (var reader = new L1VersionedJsonCacheReader(source, "ns", 0, CacheFreshness.DISABLED,
                VersionedL1CacheTest.options(64, 0), new MutableClock(2000));
             var executor = Executors.newSingleThreadExecutor()) {
            assertArrayEquals(bytes("ns:v:1000-1:id:1"), reader.getById(1).bytes());
            long epoch = reader.epoch(); source.blockedCall = 2;
            var slow = executor.submit(() -> reader.getById(1));
            try {
                assertTrue(source.entered.await(5, TimeUnit.SECONDS)); source.value = "2000-2";
                assertArrayEquals(bytes("ns:v:2000-2:id:1"), reader.getById(1).bytes());
            } finally { source.release.countDown(); }
            var response = slow.get(10, TimeUnit.SECONDS);
            assertEquals(CacheReadResult.Status.HIT, response.status());
            assertArrayEquals(bytes("ns:v:2000-2:id:1"), response.bytes());
            assertEquals(epoch + 1, reader.epoch());
            assertArrayEquals(bytes("ns:v:2000-2:id:1"), reader.getById(1).bytes());
        } finally { source.release.countDown(); }
    }

    @Test
    void sameVersionRefreshDoesNotInvalidateInFlightDataMiss() throws Exception {
        FakeSource source = new FakeSource();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        source.data = (prefix, id) -> {
            if (id.equals("7")) { entered.countDown(); await(release); }
            return CacheReadResult.hit(bytes(id));
        };
        try (var reader = reader(source, new MutableClock(1000), 0, CacheFreshness.DISABLED, 64, 0);
             var executor = Executors.newSingleThreadExecutor()) {
            reader.getById(1);
            var task = executor.submit(() -> reader.getById(7));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertArrayEquals(bytes("1"), reader.getById(1).bytes());
            } finally { release.countDown(); }
            var response = task.get(10, TimeUnit.SECONDS);
            assertEquals(CacheReadResult.Status.HIT, response.status()); assertArrayEquals(bytes("7"), response.bytes());
        } finally { release.countDown(); }
    }

    @Test
    void explicitInvalidationDuringPointerPollDiscardsPollAndCannotRepopulateOldGeneration() throws Exception {
        var source = new CapturedPollSource();
        try (var reader = new L1VersionedJsonCacheReader(source, "ns", 0, CacheFreshness.DISABLED,
                VersionedL1CacheTest.options(64, 0), new MutableClock(2000));
             var executor = Executors.newSingleThreadExecutor()) {
            reader.getById(1); source.blockedCall = 2;
            var slow = executor.submit(() -> reader.getById(1));
            try {
                assertTrue(source.entered.await(5, TimeUnit.SECONDS)); reader.invalidateVersionCache();
                source.value = "2000-2";
            } finally { source.release.countDown(); }
            assertEquals(CacheReadResult.Status.CACHE_NOT_READY, slow.get(10, TimeUnit.SECONDS).status());
            assertArrayEquals(bytes("ns:v:2000-2:id:1"), reader.getById(1).bytes());
        } finally { source.release.countDown(); }
    }

    @Test
    void losingPollCannotServeWinningSnapshotFromFutureAfterClockRollback() throws Exception {
        var source = new CapturedPollSource(); var clock = new MutableClock(1000);
        try (var reader = new L1VersionedJsonCacheReader(source, "ns", 0, new CacheFreshness(10, false),
                VersionedL1CacheTest.options(64, 0), clock); var executor = Executors.newSingleThreadExecutor()) {
            reader.getById(1); source.blockedCall = 2;
            var slow = executor.submit(() -> reader.getById(1));
            try {
                assertTrue(source.entered.await(5, TimeUnit.SECONDS)); clock.set(1001);
                assertEquals(CacheReadResult.Status.HIT, reader.getById(1).status()); clock.set(999);
            } finally { source.release.countDown(); }
            assertEquals(CacheReadResult.Status.STALE, slow.get(10, TimeUnit.SECONDS).status());
            assertEquals(CacheReadResult.Status.STALE, reader.getById(1).status());
        } finally { source.release.countDown(); }
    }

    @Test
    void longAndMalformedPointerBoundsFailClosedBeforeDataFetchAndPermitRecovery() {
        FakeSource source = new FakeSource();
        try (var reader = reader(source, new MutableClock(Long.MAX_VALUE), Long.MAX_VALUE, CacheFreshness.DISABLED, 64, 0);
             var lease = reader.newLease()) {
            source.version = "x".repeat(513); assertThrows(IllegalStateException.class, () -> reader.getById(1, lease));
            source.version = "x".repeat(257); assertThrows(IllegalStateException.class, () -> reader.getById(1, lease));
            assertEquals(0, source.fetches.get()); source.version = " ";
            reader.getById(1, lease); assertEquals(CacheReadResult.Status.CACHE_NOT_READY, lease.status()); lease.close();
            source.version = "1000-1"; reader.getById(1, lease); assertEquals(CacheReadResult.Status.HIT, lease.status());
        }
    }

    @Test
    void reusableHitAllocationGateHasPositiveControlAndMeasuresNoLegacyCopyPromise() {
        hitAllocationGate(0);
    }

    @Test
    void reusableIndexHitAllocationGateHasPositiveControlAndMeasuresNoLegacyCopyPromise() {
        hitAllocationGate(1);
    }

    @Test
    void ownedCopyHitAllocationGateDoesNotAllocateATemporaryLease() {
        hitAllocationGate(2);
    }

    private static void hitAllocationGate(int path) {
        var platform = ManagementFactory.getThreadMXBean();
        assumeTrue(platform instanceof ThreadMXBean, "Thread allocation counter unavailable");
        var bean = (ThreadMXBean) platform;
        assumeTrue(bean.isThreadAllocatedMemorySupported(), "Thread allocation counter unsupported");
        boolean enabled = bean.isThreadAllocatedMemoryEnabled();
        try {
            if (!enabled) bean.setThreadAllocatedMemoryEnabled(true);
            FakeSource source = new FakeSource();
            try (var reader = reader(source, new MutableClock(1000), 10000, CacheFreshness.DISABLED, 64, 0);
                 var lease = reader.newLease()) {
                for (int i = 0; i < 10000; i++) {
                    if (path == 2) { checksum += reader.getById(1).bytes()[0]; continue; }
                    if (path == 1) reader.getIndex("segment", "standard", lease);
                    else reader.getById(1, lease);
                    checksum += lease.get(0); lease.close();
                }
                long thread = Thread.currentThread().threadId();
                for (int i = 0; i < 100; i++) bean.getThreadAllocatedBytes(thread);
                long controlStart = bean.getThreadAllocatedBytes(thread);
                allocationControl = new byte[256 * 1024];
                long control = bean.getThreadAllocatedBytes(thread) - controlStart;
                allocationControl = null;
                assumeTrue(control >= 256 * 1024, "Allocation positive control failed; gate NOT certified: " + control);
                Object[] smallObjects = new Object[100];
                allocationControl = smallObjects;
                for (int batch = 0; batch < 8; batch++) {
                    long calibrationStart = bean.getThreadAllocatedBytes(thread);
                    for (int i = 0; i < smallObjects.length; i++) smallObjects[i] = new byte[32];
                    long delta = bean.getThreadAllocatedBytes(thread) - calibrationStart;
                    System.out.println("Phase2A allocation fineCalibrationBatch=" + batch + " allocatedBytes=" + delta
                            + " requiredRange=4000..6400 for 100 retained byte[32]");
                    assumeTrue(delta >= 4000 && delta <= 6400,
                            "Allocation gate SKIPPED: unreliable small-allocation counter; batch=" + batch + " delta=" + delta);
                }
                long before = bean.getThreadAllocatedBytes(thread);
                int total = 0;
                for (int i = 0; i < 100000; i++) {
                    if (path == 2) { total += reader.getById(1).bytes()[0]; continue; }
                    if (path == 1) reader.getIndex("segment", "standard", lease);
                    else reader.getById(1, lease);
                    total += lease.get(0); lease.close();
                }
                long allocated = bean.getThreadAllocatedBytes(thread) - before;
                checksum = total;
                assertTrue(allocated >= 0 && allocated <= (path == 2 ? 64L : 32L) * 100000,
                        "Allocated " + allocated + " bytes");
                assertEquals(1, source.fetches.get());
                System.out.println("Phase2A keyKind=" + (path == 2 ? "owned-copy" : path == 1 ? "index" : "numeric")
                        + " hits=100000 allocatedBytes=" + allocated + " bytesPerHit="
                        + allocated / 100000.0 + " positiveControlBytes=" + control + " RedisDataFetches=" + source.fetches.get()
                        + " (no legacy byte[] hit or native/RSS/RPS guarantee)");
            }
        } finally {
            allocationControl = null;
            if (!enabled) bean.setThreadAllocatedMemoryEnabled(false);
        }
    }

    private static L1VersionedJsonCacheReader reader(FakeSource source, Clock clock, long versionMillis,
                                                     CacheFreshness freshness, int entryBytes, long entryAge) {
        return new L1VersionedJsonCacheReader(source, "ns", versionMillis, freshness,
                VersionedL1CacheTest.options(entryBytes, entryAge), clock);
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    static final class FakeSource implements VersionedRedisSource {
        volatile String version = "1000-1", lastKey;
        volatile boolean pollFailure;
        volatile CountDownLatch pollEntered, pollRelease;
        volatile BiFunction<String, String, CacheReadResult> data = (prefix, id) -> CacheReadResult.hit(new byte[]{42});
        final AtomicInteger fetches = new AtomicInteger(), polls = new AtomicInteger();
        @Override public Optional<String> getString(String key) {
            polls.incrementAndGet();
            if (pollEntered != null) { pollEntered.countDown(); await(pollRelease); }
            if (pollFailure) throw new IllegalStateException("pointer failure");
            return Optional.ofNullable(version);
        }
        @Override public CacheReadResult getVersionedJson(String key, String prefix, String id) {
            fetches.incrementAndGet(); lastKey = prefix + id; return data.apply(prefix, id);
        }
    }
    static final class CapturedPollSource implements VersionedRedisSource {
        volatile String value = "1000-1";
        volatile int blockedCall = -1;
        final AtomicInteger polls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override public Optional<String> getString(String key) {
            String captured = value;
            if (polls.incrementAndGet() == blockedCall) { entered.countDown(); await(release); }
            return Optional.of(captured);
        }
        @Override public CacheReadResult getVersionedJson(String key, String prefix, String id) {
            return CacheReadResult.hit(bytes(prefix + id));
        }
    }
    static final class MutableClock extends Clock {
        private final AtomicLong millis;
        MutableClock(long value) { millis = new AtomicLong(value); }
        void set(long value) { millis.set(value); }
        @Override public long millis() { return millis.get(); }
        @Override public Instant instant() { throw new AssertionError("No allocating Instant clock path"); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
