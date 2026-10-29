package com.reactor.rust.cache.versioned;

import com.reactor.rust.cache.api.CacheReadLease;
import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.cache.api.CacheReadSnapshot;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** Fixed two-way off-heap storage. CAS pins exclude writers; no TLS, locks, native calls or raw buffer escape. */
public final class VersionedL1Cache implements AutoCloseable {
    private static final long WRITING = -1;
    private static final long EXHAUSTED = Long.MAX_VALUE;

    /** maxBytes includes the direct slab and a conservative fixed Java metadata allowance. */
    public record Options(int maxEntries, int maxEntryBytes, int maxKeyChars, long maxAgeMillis, long maxBytes) {
        public Options {
            if (maxEntries < 2 || maxEntries > 65536 || Integer.bitCount(maxEntries) != 1
                    || maxEntryBytes < 1 || maxEntryBytes > 262144 || maxKeyChars < 1 || maxKeyChars > 4096
                    || maxAgeMillis < 0 || maxBytes < 1 || maxBytes > 64L * 1024 * 1024) {
                throw new IllegalArgumentException("L1 needs bounded power-of-two entries, payload, keys, age and <=64MiB total budget");
            }
            long slab = (long) maxEntries * (maxEntryBytes + 2L * maxKeyChars);
            long total = slab + metadataBytes(maxEntries);
            if (slab > Integer.MAX_VALUE || total > maxBytes) {
                throw new IllegalArgumentException("L1 slab plus metadata exceeds maxBytes: " + total);
            }
        }
        public static Options defaults() { return new Options(64, 8192, 256, 1000, 1024 * 1024); }
        public long slabBytes() { return (long) maxEntries * (maxEntryBytes + 2L * maxKeyChars); }
        public long totalBytesEstimate() { return slabBytes() + metadataBytes(maxEntries); }
        private static long metadataBytes(int entries) { return 8192L + 96L * entries; }
    }

    private final Options options;
    private final AtomicLongArray states;
    private final long[] epochs, keys, inserted;
    private final int[] lengths, keyLengths;
    private final boolean[] stale;
    private final AtomicLong epoch;
    private final AtomicLong replacement = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private volatile ByteBuffer slab;

    public VersionedL1Cache(Options options) { this(options, 1); }

    VersionedL1Cache(Options options, long initialEpoch) {
        this.options = Objects.requireNonNull(options, "options");
        if (initialEpoch < 1) throw new IllegalArgumentException("Invalid epoch");
        int count = options.maxEntries();
        states = new AtomicLongArray(count);
        epochs = new long[count]; keys = new long[count]; inserted = new long[count];
        lengths = new int[count]; keyLengths = new int[count];
        stale = new boolean[count];
        epoch = new AtomicLong(initialEpoch);
        slab = ByteBuffer.allocateDirect(Math.toIntExact(options.slabBytes()));
    }

    public Options options() { return options; }
    public long epoch() { return epoch.get(); }
    public long hitCount() { return hits.get(); }
    public long missCount() { return misses.get(); }
    /** Diagnostic-only bounded scan; no extra counters are updated on the read path. */
    public long activeBorrows() {
        long total = 0;
        for (int i = 0; i < states.length(); i++) { long pins = states.get(i); if (pins > 0) total += pins; }
        return total;
    }
    public boolean isClosed() { return slab == null; }
    public Lease newLease() { return new Lease(); }

    /** O(1): no array walk or payload clearing. Exhaustion never recycles an epoch. */
    public long invalidate() {
        for (;;) {
            long old = epoch.get();
            if (old == EXHAUSTED) return old;
            if (epoch.compareAndSet(old, old + 1)) return old + 1;
        }
    }

    /** A published pointer generation can advance storage, never roll it back. */
    void advanceEpoch(long minimum) {
        for (;;) {
            long old = epoch.get();
            if (old >= minimum || epoch.compareAndSet(old, minimum)) return;
        }
    }

    public boolean borrow(long key, long expectedEpoch, long nowMillis, String version,
                          CacheReadResult.Status status, Lease target) {
        return borrow(key, null, null, expectedEpoch, nowMillis, version, status, target);
    }

