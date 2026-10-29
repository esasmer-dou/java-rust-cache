# Opt-In Versioned L1

[English](versioned-l1.md) | [Turkish](versioned-l1.tr.md)

This guide describes the current source checkout. These APIs are not included in
the published `0.9.0` package. Do not enable them in an application that still uses
that package.

`L1VersionedJsonCacheReader` is a separate reader. Existing
`RustCache.versionedJsonReader(...)`, `VersionedJsonCacheReader`, generated readers,
and `VersionedJsonProjectionReaders.create(...)` keep their existing paths. Merely
configuring L1 settings does not redirect an existing reader.

Generated readers can now opt in explicitly with
`@GenerateProjectionReader(..., allowL1 = true)`. Their factory uses
`ConfiguredJsonProjectionReaders` and selects each projection once at startup.
Absent/zero `l1.max-entries` selects the Redis-only implementation. This path keeps
existing `CacheReadResult` method signatures and defensive payload copies. A method
can instead declare `CacheReadSnapshot` to transfer read ownership without creating
a Java payload array on an L1 hit. Use `CacheResponses.ownedJson(snapshot, missCode,
notReadyCode)` in a synchronous REST handler; the response writer closes the owner
after writing, while native buffer retry retains the same snapshot. Do not close it
in the handler after handing it to this helper. If you never hand it off, close it
yourself. An owner is for one response, not a reusable response cache. The generated
REST configuration closes the L1 readers through its generated `@PreDestroy` method.
Existing annotations with `allowL1 = false` (the default) keep the original generated
source path, without new L1 lookups or wrappers. The sample reader includes a separate
`config/l1.properties` overlay; its normal configuration does not enable L1.

L1 reduces Redis calls for repeated keys. It also reserves local memory and loads extra
runtime classes. Higher RPS does not automatically mean lower process RSS. Start with
Redis-only when minimum memory is the main goal. Enable L1 only for projections whose
traffic and memory results justify that local copy.

The generated snapshot methods use `L1VersionedJsonCacheReader.snapshotById`,
`snapshotIndex` or `snapshotMeta` directly. A hit pins its slot without allocating
a temporary `Lease` and transferring it. Reusable lease methods remain available
for thread-confined processing. Known version metadata is preserved on cold/oversize
snapshot results too; a Redis-only reader may not expose a version.
Snapshot close/write state uses an inline atomic field (`VarHandle`), not a separate
`AtomicInteger` object per response. The handle is bound once when this opt-in class
initializes; request processing does not perform field lookup or reflection.

On the first read or a refill, Redis still returns a Java byte array. If the value
fits an available L1 slot, it is copied into the slab once. The response snapshot
then owns that slot's pin directly; it does not create another heap payload copy.
A refill remains a miss, not a hit. Oversized values and busy slots still use an
owned, defensive heap snapshot. Neither response truncation nor cache growth is
used as a fallback. Do not size the Java heap as though every read were an L1 hit.

## Startup

For a REST route that can use either backend, declare `CacheReadValue` with
`allowL1 = true`. Use `CacheResponses.valueJson(value, missCode, notReadyCode)` or
`CacheResponses.valueProblem(value)`. Backend selection happens once at startup.
Redis-only reads keep the existing `CacheReadResult` and raw byte response path.
L1 reads retain the managed snapshot path, including overflow retry and cancellation.
Do not serialize `CacheReadValue` as a DTO or share it between responses.

```properties
app.cache.projections=detail
app.cache.namespace=customer
app.cache.l1.max-entries=64
app.cache.l1.max-entry-bytes=8192
app.cache.l1.max-key-chars=256
app.cache.l1.max-bytes=1048576
app.cache.l1.max-age-ms=1000
app.cache.max-age-ms=60000
app.cache.serve-stale=false
```

Absent or zero `l1.max-entries` disables the opt-in setting. Other L1 settings are
read only when entries are nonzero. Each option supports the existing precedence:
projection-specific runtime, global runtime, projection-specific file, global file.
For example `app.cache.detail.l1.max-bytes` is projection-specific.

