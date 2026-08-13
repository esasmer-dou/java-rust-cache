# java-rust-cache 0.7.3

`0.7.3`, `rust-java-rest:4.4.1` ve `rust-spring v4.4.2` ile hizalanmış stable native provenance
düzeltmesidir.

- Windows ve Linux native dosyaları aynı temiz CI kaynak commit'inden gelir.
- Release CI; hash, ABI, 40 karakterli tam kaynak revision ve iki native dosyanın içine yazılan
  revision değerini doğrular.
- Redis standalone, Sentinel, Cluster, read-only, write-only ve read-write davranışı değişmez.
- Java API'leri, projection scheduler, lock, snapshot publish ve uygulama business logic akışı
  değişmez.

İki kütüphane aynı process içindeyse `java-rust-cache:0.7.3` sürümünü `rust-java-rest:4.4.1` ile
kullanın. Başka bir sürümden DLL/SO kopyalamayın.
