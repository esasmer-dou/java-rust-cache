# java-rust-cache 0.8.1

[English](RELEASE_NOTES_v0.8.1.md) | [Türkçe](RELEASE_NOTES_v0.8.1.tr.md)

Bu yama, cache uygulaması REST olmadan çalıştığında kullandığı native motoru yeniler.
Telemetri çok hızlı açılıp kapatılırsa exporter sahipliği takılı kalabiliyordu. Task henüz
çalışmadan iptal edilse de sahiplik artık bırakılır. Sonraki başlangıç engellenmez.

Redis komutları, erişim kipleri, TTL, snapshot ve kilit/fencing kuralları değişmez.
Java API kullanımı aynıdır. Worker, kuyruk veya pool limitleri büyütülmez. Redis ABI 6 ve
Glowroot ABI 6 korunur. REST bağımlılığı 4.7.1 ile hizalanır. Windows x64, Linux x64/ARM64 ve
macOS ARM64 dosyaları aynı temiz kaynaktan üretilir. Linux GLIBC alt sınırı 2.17 olarak kalır.

Kütüphaneyi güncelleyin ve paketin native dosyasını kullanın. REST de varsa sürümünü 4.7.1 yapın.
Yeni JAR ile elle tanımlanmış eski DLL/SO dosyasını birleştirmeyin. Telemetri isteğe bağlıdır.
Normal cache işlemleri exporter başlatmaz.

Yayın için native/doğruluk kontrolleri ve eşleşen telemetri testi geçmelidir. Kaynak branch'i
yayımlanmış paket değildir. HTTP micro-agent bellek ölçümü, her Redis iş yükü veya daha ayrıntılı
diagnostic profili için garanti değildir.