Only the new `resolveAllWithL1(...)` API reads these L1 properties. The legacy
`CacheReaderProjectionSettings` remains a three-component record
`(name, namespace, freshness)`, including its existing constructors, record patterns
and `resolveAll(...)` logic. Old resolution does not query any `.l1.*` keys or
allocate L1 options/wrappers, even if those properties are present or malformed.

Choose a wrapped projection once at startup, then explicitly create its L1 reader:

```java
L1ProjectionSettings setting = CacheReaderProjectionSettings
        .resolveAllWithL1(properties, "app.cache").stream()
        .filter(value -> value.base().name().equals("detail"))
        .findFirst().orElseThrow();

L1VersionedJsonCacheReader reader = VersionedJsonProjectionReaders
        .createL1Reader(cache, setting, 1000); // Pointer cache interval, milliseconds.
// Application shutdown: close reader before closing the separately owned cache client.
```

`L1ProjectionSettings(base, options)` is a separate immutable wrapper. Its `base()`
is the unchanged legacy record; absent/zero max-entries yields null `options()`.
`createL1Reader(...)` accepts this wrapper and rejects disabled options. It does
not overload or alter the existing legacy registry factory.

Alternatively call
`L1VersionedJsonCacheReader.create(cache, namespace, versionCacheMillis, freshness, options)`.
There is no hidden reader registration, reflection, worker thread, TLS, retry, or JNI addition.

## Reusable Borrow

Each existing worker creates its own lease on that worker thread, then reuses it:

```java
try (var lease = reader.newLease()) {
    for (long id : ids) {
        reader.getById(id, lease);
        try {
            if (lease.status() == CacheReadResult.Status.HIT
                    || lease.status() == CacheReadResult.Status.STALE_HIT) {
                consumeReadOnlyBytes(lease); // get(index) or copyTo(reusableArray, offset).
            }
        } finally {
            lease.close(); // Mandatory before the next borrow, including error paths.
        }
    }
}
```

The lease implements `CacheReadLease`. It is a checked read-only view, not a
`ByteBuffer` whose limit could be reset to expose neighboring keys/values. Create,
read and close it on the same thread; cross-thread reads/active close fail rather
than unpinning a writer's memory. An active lease must be closed even for MISS,
STALE or CACHE_NOT_READY. Source errors leave the target detached.

While active, its slot is pinned and cannot be overwritten. Version invalidation
or reader close does not mutate an already-borrowed snapshot. Close releases the
pin and all payload/cache/version references. After close its accessors throw;
reuse makes the same object represent a NEW borrow. Never keep an old alias as an
immutable snapshot or use it after close/reuse. There is no claim of stale-alias
identity tracking. No raw direct-memory address or buffer escapes.

There is no lease Cleaner or automatic leak recovery. Forgetting to close a lease
can pin a fixed slot indefinitely, reducing admission capacity until the reader
is discarded. This cannot grow the fixed slab: when both ways are pinned, reads
fall back to owned copies. Those copies still allocate per miss; bounded retained
cache memory does not make leaked leases operationally harmless. Owned-copy
methods pin a slot only while copying, then unpin it in `finally`.
They do not create a temporary lease per call. A hit still creates the returned
byte array and `CacheReadResult`; it is not the zero-allocation lease API.

`getIndex(name, value, lease)` and `getMeta(lease)` use the same ownership rules.
For an already-normalized ASCII index such as `segment` / `standard`, a hit
compares the two key parts directly against the slab. It does not create a joined
key String. A miss creates the Redis key as before. Normalization of Turkish
characters, emoji or reserved characters may still allocate. Full key comparison,
not just the hash, keeps colliding keys separate. Both numeric and ASCII index
warm paths have allocation tests. `lease.bytes()` always allocates a defensive copy. The ordinary
`getById(id)`, `getIndex(...)` and `getMeta()` methods return `CacheReadResult` with
a new caller-owned payload array: its mutable `bytes()` cannot mutate L1 storage.
They are NOT promised to cost <=32 bytes per call.

## Bounds

`VersionedL1Cache.Options` contains entries, maximum payload bytes, maximum full
String-key UTF-16 units, entry age, and TOTAL storage `maxBytes`. It validates
before allocating. The direct slab has fixed two-way slots, each reserving
`maxEntryBytes + 2 * maxKeyChars` bytes. Fixed Java metadata/reader ownership is
conservatively charged as `8192 + 96 * entries` bytes. Thus:

