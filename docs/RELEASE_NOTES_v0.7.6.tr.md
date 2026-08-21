# java-rust-cache 0.7.6

[English](RELEASE_NOTES_v0.7.6.md) | [Türkçe](RELEASE_NOTES_v0.7.6.tr.md)

`0.7.6`, bağımsız cache runtime'ını `rust-java-rest:4.6.0` ve Glowroot native ABI `4` ile hizalar.

## Kullanıcıya Ne Sağlar?

- İsteğe bağlı REST dependency sürümü `4.6.0` oldu.
- Bağımsız cache uygulamaları, REST `4.6.0` ile aynı temiz Windows ve GLIBC 2.17 uyumlu Linux
  native revision'ını paketler.
- REST ABI `29`, Dubbo ABI `7` ve Redis ABI `6` değişmedi. Glowroot ABI `3` değerinden `4` değerine
  yükseldi.
- Cache API'leri, Redis topology ayarları, projection sözleşmeleri, TTL davranışı, lock mekanizması
  ve Java business kodu değişmedi.

Bu paketle gelen DLL/SO dosyasını kullanın. Aynı process içinde `java-rust-cache:0.7.5` ile
koordineli REST `4.6.0` runtime'ını birlikte kullanmayın.