    public boolean borrow(String key, long expectedEpoch, long nowMillis, String version,
                          CacheReadResult.Status status, Lease target) {
        checkKey(key);
        return borrow(key.hashCode(), key, null, expectedEpoch, nowMillis, version, status, target);
    }

    boolean borrow(long key, String text, String tail, long expectedEpoch, long now, String version,
                   CacheReadResult.Status status, Lease target) {
        target.requireDetached();
        ByteBuffer memory = slab;
        int slot = pinSlot(memory, key, text, tail, expectedEpoch, now);
        if (slot < 0) return false;
        target.pin(this, memory, slot, payloadOffset(slot), lengths[slot], version,
                stale[slot] ? CacheReadResult.Status.STALE_HIT : status);
        return true;
    }

    CacheReadResult copy(long key, String text, String tail, long expectedEpoch, long now,
                         CacheReadResult.Status status) {
        ByteBuffer memory = slab;
        int slot = pinSlot(memory, key, text, tail, expectedEpoch, now);
        if (slot < 0) return null;
        try {
            byte[] bytes = new byte[lengths[slot]];
            memory.get(payloadOffset(slot), bytes, 0, bytes.length);
            return stale[slot] || status == CacheReadResult.Status.STALE_HIT
                    ? CacheReadResult.staleHit(bytes) : CacheReadResult.hit(bytes);
        } finally { states.decrementAndGet(slot); }
    }

    CacheReadSnapshot snapshot(long key, String text, String tail, long expectedEpoch, long now,
                               String version, CacheReadResult.Status status) {
        ByteBuffer memory = slab;
        int slot = pinSlot(memory, key, text, tail, expectedEpoch, now);
        if (slot < 0) return null;
        try {
            return new Snapshot(this, memory, slot, payloadOffset(slot), lengths[slot], version,
                    stale[slot] ? CacheReadResult.Status.STALE_HIT : status);
        } catch (RuntimeException | Error failure) {
            states.decrementAndGet(slot);
            throw failure;
        }
    }

    private int pinSlot(ByteBuffer memory, long key, String text, String tail, long expectedEpoch, long now) {
        if (memory == null) throw new IllegalStateException("L1 is closed");
        if (expectedEpoch == EXHAUSTED || expectedEpoch != epoch.get()) { misses.incrementAndGet(); return -1; }
        int first = set(key);
        for (int slot = first; slot < first + 2; slot++) {
            long state = states.get(slot);
            if (state < 0 || state == Integer.MAX_VALUE || !states.compareAndSet(slot, state, state + 1)) continue;
            boolean keep = false;
            try {
                if (slab == null) throw new IllegalStateException("L1 is closed");
                if (epoch.get() != expectedEpoch || epochs[slot] != expectedEpoch || keys[slot] != key
                        || !sameKey(memory, slot, text, tail) || now < inserted[slot]
                        || options.maxAgeMillis() != 0 && (now - inserted[slot] < 0
                        || now - inserted[slot] >= options.maxAgeMillis())) continue;
                keep = true;
                hits.incrementAndGet();
                return slot;
            } finally { if (!keep) states.decrementAndGet(slot); }
        }
        misses.incrementAndGet();
        return -1;
    }

    boolean storeAndBorrow(long key, String text, long expectedEpoch, long now, byte[] value,
                           String version, CacheReadResult.Status status, Lease target) {
        return storeAndBorrow(key, text, null, expectedEpoch, now, value, version, status, target);
    }

    boolean storeAndBorrow(long key, String text, String tail, long expectedEpoch, long now, byte[] value,
                           String version, CacheReadResult.Status status, Lease target) {
        if (target != null) target.requireDetached();
        ByteBuffer memory = slab;
        return store(key, text, tail, expectedEpoch, now, value, version, status, target, memory, false) >= 0;
    }

