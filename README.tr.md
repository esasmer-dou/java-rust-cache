# java-rust-cache

[English](https://github.com/esasmer-dou/java-rust-cache/blob/master/README.md) | [Turkish](https://github.com/esasmer-dou/java-rust-cache/blob/master/README.tr.md)

`rust-java-rest` ile birlikte çalışmak için hazırlanmış minimum yüzeyli Redis cache istemcisidir.

Bu proje bilinçli olarak Jedis, Lettuce, Spring Data Redis, Netty, runtime reflection veya generic object serialization kullanmaz. Java iş kararını verir. Rust Redis TCP I/O ve RESP encode/decode işini native tarafta yapar.

JAR içinde Windows x64 ve Linux x64 native binary dosyaları bulunur. Aynı uygulamada `rust-java-rest` zaten varsa `java-rust-cache` aynı native bridge çizgisiyle çalışır. Yoksa kendi paketlediği `rust_hyper` binary dosyasını çıkarıp yükler. Özel native build kullanmıyorsanız `java.library.path` vermeniz gerekmez.

Cluster desteği için Redis native ABI version `2` gerekir. Sentinel master failover refresh için native ABI version `3` gerekir. Aynı uygulama `rust-java-rest` de kullanıyorsa `rust-java-rest:3.2.7` veya daha yeni aynı çizgiyi kullanın. Böylece framework native bridge ve cache library ABI uyumlu kalır.

Varsayılan native binary çıkarma dizini:

```text
${user.home}/.java-rust-cache/native/<binary-sha256-prefix>/
```

Bu dizini `reactor.cache.native.extract-dir` veya `REACTOR_CACHE_NATIVE_EXTRACT_DIR` ile değiştirebilirsiniz. Kubernetes üzerinde bu değeri uygulamanın yazabildiği bir dizine veya `emptyDir` mount altına verin:

```yaml
env:
  - name: REACTOR_CACHE_NATIVE_EXTRACT_DIR
    value: /app/.java-rust-cache/native
```

Native binary dosyasını classpath içine veya JAR içine runtime sırasında yazmaya çalışmayın. Bu alanları read-only runtime artifact olarak düşünün.

Container notu: Paketlenen Linux binary `manylinux2014/glibc 2.17` tabanı ile uyumludur. CentOS 7+, CentOS 8, UBI 8/9, Ubuntu/Jammy ve Semeru/OpenJ9 gibi glibc tabanlı image'larda çalışması hedeflenir. Kendi native binary'nizi üretirseniz, platformunuzun desteklediği en eski Linux tabanında build alın.

## İlk Kapsam

Bu library şu işleri hedefler:

- Local/dev veya bilinçli kabul edilmiş tek node senaryolar için Redis standalone.
- Master discovery ve failover için Redis Sentinel.
- Slot routing, `MOVED`/`ASK` redirect ve node bazlı connection pool için Redis Cluster.
- RESP2 protokolü.
- `GET`, `MGET`, `SET`, `SET NX`, `DEL`, `EXISTS`, `INCR`, `PEXPIRE`, `PTTL`.
- Ayrı read/write connection pool.
- Ayrı read/write max-in-flight sınırı.
- Bounded response size.

Pub/Sub, Streams, user-defined Lua/Functions, TLS ve generic `Object` cache API bu library'nin dışında bırakıldı. `increment(key, ttlMillis)` tek bir internal Redis script kullanır. Böylece counter ve expiry atomik uygulanır.

Production kuralı: Kritik servisleri tek standalone Redis ile çalıştırmayın. İş birimi cache outage riskini açıkça kabul ediyorsa standalone kullanılabilir. Failover gereken tek primary yapıda Sentinel seçin. Redis tarafında yatay ölçek ve slot dağılımı gerekiyorsa Cluster seçin.

## Kullanım

Maven dependency:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-cache</artifactId>
  <version>0.2.4</version>
</dependency>
```

### GitHub Packages Erişimi

`java-rust-cache` GitHub Packages üzerinden yayınlanır. Maven paketi indirebilmek için authenticate olmalıdır. Bu GitHub Packages'ın normal erişim modelidir.

Consumer projenin `pom.xml` dosyasına repository ekleyin:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/esasmer-dou/java-rust-cache</url>
  </repository>
</repositories>
```

Sonra `read:packages` yetkili GitHub token değerini Maven `settings.xml` dosyasına ekleyin:

```xml
<servers>
  <server>
    <id>github</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>${env.GITHUB_PACKAGES_TOKEN}</password>
  </server>
</servers>
```

Maven çalıştırmadan önce token environment variable olarak verilir:

```powershell
$env:GITHUB_PACKAGES_TOKEN="YOUR_TOKEN_WITH_READ_PACKAGES"
mvn -q dependency:get "-Dartifact=com.reactor:java-rust-cache:0.2.4"
```

`401 Unauthorized` alırsanız önce üç şeyi kontrol edin:

- Token `read:packages` yetkisine sahip mi?
- Environment variable aynı shell içinde görünüyor mu?
- `pom.xml` içindeki repository `<id>` değeri ile `settings.xml` içindeki server `<id>` değeri aynı mı?

Basit kullanım:

```java
import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.core.RustCaches;
import java.nio.charset.StandardCharsets;

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

Bu yol şu senaryo için doğru seçimdir:

- Bir writer process JSON veriyi önceden hazırlar.
- Bir veya daha fazla REST pod bu JSON'u Redis'ten okur.
- REST pod DTO graph kurmadan `RawResponse` veya byte tabanlı response döner.

Reader örneği:

```java
try (RustCache cache = RustCaches.create()) {
    var reader = cache.versionedJsonReader(
            "crm.customer",
            500
    );

    var result = reader.getById(1001);
    if (result.hit()) {
        byte[] json = result.bytes();
        // rust-java-rest handler içinde RawResponse.json(json) dönebilirsiniz.
    }
}
```

Writer örneği:

```java
try (RustCache cache = RustCaches.create()) {
    var writer = cache.versionedJsonWriter(
            "crm.customer",
            128
    );

    writer.refreshSnapshotWithLock("crm.customer.refresh", 300_000, 600_000, snapshot -> {
        snapshot.putById(1001, "{\"id\":1001}".getBytes(StandardCharsets.UTF_8));
        snapshot.putIndex("customer-no", "CUST-1001", "{\"id\":1001}".getBytes(StandardCharsets.UTF_8));
    });
}
```

Varsayılan tuning değerleri konservatiftir:

| Ayar | Varsayılan | Ne işe yarar? |
| --- | ---: | --- |
| `versionCacheMillis` | `1000` | Reader'ın `namespace:current` pointer değerini Java memory içinde ne kadar tutacağını belirler. Daha hızlı publish görünürlüğü için düşürün. Çok sıcak read endpoint için artırabilirsiniz. |
| `batchSize` | `256` | Writer'ın Redis'e bir batch içinde kaç key göndereceğini belirler. Memory öncelikli podlarda düşürün. Redis latency düşük ve writer yavaşsa dikkatli artırın. |

Versioned API key parçaları delimiter-safe üretilir. `:` ve non-ASCII/control byte değerleri percent-encode edilir. Her key parçası 256 UTF-8 byte ile sınırlıdır. Writer ve reader aynı namespace değerini kullanmalıdır. Internal Redis key'leri uygulama kodunda elle üretmeyin.

## Paket Yapısı

| Package | Amaç |
| --- | --- |
| `com.reactor.rust.cache.core` | Ana client, factory ve cache exception. |
| `com.reactor.rust.cache.config` | Runtime config ve property/env binding. |
| `com.reactor.rust.cache.api` | Read/write contract ve cache read result modeli. |
| `com.reactor.rust.cache.lock` | Scheduled writer için Redis-backed bounded lock. |
| `com.reactor.rust.cache.versioned` | Versioned JSON snapshot reader/writer API. |
| `com.reactor.rust.cache.projection` | Reader/writer sample için declarative projection ayarları. Namespace, TTL, interval ve lock name burada çözülür. |
| `com.reactor.rust.cache.scheduler` | Projection refresh scheduler ve sonuç modeli. |
| `com.reactor.rust.cache.json` | Low-allocation explicit JSON builder için küçük base class. |
| `com.reactor.rust.cache.jdbc` | Writer-side database read için opsiyonel JDBC/Hikari helper sınıfları. |
| `com.reactor.rust.cache.internal.nativebridge` | Rust Redis data plane için JNI bridge. Internal kabul edin. |

## Declarative Projection Settings

Bir uygulama birden fazla Redis projection yazıyor, başka bir uygulama aynı projection listesini okuyorsa `com.reactor.rust.cache.projection` kullanın. Library property override, namespace, writer interval, lock name ve güvenli TTL değerlerini çözer. Business dönüşüm kodu yine uygulamanızda kalır.

Writer örneği:

```java
List<CacheWriterProjectionSettings> projections =
        CacheWriterProjectionSettings.resolveAll(properties, "sample.writer");

for (CacheWriterProjectionSettings projection : projections) {
    scheduler.schedule(projection, () -> materializer.refresh(projection.name()));
}
```

Reader örneği:

```java
List<CacheReaderProjectionSettings> projections =
        CacheReaderProjectionSettings.resolveAll(properties, "sample.cache.customer");

CustomerCacheService service = new CustomerCacheService(cache, projections);
```

Property source örneği:

```java
public final class AppProperties implements ProjectionPropertySource {
    public String get(String key) { return requiredProperty(key); }
    public String getOptional(String key) { return optionalProperty(key); }
    public String getRuntimeOverride(String key) { return System.getProperty(key); }
    public String getFileOptional(String key) { return filePropertyOrNull(key); }
}
```

BEST: Tekrar eden config parse kodunu library'ye bırakın. JSON şekli, SQL, cache key tasarımı ve business kararları uygulamada açık kalsın.

ANTI-PATTERN: DTO class adından Redis key tahmin eden generic reflection mapper yazmak. Bu hem allocation üretir hem de runtime davranışını görünmez yapar.

## Writer-Side Boilerplate Helpers

`java-rust-cache`, cache-writer process'leri için küçük helper sınıflar da taşır. Bunlar bilinçli olarak explicit tutuldu:

- `ProjectionRefreshScheduler`: Projection schedule, run-once mode, Redis lock sonucu loglama ve TTL/config uyarılarını yönetir.
- `JsonWriter`: UTF-8 JSON escaping ve primitive field helper sağlar. Domain JSON alanlarına uygulama karar verir.
- `JdbcRepository`: `DataSource` etrafında connection/query/page/lifecycle boilerplate kodunu azaltır.
- `HikariDataSources`: `sample.db.*` benzeri property'lerden Hikari pool oluşturabilir. Hikari opsiyonel dependency'dir. Reader-only servislerin Hikari yüklemesi gerekmez.

Örnek:

```java
ProjectionRefreshScheduler scheduler = ProjectionRefreshScheduler.builder()
        .settings(projectionSettings)
        .refresher(materializer::refreshProjection)
        .schedulerThreads(properties.getInt("sample.writer.scheduler-threads"))
        .runOnce(properties.getBoolean("sample.writer.run-once"))
        .threadNamePrefix("cache-writer")
        .build();
```

BEST: Lifecycle boilerplate kodunu library'ye bırakın. SQL, row mapping, JSON shape ve cache key business kararlarını uygulama kodunda açık tutun.

## Configuration

Tüm key'ler Java system property, environment variable veya `Properties` object üzerinden verilebilir.

| Property | Env | Varsayılan |
| --- | --- | --- |
| `reactor.cache.redis.topology` | `REACTOR_CACHE_REDIS_TOPOLOGY` | `standalone` |
| `reactor.cache.redis.nodes` | `REACTOR_CACHE_REDIS_NODES` | boş |
| `reactor.cache.redis.sentinel.master-name` | `REACTOR_CACHE_REDIS_SENTINEL_MASTER_NAME` | boş |
| `reactor.cache.redis.sentinel.username` | `REACTOR_CACHE_REDIS_SENTINEL_USERNAME` | boş |
| `reactor.cache.redis.sentinel.password` | `REACTOR_CACHE_REDIS_SENTINEL_PASSWORD` | boş |
| `reactor.cache.redis.sentinel.master-check-ms` | `REACTOR_CACHE_REDIS_SENTINEL_MASTER_CHECK_MS` | `1000` |
| `reactor.cache.redis.cluster.max-redirects` | `REACTOR_CACHE_REDIS_CLUSTER_MAX_REDIRECTS` | `5` |
| `reactor.cache.redis.topology-refresh-ms` | `REACTOR_CACHE_REDIS_TOPOLOGY_REFRESH_MS` | `30000` |
| `reactor.cache.redis.host` | `REACTOR_CACHE_REDIS_HOST` | `127.0.0.1` |
| `reactor.cache.redis.port` | `REACTOR_CACHE_REDIS_PORT` | `6379` |
| `reactor.cache.redis.username` | `REACTOR_CACHE_REDIS_USERNAME` | boş |
| `reactor.cache.redis.password` | `REACTOR_CACHE_REDIS_PASSWORD` | boş |
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

## Topology Reçeteleri

Standalone çoğunlukla local development için uygundur:

```properties
reactor.cache.redis.topology=standalone
reactor.cache.redis.host=127.0.0.1
reactor.cache.redis.port=6379
```

Sentinel, Redis tarafında tek writable primary ve replica yapısı varsa doğru seçimdir. Library Sentinel'den güncel master bilgisini alır, native TCP pool'ları o master'a açar, socket error veya `READONLY` cevabı sonrası discovery bilgisini yeniler:

```properties
reactor.cache.redis.topology=sentinel
reactor.cache.redis.nodes=redis-sentinel-0:26379,redis-sentinel-1:26379,redis-sentinel-2:26379
reactor.cache.redis.sentinel.master-name=mymaster
reactor.cache.redis.sentinel.master-check-ms=1000
reactor.cache.redis.username=app-cache-user
reactor.cache.redis.password=${REDIS_PASSWORD}
```

`reactor.cache.redis.sentinel.master-check-ms`, `reactor.cache.redis.topology-refresh-ms` değerinden ayrıdır. Sentinel failover için ucuz ve sık master kontrolü gerekir. Cluster slot topology refresh daha seyrek kalabilir. Güvenli başlangıç değeri `1000` ms'dir.

Sentinel için ayrı ACL gerekiyorsa:

```properties
reactor.cache.redis.sentinel.username=sentinel-user
reactor.cache.redis.sentinel.password=${REDIS_SENTINEL_PASSWORD}
```

Cluster, Redis tarafında shard kullanıyorsanız doğru seçimdir. Library `CLUSTER SLOTS` bilgisini yükler, key'i owner node'a yönlendirir, `MOVED`/`ASK` cevabını işler ve cross-slot `MGET`/`setMany` işlerini güvenli gruplar:

```properties
reactor.cache.redis.topology=cluster
reactor.cache.redis.nodes=redis-cluster-0:6379,redis-cluster-1:6379,redis-cluster-2:6379
reactor.cache.redis.cluster.max-redirects=5
reactor.cache.redis.topology-refresh-ms=30000
reactor.cache.redis.database=0
```

Redis Cluster database selection desteklemez. `reactor.cache.redis.database` değeri `0` kalmalıdır. Çoklu key locality gerekiyorsa Redis hash tag kullanın:

```text
customer:{1001}:profile
customer:{1001}:orders
```

Aynı tag aynı slota gider.

Kubernetes notu: Cluster node'ları uygulama pod'undan erişilebilir adresleri announce etmelidir. Redis `CLUSTER SLOTS` veya `MOVED` içinde pod-local ya da container-local adres döndürürse client doğru olsa bile routing başarısız olur.

## Doğrulama

Normal unit test Redis istemez:

```powershell
mvn -q test
```

Gerçek native Redis data plane için Redis başlatıp integration test'i açın:

```powershell
docker run -d --name java-rust-cache-redis-test -p 16379:6379 redis:8.2.1-alpine3.22

mvn -q test `
  "-Dreactor.cache.redis.integration=true" `
  "-Dreactor.cache.redis.integration.port=16379"

docker rm -f java-rust-cache-redis-test
```

Bu test `GET`, `MGET`, `SET`, `SET NX`, `setMany`, `DEL`, `EXISTS`, `INCR`, `PEXPIRE`, `PTTL`, lock acquire/renew/release ve native metrics akışını kapsar. Java package refactor sonrası bu test kırılırsa önce Rust JNI export isimlerini kontrol edin.

Sentinel veya Cluster smoke test:

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

Bunlar smoke gate'tir. Native bridge'in seçilen topology ile konuşabildiğini gösterir. Failover davranışını tek başına kanıtlamaz.

Production promotion için gerçek çok node'lu Redis topology üzerinde şu gate'leri çalıştırın:

```powershell
mvn -q test `
  "-Dtest=RedisTopologyGateTest#sentinelRefreshesExistingClientAfterFailover" `
  "-Dreactor.cache.redis.sentinel-failover-gate=true" `
  "-Dreactor.cache.redis.integration.nodes=redis-sentinel-0:26379,redis-sentinel-1:26379,redis-sentinel-2:26379" `
  "-Dreactor.cache.redis.integration.sentinel.master-name=mymaster"

mvn -q test `
  "-Dtest=RedisTopologyGateTest#clusterHandlesMovedAndAskRedirects" `
  "-Dreactor.cache.redis.cluster-redirect-gate=true" `
  "-Dreactor.cache.redis.integration.nodes=redis-cluster-0:6379,redis-cluster-1:6379,redis-cluster-2:6379"

mvn -q test `
  "-Dtest=RedisTopologyGateTest#clusterRefreshesExistingClientAfterReplicaFailover" `
  "-Dreactor.cache.redis.cluster-failover-gate=true" `
  "-Dreactor.cache.redis.integration.nodes=redis-cluster-0:6379,redis-cluster-1:6379,redis-cluster-2:6379,redis-cluster-3:6379,redis-cluster-4:6379,redis-cluster-5:6379"
```

Bu gate'lerin geçmesi mevcut Java client object'in Sentinel failover, Redis Cluster `MOVED`/`ASK` redirect ve Cluster master failover sonrası native topology bilgisini yenileyebildiğini gösterir. Yine de stable demeden önce gerçek Kubernetes CPU/memory limitleri altında load gate çalıştırın.

Docker Desktop üzerinde Redis Sentinel çalışırken Maven dependency download veya tam Surefire bootstrap başlatmayın. Container runtime event loop duraksarsa Sentinel `TILT` mode'a girebilir. Local live gate için önce şunu çalıştırın:

```powershell
mvn -DskipTests test-compile dependency:build-classpath
```

Sonra `com.reactor.rust.cache.core.RedisTopologyLiveGateMain` sınıfını `target/test-classes` üzerinden çalıştırın. Aynı gate logic daha düşük runtime gürültüsüyle koşar.

Yeni build'i promote etmeden önce Redis restart ve kısa load gate de çalıştırın:

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

Reconnect gate restart sonrası ilk operation'ın fail etmesine izin verir. Production beklentisi şudur: bozuk socket atılır ve sonraki operation yeni Redis connection açar.
