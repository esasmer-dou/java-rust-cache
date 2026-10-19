# java-rust-cache 0.8.0

Rust-Java REST/platform 4.7.0 ile kullanın. Redis ABI 6 olarak kalır.

- `CacheFreshness` ile snapshot için en yüksek yaşı belirleyin. Mevcut davranış için yaşı sıfır bırakın. Kontrol mevcut sürüm bilgisini kullanır. Ek Redis komutu göndermez.
- Eski snapshot'ın sunulup sunulmayacağını seçin. Eksik kayıtta 404, hazır olmayan snapshot'ta 503 için açık problem-response adaptörünü kullanın. Eski JSON adaptörünün davranışı korunur.
- Tam bir ara String kurmadan sınırlı UTF-8 JSON yazmak için `Utf8JsonWriter` sınıfını genişletin. ThreadLocal içinde buffer tutulmaz. Son byte dizisi kopyası devam eder.
- DB writer uygulamasına `rust-java-jdbc:4.7.0` bağımlılığını açıkça ekleyin. Reader bu isteğe bağlı modülü almaz.
- Güvenli ASCII anahtarlar için geçici UTF-8 dizisi üretilmez. RESP yazıları küçük parçalar yerine birlikte gönderilir. Bağlantı, worker veya tekrar sayısı artmaz.

Windows x64, Linux x64/ARM64 ve macOS ARM64 dosyaları temiz kaynak manifestiyle paketlenir. Linux için GLIBC 2.17 uyumu korunur. Eski DLL/SO dosyalarını karıştırmak yerine paketteki native dosyayı kullanın.

Allocator, huge-page veya nursery denemeleri varsayılan ayar yapılmaz.

## Doğrulama Notu

Eş koşullu Linux ölçümünde cache c16/c64 RPS ve p99 değerleri iyileşti. Writer tekrar doğrulamasında ortalama tepe RSS 92,417 MiB'den 91,382 MiB'ye düştü. Ancak son boşta RSS 77,268 MiB'den 78,591 MiB'ye çıktı. Bu artış yayın için kabul edilen bir risktir. Birlikte ölçülen consumer tarafında da kabul edilmiş sapmalar vardır. [Ölçüm kapsamını ve değişmeyen katı kontrol sonuçlarını](https://github.com/esasmer-dou/rust-java-rest/blob/v4.7.0/docs/release-notes/v4.7.0.tr.md) inceleyin.

[Örnekler ve maliyetler](functional-operations.tr.md) | [English](RELEASE_NOTES_v0.8.0.md)