    /** Transfers the insertion pin directly to the response, including the first cache fill. */
    CacheReadSnapshot storeAndSnapshot(long key, String text, String tail, long expectedEpoch, long now,
                                      byte[] value, String version, CacheReadResult.Status status) {
        ByteBuffer memory = slab;
        int slot = store(key, text, tail, expectedEpoch, now, value, version, status, null, memory, true);
        if (slot < 0) return null;
        try {
            return new Snapshot(this, memory, slot, payloadOffset(slot), value.length, version, status);
        } catch (RuntimeException | Error failure) {
            states.decrementAndGet(slot);
            throw failure;
        }
    }

    private int store(long key, String text, String tail, long expectedEpoch, long now, byte[] value,
                      String version, CacheReadResult.Status status, Lease target, ByteBuffer memory,
                      boolean keepPin) {
        if (memory == null) throw new IllegalStateException("L1 is closed");
        if (expectedEpoch == EXHAUSTED || expectedEpoch != epoch.get() || value.length > options.maxEntryBytes()) return -1;
        if (text != null) {
            checkKey(text);
            if (tail != null && (long) text.length() + 1 + tail.length() > options.maxKeyChars()) {
                throw new IllegalArgumentException("L1 key exceeds bound");
            }
        }
        int keyLength = keyLength(text, tail);
        int first = set(key);
        int preferred = (int) (replacement.getAndIncrement() & 1);
        for (int attempt = 0; attempt < 2; attempt++) {
            int slot = first + ((preferred + attempt) & 1);
            if (!states.compareAndSet(slot, 0, WRITING)) continue;
            boolean keep = false;
            try {
                epochs[slot] = 0;
                int offset = slotOffset(slot);
                for (int i = 0; i < keyLength; i++) {
                    char ch = keyChar(text, tail, i);
                    memory.put(offset + 2 * i, (byte) (ch >>> 8));
                    memory.put(offset + 2 * i + 1, (byte) ch);
                }
                memory.put(payloadOffset(slot), value, 0, value.length);
                keys[slot] = key; keyLengths[slot] = keyLength;
                lengths[slot] = value.length; inserted[slot] = now;
                stale[slot] = status == CacheReadResult.Status.STALE_HIT;
                if (slab == null || epoch.get() != expectedEpoch) return -1;
                epochs[slot] = expectedEpoch;
                if (target != null) target.pin(this, memory, slot, payloadOffset(slot), value.length, version, status);
                keep = true;
                return slot;
            } finally { states.set(slot, keep && (target != null || keepPin) ? 1 : 0); }
        }
        return -1;
    }

    private boolean sameKey(ByteBuffer memory, int slot, String text, String tail) {
        if (text == null) return keyLengths[slot] == -1;
        int length = keyLength(text, tail);
        if (keyLengths[slot] != length) return false;
        int offset = slotOffset(slot);
        for (int i = 0; i < length; i++) {
            int ch = ((memory.get(offset + i * 2) & 255) << 8) | (memory.get(offset + i * 2 + 1) & 255);
            if (ch != keyChar(text, tail, i)) return false;
        }
        return true;
    }

    private static int keyLength(String text, String tail) {
        return text == null ? -1 : text.length() + (tail == null ? 0 : 1 + tail.length());
    }

    // Compare/store the same UTF-16 identity without materializing name + ':' + value on a hit.
    private static char keyChar(String text, String tail, int index) {
        if (tail == null || index < text.length()) return text.charAt(index);
        return index == text.length() ? ':' : tail.charAt(index - text.length() - 1);
    }

    private void checkKey(String key) {
        if (key == null || key.isEmpty() || key.length() > options.maxKeyChars()) throw new IllegalArgumentException("L1 key exceeds bound");
    }
    private int set(long key) {
        return ((int) (key ^ (key >>> 32)) & (options.maxEntries() / 2 - 1)) * 2;
    }
    private int slotOffset(int slot) { return slot * (options.maxEntryBytes() + 2 * options.maxKeyChars()); }
    private int payloadOffset(int slot) { return slotOffset(slot) + 2 * options.maxKeyChars(); }

    /** Drops cache ownership only. Active leases keep memory alive; the JVM eventually frees the direct buffer. */
    @Override public void close() { slab = null; invalidate(); }

