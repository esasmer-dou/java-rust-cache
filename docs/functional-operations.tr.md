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

### Açık SWAR Sabitleri

Burada anlatılan SWAR API'si kaynak kod deposuna eklenmiştir. Yeni yayımlanmış bir
Maven paketi veya kabul edilmiş bir performans iyileştirmesi değildir.

`Utf8JsonWriter` SWAR'ı kendiliğinden kullanmaz; mevcut field/quote metotları
değişmez. Ölçümle seçilmiş bir writer, `Utf8JsonBuffer.writePrebakedLong` çağrısını
mevcut kaçışlama metotlarıyla birleştirebilir. Bu API yalnız **güvenilir, önceden
kaçışlanmış sabit byte'lar** içindir:

```java
var out = json(128).writePrebakedLong(0x3a22656d616e227bL); // {"name":
quote(out, name).append('}');
return utf8(out);
```

Her word, platform byte order veya hizasından bağımsız olarak düşük byte önce
olacak şekilde sekiz byte yazar. Bekleyen eşleşmemiş high surrogate için gereken
replacement byte dahil sınır, word'ün hiçbir parçası yazılmadan denetlenir.
Taşmada `IllegalStateException` oluşur; yarım belgeyi yayımlamayın. Dinamik
değerleri denetimsiz paketlemeyin, yine `quote` kullanın. Null string değerler
mevcut boş-string sözleşmesini korur. Buffer büyümesi ve son byte dizisi kopyası
devam eder; sıfır tahsisat veya her yerde hızlanma iddia edilmez.

Bu cache API'si little-endian byte-array VarHandle kullanmaya devam eder. Değişmez,
REST'in `JsonBufferWriter` sınıfından ayrıdır ve otomatik üreteç bağlantısı yoktur.
REST üreteci, isteğe bağlı SWAR yolunda tam `"field":` önekinin UTF-8 byte uzunluğuna
göre seçim yapar:

- **8..16 byte:** tek `writePrebakedLiteral(first, last, len)` çağrısı üretilir;
  bu önek için kalan byte'lar tabloda tutulmaz. İlk ve son sekiz byte'lık word,
  düşük byte önce olacak şekilde paketlenir; örtüşen byte'lar aynı olmalıdır.
  Yeterli yer varsa önekin başına ve `start + len - 8` konumuna mutlak
  `ByteBuffer.putLong` yazımları yapılır; bu yazımlar örtüşebilir. Uzunluk 8 ise
  yalnız bir kez yazılır ve `last` kullanılmaz.
- **16 byte'tan uzun:** mevcut tam-word ve 0..7 byte'lık kalan parça tablosu yolları korunur.
- **8 byte'tan kısa:** mevcut byte tablosu yolu korunur.

REST, big-endian çıktıda yazılan word'ün sırasını `Long.reverseBytes` ile ters çevirir.
VarHandle ByteBuffer view kullanmaz; position, limit ve byte order değerlerini korur.
Birleşik metot, cursor ve gerekli-byte sayacının tam sayı toplamalarını herhangi bir
yazım veya sayaç güncellemesinden önce denetler ve 8..16 dışındaki uzunlukları reddeder.
Yer azsa buffer limitine kadar byte byte yazan yolu kullanır, ancak tam gerekli
uzunluğu sayar. Bunlar REST davranışlarıdır; yukarıdaki sınırlı cache byte-array
API'sini değiştirmez.

REST'in derleme özelliği `reactor.json.swar` varsayılan olarak kapalıdır. Açmak için
işaretlenmiş DTO artifact'ini `-Dreactor.json.swar=true` ile temizden derleyin.
Derleyici ayarları REST `docs/native-views.tr.md` içindedir.

Bu seçenek burada anlatılan API'yi, mevcut cache writer'larını veya hazır JSON
cache okumalarını değiştirmez. REST'in ayrı generated-literal endpoint'i,
EmaTech'teki dört çiftli RPS/p99/+3 MiB karşılaştırmasını geçti. Bu sonuç burada
anlatılan byte-array API'si için cache okuma hızlanması veya bellek azalması
kanıtı değildir. Dar cache testlerini, eşleşen bağımlılıklar kurulduktan sonra
workspace kökünden çalıştırın:

```sh
mvn -B -ntp -f java-rust-cache/pom.xml -Dtest=SwarJsonWriteTest,Utf8JsonWriterTest,Utf8JsonBufferResetTest test
```

## Native Socket Buffer Kullanımı

Senkron Redis bağlantısı, küçük protokol parçalarını birleştirerek gönderir.
Aktif komut veya batch için stack üzerinde iki adet sabit 1 KiB buffer kullanır.
Büyük veriler için veri boyutunda ikinci bir geçici buffer oluşturmaz.
Ek Redis komutu, bağlantı, worker veya otomatik tekrar eklenmez.
Cevap boyutu limitleri, timeout ve okuma/yazma yetkileri korunur.
Yeni bir ayar yapmanız veya Java kodunu değiştirmeniz gerekmez.