```text
slabBytes = entries * (maxEntryBytes + 2 * maxKeyChars)
totalBytesEstimate = slabBytes + 8192 + 96 * entries
totalBytesEstimate <= maxBytes
```

Defaults: 64 entries, 8192-byte payload, 256-unit keys, 1000ms L1 age, 1 MiB total
budget. The default slab is 557056 bytes; total conservative estimate is 571392
bytes. Limits: power-of-two entries 2..65536, payload 1..262144 bytes, String keys
1..4096 UTF-16 units, nonnegative age, total budget 1 byte..64 MiB. The unsafe
65536 x 262144 Cartesian slab is rejected, not allocated. Payload length is an
`int`, not a packed 16-bit length. Total multiplication uses `long` validation.

The estimate covers cache-owned fixed storage, not application-owned leases,
returned/cold-path copies, the existing Redis client, provider/clock internals,
JVM allocator overhead outside the allowance, or process RSS. Each explicitly
created reader has its own budget; the application must sum multiple readers'
budgets. It does not prove a +3 MiB container/RSS acceptance gate.

There are no retained payload arrays or key Strings in L1 slots. String keys are
copied as full UTF-16 units into the slab; numeric IDs retain their complete
64-bit value in fixed metadata. Hashes select a set only, never establish identity.
Writers can claim only an unpinned slot; readers CAS-pin before checking identity
and epoch. If both ways are pinned, a payload is oversized, or an index identity
exceeds the key admission bound, the valid cache result is returned through an
owned heap-copy fallback, without L1 admission.

`reader.close()` drops source/pointer/slab ownership, not the supplied Redis
client. Active leases keep their slab alive until their owner closes them. There
is no safe standard-Java21 explicit direct-buffer free API here: reclamation is
GC-based, not immediate native-memory release. No Unsafe, reflection cleaner,
preview API, forced GC or background maintenance is used.

## Versions, Freshness And Failures

Pointer observations use the existing fenced-version decoding and timestamp
freshness rules. Timestamp parsing occurs once per pointer refresh, not on every
hit. `Clock.millis()` provides the test seam and avoids `Instant` allocations.
Missing pointers return CACHE_NOT_READY; missing data returns MISS. Neither is
positive-cached. STALE with `serveStale=false` returns before touching L1 or data
Redis. Allowed STALE_HIT can reuse L1 and remains marked stale.

On an observed version change, one monotonically increasing 64-bit epoch changes
in O(1), with no slab clear or entry walk. Returning to an earlier version name
does not revive earlier entries. At Long.MAX_VALUE the epoch saturates and L1
permanently bypasses instead of wrapping; replace the reader to enable L1 again.
`invalidateVersionCache()` also invalidates data in O(1).

The pointer poll interval permits the last observed version until the next poll.
Use interval 0 to poll every read, or explicit invalidation when the application
knows publication happened. Interval 0 includes Redis/parsing allocations and is
not the zero-allocation warm-loop configuration. Entry `l1.max-age-ms` separately
expires at `age >= limit`; 0 disables this entry-age expiration. Snapshot freshness
keeps the existing `now > created + maxAge` boundary. Clock rollback forces refresh
and prevents reuse of entries apparently inserted in the future.

The existing bridge has no atomic versioned-json JNI operation. The package-private
source adapter uses `RustCache.getString/getBytes`, with data keys containing the
observed snapshot version. This is NOT an atomic Redis transaction/latest-pointer
guarantee. Concurrent in-flight reads may hold an earlier snapshot; an observed
generation change during a miss discards that old miss result as CACHE_NOT_READY.
Concurrent cold misses may fetch more than once. Each caller whose pointer interval
has expired polls Redis at most once, independently. Interval 0 polls on every call.
There is no single-flight lock, waiting/spinning for I/O, contention rejection,
stale-while-refresh grace or hidden retry. A concurrent burst can produce one pointer
poll per caller, bounded by the existing Redis client's admission/timeouts, not one
global poll. Configure that client for the application's concurrency budget.