    private static final class Snapshot extends CacheReadSnapshot {
        private VersionedL1Cache cache;
        private ByteBuffer memory;
        private final int slot, offset;
        Snapshot(VersionedL1Cache cache, ByteBuffer memory, int slot, int offset, int length,
                 String version, CacheReadResult.Status status) {
            super(status, version, length);
            this.cache = cache; this.memory = memory; this.slot = slot; this.offset = offset;
        }
        protected void copyTo(ByteBuffer out, int destination) { out.put(destination, memory, offset, length()); }
        protected void release() {
            VersionedL1Cache previous = cache;
            cache = null; memory = null;
            previous.states.decrementAndGet(slot);
        }
    }

    public static final class Lease implements CacheReadLease {
        private final long creatingThreadId = Thread.currentThread().threadId();
        private VersionedL1Cache cache;
        private ByteBuffer memory;
        private byte[] heap;
        private Thread owner;
        private String version;
        private CacheReadResult.Status status;
        private int slot, offset, length;

        private Lease() {}
        void requireDetached() {
            if (creatingThreadId != Thread.currentThread().threadId()) throw new IllegalStateException("Use a lease only on its creating thread");
            if (owner != null) throw new IllegalStateException("Close the current lease before reuse");
        }
        private void pin(VersionedL1Cache cache, ByteBuffer memory, int slot, int offset, int length,
                         String version, CacheReadResult.Status status) {
            this.cache = cache; this.memory = memory; this.slot = slot; this.offset = offset; this.length = length;
            this.version = version; this.status = status; owner = Thread.currentThread();
        }
        void result(String version, CacheReadResult.Status status, byte[] value) {
            requireDetached();
            byte[] owned = value == null ? null : value.clone();
            this.heap = owned; this.length = owned == null ? 0 : owned.length;
            this.version = version; this.status = status; owner = Thread.currentThread();
        }
        private void requireOwner() {
            if (owner != Thread.currentThread()) throw new IllegalStateException("Lease is detached or owned by another thread");
        }
        @Override public CacheReadResult.Status status() { requireOwner(); return status; }
        @Override public String version() { requireOwner(); return version; }
        @Override public int length() { requireOwner(); return length; }
        @Override public byte get(int index) {
            requireOwner();
            Objects.checkIndex(index, length);
            return memory == null ? heap[index] : memory.get(offset + index);
        }
        @Override public void copyTo(byte[] destination, int destinationOffset) {
            requireOwner();
            Objects.checkFromIndexSize(destinationOffset, length, destination.length);
            if (memory != null) memory.get(offset, destination, destinationOffset, length);
            else if (heap != null) System.arraycopy(heap, 0, destination, destinationOffset, length);
        }
        @Override public byte[] bytes() {
            requireOwner();
            if (status != CacheReadResult.Status.HIT && status != CacheReadResult.Status.STALE_HIT) return null;
            byte[] copy = new byte[length]; copyTo(copy, 0); return copy;
        }
        /** Transfers the pin to an immutable owner that can be serialized/closed on another thread. */
        public CacheReadSnapshot transfer() {
            requireOwner();
            CacheReadSnapshot snapshot;
            if (memory != null) snapshot = new Snapshot(cache, memory, slot, offset, length, version, status);
            else snapshot = CacheReadSnapshot.takeOwnership(switch (status) {
                case HIT -> CacheReadResult.hit(heap);
                case STALE_HIT -> CacheReadResult.staleHit(heap);
                case MISS -> CacheReadResult.miss();
                case STALE -> CacheReadResult.stale();
                case CACHE_NOT_READY -> CacheReadResult.cacheNotReady();
            }, version);
            cache = null; memory = null; heap = null; version = null; status = null;
            offset = 0; length = 0; owner = null;
            return snapshot;
        }
        @Override public void close() {
            if (owner == null) return;
            requireOwner();
            VersionedL1Cache previous = cache;
            int previousSlot = slot;
            cache = null; memory = null; heap = null; version = null; status = null;
            offset = 0; length = 0; owner = null;
            if (previous != null) previous.states.decrementAndGet(previousSlot);
        }
    }
}
