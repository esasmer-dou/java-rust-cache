# 0.9.1 — ARM64 Paket Düzeltmesi

[English](RELEASE_NOTES_v0.9.1.md) | [Türkçe](RELEASE_NOTES_v0.9.1.tr.md)

2026-10-30 tarihli kararlı düzeltme sürümüdür. 0.9.0'dan geçiş adımları aşağıdadır.

Linux ARM64 veya macOS ARM64 kullanıyorsanız bu düzeltmeye geçin.
Önceki JAR'da native dosyalar günceldir; platform manifestleri eskidir. Yükleyici
kök manifestten önce bu dosyayı seçer ve eski özeti reddeder.

Manifestler paketlenen dosyalardan yeniden üretilir. Üretilen JAR için de kontrol
eklenir. ARM manifestlerinde `runtime.verified=false` korunur. Bu bir ARM çalışma
testi değildir. Kaynak sürümü `3418671b5010afb5dc049897f2a7287dc6a58daf` kalır.
Native dosyalar yeniden derlenmez. Dört platformun dosyaları da aynı kalır.

REST varsa cache `0.9.1` ile REST/platform `4.8.1` kullanın. Uygulamayı eşleşen
paket kaynaklarıyla yeniden derleyin. Sample sürümü `0.8.1`, model `0.5.1`,
utility `0.5.1` olur. Java API'leri, isteğe bağlı özelliklerin
varsayılanları, Redis ABI 6 ve diğer native ABI sözleşmeleri değişmez. Bu paket
düzeltmesi yeni bellek veya throughput kazanımı iddiası taşımaz.
