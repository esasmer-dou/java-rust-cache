package com.reactor.rust.cache.versioned;

import com.reactor.rust.cache.api.CacheReadSnapshot;
import com.reactor.rust.cache.http.CacheResponses;
import com.reactor.rust.json.ManagedJsonBodyProducer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheReadSnapshotTest {
    @Test
    void directSnapshotReadsPinWithoutATransferAndKeepVersionedPayloadsImmutable() {
        for (int kind = 0; kind < 3; kind++) {
            var source = new L1VersionedJsonCacheReaderTest.FakeSource();
            byte[] body = "{\"name\":\"\u0130pek \ud83d\ude80\"}".getBytes(StandardCharsets.UTF_8);
            source.data = (prefix, id) -> com.reactor.rust.cache.api.CacheReadResult.hit(body);
            var clock = new L1VersionedJsonCacheReaderTest.MutableClock(1000);
            try (var reader = new L1VersionedJsonCacheReader(source, "ns", 1000, CacheFreshness.DISABLED,
                    VersionedL1CacheTest.options(64, 0), clock)) {
                try (var cold = snapshot(reader, kind)) {
                    assertEquals(body.length, cold.length());
                    assertEquals(1, reader.l1ActiveBorrows());
                    assertEquals(0, reader.l1Hits());
                    assertEquals(1, reader.l1Misses());
                }
                assertEquals(0, reader.l1ActiveBorrows());
                try (var hit = snapshot(reader, kind)) {
                    assertEquals(1, reader.l1ActiveBorrows());
                    assertEquals(1, source.fetches.get());
                    assertEquals(1, reader.l1Hits());
                    assertEquals(1, reader.l1Misses());
                    reader.invalidateVersionCache();
                    source.version = "2000-2";
                    try (var newer = snapshot(reader, kind)) { assertEquals("2000-2", newer.version()); }
                    reader.close();
                    var out = ByteBuffer.allocateDirect(body.length + 2);
                    assertEquals(-body.length, hit.writeTo(ByteBuffer.allocateDirect(1), 0));
                    assertEquals(body.length, hit.writeTo(out, 2));
                    byte[] actual = new byte[body.length]; out.get(2, actual);
                    assertArrayEquals(body, actual);
                }
                assertEquals(0, reader.l1ActiveBorrows());
            }
        }
    }

    private static CacheReadSnapshot snapshot(L1VersionedJsonCacheReader reader, int kind) {
        return switch (kind) {
            case 0 -> reader.snapshotById(1);
            case 1 -> reader.snapshotIndex("segment", "\u0130stanbul");
            default -> reader.snapshotMeta();
        };
    }

    @Test
    void filledSnapshotOwnsTheSlabNotTheMutableSourceAndReleasesItsPinOnAnotherThread() throws Exception {
        var source = new L1VersionedJsonCacheReaderTest.FakeSource();
        byte[] body = new byte[]{42, 7};
        source.data = (prefix, id) -> com.reactor.rust.cache.api.CacheReadResult.hit(body);
        try (var reader = new L1VersionedJsonCacheReader(source, "ns", 1000, CacheFreshness.DISABLED,
                VersionedL1CacheTest.options(64, 0), new L1VersionedJsonCacheReaderTest.MutableClock(1000));
             var executor = Executors.newSingleThreadExecutor(); var filled = reader.snapshotById(1)) {
            assertEquals(1, reader.l1ActiveBorrows());
            body[0] = 0;
            reader.invalidateVersionCache();
            reader.close();
            executor.submit(() -> {
                var out = ByteBuffer.allocateDirect(2);
                assertEquals(-2, filled.writeTo(ByteBuffer.allocateDirect(1), 0));
                assertEquals(2, filled.writeTo(out, 0));
                assertEquals(42, out.get(0));
                filled.close(); filled.close();
            }).get(5, TimeUnit.SECONDS);
            assertEquals(0, reader.l1ActiveBorrows());
        }
    }

    @Test
    void filledSnapshotsNeverOverwritePinnedCollisionsAndHeapFallbackRemainsDefensive() {
        var source = new L1VersionedJsonCacheReaderTest.FakeSource();
        byte[] body = new byte[]{42};
        source.data = (prefix, id) -> com.reactor.rust.cache.api.CacheReadResult.hit(body);
        var options = new VersionedL1Cache.Options(2, 64, 32, 0, 16384);
        try (var reader = new L1VersionedJsonCacheReader(source, "ns", 1000, CacheFreshness.DISABLED,
                options, new L1VersionedJsonCacheReaderTest.MutableClock(1000));
             var first = reader.snapshotById(1); var second = reader.snapshotById(2);
             var fallback = reader.snapshotById(3)) {
            assertEquals(2, reader.l1ActiveBorrows());
            body[0] = 0;
            for (var snapshot : new CacheReadSnapshot[]{first, second, fallback}) {
                var out = ByteBuffer.allocateDirect(1);
                assertEquals(1, snapshot.writeTo(out, 0));
                assertEquals(42, out.get(0));
            }
            fallback.close(); assertEquals(2, reader.l1ActiveBorrows());
            first.close(); second.close(); assertEquals(0, reader.l1ActiveBorrows());
        }
    }

    @Test
    void directOversizedSnapshotsStayCompleteAndSourceFailuresDoNotLeavePins() {
        var source = new L1VersionedJsonCacheReaderTest.FakeSource();
        byte[] large = new byte[128]; large[0] = 42;
        source.data = (prefix, id) -> com.reactor.rust.cache.api.CacheReadResult.hit(large);
        try (var reader = new L1VersionedJsonCacheReader(source, "ns", 1000, CacheFreshness.DISABLED,
                VersionedL1CacheTest.options(64, 0), new L1VersionedJsonCacheReaderTest.MutableClock(1000))) {
            try (var snapshot = reader.snapshotById(1)) {
                assertEquals("1000-1", snapshot.version());
                assertEquals(128, snapshot.length());
                assertEquals(0, reader.l1ActiveBorrows());
                large[0] = 7;
                var out = ByteBuffer.allocateDirect(128);
                assertEquals(-128, snapshot.writeTo(ByteBuffer.allocateDirect(127), 0));
                assertEquals(128, snapshot.writeTo(out, 0));
                assertEquals(42, out.get(0));
            }
            try (var ignored = reader.snapshotById(1)) { assertEquals(2, source.fetches.get()); }
            source.data = (prefix, id) -> { throw new IllegalStateException("source unavailable"); };
            assertThrows(IllegalStateException.class, () -> reader.snapshotById(1));
            assertEquals(0, reader.l1ActiveBorrows());
        }
    }

    @Test
    void transferredSlotStaysPinnedAcrossInvalidationAndOtherThreadWritesUntilExactlyOnceClose() throws Exception {
        var source = new L1VersionedJsonCacheReaderTest.FakeSource();
        byte[] body = "{\"name\":\"\u0130pek \ud83d\ude80\"}".getBytes(StandardCharsets.UTF_8);
        source.data = (prefix, id) -> com.reactor.rust.cache.api.CacheReadResult.hit(body);
        try (var reader = new L1VersionedJsonCacheReader(source, "ns", 1000, CacheFreshness.DISABLED,
                VersionedL1CacheTest.options(64, 0), new L1VersionedJsonCacheReaderTest.MutableClock(1000));
             var lease = reader.newLease(); var executor = Executors.newSingleThreadExecutor()) {
            reader.getById(1, lease);
            try (var snapshot = lease.transfer()) {
                assertEquals(1, reader.l1ActiveBorrows());
                assertThrows(IllegalStateException.class, lease::length);
                reader.invalidateVersionCache(); reader.close();
                var out = ByteBuffer.allocateDirect(body.length + 2);
                executor.submit(() -> assertEquals(body.length, snapshot.writeTo(out, 2))).get(5, TimeUnit.SECONDS);
                byte[] actual = new byte[body.length]; out.get(2, actual);
                assertArrayEquals(body, actual);
                executor.submit(snapshot::close).get(5, TimeUnit.SECONDS);
                assertEquals(0, reader.l1ActiveBorrows());
                assertThrows(IllegalStateException.class, () -> snapshot.writeTo(out, 2));
            }
        }
    }

    @Test
    void cancellationDuringCopyWaitsForWriterBeforeReleasingItsStorage() throws Exception {
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1); var releases = new AtomicInteger();
        CacheReadSnapshot snapshot = new CacheReadSnapshot(com.reactor.rust.cache.api.CacheReadResult.Status.HIT, "v", 1) {
            protected void copyTo(ByteBuffer out, int offset) {
                entered.countDown();
                try { assertTrue(resume.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); }
                assertEquals(0, releases.get()); out.put(offset, (byte) 42);
            }
            protected void release() { releases.incrementAndGet(); }
        };
        try (snapshot; var executor = Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> snapshot.writeTo(ByteBuffer.allocateDirect(1), 0));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            snapshot.close(); snapshot.close(); assertEquals(0, releases.get());
            resume.countDown(); assertEquals(1, task.get(5, TimeUnit.SECONDS));
            assertEquals(1, releases.get());
        } finally { resume.countDown(); }
    }

    @Test
    void httpProducerKeepsOwnershipOnOverflowAndPreservesEmptyBodiesAndErrorCodes() {
        var data = com.reactor.rust.cache.api.CacheReadResult.hit(new byte[]{'{','}'});
        try (var snapshot = CacheReadSnapshot.takeOwnership(data)) {
            var response = CacheResponses.ownedJson(snapshot, "missing", "not_ready");
            var producer = (ManagedJsonBodyProducer) response.getBody();
            assertEquals(-2, producer.write(ByteBuffer.allocateDirect(1), 0));
            assertEquals(2, producer.write(ByteBuffer.allocateDirect(2), 0));
            assertThrows(IllegalStateException.class, () -> snapshot.writeTo(ByteBuffer.allocateDirect(2), 0));
        }
        try (var empty = CacheReadSnapshot.takeOwnership(com.reactor.rust.cache.api.CacheReadResult.hit(new byte[0]))) {
            var producer = (ManagedJsonBodyProducer) CacheResponses.ownedJson(empty, "missing", "not_ready").getBody();
            assertEquals(0, producer.write(ByteBuffer.allocateDirect(1), 50));
            assertEquals(0, producer.write(ByteBuffer.allocateDirect(50), 50));
            assertThrows(IllegalStateException.class, () -> empty.writeTo(ByteBuffer.allocateDirect(0), 0));
        }
        try (var missing = CacheReadSnapshot.takeOwnership(com.reactor.rust.cache.api.CacheReadResult.miss())) {
            assertEquals(404, CacheResponses.ownedJson(missing, "missing", "not_ready").getStatus().getCode());
        }
    }
}
