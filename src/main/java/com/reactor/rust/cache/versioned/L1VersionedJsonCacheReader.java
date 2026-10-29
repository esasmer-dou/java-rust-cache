package com.reactor.rust.cache.versioned;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.cache.api.CacheReadSnapshot;
import com.reactor.rust.cache.core.RustCache;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Explicit L1 opt-in, leaving VersionedJsonCacheReader and generated/default reader paths unchanged.
 * Pointer polling is bounded by versionCacheMillis, not a Redis linearizability guarantee. Concurrent
 * expired callers poll independently, at most once per call. Source errors propagate; no hidden
 * stale-on-error/retry policy, single-flight wait or refresh-contention rejection.
 */
public final class L1VersionedJsonCacheReader implements AutoCloseable {
    private record Version(String value, long epoch, Object generation, long observedAt, long until, long freshUntil) {}
    private static final Version CLOSED = new Version(null, -1, null, 0, 0, 0);
    private volatile VersionedRedisSource source;
    private final String namespace, versionKey;
    private final long versionCacheMillis;
    private final CacheFreshness freshness;
    private final Clock clock;
    private final VersionedL1Cache l1;
    private final AtomicReference<Version> version = new AtomicReference<>(new Version(null, 1, new Object(), 0, 0, 0));

    public static L1VersionedJsonCacheReader create(RustCache cache, String namespace, long versionCacheMillis,
                                                   CacheFreshness freshness, VersionedL1Cache.Options options) {
        return new L1VersionedJsonCacheReader(VersionedRedisSource.nativeSource(Objects.requireNonNull(cache, "cache")),
                namespace, versionCacheMillis, freshness, options, Clock.systemUTC());
    }

    L1VersionedJsonCacheReader(VersionedRedisSource source, String namespace, long versionCacheMillis,
                              CacheFreshness freshness, VersionedL1Cache.Options options, Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.namespace = VersionedJsonKeys.normalizeNamespace(namespace);
        versionKey = VersionedJsonKeys.currentKey(this.namespace);
        this.versionCacheMillis = VersionedJsonKeys.requireVersionCacheMillis(versionCacheMillis);
        this.freshness = Objects.requireNonNull(freshness, "freshness");
        this.clock = Objects.requireNonNull(clock, "clock");
        l1 = new VersionedL1Cache(options);
    }

    public VersionedL1Cache.Lease newLease() { return l1.newLease(); }
    public long l1Hits() { return l1.hitCount(); }
    public long l1Misses() { return l1.missCount(); }
    public long l1ActiveBorrows() { return l1.activeBorrows(); }
    public long epoch() { return l1.epoch(); }
    public VersionedL1Cache.Options options() { return l1.options(); }

    /** No key String is constructed on a hit. Lease must be created/used/closed on the same thread. */
    public void getById(long id, VersionedL1Cache.Lease target) {
        read(id, null, null, null, Objects.requireNonNull(target, "target"), false);
    }

    public void getIndex(String indexName, String indexValue, VersionedL1Cache.Lease target) {
        String name = VersionedJsonKeys.sanitize(indexName);
        String value = VersionedJsonKeys.sanitize(indexValue);
        int hash = 31 * name.hashCode() + ':';
        for (int i = 0; i < value.length(); i++) hash = 31 * hash + value.charAt(i);
        read(hash, name, value, "idx:", Objects.requireNonNull(target, "target"), false);
    }

    public void getMeta(VersionedL1Cache.Lease target) {
        read("meta".hashCode(), "meta", null, "", Objects.requireNonNull(target, "target"), false);
    }

