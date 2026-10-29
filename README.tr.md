# java-rust-cache

[English](README.md) | [Turkish](README.tr.md)

## 0.9.0 İle Başlayın

İsteğe bağlı sürümlü L1 okuma ve sahipliği izlenen JSON yanıtları eklendi. Desteklenen yollarda Redis tekrarlarını ve Java payload dizilerini azaltır. Snapshot koruması, projection kilitleri ve okuma/yazma ayrımı korunur. Native dosya, belirlenen ana dizine sınırlı bir tamponla çıkarılır.

[Kullanım rehberi](docs/versioned-l1.md) · [Sürüm notları](docs/RELEASE_NOTES_v0.9.0.md)


Yeni kullanım rehberi: [Açık ve sınırlı işlemler](docs/functional-operations.tr.md).

Yayımlanmamış kaynak hazırlığı: [Native platformlar ve başlangıç uyumluluğu](docs/native-availability.tr.md).
Korunan ARM temel dosyaları yeni isteğe bağlı özelliklerin güncel kaynak derlemesi değildir.

Kaynak kod rehberi: [Opsiyonel versiyonlu L1](docs/versioned-l1.tr.md). Bu yeni
API'ler yayımlanmış `0.9.0` paketinde yoktur. Mevcut okuyucular değişmez.

