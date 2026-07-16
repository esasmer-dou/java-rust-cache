# java-rust-cache 0.4.1

`0.4.1` is a native compatibility refresh for applications that combine Redis cache and Dubbo in
the same Java/Rust process.

## What Changed

- Refreshed packaged Windows x64 and Linux x64 native libraries from the current clean source
  revision.
- Updated the shared native provenance line to REST ABI `24`, Dubbo ABI `7`, and Redis ABI `6`.
- Updated release workflow validation so a stale ABI `6` Dubbo binary cannot be published.

## Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-cache</artifactId>
  <version>0.4.1</version>
</dependency>
```

Use `rust-java-rest:3.4.1` when REST and cache share a process. Use
`java-rust-dubbo:0.4.1` when that process also uses native Dubbo.

## Compatibility

- Redis ABI remains `6`.
- Cache APIs, access modes, Sentinel support, Cluster support, locks, TTL behavior, and projection
  helpers are unchanged.
- Cache-only applications receive the rebuilt native binary but no Redis behavior change.
