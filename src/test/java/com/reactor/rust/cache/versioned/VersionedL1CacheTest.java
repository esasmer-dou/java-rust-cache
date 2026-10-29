package com.reactor.rust.cache.versioned;

import com.reactor.rust.cache.api.CacheReadResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VersionedL1CacheTest {
    private static final CacheReadResult.Status HIT = CacheReadResult.Status.HIT;
    static VersionedL1Cache.Options options(int bytes, long age) {
        return new VersionedL1Cache.Options(2, bytes, 32, age, 1024 * 1024);
    }

    @Test
    void fullLongAndStringIdentitySurviveHashCollisionsAndDoNotMixKeyKinds() {
        try (var cache = new VersionedL1Cache(options(64, 0)); var lease = cache.newLease()) {
            assertEquals("Aa".hashCode(), "BB".hashCode());
            put(cache, "Aa", "alpha", lease); lease.close();
            put(cache, "BB", "beta", lease); lease.close();
            assertTrue(cache.borrow("Aa", cache.epoch(), 1000, "v", HIT, lease));
            assertArrayEquals(bytes("alpha"), lease.bytes()); lease.close();
            assertTrue(cache.borrow("BB", cache.epoch(), 1000, "v", HIT, lease));
            assertArrayEquals(bytes("beta"), lease.bytes()); lease.close();
            put(cache, 0, "zero", lease); lease.close();
            put(cache, 1L << 32, "high", lease); lease.close();
            assertTrue(cache.borrow(0, cache.epoch(), 1000, "v", HIT, lease));
            assertEquals('z', lease.get(0)); lease.close();
            assertTrue(cache.borrow(1L << 32, cache.epoch(), 1000, "v", HIT, lease));
            assertEquals('h', lease.get(0)); lease.close();
            put(cache, (long) "Aa".hashCode(), "number", lease); lease.close();
            assertFalse(cache.borrow("Aa", cache.epoch(), 1000, "v", HIT, lease));
        }
    }

    @Test
    void fullUtf16KeysIncludeTurkishEmojiAndSurrogatesWithoutHashOnlyEquality() {
        try (var cache = new VersionedL1Cache(options(64, 0)); var lease = cache.newLease()) {
            String key = "\u0130\u011f\ud83d\ude80\ud800";
            put(cache, key, "one", lease); lease.close();
            assertTrue(cache.borrow(key, cache.epoch(), 1000, "v", HIT, lease)); lease.close();
            assertFalse(cache.borrow(key.substring(0, key.length() - 1) + '\ud801', cache.epoch(), 1000, "v", HIT, lease));
            assertThrows(IllegalArgumentException.class, () -> cache.borrow("x".repeat(33), cache.epoch(), 1000, "v", HIT, lease));
        }
    }

    @Test
    void exactTotalBudgetAndIntegerBoundsRejectCartesianSixteenGiBWithoutAllocatingIt() {
        var probe = options(16, 0);
        long exact = probe.totalBytesEstimate();
        var valid = new VersionedL1Cache.Options(2, 16, 32, 0, exact);
        try (var cache = new VersionedL1Cache(valid)) {
            assertEquals(exact, cache.options().totalBytesEstimate());
            assertTrue(cache.options().slabBytes() < exact);
        }
        assertThrows(IllegalArgumentException.class, () -> new VersionedL1Cache.Options(2, 16, 32, 0, exact - 1));
        assertTrue(VersionedL1Cache.Options.defaults().totalBytesEstimate() <= 1024 * 1024);
        for (int count : new int[]{0, 1, 3, 65537, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new VersionedL1Cache.Options(count, 1, 1, 0, 1024 * 1024));
        }
        assertThrows(IllegalArgumentException.class, () -> new VersionedL1Cache.Options(65536, 262144, 256, 0, 64L * 1024 * 1024));
        assertThrows(IllegalArgumentException.class, () -> new VersionedL1Cache.Options(2, 262145, 1, 0, 1024 * 1024));
        assertThrows(IllegalArgumentException.class, () -> new VersionedL1Cache.Options(2, 1, 4097, 0, 1024 * 1024));
        assertThrows(IllegalArgumentException.class, () -> new VersionedL1Cache.Options(2, 1, 1, -1, 1024 * 1024));
        assertThrows(IllegalArgumentException.class, () -> new VersionedL1Cache.Options(2, 1, 1, 0, Long.MAX_VALUE));
    }

    @Test
    void payloadLengthIsNotTruncatedToSixteenBitsAndMaximumEntryIsSupported() {
        for (int size : new int[]{0, 65535, 65536, 262144}) {
            try (var cache = new VersionedL1Cache(options(262144, 0)); var lease = cache.newLease()) {
                byte[] data = new byte[size];
                Arrays.fill(data, (byte) 42);
                assertTrue(cache.storeAndBorrow(1, null, cache.epoch(), 1000, data, "v", HIT, lease));
                assertEquals(size, lease.length());
                if (size > 0) assertEquals(42, lease.get(size - 1));
                assertArrayEquals(data, lease.bytes()); lease.close();
                assertTrue(cache.borrow(1, cache.epoch(), 1000, "v", HIT, lease));
                assertEquals(size, lease.length()); lease.close();
                assertFalse(cache.storeAndBorrow(2, null, cache.epoch(), 1000, new byte[262145], "v", HIT, lease));
                assertThrows(IllegalStateException.class, lease::status);
            }
        }
    }

    @Test
    void borrowedSlotsCannotBeEvictedAndCopiesCannotMutateStorage() {
        try (var cache = new VersionedL1Cache(options(64, 0)); var a = cache.newLease();
             var b = cache.newLease(); var c = cache.newLease()) {
            put(cache, 1, "alpha", a);
            put(cache, 2, "beta", b);
            assertFalse(cache.storeAndBorrow(3, null, cache.epoch(), 1000, bytes("third"), "v", HIT, c));
            assertArrayEquals(bytes("alpha"), a.bytes());
            assertArrayEquals(bytes("beta"), b.bytes());
            byte[] copy = a.bytes(); copy[0] = '!'; assertEquals('a', a.get(0));
            byte[] target = new byte[8]; a.copyTo(target, 2); assertEquals('a', target[2]);
            assertThrows(IndexOutOfBoundsException.class, () -> a.get(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> a.get(a.length()));
            assertThrows(IndexOutOfBoundsException.class, () -> a.copyTo(new byte[4], 0));
            assertThrows(IllegalStateException.class, () -> cache.borrow(1, cache.epoch(), 1000, "v", HIT, a));
            a.close();
            assertThrows(IllegalStateException.class, a::length);
            put(cache, 3, "third", c);
            assertArrayEquals(bytes("beta"), b.bytes());
        }
    }

    @Test
    void invalidationDoesNotTouchPinnedMemoryAndExhaustedEpochNeverWrapsOrReusesOldSlots() {
        try (var cache = new VersionedL1Cache(options(64, 0), Long.MAX_VALUE - 1);
             var old = cache.newLease(); var next = cache.newLease()) {
            put(cache, 1, "old", old);
            assertEquals(Long.MAX_VALUE, cache.invalidate());
            for (int i = 0; i < 100; i++) assertEquals(Long.MAX_VALUE, cache.invalidate());
            assertArrayEquals(bytes("old"), old.bytes());
            assertFalse(cache.borrow(1, cache.epoch(), 1000, "new", HIT, next));
            assertFalse(cache.storeAndBorrow(1, null, cache.epoch(), 1000, bytes("new"), "new", HIT, next));
            assertThrows(IllegalStateException.class, next::status);
        }
        try (var cache = new VersionedL1Cache(options(64, 0)); var lease = cache.newLease()) {
            put(cache, 1, "old", lease); lease.close();
            long previous = cache.epoch();
            assertEquals(previous + 1, cache.invalidate());
            assertFalse(cache.borrow(1, previous, 1000, "old", HIT, lease));
            assertFalse(cache.borrow(1, cache.epoch(), 1000, "new", HIT, lease));
        }
    }

    @Test
    void publishedEpochAdvanceCannotRollBackOrReenableAnExhaustedCache() {
        try (var cache = new VersionedL1Cache(options(64, 0)); var lease = cache.newLease()) {
            put(cache, 1, "old", lease); lease.close();
            cache.advanceEpoch(10); cache.advanceEpoch(2);
            assertEquals(10, cache.epoch());
            assertFalse(cache.borrow(1, 1, 1000, "v", HIT, lease));
            put(cache, 1, "new", lease); lease.close();
            cache.advanceEpoch(Long.MAX_VALUE); cache.advanceEpoch(10);
            assertEquals(Long.MAX_VALUE, cache.epoch());
            assertFalse(cache.borrow(1, Long.MAX_VALUE, 1000, "v", HIT, lease));
            assertFalse(cache.storeAndBorrow(1, null, Long.MAX_VALUE, 1000, bytes("x"), "v", HIT, lease));
        }
    }

    @Test
    void entryAgeExactBoundaryClockRollbackAndSubtractionOverflowFailClosed() {
        try (var cache = new VersionedL1Cache(options(64, 10)); var lease = cache.newLease()) {
            put(cache, 1, "u", lease); lease.close();
            assertTrue(cache.borrow(1, cache.epoch(), 1009, "v", HIT, lease)); lease.close();
            assertFalse(cache.borrow(1, cache.epoch(), 1010, "v", HIT, lease));
            assertFalse(cache.borrow(1, cache.epoch(), 999, "v", HIT, lease));
            assertTrue(cache.storeAndBorrow(2, null, cache.epoch(), Long.MIN_VALUE, bytes("u"), "v", HIT, lease)); lease.close();
            assertFalse(cache.borrow(2, cache.epoch(), Long.MAX_VALUE, "v", HIT, lease));
        }
    }

    @Test
    void closeDropsCacheOwnershipButActiveReadStaysSafeUntilOwnerClosesIt() throws Exception {
        var cache = new VersionedL1Cache(options(64, 0));
        var lease = cache.newLease(); put(cache, 1, "snapshot", lease);
        Thread closer = Thread.ofPlatform().start(cache::close); closer.join();
        assertTrue(cache.isClosed());
        assertArrayEquals(bytes("snapshot"), lease.bytes());
        assertThrows(IllegalStateException.class, () -> cache.borrow(1, cache.epoch(), 1000, "v", HIT, cache.newLease()));
        lease.close(); lease.close();
        assertThrows(IllegalStateException.class, lease::bytes);
    }

    @Test
    void crossThreadUseAndCloseCannotUnpinSomeoneElsesLease() throws Exception {
        try (var cache = new VersionedL1Cache(options(64, 0)); var lease = cache.newLease()) {
            put(cache, 1, "owned", lease);
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> {
                    assertThrows(IllegalStateException.class, lease::close);
                    assertThrows(IllegalStateException.class, () -> lease.get(0));
                    assertThrows(IllegalStateException.class, () -> cache.borrow(1, cache.epoch(), 1000, "v", HIT, lease));
                }).get(10, TimeUnit.SECONDS);
            }
            assertArrayEquals(bytes("owned"), lease.bytes());
        }
    }

    @Test
    void concurrentWritersReadersEvictionsAndInvalidationsNeverTearOrMixPayloads() throws Exception {
        try (var cache = new VersionedL1Cache(options(256, 0)); var executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger reads = new AtomicInteger();
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 8; t++) {
                int id = t;
                tasks.add(executor.submit(() -> {
                    byte[] payload = new byte[256]; Arrays.fill(payload, (byte) id);
                    try (var lease = cache.newLease()) {
                        start.await();
                        for (int i = 0; i < 1000; i++) {
                            long epoch = cache.epoch();
                            if (cache.storeAndBorrow(id, null, epoch, 1000, payload, "v", HIT, lease)) {
                                for (int j = 0; j < 256; j++) assertEquals((byte) id, lease.get(j));
                                lease.close(); reads.incrementAndGet();
                            }
                            if (cache.borrow(id, cache.epoch(), 1000, "v", HIT, lease)) {
                                for (int j = 0; j < 256; j++) assertEquals((byte) id, lease.get(j));
                                lease.close();
                            }
                            if (i % 73 == 0) cache.invalidate();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
            assertTrue(reads.get() > 0);
            try (var lease = cache.newLease()) { put(cache, 99, "all pins released", lease); }
        }
    }

    private static void put(VersionedL1Cache cache, long key, String value, VersionedL1Cache.Lease target) {
        assertTrue(cache.storeAndBorrow(key, null, cache.epoch(), 1000, bytes(value), "v", HIT, target));
    }
    private static void put(VersionedL1Cache cache, String key, String value, VersionedL1Cache.Lease target) {
        assertTrue(cache.storeAndBorrow(key.hashCode(), key, cache.epoch(), 1000, bytes(value), "v", HIT, target));
    }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
