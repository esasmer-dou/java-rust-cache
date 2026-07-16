# java-rust-cache 0.4.0

`0.4.0` adds explicit read-only, write-only, and read-write runtime modes. The selected mode is
enforced in Java and also controls which native Redis transport planes are allocated.

## What's New

- Added `RedisAccessMode` with `read-only`, `write-only`, and `read-write` values.
- Added `reactor.cache.redis.access-mode` to properties and the builder API.
- A read-only client does not allocate write pools, write permits, or write topology state.
- A write-only client does not allocate read pools, read permits, or read topology state.
- Disallowed operations fail immediately in Java before crossing JNI.
- Lock helpers are initialized lazily and are unavailable in read-only mode.
- Added native ABI `6` and access-mode benchmark tooling.

## Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-cache</artifactId>
  <version>0.4.0</version>
</dependency>
```

Use `rust-java-rest:3.4.0` when REST and cache run in the same process. The aligned binary reports
REST ABI `24`, Dubbo ABI `6`, and Redis ABI `6`.

## Starting Configuration

Reader process:

```properties
reactor.cache.redis.access-mode=read-only
```

Writer or scheduler process:

```properties
reactor.cache.redis.access-mode=write-only
```

Use `read-write` only when one process genuinely performs both roles. Do not enable unused pools to
avoid configuration work; they consume connections, permits, topology state, and retained memory.

## Compatibility

- The default remains `read-write`, so existing applications preserve behavior.
- Existing reader, writer, versioned snapshot, and lock APIs remain available.
- Read-only/write-only modes require the aligned Redis ABI `6` native library.
