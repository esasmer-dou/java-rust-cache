# java-rust-cache 0.7.0

`0.7.0`, Redis JNI library'sini `rust-java-rest:4.2.0` ve ortak REST ABI `26` runtime ile hizalar.
Redis ABI `6` olarak kalır.

## Neler Değişti?

- Normal runtime JAR ile build-only cache codegen JAR'ı ayrı kalır.
- Codegen sınıfları açık bir build dizininden paketlenir. Processor implementation sınıfları
  production runtime artefact'ine girmez.
- Windows ve Linux native binary'leri aynı temiz `rust-spring` source revision'dan üretilir.
- Native provenance; REST ABI `26`, Dubbo ABI `7`, Redis ABI `6`, source revision ve platform
  SHA-256 değerlerini kaydeder.
- Reader-only ve writer-only starter'lar kapalı transport plane'lerini aktif runtime'a eklemez.

## Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-cache</artifactId>
  <version>0.7.0</version>
</dependency>
```

Aynı process REST endpoint açıyorsa `rust-java-rest:4.2.0` kullanın.

## Uyumluluk

Redis key formatı, projection namespace, TTL validation, standalone/Sentinel/Cluster topology,
bounded pool ve generated reader API'leri değişmedi. Eski REST ABI `24` binary kopyalamak yerine bu
sürümle paketlenen DLL/SO kullanılmalıdır.