    /** Legacy results own a fresh payload copy. Their mutable bytes() cannot corrupt slab contents. */
    public CacheReadResult getById(long id) {
        return (CacheReadResult) read(id, null, null, null, null, false);
    }
    public CacheReadResult getIndex(String indexName, String value) {
        String name = VersionedJsonKeys.sanitize(indexName);
        String sanitizedValue = VersionedJsonKeys.sanitize(value);
        int hash = 31 * name.hashCode() + ':';
        for (int i = 0; i < sanitizedValue.length(); i++) hash = 31 * hash + sanitizedValue.charAt(i);
        return (CacheReadResult) read(hash, name, sanitizedValue, "idx:", null, false);
    }
    public CacheReadResult getMeta() { return (CacheReadResult) read("meta".hashCode(), "meta", null, "", null, false); }

    /** Pins the immutable hit directly, without allocating an intermediate thread-confined lease. */
    public CacheReadSnapshot snapshotById(long id) {
        return (CacheReadSnapshot) read(id, null, null, null, null, true);
    }
    public CacheReadSnapshot snapshotIndex(String indexName, String value) {
        String name = VersionedJsonKeys.sanitize(indexName);
        String sanitized = VersionedJsonKeys.sanitize(value);
        int hash = 31 * name.hashCode() + ':';
        for (int i = 0; i < sanitized.length(); i++) hash = 31 * hash + sanitized.charAt(i);
        return (CacheReadSnapshot) read(hash, name, sanitized, "idx:", null, true);
    }
    public CacheReadSnapshot snapshotMeta() {
        return (CacheReadSnapshot) read("meta".hashCode(), "meta", null, "", null, true);
    }

    private static Object deliver(String version, CacheReadResult.Status status, byte[] bytes,
                                  VersionedL1Cache.Lease target, boolean snapshot) {
        if (target != null) { target.result(version, status, bytes); return null; }
        CacheReadResult result = switch (status) {
            case HIT -> CacheReadResult.hit(bytes.clone());
            case STALE_HIT -> CacheReadResult.staleHit(bytes.clone());
            case STALE -> CacheReadResult.stale();
            case MISS -> CacheReadResult.miss();
            case CACHE_NOT_READY -> CacheReadResult.cacheNotReady();
        };
        return snapshot ? CacheReadSnapshot.takeOwnership(result, version) : result;
    }

    private Object read(long key, String text, String tail, String suffix, VersionedL1Cache.Lease target,
                        boolean snapshot) {
        if (target != null) target.requireDetached();
        Version observed = currentVersion();
        if (observed == null || observed.value() == null) {
            return deliver(null, CacheReadResult.Status.CACHE_NOT_READY, null, target, snapshot);
        }
        long now = clock.millis();
        boolean stale = stale(observed, now);
        if (stale && !freshness.serveStale()) {
            return deliver(observed.value(), CacheReadResult.Status.STALE, null, target, snapshot);
        }
        CacheReadResult.Status status = stale ? CacheReadResult.Status.STALE_HIT : CacheReadResult.Status.HIT;
        boolean admissibleKey = text == null
                || (long) text.length() + (tail == null ? 0 : 1L + tail.length()) <= options().maxKeyChars();
        if (admissibleKey) {
            if (snapshot) {
                CacheReadSnapshot hit = l1.snapshot(key, text, tail, observed.epoch(), now, observed.value(), status);
                if (hit != null) return hit;
            } else if (target == null) {
                CacheReadResult hit = l1.copy(key, text, tail, observed.epoch(), now, status);
                if (hit != null) return hit;
            } else if (l1.borrow(key, text, tail, observed.epoch(), now, observed.value(), status, target)) return null;
        }
        VersionedRedisSource remote = source;
        if (remote == null) throw new IllegalStateException("L1 reader is closed");
        String prefix = namespace + ":v:" + observed.value() + ':' + (text == null ? "id:" : suffix);
        String id = text == null ? Long.toString(key) : tail == null ? text : text + ':' + tail;
        CacheReadResult result = Objects.requireNonNull(remote.getVersionedJson(versionKey, prefix, id), "source result");
        if (!isCurrent(observed)) {
            return deliver(null, CacheReadResult.Status.CACHE_NOT_READY, null, target, snapshot);
        }
        now = clock.millis();
        stale = stale(observed, now) || result.isStale();
        if (stale && !freshness.serveStale()) {
            return deliver(observed.value(), CacheReadResult.Status.STALE, null, target, snapshot);
        }
        if (!result.hit()) return deliver(observed.value(), result.status(), null, target, snapshot);
        byte[] bytes = Objects.requireNonNull(result.bytes(), "source hit bytes");
        status = stale ? CacheReadResult.Status.STALE_HIT : CacheReadResult.Status.HIT;
        if (admissibleKey) {
            if (snapshot) {
                CacheReadSnapshot filled = l1.storeAndSnapshot(key, text, tail, observed.epoch(), now,
                        bytes, observed.value(), status);
                if (filled != null) return filled;
            } else if (l1.storeAndBorrow(key, text, tail, observed.epoch(), now, bytes, observed.value(), status, target)) {
                return target == null ? deliver(observed.value(), status, bytes, null, false) : null;
            }
        }
        if (!isCurrent(observed)) {
            return deliver(null, CacheReadResult.Status.CACHE_NOT_READY, null, target, snapshot);
        }
        return deliver(observed.value(), status, bytes, target, snapshot);
    }

