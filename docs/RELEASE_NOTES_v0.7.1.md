# java-rust-cache 0.7.1

`0.7.1` aligns the Redis JNI library with `rust-java-rest:4.3.0` and the clean native source
revision used by that release.

## User Impact

- The public cache API, topology configuration, read/write modes, Sentinel support, Cluster support,
  locks, TTL behavior, and scheduler integrations are unchanged.
- REST ABI remains `26`, Dubbo ABI remains `7`, and Redis ABI remains `6`.
- Windows and Linux native binaries were rebuilt and their SHA-256 provenance was refreshed.
- Use `java-rust-cache:0.7.1` with `rust-java-rest:4.3.0` when both are in the same process.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-cache</artifactId>
  <version>0.7.1</version>
</dependency>
```

Always load the native binary packaged with this version. Do not copy a DLL/SO from an older
release.
