# 0.9.3: Sürümlü JSON Verisini Kimlikle Daha Hızlı Okuyun

[English](RELEASE_NOTES_v0.9.3.md) | [Turkish](RELEASE_NOTES_v0.9.3.tr.md)

Maven bağımlılığını `com.reactor:java-rust-cache:0.9.3` olarak güncelleyin.
Cache kod üreticisini kullanıyorsanız `codegen` paketini de aynı sürüme
çekin. REST/platform sürümünü, Redis ayarlarını veya uygulama kodunu
değiştirmeniz gerekmez.

`VersionedJsonCacheReader.getById`, Redis anahtarının sabit kısmını her
okumada değil, her veri sürümü için bir kez oluşturur. Redis anahtarları
aynı kaldığı için mevcut veriyi yeniden yayımlamanız gerekmez. TTL,
yanıt biçimleri, native dosyalar ve Redis JNI ABI 6 değişmedi. Bu iyileştirme
segment aramalarını veya yazma işlemlerini değil, kimlikle yapılan senkron okumaları kapsar.

EmaTech'te REST `4.9.0` ile yapılan dar kapsamlı karşılaştırmada,
16 eşzamanlı bağlantıyla çalışan Cache Reader detay rotası saniyede %3,5
daha fazla başarılı yanıt verdi. p99 gecikmesi 47,4 ms'den 46,5 ms'ye indi.
Yük altındaki süreç RSS'i 68,39 MiB'den 68,46 MiB'ye çıktı; gözlenen en
yüksek RSS ise 100,15 MiB'den 91,60 MiB'ye indi. Bunlar ölçülen iş yüküne
ait sonuçlardır; diğer rotalar için garanti değildir.

GitHub Packages erişimi için Maven `settings.xml` içinde `read:packages`
yetkili bir token kullanın. [Kurulum](../README.tr.md#github-packages-erişimi).
