# java-rust-cache 0.7.0

`0.7.0` aligns the Redis JNI library with `rust-java-rest:4.2.0` and the shared REST ABI `26`
runtime while preserving Redis ABI `6`.

## What Changed

- The normal runtime JAR remains separate from the build-only cache codegen JAR.
- Codegen classes are assembled from an explicit build directory; processor implementation classes
  do not leak into the production runtime artifact.
- Windows and Linux native binaries are rebuilt from the same clean `rust-spring` source revision.
- Native provenance records REST ABI `26`, Dubbo ABI `7`, Redis ABI `6`, source revision, and
  platform SHA-256 hashes.
- Reader-only and writer-only starters keep disabled transport planes out of the active runtime.

## Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-cache</artifactId>
  <version>0.7.0</version>
</dependency>
```

Use `rust-java-rest:4.2.0` when the same process exposes REST endpoints.

## Compatibility

Redis key formats, projection namespaces, TTL validation, standalone/Sentinel/Cluster topology,
bounded pools, and generated reader APIs are unchanged. Applications must use the packaged DLL/SO
instead of copying an older REST ABI `24` binary.
