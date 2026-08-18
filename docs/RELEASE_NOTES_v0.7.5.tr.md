# java-rust-cache 0.7.5

[English](RELEASE_NOTES_v0.7.5.md) | [Türkçe](RELEASE_NOTES_v0.7.5.tr.md)

`0.7.5`, cache kütüphanesini kararlı `rust-java-rest:4.5.5` native runtime'ıyla hizalar.

## Kullanıcıya Gelen Değişiklikler

- İsteğe bağlı REST dependency sürümü `4.5.5` oldu.
- Sade cache uygulamaları, REST `4.5.5` ile aynı temiz Windows ve Linux native revision'ını paketler.
- REST ABI `29`, Dubbo ABI `7`, Redis ABI `6` ve Glowroot ABI `3` değişmedi.
- Cache API'leri, Redis topology ayarları, projection kontratları, TTL davranışı, lock'lar ve Java iş
  kodu değişmedi.

Paketin içindeki native dosyaları kullanın. İlgisiz bir sürümden DLL veya SO kopyalamayın.
