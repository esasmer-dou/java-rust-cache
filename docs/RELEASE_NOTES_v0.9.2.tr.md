# 0.9.2: Uyumlu Native Yanıt Yaşam Döngüsü

[English](RELEASE_NOTES_v0.9.2.md) | [Turkish](RELEASE_NOTES_v0.9.2.tr.md)

`com.reactor:java-rust-cache:0.9.2` bağımlılığını REST/platform `4.8.2` ile kullanın.
Redis komutları, topoloji seçimi, TTL ve projection ayarları değişmedi.

Paketteki native dosya, REST 4.8.2 ile aynı temiz kaynak revizyonundan gelir.
Sınırlı yanıt boyutu tahmini, JNI tamponunun tekrar tekrar büyümesini azaltır.
REST paketinde, başlıklarına erişilmeyen JSON yanıtları için Java haritası
oluşturulmaz. `CacheResponses.reply`, cache okuma sonucunu HTTP yanıtına çeviren
ortak yardımcı olarak kullanılmaya devam eder. Elle gövde kopyalamanız veya
yeni bir handler annotation'ı eklemeniz gerekmez.

Native DLL/SO/dylib yolunu elle veriyorsanız JAR ile birlikte o dosyayı da
değiştirin. Redis ABI 6 olarak kalır. Farklı sürümlerin native dosyalarını
karıştırmayın.

EmaTech detail/segment c16/c64 karşılaştırması mevcut RPS/p99/RSS sınırlarını
geçti. Beklenmeyen hata görülmedi. Ölçüm ortak Java/native değişimini kapsar;
yalnız bu kütüphanenin katkısını ayırmaz. Platform uyumluluğu ve uzun süreli
çalışma kayıtları release eklerinde sunulur. Derleme sonucu test yerine geçmez.

GitHub Packages için Maven `settings.xml` içinde repository ID ile eşleşen
server tanımı gerekir. Token `read:packages` yetkisine sahip olmalıdır. SSH ile
repo erişimi Maven paket erişiminin yerine geçmez. README örneklerini kullanın.
