# java-rust-cache

[English](https://github.com/esasmer-dou/java-rust-cache/blob/master/README.md) | [Turkish](https://github.com/esasmer-dou/java-rust-cache/blob/master/README.tr.md)

Minimal Redis cache client for `rust-java-rest`.

This project intentionally does **not** use Jedis, Lettuce, Spring Data Redis, Netty, runtime reflection, or generic object serialization. Java owns the business decision; Rust owns Redis TCP I/O and RESP encoding/decoding through the existing `rust_hyper` native library.

The JAR includes the matching Windows x64 and Linux x64 native binaries. If `rust-java-rest` is already on the classpath, `java-rust-cache` reuses its native bridge; otherwise it extracts and loads its own packaged `rust_hyper` binary. A manual `java.library.path` is only needed for custom native builds.

Cluster routing requires Redis native ABI `2`; Sentinel master refresh requires ABI `3`; fenced
snapshot publish requires ABI `4`; async GET and native JSON response handles require ABI `5`. If
the same application also uses `rust-java-rest`, use the current aligned line,
`rust-java-rest:3.3.1` or newer, so the framework native bridge and cache library use the same
binary contract. The packaged provenance manifest records REST ABI `23`, Dubbo ABI `5`, Redis ABI
`5`, source revision, and platform SHA-256 hashes. Startup rejects a stale or mismatched binary.

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

Container image note: the packaged Linux binary is built on a manylinux2014/glibc 2.17 baseline. It is intended to run on common glibc-based images including CentOS 7+, CentOS 8, UBI 8/9, Ubuntu/Jammy, and Semeru/OpenJ9 images. If you replace the packaged native binary with a custom build, build it on the oldest Linux base your platform supports.

## First Scope

- Redis standalone for local/dev or explicitly accepted single-node deployments.
- Redis Sentinel for master discovery and failover.
- Redis Cluster for slot-based routing, `MOVED`/`ASK` redirect handling, and node-level connection pools.
- RESP2 only.
- `GET`, `MGET`, `SET`, `SET NX`, `DEL`, `EXISTS`, `INCR`, `PEXPIRE`, `PTTL`.
- Separate read/write connection pools.
- Separate read/write max-in-flight limits.
- Bounded response size.

Pub/Sub, Streams, user-defined Lua/Functions, TLS, and generic `Object` cache APIs remain intentionally outside this library. `increment(key, ttlMillis)` uses one fixed internal Redis script so the counter and expiry are applied atomically.

Production rule: do not run critical services against a single standalone Redis unless the business explicitly accepts cache outage. Use Sentinel when you need one writable primary with failover. Use Cluster when you need horizontal Redis scaling and slot distribution.

## Usage

Maven dependency:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-cache</artifactId>
  <version>0.3.1</version>
</dependency>
```

### GitHub Packages Access

`java-rust-cache` is published through GitHub Packages. Maven must authenticate before it can download the package; this is GitHub Packages' normal access model.

Add the package repository to the consuming project's `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/esasmer-dou/java-rust-cache</url>
  </repository>
</repositories>
```

Then add a GitHub token with `read:packages` permission to your Maven `settings.xml`:

```xml
<servers>
  <server>
    <id>github</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>${env.GITHUB_PACKAGES_TOKEN}</password>
  </server>
</servers>
```

Set the token before running Maven:

```powershell
$env:GITHUB_PACKAGES_TOKEN="YOUR_TOKEN_WITH_READ_PACKAGES"
mvn -q dependency:get "-Dartifact=com.reactor:java-rust-cache:0.3.1"
```

If Maven returns `401 Unauthorized`, first check that the token has `read:packages`, the environment variable is visible to the shell, and the `<server><id>` value matches the repository id in `pom.xml`.

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
| `com.reactor.rust.cache.projection` | Declarative projection settings for reader/writer samples: namespace, TTL, interval, and lock names. |
| `com.reactor.rust.cache.scheduler` | Projection refresh scheduler and result model for writer processes. |
| `com.reactor.rust.cache.json` | Small JSON writer base for explicit low-allocation JSON builders. |
| `com.reactor.rust.cache.jdbc` | Optional JDBC/Hikari helper classes for writer-side database reads. |
| `com.reactor.rust.cache.internal.nativebridge` | JNI bridge to the native Rust Redis data plane. Treat as internal. |

## Declarative Projection Settings

Use `com.reactor.rust.cache.projection` when one application writes several Redis projections and
another application reads the same projection list. The library resolves property overrides,
namespace names, writer intervals, lock names, and safe TTL values. Your application still owns the
business transformation.

Writer process example:

```java
public final class CacheWriterApplication {
    public static void main(String[] args) {
        ProjectionWriterApplication.run(
                "cache-writer.properties",
                "sample.writer",
                CacheWriterModule.INSTANCE);
    }
}
```

The named module keeps the business wiring explicit:

```java
public void configure(ProjectionWriterApplication.ModuleContext context) {
        CacheProperties properties = context.properties();
        Repository repository = context.manage(Repository.open(properties));
        RustCache cache = context.manage(RustCaches.create(properties.asProperties()));
        CustomerMaterializer materializer = new CustomerMaterializer(repository, cache, properties);
        context.refresher(materializer::refreshProjection);
}
```

Use the builder only for advanced scheduler customization. The simple launcher still owns shutdown,
startup rollback, loaded properties, bounded scheduler threads, and managed resources.

Reader example:

```java
List<CacheReaderProjectionSettings> projections =
        CacheReaderProjectionSettings.resolveAll(properties, "sample.cache.customer");

VersionedJsonProjectionReaders readers = VersionedJsonProjectionReaders.create(
        cache, projections, properties.getLong("sample.cache.customer.version-cache-ms"));

CacheReadResult customer = readers.getById("detail", customerId);
```

Use `CacheProperties` for classpath defaults, `reactor.config.file` overlays, environment variables,
and `-D` system properties. Implement `ProjectionPropertySource` yourself only when configuration
comes from a different source.

```java
public final class AppProperties implements ProjectionPropertySource {
    public String get(String key) { return requiredProperty(key); }
    public String getOptional(String key) { return optionalProperty(key); }
    public String getRuntimeOverride(String key) { return System.getProperty(key); }
    public String getFileOptional(String key) { return filePropertyOrNull(key); }
}
```

BEST: use projection settings to remove repeated config parsing. Keep the JSON shape, DB query, and
cache key business decisions explicit in your application code. ANTI-PATTERN: a generic reflection
mapper that guesses Redis keys from DTO class names.

## Writer-Side Boilerplate Helpers

`java-rust-cache` also includes small helper classes for cache processes. These helpers are
deliberately explicit:

- `CacheProperties` applies one predictable property precedence rule for reader and writer apps.
- `ProjectionWriterApplication` owns scheduler startup, run-once mode, shutdown hooks, startup
  rollback, and managed resource cleanup.
- `VersionedJsonProjectionMaterializer` maps explicit projection names to business writers and owns
  fenced lock execution.
- `VersionedJsonProjectionReaders` owns the named reader registry and controlled not-ready result.
- `ProjectionRefreshScheduler` schedules configured projections and handles run-once mode, Redis
  lock result logging, and TTL/config warnings.
- `JsonWriter` provides UTF-8 JSON escaping and primitive field helpers. Your domain writer still
  decides every JSON field.
- `JdbcRepository` centralizes connection/query/page/lifecycle boilerplate around a `DataSource`.
- `HikariDataSources` can create a Hikari pool from `sample.db.*`-style properties, but Hikari is an
  optional dependency. Reader-only services do not need to load it. Pool tuning keys have small
  built-in defaults, so a writer can start with only JDBC URL, driver, username and password.

Example:

```java
VersionedJsonProjectionMaterializer materializer =
        VersionedJsonProjectionMaterializer.builder(cache, projectionSettings, batchSize)
            .projection("detail", this::writeDetail)
            .projection("campaign", this::writeCampaign)
            .build();
```

BEST: let the library own lifecycle boilerplate. Keep SQL, row mapping, JSON shape, and cache key
business decisions in your application.

## Configuration

All keys can be provided as Java system properties, environment variables, or a `Properties` object.

| Property | Env | Default |
| --- | --- | --- |
| `reactor.cache.redis.topology` | `REACTOR_CACHE_REDIS_TOPOLOGY` | `standalone` |
| `reactor.cache.redis.nodes` | `REACTOR_CACHE_REDIS_NODES` | empty |
| `reactor.cache.redis.sentinel.master-name` | `REACTOR_CACHE_REDIS_SENTINEL_MASTER_NAME` | empty |
| `reactor.cache.redis.sentinel.username` | `REACTOR_CACHE_REDIS_SENTINEL_USERNAME` | empty |
| `reactor.cache.redis.sentinel.password` | `REACTOR_CACHE_REDIS_SENTINEL_PASSWORD` | empty |
| `reactor.cache.redis.sentinel.master-check-ms` | `REACTOR_CACHE_REDIS_SENTINEL_MASTER_CHECK_MS` | `1000` |
| `reactor.cache.redis.cluster.max-redirects` | `REACTOR_CACHE_REDIS_CLUSTER_MAX_REDIRECTS` | `5` |
| `reactor.cache.redis.topology-refresh-ms` | `REACTOR_CACHE_REDIS_TOPOLOGY_REFRESH_MS` | `30000` |
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

### Topology Recipes

Standalone is mainly for local development:

```properties
reactor.cache.redis.topology=standalone
reactor.cache.redis.host=127.0.0.1
reactor.cache.redis.port=6379
```

Sentinel is the right fit when Redis has one writable primary and replicas. The library asks Sentinel for the current master, opens native TCP pools to that master, and refreshes discovery after socket errors or `READONLY` responses:

```properties
reactor.cache.redis.topology=sentinel
reactor.cache.redis.nodes=redis-sentinel-0:26379,redis-sentinel-1:26379,redis-sentinel-2:26379
reactor.cache.redis.sentinel.master-name=mymaster
reactor.cache.redis.sentinel.master-check-ms=1000
reactor.cache.redis.username=app-cache-user
reactor.cache.redis.password=${REDIS_PASSWORD}
```

`reactor.cache.redis.sentinel.master-check-ms` is intentionally separate from `reactor.cache.redis.topology-refresh-ms`. Sentinel failover needs a cheap, frequent check of the current master, while Cluster slot topology refresh can stay slower. Keep `1000` as a safe starting point; lower it only if failover recovery time is more important than a small increase in Sentinel polling.

If Sentinel itself has different ACL credentials, set them separately:

```properties
reactor.cache.redis.sentinel.username=sentinel-user
reactor.cache.redis.sentinel.password=${REDIS_SENTINEL_PASSWORD}
```

Cluster is the right fit when Redis is sharded. The library loads `CLUSTER SLOTS`, routes each key to the owning node, handles `MOVED`/`ASK`, and groups cross-slot `MGET`/`setMany` work safely:

```properties
reactor.cache.redis.topology=cluster
reactor.cache.redis.nodes=redis-cluster-0:6379,redis-cluster-1:6379,redis-cluster-2:6379
reactor.cache.redis.cluster.max-redirects=5
reactor.cache.redis.topology-refresh-ms=30000
reactor.cache.redis.database=0
```

Redis Cluster does not support database selection; `reactor.cache.redis.database` must stay `0`. If you need multi-key locality, use Redis hash tags in your own key design, for example `customer:{1001}:profile` and `customer:{1001}:orders`. Keys with the same tag route to the same slot.

Kubernetes note: Cluster nodes must announce addresses reachable from the application pod. If Redis returns pod-local or container-local addresses in `CLUSTER SLOTS`/`MOVED`, routing will fail regardless of the client.

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

The same integration test can be pointed at Sentinel or Cluster:

```powershell
mvn -q test `
  "-Dreactor.cache.redis.integration=true" `
  "-Dreactor.cache.redis.integration.topology=sentinel" `
  "-Dreactor.cache.redis.integration.nodes=127.0.0.1:26379" `
  "-Dreactor.cache.redis.integration.sentinel.master-name=mymaster"

mvn -q test `
  "-Dreactor.cache.redis.integration=true" `
  "-Dreactor.cache.redis.integration.topology=cluster" `
  "-Dreactor.cache.redis.integration.nodes=127.0.0.1:17000"
```

These are smoke gates. They prove that the native bridge can talk to the selected topology, but they do not prove failover behavior.

For a production promotion, run the topology gates against real multi-node Redis topologies. The tests are intentionally opt-in because they require Docker networks or externally managed Redis environments:

```powershell
# Requires a 3-Sentinel setup and a master name that can fail over to a replica.
mvn -q test `
  "-Dtest=RedisTopologyGateTest#sentinelRefreshesExistingClientAfterFailover" `
  "-Dreactor.cache.redis.sentinel-failover-gate=true" `
  "-Dreactor.cache.redis.integration.nodes=redis-sentinel-0:26379,redis-sentinel-1:26379,redis-sentinel-2:26379" `
  "-Dreactor.cache.redis.integration.sentinel.master-name=mymaster"

# Requires a real Redis Cluster with at least 3 masters.
mvn -q test `
  "-Dtest=RedisTopologyGateTest#clusterHandlesMovedAndAskRedirects" `
  "-Dreactor.cache.redis.cluster-redirect-gate=true" `
  "-Dreactor.cache.redis.integration.nodes=redis-cluster-0:6379,redis-cluster-1:6379,redis-cluster-2:6379"

# Requires a Redis Cluster with replicas so one master can be stopped and replaced.
mvn -q test `
  "-Dtest=RedisTopologyGateTest#clusterRefreshesExistingClientAfterReplicaFailover" `
  "-Dreactor.cache.redis.cluster-failover-gate=true" `
  "-Dreactor.cache.redis.integration.nodes=redis-cluster-0:6379,redis-cluster-1:6379,redis-cluster-2:6379,redis-cluster-3:6379,redis-cluster-4:6379,redis-cluster-5:6379"
```

Passing these gates means the existing Java client object can recover its native topology view after Sentinel failover, Redis Cluster `MOVED`/`ASK` redirects, and Cluster master failover. Still run a sustained load gate under your real Kubernetes CPU/memory limits before calling the profile stable.

On Docker Desktop, avoid starting Maven dependency download or full Surefire bootstrap while Redis Sentinel is running. Sentinel can enter `TILT` mode when the container runtime pauses its event loop, and failover will be delayed by design. For local live gates, pre-run `mvn -DskipTests test-compile dependency:build-classpath` and then execute `com.reactor.rust.cache.core.RedisTopologyLiveGateMain` from `target/test-classes`. This uses the same gate logic with much lower runtime noise.

Before promoting a new build, also run the Redis restart and short load gates:

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

If the integration Redis uses ACL or `requirepass`, add
`-Dreactor.cache.redis.integration.username=...` and
`-Dreactor.cache.redis.integration.password=...` to both Maven commands. Omit the username for a
password-only Redis configuration.

The reconnect gate intentionally allows the first operation after restart to fail. The production expectation is that the failed socket is discarded and the next operation opens a fresh Redis connection.
