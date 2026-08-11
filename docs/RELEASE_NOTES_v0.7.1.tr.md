# java-rust-cache 0.7.1

`0.7.1`, Redis JNI library'sini `rust-java-rest:4.3.0` ve bu sürüm için kullanılan temiz native
kaynak revision'ı ile hizalar.

## Kullanıcıya Etkisi

- Public cache API, topology ayarları, read/write modları, Sentinel, Cluster, lock, TTL ve scheduler
  entegrasyonları değişmedi.
- REST ABI `26`, Dubbo ABI `7` ve Redis ABI `6` olarak kalır.
- Windows ve Linux native binary dosyaları yeniden üretildi. SHA-256 provenance bilgileri yenilendi.
- Aynı process içinde REST ve cache kullanıyorsanız `java-rust-cache:0.7.1` ile
  `rust-java-rest:4.3.0` kullanın.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-cache</artifactId>
  <version>0.7.1</version>
</dependency>
```

Her zaman bu sürümün içindeki native binary dosyasını kullanın. Eski bir sürümden DLL/SO
kopyalamayın.
