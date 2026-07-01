# java-rust-cache

Minimal Redis cache client for `rust-java-rest`.

This project intentionally does **not** use Jedis, Lettuce, Spring Data Redis, Netty, runtime reflection, or generic object serialization. Java owns the business decision; Rust owns Redis TCP I/O and RESP encoding/decoding through the existing `rust_hyper` native library.

The JAR includes the matching Windows x64 and Linux x64 native binaries. If `rust-java-rest` is already on the classpath, `java-rust-cache` reuses its native bridge; otherwise it extracts and loads its own packaged `rust_hyper` binary. A manual `java.library.path` is only needed for custom native builds.

By default, packaged native binaries are extracted under:

```text
${user.home}/.java-rust-cache/native/<binary-sha256-prefix>/
```

You can override this location with `reactor.cache.native.extract-dir` or `REACTOR_CACHE_NATIVE_EXTRACT_DIR`. In Kubernetes, point it to a writable application-owned directory or an `emptyDir` mount:

```yaml
env:
  - name: REACTOR_CACHE_NATIVE_EXTRACT_DIR
    value: /app/.java-rust-cache/native
```

Do not extract into the application classpath or inside the JAR. Those locations should be treated as read-only runtime artifacts.

## First Scope

- Redis standalone or Kubernetes Service DNS.
- RESP2 only.
- `GET`, `MGET`, `SET`, `SET NX`, `DEL`, `EXISTS`, `INCR`, `PEXPIRE`, `PTTL`.
- Separate read/write connection pools.
- Separate read/write max-in-flight limits.
- Bounded response size.

Cluster, Sentinel, Pub/Sub, Streams, user-defined Lua/Functions, TLS, and generic `Object` cache APIs are intentionally outside the first release. `increment(key, ttlMillis)` uses one fixed internal Redis script so the counter and expiry are applied atomically.

## Usage

Maven dependency:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-cache</artifactId>
  <version>0.1.0-rc3</version>
</dependency>
```

```java
import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.core.RustCaches;

