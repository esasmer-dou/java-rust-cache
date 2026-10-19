# Veri Yaşı Ve Sınırlı JSON Yazımı

[English](functional-operations.md) | [Turkish](functional-operations.tr.md)

Bu API'ler java-rust-cache 0.8.0 paketindedir. Mevcut okuma davranışı varsayılan olarak korunur.

## Politika Seçin

| İhtiyaç | Ayar | Sonuç |
| --- | --- | --- |
| Mevcut davranış kalsın | Yaş sınırı 0 | Yaşa göre ret yapılmaz |
| Eski müşteri verisi sunulmasın | 120000 ms, serve stale false | STALE |
| Eski katalog verisi sunulabilir | 120000 ms, serve stale true | STALE_HIT |

Yaş, snapshot hazırlanmaya başladığında hesaplanır. Yayın anını göstermez.
Makinelerin saatleri eşleşmelidir. Bilinmeyen veya gelecekteki zaman eski kabul edilir.
TTL veriyi silmeye devam eder. Yaş kontrolü silinen veriyi geri getirmez.
Şema değişince `customer.v2.detail` gibi yeni bir namespace kullanın.
Bu yöntem otomatik JSON şema doğrulaması yapmaz.

```java
var reader = VersionedJsonCacheReader.create(
    cache, "customer.v2.detail", 1000, new CacheFreshness(120000, false));
CacheReadResult result = reader.getById(42);
```

Kontrol ek Redis çağrısı yapmaz. Mevcut sürüm bilgisini kullanır.
Reader, sürüm bilgisini `versionCacheMillis` boyunca tutabilir.
Bu değeri sıfır yaparsanız her okumada sürüm sorgusu yapılır.

`CacheResponses.json` mevcut yanıt sözleşmesini korur.
`CacheResponses.problem` bulunamayan kayıt için 404 döner.
Snapshot yoksa veya fazla eskiyse 503 döner.
Eski veri sunulmasına izin verirseniz yanıtta `X-Cache-Stale: true` olur.

## Tam String Oluşturmadan JSON Yazın

Ölçüm uygunsa `JsonWriter` yerine `Utf8JsonWriter` kullanın.
JSON doğrudan sınırlandırılmış byte dizisine yazılır.
ThreadLocal içinde kalıcı buffer tutulmaz.
Varsayılan sınır 1048576 byte'tır. `super(maxBytes)` ile değiştirebilirsiniz.
Son byte dizisi yine kopyalanır. Bu yol sıfır kopya değildir.

```java
final class ProductWriter extends Utf8JsonWriter {
    byte[] product(long id, String name) {
        var out = json(128).append('{');
        field(out, "id", id).append(',');
        stringField(out, "name", name).append('}');
        return utf8(out);
    }
}
```

DB işlemleri, satır satır export ve yenileme bildirimi için `rust-java-jdbc` kullanın.
Bu bağımlılığı DB kullanan uygulamada açıkça ekleyin. Reader'a otomatik taşınmaz.
Outbox bildirimi ile DB değişikliği aynı transaction içinde yazılır.
Redis yayını ayrı bir işlemdir. Mevcut fenced writer kilidini koruyun.

## Native Socket Buffer Kullanımı

Senkron Redis bağlantısı, küçük protokol parçalarını birleştirerek gönderir.
Aktif komut veya batch için stack üzerinde iki adet sabit 1 KiB buffer kullanır.
Büyük veriler için veri boyutunda ikinci bir geçici buffer oluşturmaz.
Ek Redis komutu, bağlantı, worker veya otomatik tekrar eklenmez.
Cevap boyutu limitleri, timeout ve okuma/yazma yetkileri korunur.
Yeni bir ayar yapmanız veya Java kodunu değiştirmeniz gerekmez.