Kaynak kod REST entegrasyonu: [Herkese açık GET için native edge kuralları](https://github.com/esasmer-dou/rust-java-rest/blob/master/NATIVE_EDGE.tr.md).
Küçük bir okuma istemcisi açıkça seçilir. Java guard'ları kendiliğinden atlanmaz.

Kaynak kodda public kurallar şu tanımla kaydedilebilir:
`@EnableRustCache(publicEdgePolicies = PublicCatalogHandler.class,
publicEdgeEnabledProperty = "sample.public.edge.enabled")`. Kurulum kodu derlemede
üretilir. Reflection kullanılmaz. Property yalnız başlangıçta okunur; verilmezse
`false` kabul edilir. Bu seçim yapılmayan mevcut annotation'lar önceki başlangıç
kodunu üretir. [Ayrı public katalog örneğini](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/docs/public-edge.tr.md)
inceleyin. Bu annotation seçenekleri yayımlanmış `0.9.0` paketinde henüz yoktur.

[![Sürüm](https://img.shields.io/badge/sürüm-0.9.0-blue.svg)](https://github.com/esasmer-dou/java-rust-cache/releases/tag/v0.9.0)
[![REST çizgisi](https://img.shields.io/badge/rust--java--rest-4.8.0-green.svg)](https://github.com/esasmer-dou/rust-java-rest/releases/tag/v4.8.0)

`rust-java-rest` ile birlikte çalışmak için hazırlanmış minimum yüzeyli Redis cache istemcisidir.

Bu proje bilinçli olarak Jedis, Lettuce, Spring Data Redis, Netty, runtime reflection veya generic object serialization kullanmaz. Java iş kararını verir. Rust Redis TCP I/O ve RESP encode/decode işini native tarafta yapar.

JAR içinde Windows x64, Linux x64, Linux ARM64 ve macOS ARM64 native dosyaları bulunur. Aynı uygulamada `rust-java-rest` zaten varsa aynı native bridge kullanılır. Yoksa cache kendi paketlediği `rust_hyper` dosyasını çıkarıp yükler. Özel native build kullanmıyorsanız `java.library.path` gerekmez.

## Beş Dakikada Karar Verin

| Process | Access mode | Önerilen API |
| --- | --- | --- |
| Hazır JSON okuyan REST API | `read-only` | Generated projection reader ve native response handle |
| PostgreSQL'den Redis'e yazan scheduler | `write-only` | Projection writer ve fenced snapshot publish |
| Gerçekten hem okuyan hem yazan yönetim aracı | `read-write` | Açık `RustCache` işlemleri |

Normal CQRS yapısında reader ve writer process'lerini ayırın. `read-only`, native write plane açmaz.
`write-only`, native read plane açmaz. Böylece kullanılmayan pool yalnız Java'da kapatılmaz; native
tarafta da oluşturulmaz.

Business kodunda Redis key'lerini elle birleştirmek yerine versioned snapshot ve deklaratif
projection kullanın. Java hangi verinin yayınlanacağına karar verir. RESP, connection, topology
routing ve bounded I/O işlerini Rust yönetir.

Cluster routing için Redis native ABI `2`, Sentinel master yenileme için ABI `3`, fenced snapshot
publish için ABI `4`, async GET ve native JSON response handle için ABI `5`, role göre native
transport plane ayırmak için ABI `6` gerekir. Aynı uygulama
`rust-java-rest` de kullanıyorsa ABI `29` içindeki `rust-java-rest:4.8.0` veya daha yeni sürümü kullanın. Böylece
framework native bridge ile cache library aynı binary sözleşmesini kullanır. Paketlenen provenance
manifesti REST ABI `29`, Dubbo ABI `7`, Redis ABI `6`, Glowroot ABI `6`, tam kaynak revision ve
platform SHA-256 hash'lerini taşır. Eski veya uyumsuz binary startup sırasında reddedilir.

Varsayılan native binary çıkarma dizini:

```text
${user.home}/.java-rust-cache/native/<binary-sha256-prefix>/
```

Yayımlanmamış başlangıç davranışı: cache kendi paketlediği native dosyayı yüklerken kaynağı sabit
16 KiB tamponla, bu kullanıcı ana dizini veya yapılandırılmış çıkarma kökü altında geçici bir
dosyaya akış halinde kopyalar. Dosya doğrulanmadan hash adlı dizine yerleştirilmez. Eşleşen mevcut
dosya yeniden kullanılır, geçici dosyalar temizlenir. 64 MiB'ı aşan kaynaklar reddedilir.

Bu çıkarma yolu `java.io.tmpdir` kullanmaz ve dosyanın tamamını Java `byte[]` dizisinde tutmaz.
Yapılandırma adları ve native ABI'ler değişmez. Sınırlı çıkarma tamponları, ölçülmüş bir RSS azalması
iddiası değildir.

Bu dizini `reactor.cache.native.extract-dir` veya `REACTOR_CACHE_NATIVE_EXTRACT_DIR` ile değiştirebilirsiniz. Kubernetes üzerinde bu değeri uygulamanın yazabildiği bir dizine veya `emptyDir` mount altına verin:

```yaml
env:
  - name: REACTOR_CACHE_NATIVE_EXTRACT_DIR
    value: /app/.java-rust-cache/native
```

Native binary dosyasını classpath içine veya JAR içine runtime sırasında yazmaya çalışmayın. Bu alanları read-only runtime artifact olarak düşünün.

## Buradan Başlayın

| Uygulamanız | Başlangıç | Neden? |
| --- | --- | --- |
| REST API hazır JSON okuyor | `rust-java-starter-cache-reader` ve `read-only` | Writer pool'u ve scheduler oluşturulmaz. |
| Zamanlanmış süreç DB read modelini yayınlıyor | `rust-java-starter-cache-writer` ve `write-only` | REST server ve Redis read pool'u oluşturulmaz. |
| Araç gerçekten hem okuyor hem yazıyor | Doğrudan `java-rust-cache` ve `read-write` | İki veri yolu da açık ve sınırlıdır. |

Normal reader/writer mimarisi için
[`rest-sample-cache-reader`](https://github.com/esasmer-dou/rest-sample-cache-reader) ve
[`rest-sample-cache-writer`](https://github.com/esasmer-dou/rest-sample-cache-writer) projelerinden
başlayın.

## İçindekiler

- [Beş dakikada karar](#beş-dakikada-karar-verin)
- [Desteklenen kapsam](#desteklenen-kapsam)
- [Hızlı başlangıç](#kullanım)
- [Sürümlü snapshot](#versioned-json-snapshot-api)
- [Deklaratif projection](#declarative-projection-settings)
- [Konfigürasyon](#configuration)
- [Standalone, Sentinel ve Cluster](#topology-reçeteleri)
- [Production doğrulaması](#doğrulama)

## Veri Akışı

```mermaid
flowchart LR
    DB["Veritabanı"] --> W["Java materializer"]
    W -->|"sınırlı native yazma"| R["Redis"]
    R -->|"sınırlı native okuma"| C["Java cache reader"]
    C --> H["Rust HTTP response"]
```

Cache politikası, projection adları, TTL kararı ve iş verisinin dönüşümü Java'da kalır. RESP,
connection, topology yenileme, in-flight sınırı ve native response handle Rust tarafından yönetilir.
Kütüphane hangi iş verisinin cache'e alınacağına karar vermez.

Container notu: Paketlenen Linux binary `manylinux2014/glibc 2.17` tabanı ile uyumludur. CentOS 7+, CentOS 8, UBI 8/9, Ubuntu/Jammy ve Semeru/OpenJ9 gibi glibc tabanlı image'larda çalışması hedeflenir. Kendi native binary'nizi üretirseniz, platformunuzun desteklediği en eski Linux tabanında build alın.

## Desteklenen Kapsam

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
  <version>0.9.0</version>
</dependency>
```

### Native Platform Seçimi

Yükleyici, işletim sistemi ve işlemci mimarisine göre dosyayı seçer:

| Platform | Cache resource yolu |
| --- | --- |
| Windows x64 | `native/windows-x64/rust_hyper-windows-x64.dll` |
| Linux x64 | `native/linux-x64/librust_hyper-linux-x64.so` |
| macOS ARM64 | `native/macos-arm64/librust_hyper-macos-arm64.dylib` |
| Linux ARM64 | `native/linux-arm64/librust_hyper-linux-arm64.so` |

`0.9.0` paketi dört platformun native dosyalarını ve checksum bilgilerini içerir.
ARM64 native build'leri CI üzerinde doğrulanır. Bu sürümün uygulama smoke testleri x64 kullanır.
Özel build için REST reposundaki `scripts/sync-native-artifacts.ps1` aracını kullanın.
Yükleyici, dosyayı açmadan önce Redis ABI `6`
ve seçilen platformun hash bilgisini doğrular. Yalnız cache kullanan process, REST veya Glowroot
ABI işlevlerini çağırmaz. Ayar verilmezse dosya `${user.home}/.java-rust-cache/native` altında açılır.

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
mvn -q dependency:get "-Dartifact=com.reactor:java-rust-cache:0.9.0"
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

Kaynak kodda her kilit alımı farklı ve sınırlı uzunlukta bir sahiplik token'ı alır.
JVM için 128 bit rastgele bir önek yalnız bir kez üretilir. Her alımı atomik bir
sıra numarası ayırır. Yenileme işleri her çalışmada ortak rastgele sayı üretecini
beklemez. Redis, kilidi yenilerken veya bırakırken tam sahiplik değerini yine
kontrol eder. TTL, fencing ve birden fazla replica ile çalışma kuralı değişmez.
Açık DB/Redis bağlantıları veya kilitleri olan writer'dan checkpoint almayın.
Bu kaynakları ve yenileme işlerini restore sonrasında başlatın.

Henüz yayımlanmayan kilit yenileme düzeni: kilit TTL'i en az 300 ms olan işler,
aynı kilit yöneticisindeki ortak yenileme görevini kullanır. Kısa snapshot işi
her çalışmada ayrı zamanlayıcı oluşturup iptal etmez. Daha kısa TTL'ler mevcut
iş başına zamanlamayı kullanır. Uzun işin kilidi yenilenir. Yayın sırasında kilit
sahibi ve fencing token'ı yine kontrol edilir. Mevcut yenileme thread'i korunur.
Yeni property, worker veya SQL cache'i eklenmez. İşler arasında kilit tutulmaz.
Refresh API'si değişmez. Güvenli yayın için gereken Redis çağrıları kaldırılmaz.
Ortak zamanlayıcı, yalnız kendi yenileme zamanı gelen kilidi yeniler. Kilit yeni
alındıysa araya gereksiz Redis çağrısı girmez ve yayın bu çağrıyı beklemez.
Uzun işlerde TTL yenilemesi ve fencing kontrolü korunur.

Kaynak kodda isteğe bağlı native snapshot birleştirme desteği bulunur.
Varsayılan olarak kapalıdır. Kendi yükünüzü ölçtükten sonra
`reactor.cache.redis.snapshot-batch-enabled=true` veya
`REACTOR_CACHE_REDIS_SNAPSHOT_BATCH_ENABLED=true` ile seçebilirsiniz.
Bu seçenek deneysel kaynak desteğidir; önerilen production varsayılanı değildir.
`refreshSnapshotWithLock` ve üretilen projection materializer'ları, uygun native
dosya varsa standalone/Sentinel üzerinde bu yolu kullanır. Küçük yenileme için
kilit, token, veri, sürüm ve kilit bırakma ayrı ayrı gönderilmez. İki yazma çağrısı
yeterlidir. Son yayın hem Redis'teki kilit sahibini hem fencing token'ı kontrol
eder. Büyük token'lar Lua'nın sayı hassasiyetine bağlı kalmadan karşılaştırılır.
Veri yazımı başarısızsa aktif sürüm ilerletilmez. Partiler sınırlı kalır.
Cluster ve eski uyumlu native dosyalar mevcut uygulamayı kullanır. Redis ABI 6
ve mevcut JNI imzaları değişmez. Yeni yol için birlikte üretilmiş native dosyayı
kullanın. Bu açıklama, eski yayınlanmış pakette özelliğin bulunduğu veya her
ortamda aynı gecikmenin elde edileceği anlamına gelmez.


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

Writer process örneği:

```java
public final class CacheWriterApplication {
    public static void main(String[] args) {
        ProjectionWriterApplication.runCache(
                "cache-writer.properties",
                "sample.writer",
                PostgresRepository::open,
                CustomerMaterializer::new);
    }
}
```

İlk factory, yönetilen business kaynağını oluşturur. Library, scheduler ve native cache kapandıktan
sonra bu kaynağı kapatır. İkinci factory refresher'ı oluşturur:

```java
final class CustomerMaterializer implements ProjectionRefreshScheduler.ProjectionRefresher {
    CustomerMaterializer(PostgresRepository repository, RustCache cache, CacheProperties properties) {
        // SQL ve JSON dönüşüm kararları burada açık kalır.
    }
}
```

Dört parametreli launcher, resource lifecycle boilerplate kodunu kaldırır. Reflection kullanmaz.
Projection refresh hot path'ini değiştirmez.

REST cache reader örneği:

```java
@EnableRustCache
@ReactorApplication(scanBasePackages = "com.example.cache")
public final class CacheReaderApplication {
    public static void main(String[] args) {
        RestApplication.run(CacheReaderApplication.class, args);
    }
}

@GenerateProjectionReader(rootPrefix = "sample.cache.customer", restBean = true)
interface CustomerCacheReads {
    @ProjectionIdRead(projection = "detail")
    CacheReadResult customer(long id);

    @ProjectionIndexRead(projection = "segment", index = "segment")
    CacheReadResult bySegment(String segment);
}
```

`@EnableRustCache` tek bir managed native cache bean'i üretir. `@GenerateProjectionReader`, bound
reader implementasyonunu üretir. Projection ve index adları başlangıçta bir kez çözülür.
Reader kontratı başka bir interface'teki generic olmayan annotation'lı metotları miras alabilir.
Default yardımcı metotlar olduğu gibi kalır. Generic reader kontratı, boş prefix veya geçersiz
generated class adı varsa build durur. Runtime reflection fallback kullanılmaz.

Builder yalnızca ileri scheduler ayarları için kullanılmalıdır. Basit launcher da shutdown, başlangıç
hatasında geri alma, property yükleme, sınırlı scheduler thread'leri ve kaynak yönetimini korur.

Reader örneği:

```java
List<CacheReaderProjectionSettings> projections =
        CacheReaderProjectionSettings.resolveAll(properties, "sample.cache.customer");

VersionedJsonProjectionReaders readers = VersionedJsonProjectionReaders.create(
        cache, projections, properties.getLong("sample.cache.customer.version-cache-ms"));

VersionedJsonProjectionReaders.BoundProjection details =
        readers.bind(CustomerProjection.DETAIL);
VersionedJsonProjectionReaders.BoundIndex customerNumber =
        details.bind(CustomerProjectionIndex.CUSTOMER_NO);

CacheReadResult customer = details.getById(customerId);
CacheReadResult byNumber = customerNumber.get("CUST-1001");
```

Ortak enum'lar `Supplier<String>` uygular. Bu nedenle model artifact'i cache library'ye bağımlı
olmaz. Projection ve index bağlantılarını uygulama başlarken bir kez kurun. Bound nesneler seçilen native
cache reader'ı doğrudan çağırır. Her istekte projection registry araması tekrarlanmaz.

REST endpoint'inde cache hazırlığı ile gerçek business miss sonucunu ayırın:

```java
return CacheResponses.json(
        customerService.find(customerId),
        CacheResponsePolicy.production("customer_not_found"));
```

Snapshot hazırsa fakat müşteri yoksa `404` döner. Writer henüz snapshot publish etmediyse `503`
döner. Miss ve hazırlık davranışı uygulama kodunda açıkça görülsün diye `CacheResponsePolicy`
değerini doğrudan verin.

Classpath varsayılanları, `reactor.config.file` overlay dosyaları, ortam değişkenleri ve `-D` system
property değerleri için `CacheProperties` kullanın. Config başka bir kaynaktan geliyorsa
`ProjectionPropertySource` implement edin.

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

`java-rust-cache`, cache process'leri için küçük helper sınıflar da taşır. Bunlar bilinçli olarak açık tutulur:

- `CacheProperties`: Reader ve writer için aynı property öncelik sırasını uygular.
- `ProjectionWriterApplication`: Scheduler başlangıcını, run-once modunu, shutdown hook'u, startup
  rollback işlemini ve kaynak kapatmayı yönetir.
- `VersionedJsonProjectionMaterializer`: Açık projection adlarını business writer'lara bağlar ve
  fenced lock akışını yönetir.
- `VersionedJsonProjectionReaders`: İsimlendirilmiş reader registry'sini ve kontrollü not-ready
  sonucunu yönetir.
- `BoundProjection` ve `BoundIndex`: Projection adlarını başlangıçta bir kez çözer. Request yolundaki
  registry aramasını kaldırır.
- `ProjectionRefreshScheduler`: Projection schedule, run-once mode, Redis lock sonucu loglama ve TTL/config uyarılarını yönetir.
- `JsonWriter`: UTF-8 JSON escaping ve primitive field helper sağlar. Domain JSON alanlarına uygulama karar verir.
- `JdbcRepository`: `DataSource` etrafında connection/query/page/lifecycle boilerplate kodunu azaltır.
- `HikariDataSources`: `sample.db.*` benzeri property'lerden Hikari pool oluşturabilir. Hikari opsiyonel dependency'dir. Reader-only servislerin Hikari yüklemesi gerekmez.

Örnek:

```java
@GenerateProjectionRegistry(CustomerProjection.class)
final class CustomerMaterializer {
    private final VersionedJsonProjectionMaterializer materializer;

    CustomerMaterializer(RustCache cache, CacheProperties properties) {
        materializer = CustomerMaterializerProjectionRegistry.create(
                this, cache, properties, "sample.writer");
    }

    SnapshotResult writeDetail(ProjectionTarget target) { /* iş dönüşümü */ }
    SnapshotResult writeCampaign(ProjectionTarget target) { /* iş dönüşümü */ }
}
```

Generated registry her enum değerini derleme sırasında doğrular. Doğrudan method reference üretir.
Refresh sırasında reflection veya projection adı parse işlemi çalışmaz.

Processor'ı yalnız build path'e ekleyin:

```xml
<path>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-cache</artifactId>
  <version>0.9.0</version>
  <classifier>codegen</classifier>
</path>
```

Codegen JAR processor'ları otomatik bulur. Her uygulama POM'una processor sınıf adlarını yazmanız
gerekmez. Release build, processor sınıflarının ve processor service metadata'sının runtime JAR
içinde olmadığını doğrular.

BEST: Lifecycle boilerplate kodunu library'ye bırakın. SQL, row mapping, JSON shape ve cache key business kararlarını uygulama kodunda açık tutun.

## Configuration

### UTF-8 Writer Davranışı

Byte tabanlı JSON writer, art arda gelen normal metni tek aralık olarak kopyalar.
Her alan için geçici `String` veya `char[]` oluşturmaz. Türkçe karakterler, surrogate
çiftleri, tırnak ve kontrol karakterleri UTF-8 ve JSON kaçış kurallarıyla yazılır.
Buffer boyut sınırı ve sıfırlama davranışı korunur. Yeni property, thread, cache
veya thread başına kalıcı buffer eklenmez. Ayrı ve isteğe bağlı SWAR writer bu
değişiklikle açılmaz. Encode maliyetinin azalması; DB, Redis kilidi, yayın işlemi
ve JVM derleme süresinin refresh işinden kalktığı anlamına gelmez.

Tüm key'ler Java system property, environment variable veya `Properties` object üzerinden verilebilir.

| Property | Env | Varsayılan |
| --- | --- | --- |
| `reactor.cache.redis.topology` | `REACTOR_CACHE_REDIS_TOPOLOGY` | `standalone` |
| `reactor.cache.redis.access-mode` | `REACTOR_CACHE_REDIS_ACCESS_MODE` | `read-write` |
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

Process rolünü açıkça seçin:

```properties
# REST cache reader: native write pool açılmaz. Write çağrısı JNI'ye ulaşmadan reddedilir.
reactor.cache.redis.access-mode=read-only

# Scheduled materializer: native read pool açılmaz. Read çağrısı JNI'ye ulaşmadan reddedilir.
# reactor.cache.redis.access-mode=write-only
```

Tek process gerçekten hem okuyor hem yazıyorsa `read-write` kullanın. Access mode native client
oluşturulurken sabitlenir. Runtime sırasında genişletilmez. `read_capable_clients` ve
`write_capable_clients` metrikleri kullanılmayan plane'in açılmadığını gösterir. Bu ayarın temel
kazancı yanlış write işlemini ve yük altında gereksiz connection/in-flight büyümesini engellemektir.
Boşta duran kullanılmamış pool'un RSS maliyeti zaten çok küçüktür.

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

Integration Redis ACL veya `requirepass` kullanıyorsa iki Maven komutuna da
`-Dreactor.cache.redis.integration.username=...` ve
`-Dreactor.cache.redis.integration.password=...` ekleyin. Redis yalnız parola kullanıyorsa username
parametresini vermeyin.

Reconnect gate restart sonrası ilk operation'ın fail etmesine izin verir. Production beklentisi şudur: bozuk socket atılır ve sonraki operation yeni Redis connection açar.

## Production Kontrol Listesi

- Ayrı CQRS uygulamalarında `read-only` ve `write-only` kullanın. Alışkanlıkla iki veri yolunu açmayın.
- Connection, max-in-flight, response byte ve işlem timeout değerlerini sınırlı tutun.
- TTL değerini ilgili projection'ın yenileme aralığından uzun tutun.
- Bağımsız zamanlanan her projection için ayrı lock adı kullanın.
- Cache erişilebilirliği zorunluysa Sentinel veya Cluster kullanın.
- Pod limitleriyle restart/failover, c64/c256 yük, p99, rejection, RSS ve yük sonrası idle testi yapın.
- Cache miss durumunu normal iş sonucu olarak ele alın. Transport hatası gibi tekrar denemeyin.
- Parolayı takip edilen property dosyasına veya release artifact'ine yazmayın.

## Sık Yenilemeleri Aynı Ana Toplamayın

Varsayılan davranışta interval, önceki iş bittikten sonra başlar. İş süreleri
farklıysa başlangıçta aralıklı çalışan projection'lar zamanla aynı ana kayabilir.
Sık ve bağımsız yenilemelerde başlangıç fazını koruyan seçeneği açabilirsiniz:

```properties
sample.writer.phase-anchored=true
sample.writer.interval-ms=500
sample.writer.detail.initial-delay-ms=2000
sample.writer.segment.initial-delay-ms=2100
sample.writer.status.initial-delay-ms=2200
sample.writer.campaign.initial-delay-ms=2300
sample.writer.meta.initial-delay-ms=2400
```

Her projection, monoton saat üzerinde kendi başlangıç aralığını korur. Uzayan
iş nedeniyle kaçan zaman dilimleri peş peşe çalıştırılmaz. Aynı projection kendi
kendiyle eşzamanlı çalışmaz. Thread sınırı, Redis kilidi, fencing, TTL ve kapanış
kuralları değişmez. Ek worker veya kuyruk açılmaz. Varsayılan değer `false` olur.
İş bittikten sonra tam bir interval dinlenmesi gerekiyorsa varsayılanı koruyun.
Tek seferlik çalışma değişmez. TTL; interval, beklenen en uzun iş/kesinti süresi
ve güvenlik payının toplamından uzun olmalıdır.

Bu kaynak kod seçeneği başlangıç zamanını düzenler. İşin çalışma süresini
kısaltmayı veya daha düşük p99'u garanti etmez. Yeni production varsayılanı değildir.

## Kısa Sözlük

| Terim | Basit anlamı |
| --- | --- |
| Projection | Bir endpoint veya sorgu biçimi için hazırlanmış okuma modeli |
| Snapshot | Tutarlı biçimde yayınlanan, sürümlü projection veri grubu |
| Fenced lock | Eski writer'ın yayın yapmasını ownership token ile engelleyen dağıtık kilit |
| Access mode | Açılan native veri yolu: okuma, yazma veya ikisi |
| Sentinel | Sentinel node'larıyla Redis primary discovery ve failover |
| Cluster | Redis slot'larının birden fazla primary node'a dağıtılması |
| In-flight | Başlamış fakat henüz tamamlanmamış işlem |
| Native handle | Body Rust belleğinde kalırken Java'nın taşıdığı küçük response kimliği |

Sürüm ayrıntıları: [java-rust-cache 0.9.0](docs/RELEASE_NOTES_v0.9.0.tr.md).