    private boolean stale(Version observed, long now) {
        return freshness.maxAgeMillis() != 0
                && (now < observed.observedAt() || observed.freshUntil() == 0 || now > observed.freshUntil());
    }

    private boolean isCurrent(Version observed) {
        Version current = version.get();
        return current.generation() == observed.generation() && l1.epoch() == observed.epoch();
    }

    private static long nextEpoch(long epoch) {
        return epoch == Long.MAX_VALUE ? epoch : epoch + 1;
    }

    private Version currentVersion() {
        long now = clock.millis();
        Version previous = version.get();
        if (previous == CLOSED) throw new IllegalStateException("L1 reader is closed");
        if (previous.value() != null && previous.epoch() == l1.epoch()
                && now >= previous.observedAt() && now < previous.until()) return previous;
        VersionedRedisSource remote = source;
        if (remote == null) throw new IllegalStateException("L1 reader is closed");
        String encoded = remote.getString(versionKey).orElse(null);
        now = clock.millis();
        if (encoded != null && encoded.length() > 512) throw new IllegalStateException("Redis version exceeds bound");
        String value = VersionedJsonKeys.decodeCurrentVersion(encoded);
        if (value != null && value.isBlank()) value = null;
        if (value != null && value.length() > 256) throw new IllegalStateException("Redis version exceeds bound");
        long freshUntil = value == null ? 0 : freshness.freshUntilMillis(value, now);
        boolean changed = !Objects.equals(previous.value(), value) || now < previous.observedAt()
                || previous.freshUntil() != freshUntil;
        long nextEpoch = changed ? nextEpoch(previous.epoch()) : previous.epoch();
        long until = now > Long.MAX_VALUE - versionCacheMillis ? Long.MAX_VALUE : now + versionCacheMillis;
        Version next = new Version(value, nextEpoch, changed ? new Object() : previous.generation(), now, until, freshUntil);
        // Only a poll based on the still-current observation may publish. A slow loser cannot overwrite a winner.
        Version observed = version.compareAndSet(previous, next) ? next : version.get();
        if (observed == CLOSED) throw new IllegalStateException("L1 reader is closed");
        l1.advanceEpoch(observed.epoch());
        return observed;
    }

    /** Invalidates data in O(1) as well as forcing the next pointer refresh. */
    public void invalidateVersionCache() {
        Version invalidated = version.updateAndGet(old -> old == CLOSED ? CLOSED
                : new Version(null, nextEpoch(old.epoch()), new Object(), 0, 0, 0));
        if (invalidated != CLOSED) l1.advanceEpoch(invalidated.epoch());
    }

    /** Does not close the supplied Redis client. Borrowed payloads remain valid until their owner closes the lease. */
    @Override public void close() { version.set(CLOSED); source = null; l1.close(); }
}