A poll publishes only if the pointer state captured BEFORE its I/O is still current.
A losing poll discards its value and uses the winning observation, so a slow old
poll cannot overwrite a concurrently published newer observation. Same-version
refreshes share generation identity and do not cancel in-flight data reads.
Explicit invalidation, a changed version, clock rollback or a timestamp-validation
transition (such as a future timestamp becoming valid) creates a new generation;
close remains terminal. Even after long-epoch exhaustion, generation identity is
not reused, and the slab stays bypassed. No poll can repopulate a closed reader or
overwrite an explicit invalidation marker. Real generation changes during data
fetch may still return CACHE_NOT_READY, deliberately discarding the old result.
Redis failures propagate, never silently serving stale-on-error. Existing client
timeouts apply; this adapter adds no automatic retries.

## Startup-Selected HTTP Responder

The original `CacheResponses.json(...)` methods are unchanged. They do not read
the new property or acquire a per-response disabled-feature branch. Applications
that want this behavior explicitly select a responder once at startup:

```properties
reactor.cache.responses.mark-stale-hit=true
```

```java
CacheJsonResponder responder = CacheJsonResponder.fromProperties(
        properties, CacheResponsePolicy.production("customer_not_found"));
// Request path uses a copied, caller-owned CacheReadResult, not a lease after close.
return responder.json(reader.getById(id));
```

`properties` is the application's `ProjectionPropertySource` (including
`CacheProperties`). The factory reads the property once: absent/blank means false;
otherwise only case-insensitive `true` or `false` is accepted. It captures the
immutable policy, not the properties source. Changing configuration later requires
replacing the responder. `CacheJsonResponder.create(policy, boolean)` skips property
resolution entirely. Nothing invokes these factories for existing callers.

The disabled implementation delegates directly to the existing JSON method. The
enabled implementation adds `X-Cache-Stale: true` only to STALE_HIT, not to refused
STALE, MISS or CACHE_NOT_READY. Existing body/status/error policy stays unchanged.
The existing `problem(...)` adapter keeps its existing behavior. REST response
construction, header maps and the legacy payload copy can allocate; this adapter
is not the reusable-lease allocation gate or a zero-copy HTTP transport API.

## Verification Scope

The focused tests cover full-key collisions, epochs/ABA, exact budgets, 65536 and
262144-byte payloads, reader pins/concurrent eviction, lease ownership/close,
freshness/clock boundaries, source failures, pointer races, settings and copies.
The allocation gate uses ThreadMXBean with large and fine-grained positive controls and 100000 warm
hits for each of the numeric-ID and ASCII segment/index paths, with a reusable lease, one-byte payload, fixed nonallocating Clock,
cached pointer and no entry-age expiry. Source fetch counts use the deterministic
package-private seam; this is not a real Redis/native transport benchmark.
Cold fetches, pointer polls, legacy copies and index normalization can allocate.
No release/RSS/RPS/p99 claim follows from these unit tests.

The earlier numeric-only 61-test focused selection on Semeru/OpenJ921.0.2 produced 60 passes and
one explicit allocation-gate skip, with no failures/errors: its first batch of
100 retained byte[32] objects reported 0 bytes. Previous OpenJ9 0-byte warm-loop
readings are withdrawn as allocation certification; the large control alone is
insufficient. The gate requires eight batches of 100 retained byte[32] allocations,
each reporting 4000..6400 bytes, otherwise it skips. A preallocated Object[100]
is rooted through a static volatile field outside timing, cleared in finally.

The same 61 focused tests passed on GraalVM HotSpot21.0.6 with zero failures,
errors or skips. Each of eight fine-calibration batches reported exactly 4800 bytes.
The subsequent 100000 warmed reusable-lease hits reported 0 allocated bytes, with
a 262160-byte large control and one data-source fetch. This calibrated result is
specific to that HotSpot run, not an OpenJ9 allocation certificate.
The compatibility regressions compile/run the original three-component Java21
record pattern without preview, verify the exact component count/names/types,
and record the original 13 legacy resolver lookups with no L1 queries, even when
L1 properties are malformed. The projection suite now contains seven tests.
The 64-caller tests exercise expired intervals and interval 0 without refresh
rejection; slow old polls, same-version in-flight misses, invalidation/close and
clock/future-timestamp transitions have deterministic regressions. These are
focused unit/seam results, not live Redis or endpoint load-test acceptance.
