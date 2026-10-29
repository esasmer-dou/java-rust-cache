# Freshness And Bounded JSON

[English](functional-operations.md) | [Turkish](functional-operations.tr.md)

Available in java-rust-cache 0.8.0. Existing reader behavior remains the default.

## Choose A Policy

| Need | Setting | Result |
| --- | --- | --- |
| Keep existing behavior | max age 0 | No age rejection |
| Reject old customer data | max age 120000, serve stale false | STALE result |
| Permit old catalogue data | max age 120000, serve stale true | STALE_HIT result |

Age starts when snapshot construction starts, not when publication finishes.
Keep clocks synchronized. Unknown or future timestamps are stale.
TTL still removes data. Age checks cannot recover expired data.
Use a schema version in the namespace, for example `customer.v2.detail`.
This is an explicit migration boundary, not automatic JSON schema validation.

```java
var reader = VersionedJsonCacheReader.create(
    cache, "customer.v2.detail", 1000, new CacheFreshness(120000, false));
CacheReadResult result = reader.getById(42);
```

The check reuses the cached version pointer. It adds no Redis command.
A cached pointer can remain visible for `versionCacheMillis`.
Use zero for that setting only when the extra pointer lookup is acceptable.

`CacheResponses.json` preserves the existing response contract.
`CacheResponses.problem` returns 404 for a missing item and 503 for missing/old snapshots.
Allowed stale hits carry `X-Cache-Stale: true` on the problem adapter.

## Write JSON Without A Full String

Extend `Utf8JsonWriter` instead of `JsonWriter` when measurements justify it.
It writes directly to a bounded byte array. It does not retain a thread-local buffer.
The default limit is 1048576 bytes. Use `super(maxBytes)` to choose another bound.
The final exact-sized array is still copied; this is not a zero-copy claim.

```java
final class ProductWriter extends Utf8JsonWriter {
    byte[] product(long id, String name) {
        var out = json(128).append('{');
        field(out, "id", id).append(',');
        stringField(out, "name", name).append('}');
        return utf8(out);
    }
}
```

For atomic DB updates, cursor exports and refresh notifications, use the explicit
`rust-java-jdbc` APIs. Add that dependency explicitly in database applications;
cache readers do not inherit it. An outbox notice and a DB mutation commit together.
Redis publication remains a separate operation. Keep the existing fenced writer lock.

### Explicit SWAR Literals

The SWAR API described here is a source-checkout addition, not a newly released
Maven package or an accepted performance improvement.

`Utf8JsonWriter` does not automatically use SWAR. Its ordinary field/quote methods
remain unchanged. A measured writer can compose `Utf8JsonBuffer.writePrebakedLong`
with the existing escaping methods for **trusted, pre-escaped literal bytes only**:

```java
var out = json(128).writePrebakedLong(0x3a22656d616e227bL); // {"name":
quote(out, name).append('}');
return utf8(out);
```

Each word writes eight bytes, low byte first, regardless of native byte order or
alignment. Bounds are checked before any part of the word is appended, including
the replacement byte for a pending unmatched high surrogate. Overflow throws
`IllegalStateException`; never publish the partially built document. Dynamic values
must still pass through `quote`, not be packed as unchecked application input.
Null string values retain the existing empty-string contract. Buffer growth and the
final byte-array copy remain; no zero-allocation or universal throughput claim applies.

This cache API still uses a little-endian byte-array VarHandle. It is unchanged,
separate from REST's `JsonBufferWriter`, and has no automatic generator integration.
REST's opt-in generator chooses by the complete UTF-8 `"field":` prefix length:

- **8..16 bytes:** one `writePrebakedLiteral(first, last, len)` call, with no retained
  tail bytes for that prefix. The first and last eight-byte words are packed low byte
  first, with matching overlap. With enough space, absolute `ByteBuffer.putLong`
  stores write at the prefix start and `start + len - 8`, potentially overlapping.
  Length 8 uses only one store and ignores `last`.
- **More than 16 bytes:** the existing full-word and 0..7-byte tail-table paths remain.
- **Fewer than 8 bytes:** the existing byte-table path remains.

REST reverses each stored word with `Long.reverseBytes` for big-endian output; it
uses no VarHandle ByteBuffer view and preserves position, limit and byte order.
Its fused method checks cursor and required-byte integer additions before any write
or counter update, rejects lengths outside 8..16, and uses a limit-respecting bytewise
fallback when space is short while counting the full required length. These are REST
semantics, not changes to the bounded cache byte-array API above.

REST's build-time `reactor.json.swar` property is off by default; enable it with a
clean rebuild of the annotated DTO artifact using `-Dreactor.json.swar=true`.
See REST `docs/native-views.md` for the compiler configuration.

That option does not change this API, existing cache writers or already serialized
cache reads. REST's separate generated-literal route passed its focused four-pair
EmaTech RPS/p99/+3 MiB matrix. That result does not establish a cache-read speedup
or memory reduction for this explicit byte-array API. Run the focused cache tests
from the workspace root with the matching dependencies installed:

```sh
mvn -B -ntp -f java-rust-cache/pom.xml -Dtest=SwarJsonWriteTest,Utf8JsonWriterTest,Utf8JsonBufferResetTest test
```

## Native Socket Buffering

The synchronous Redis transport groups small RESP fragments before sending them.
It uses two fixed 1 KiB stack buffers for the active command or batch.
Large values pass through without a new value-sized staging buffer.
No extra Redis command, connection, worker or retry is added.
Response size limits, timeouts and read/write access rules still apply.
There is no new setting or Java API to enable.
