# java-rust-cache 0.7.2

`0.7.2`, cache kütüphanesini `rust-java-rest:4.4.0` ve
`28688c3fca2618d469267ea4e61481a4295387f1` Rust commit'inden üretilen temiz native runtime ile
hizalar.

- Redis davranışı, Java API'leri, topology desteği, scheduler entegrasyonu ve business kodu değişmez.
- Paket içindeki DLL/SO, REST ABI `28`, Dubbo ABI `7`, Redis ABI `6` ve Glowroot ABI `1` taşır.
- Release CI, package yayınlanmadan önce tam kaynak commit'ini ve iki platformun SHA-256 değerini
  doğrular.
- Native Redis süreleri, host uygulama açtığında isteğe bağlı Glowroot mikro telemetry katmanına
  aktarılabilir.

`java-rust-cache:0.7.2` sürümünü `rust-java-rest:4.4.0` ile kullanın. Eski sürümden native dosya
kopyalamayın.
