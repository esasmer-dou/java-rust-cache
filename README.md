# java-rust-cache

Minimal Redis cache client for `rust-java-rest`.

This project intentionally does **not** use Jedis, Lettuce, Spring Data Redis, Netty, runtime reflection, or generic object serialization. Java owns the business decision; Rust owns Redis TCP I/O and RESP encoding/decoding through the existing `rust_hyper` native library.

## First Scope

- Redis standalone or Kubernetes Service DNS.
- RESP2 only.
- `GET`, `MGET`, `SET`, `SET NX`, `DEL`, `EXISTS`, `INCR`, `PEXPIRE`, `PTTL`.
- Separate read/write connection pools.
- Separate read/write max-in-flight limits.
- Bounded response size.

Cluster, Sentinel, Pub/Sub, Streams, user-defined Lua/Functions, TLS, and generic `Object` cache APIs are intentionally outside the first release. `increment(key, ttlMillis)` uses one fixed internal Redis script so the counter and expiry are applied atomically.

## Usage

```java
try (RustCache cache = RustCaches.create()) {
    byte[] cached = cache.reader().getBytes("customer:1001");
    if (cached != null) {
        return;
    }

    byte[] payload = "{\"id\":1001}".getBytes(StandardCharsets.UTF_8);
    cache.writer().setBytes("customer:1001", payload, 60_000);
}
```

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
