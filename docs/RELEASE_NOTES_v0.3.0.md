# java-rust-cache 0.3.0

`0.3.0` adds a declarative projection runtime while keeping Redis protocol, topology and I/O work
inside the Rust native data plane.

## What's New

- Added `CacheProperties` for layered classpath, environment, JVM and external-overlay settings.
- Added `ProjectionWriterApplication` with bounded scheduling, projection-level locks and owned
  resource shutdown.
- Added `VersionedJsonProjectionMaterializer` and `VersionedJsonProjectionReaders` so applications
  declare projection settings instead of rebuilding namespace/version boilerplate.
- Added native cache response handles and asynchronous GET completion to reduce Java heap copies.
- Hardened standalone, Sentinel and Cluster behavior, including redirect handling, failover checks,
  fenced publication and pending-operation cleanup.
- Added native release provenance for source commit `2a9929cac331`.

## Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-cache</artifactId>
  <version>0.3.0</version>
</dependency>
```

## Compatibility

- Existing `RustCache`, versioned reader/writer and lock APIs remain available.
- Redis native ABI is now `5`; use the DLL/SO packaged in `0.3.0`.
- Projection helpers are optional. Low-level APIs remain available for specialized workloads.
