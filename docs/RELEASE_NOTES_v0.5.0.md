# java-rust-cache 0.5.0

`0.5.0` reduces application boilerplate while keeping Redis transport, pooling, topology handling,
timeouts, and backpressure in Rust.

## What Users Get

- Build-time projection registry generation in a separate `codegen` JAR.
- Declarative projection IDs and indexes shared by cache readers and writers.
- `CacheResponses` and explicit `CacheResponsePolicy` helpers for predictable REST cache-hit,
  not-found, and unavailable responses.
- Reusable projection reader and writer application wiring.
- Separate `VersionedJsonCacheReader` and `VersionedJsonCacheWriter` APIs, so read-only and write-only
  processes do not expose operations they should not use.

## Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-cache</artifactId>
  <version>0.5.0</version>
</dependency>
```

The aligned REST framework version is `rust-java-rest:4.0.0`. Redis native ABI remains `6`; no new
DLL or SO contract is introduced by this release.

## Migration From 0.4.x

| Removed compatibility API | Use instead |
|---------------------------|-------------|
| `RustCache.versionedJson(...)` / `VersionedJsonCache` | `versionedJsonReader(...)` or `versionedJsonWriter(...)` |
| `CacheResponses.json(result, code)` | `CacheResponses.json(result, CacheResponsePolicy...)` |
| legacy not-found policy shortcut | an explicit `CacheResponsePolicy` |

This separation is intentional. A cache reader should not allocate or expose writer state, and a
writer should not accidentally perform online reads.

## Production Notes

- Keep reader and writer processes separate.
- Use explicit Sentinel or Cluster topology settings when required.
- Set projection TTL longer than its refresh interval.
- Keep lock names projection-specific when replicas may refresh different projections in parallel.
- Tune pool, permit, timeout, and retry values with container RSS, p99, and error-rate measurements.
