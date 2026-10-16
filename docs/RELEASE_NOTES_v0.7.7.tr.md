# java-rust-cache 0.7.7

[English](RELEASE_NOTES_v0.7.7.md) | [Turkish](RELEASE_NOTES_v0.7.7.tr.md)

Windows/Linux x64 yanında Linux ARM64 ve macOS ARM64 native dosyaları paketlenir. REST 4.6.3 ile aynı temiz native kaynak kullanılır. Redis ABI 6 korunur. Key, TTL, lock, Cluster ve Sentinel sözleşmeleri değişmez.

## Güncelleme

Maven bağımlılığını veya parent sürümünü güncelleyin. Uygulamayı yeniden derleyin. Annotation processor sürümü platformla hizalı olsun. İşletim sistemi ve işlemcinize uygun paket içi native dosyayı kullanın. Eski sürümün dosyalarını yerinde değiştirmeyin.

İş mantığı Java'da kalır. Runtime reflection veya yeni bir Java I/O katmanı eklenmez. Telemetri kendiliğinden açılmaz. ARM64 native dosyaları CI üzerinde derlenir. Bu sürümün uygulama kontrolleri x64 üzerinde yapılır. Bu patch için yeni ve evrensel bir RSS veya throughput kazanımı iddia edilmez.

GitHub Packages için Maven `settings.xml` içinde geçerli `read:packages` token gerekir. Repo public olsa da bu kural geçerlidir. Git SSH erişimi Maven kimlik doğrulamasından ayrıdır.