try (RustCache cache = RustCaches.create()) {
    byte[] cached = cache.reader().getBytes("customer:1001");
    if (cached != null) {
        return;
    }

    byte[] payload = "{\"id\":1001}".getBytes(StandardCharsets.UTF_8);
    cache.writer().setBytes("customer:1001", payload, 60_000);
}
```

## Versioned JSON Snapshot API

Use this path when one writer process precomputes JSON and one or more REST pods read it without rebuilding DTO graphs.

```java
try (RustCache cache = RustCaches.create()) {
    var reader = cache.versionedJsonReader(
            "crm.customer",
            500 // cache current snapshot version in the reader for 500 ms
    );

    var result = reader.getById(1001);
    if (result.hit()) {
        byte[] json = result.bytes();
        // In rust-java-rest handlers, return RawResponse.json(json).
    }
}
```

Writer process:

```java
try (RustCache cache = RustCaches.create()) {
    var writer = cache.versionedJsonWriter(
            "crm.customer",
            128 // write Redis keys in batches of 128
    );

    writer.refreshSnapshotWithLock("crm.customer.refresh", 300_000, 600_000, snapshot -> {
        snapshot.putById(1001, "{\"id\":1001}".getBytes(StandardCharsets.UTF_8));
        snapshot.putIndex("customer-no", "CUST-1001", "{\"id\":1001}".getBytes(StandardCharsets.UTF_8));
    });
}
```

If you do not pass the two tuning values, the defaults are conservative:

| Setting | Default | What it controls |
| --- | ---: | --- |
| `versionCacheMillis` | `1000` | How long a reader keeps the `namespace:current` pointer in Java memory before checking Redis again. Lower it when you need faster publish visibility; raise it for very hot read endpoints. |
| `batchSize` | `256` | How many keys the writer sends per Redis batch. Valid range is `1..4096`. Lower it for memory-first pods; raise carefully if the writer is too slow and Redis latency is low. |

Key parts used by the versioned API are now delimiter-safe. `:` and non-ASCII/control bytes are percent-encoded, and each key part is capped at 256 UTF-8 bytes. Keep the same namespace in writer and reader; do not manually build these internal keys in application code.

## Package Layout

The public classes are grouped by responsibility:

| Package | Purpose |
| --- | --- |
| `com.reactor.rust.cache.core` | Main client, factory, and cache exception. |
| `com.reactor.rust.cache.config` | Runtime configuration and property/env binding. |
| `com.reactor.rust.cache.api` | Read/write contracts and cache read result model. |
| `com.reactor.rust.cache.lock` | Redis-backed bounded lock abstraction for scheduled writers. |
| `com.reactor.rust.cache.versioned` | Versioned JSON snapshot reader/writer API. |
| `com.reactor.rust.cache.internal.nativebridge` | JNI bridge to the native Rust Redis data plane. Treat as internal. |

## Configuration

All keys can be provided as Java system properties, environment variables, or a `Properties` object.

| Property | Env | Default |
| --- | --- | --- |
| `reactor.cache.redis.host` | `REACTOR_CACHE_REDIS_HOST` | `127.0.0.1` |
| `reactor.cache.redis.port` | `REACTOR_CACHE_REDIS_PORT` | `6379` |
| `reactor.cache.redis.username` | `REACTOR_CACHE_REDIS_USERNAME` | empty |
| `reactor.cache.redis.password` | `REACTOR_CACHE_REDIS_PASSWORD` | empty |
| `reactor.cache.redis.database` | `REACTOR_CACHE_REDIS_DATABASE` | `0` |
| `reactor.cache.redis.connect-timeout-ms` | `REACTOR_CACHE_REDIS_CONNECT_TIMEOUT_MS` | `500` |
| `reactor.cache.redis.read-timeout-ms` | `REACTOR_CACHE_REDIS_READ_TIMEOUT_MS` | `500` |
| `reactor.cache.redis.write-timeout-ms` | `REACTOR_CACHE_REDIS_WRITE_TIMEOUT_MS` | `500` |
| `reactor.cache.redis.read-connections` | `REACTOR_CACHE_REDIS_READ_CONNECTIONS` | `4` |
| `reactor.cache.redis.write-connections` | `REACTOR_CACHE_REDIS_WRITE_CONNECTIONS` | `2` |
| `reactor.cache.redis.max-read-inflight` | `REACTOR_CACHE_REDIS_MAX_READ_INFLIGHT` | `128` |
| `reactor.cache.redis.max-write-inflight` | `REACTOR_CACHE_REDIS_MAX_WRITE_INFLIGHT` | `64` |
| `reactor.cache.redis.max-response-bytes` | `REACTOR_CACHE_REDIS_MAX_RESPONSE_BYTES` | `1048576` |
| `reactor.cache.native.extract-dir` | `REACTOR_CACHE_NATIVE_EXTRACT_DIR` | `${user.home}/.java-rust-cache/native` |

## Verification

The normal unit test suite does not need Redis:

```powershell
mvn -q test
```

To verify the real native Redis data plane, start Redis and opt into the integration test:

```powershell
docker run -d --name java-rust-cache-redis-test -p 16379:6379 redis:8.2.1-alpine3.22

mvn -q test `
  "-Dreactor.cache.redis.integration=true" `
  "-Dreactor.cache.redis.integration.port=16379"

docker rm -f java-rust-cache-redis-test
```

This integration test covers `GET`, `MGET`, `SET`, `SET NX`, `setMany`, `DEL`, `EXISTS`, `INCR`, `PEXPIRE`, `PTTL`, lock acquire/renew/release, and native metrics. If this test fails after a Java package refactor, check the Rust JNI export names first.

Before promoting an RC to stable, also run the Redis restart and short load gates:

```powershell
docker run -d --name java-rust-cache-redis-test -p 16379:6379 redis:8.2.1-alpine3.22

mvn -q test `
  "-Dreactor.cache.redis.reconnect-gate=true" `
  "-Dreactor.cache.redis.integration.container=java-rust-cache-redis-test" `
  "-Dreactor.cache.redis.integration.port=16379"

mvn -q test `
  "-Dreactor.cache.redis.load-gate=true" `
  "-Dreactor.cache.redis.integration.port=16379" `
  "-Dreactor.cache.redis.integration.read-connections=8" `
  "-Dreactor.cache.redis.integration.write-connections=8" `
  "-Dreactor.cache.redis.integration.max-read-inflight=64" `
  "-Dreactor.cache.redis.integration.max-write-inflight=64" `
  "-Dreactor.cache.redis.load-gate.threads=8" `
  "-Dreactor.cache.redis.load-gate.operations-per-thread=500"

docker rm -f java-rust-cache-redis-test
```

The reconnect gate intentionally allows the first operation after restart to fail. The production expectation is that the failed socket is discarded and the next operation opens a fresh Redis connection.
