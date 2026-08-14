# java-rust-cache 0.7.4

`0.7.4`, Redis ile `rust-java-rest:4.5.0` sürümünü aynı process içinde kullanan uygulamalar için
native provenance hizalama sürümüdür.

## Neler Değişti?

- Paketteki Windows DLL ve Linux SO artık Rust-Java REST `4.5.0` ile aynı temiz
  `rust-spring v4.5.0` commit'inden gelir.
- Manifest; REST ABI `29`, Dubbo ABI `7`, Redis ABI `6` ve Glowroot ABI `3` değerlerini taşır.
- İsteğe bağlı Rust-Java REST dependency sürümü `4.5.0` ile hizalandı.
- Redis komutları, topology davranışı, public Java API ve Redis ABI değişmedi.

İki kütüphane aynı process içindeyse `java-rust-cache:0.7.4` ile `rust-java-rest:4.5.0` kullanın.
`0.7.3` veya REST `4.4.x` içinden DLL/SO kopyalamayın. Startup provenance kontrolü karışık
binary'leri reddeder.
