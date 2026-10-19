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

## Native Socket Buffering

The synchronous Redis transport groups small RESP fragments before sending them.
It uses two fixed 1 KiB stack buffers for the active command or batch.
Large values pass through without a new value-sized staging buffer.
No extra Redis command, connection, worker or retry is added.
Response size limits, timeouts and read/write access rules still apply.
There is no new setting or Java API to enable.
