# Opsiyonel Versiyonlu L1

[English](versioned-l1.md) | [Turkish](versioned-l1.tr.md)

Bu rehber `java-rust-cache:0.9.0` paketindeki isteğe bağlı API'leri anlatır.
REST entegrasyonu ve native dosyalar için eşleşen `rust-java-rest:4.8.0` kullanın.
L1 açıkça seçilmedikçe kapalıdır. Her iş yükünde bellek kazancı sağlamaz.

`L1VersionedJsonCacheReader` ayrı ve açıkça seçilen bir okuyucudur. Mevcut
`RustCache.versionedJsonReader(...)`, `VersionedJsonCacheReader`, üretilmiş
okuyucular ve `VersionedJsonProjectionReaders.create(...)` değişmez. Ayar eklemek
tek başına mevcut okuyucuyu L1'e yönlendirmez.

Üretilen okuyucu artık `@GenerateProjectionReader(..., allowL1 = true)` ile açıkça
L1 seçimine izin verebilir. Başlangıçta `ConfiguredJsonProjectionReaders` kullanılır.
Her projection için uygulama bir kez seçilir. `l1.max-entries` yoksa veya 0 ise
yalnız Redis okuyucusu kullanılır. Mevcut `CacheReadResult` metotları ve bağımsız
byte dizileri korunur. Metot bunun yerine `CacheReadSnapshot` döndürebilir. L1
hit'inde bu yol Java payload dizisi oluşturmadan okuma sahipliğini taşır. Senkron
REST handler'ında `CacheResponses.ownedJson(snapshot, missCode, notReadyCode)` kullanın.
Yanıt yazıcısı işi bitirince sahibi kapatır. Native buffer tekrarı aynı snapshot'ı
korur. Yardımcı metoda devrettikten sonra handler'da kapatmayın. Hiç devretmezseniz
kendiniz kapatın. Bir snapshot yalnız bir yanıta aittir; ortak yanıt cache'i değildir.
Üretilen REST konfigürasyonu, L1 okuyucularını
üretilen `@PreDestroy` metodu ile kapatır. `allowL1 = false` varsayılanını kullanan
kontratlarda eski kod üretimi korunur; L1 ayarı okunmaz ve yeni wrapper oluşturulmaz.
Sample reader ayrı `config/l1.properties` dosyasını içerir. Normal başlangıç L1'i açmaz.

L1, aynı anahtar tekrar okunduğunda Redis çağrılarını azaltır. Bunun için uygulama
içinde ek alan tutar ve ek sınıflar yükler. RPS artarken süreç RSS'i de artabilir.
En düşük bellek önceliğinde Redis-only ile başlayın. L1'i yalnız trafik ve bellek
ölçümü bu ek alanı haklı çıkaran projection'larda açın.

Üretilen snapshot metotları doğrudan `L1VersionedJsonCacheReader.snapshotById`,
`snapshotIndex` veya `snapshotMeta` kullanır. Hit sırasında slot pinlenir; geçici bir
`Lease` oluşturulup aktarılmaz. Tek thread içinde yeniden kullanılan lease metotları
ayrıca korunur. Bilinen sürüm bilgisi, ilk okumada ve boyut sınırını aşan snapshot'ta
da taşınır. Yalnız Redis kullanan okuyucuda sürüm bilgisi olmayabilir.
Snapshot'ın yazma/kapatma durumu, kendi atomik alanında (`VarHandle`) tutulur.
Her yanıt için ayrıca `AtomicInteger` oluşturulmaz. Alan bağlantısı bu isteğe bağlı
sınıf yüklenirken bir kez kurulur; request sırasında alan araması veya reflection yoktur.

İlk okuma ve yeniden doldurma sırasında Redis yine bir Java byte dizisi döndürür.
Veri boş bir L1 slotuna sığıyorsa slab'a bir kez kopyalanır. Yanıt snapshot'ı bu
slotun pin'ini doğrudan devralır. Yanıt için ikinci bir heap payload kopyası oluşmaz.
Doldurma işlemi yine miss sayılır; hit sayacı artmaz. Veri büyükse veya slotlar
kullanımdaysa bağımsız heap snapshot'ı kullanılır. Yanıt kesilmez ve cache büyütülmez.
Java heap'ini, her okuma L1 hit'i olacakmış gibi boyutlandırmayın.

## Başlatma

REST rotası her iki okuyucuyla da çalışacaksa `allowL1 = true` ve `CacheReadValue`
kullanın. Yanıtı `CacheResponses.valueJson(value, missCode, notReadyCode)` veya
`CacheResponses.valueProblem(value)` ile döndürün. Okuyucu başlangıçta bir kez seçilir.
Yalnız Redis kullanan yol, mevcut `CacheReadResult` ve raw byte yanıtını korur.
L1 yolu, buffer tekrarı ve iptal desteğiyle kontrollü snapshot sahipliğini korur.
`CacheReadValue` bir DTO olarak serialize edilmemelidir. Birden fazla yanıtta paylaşmayın.

```properties
app.cache.projections=detail
app.cache.namespace=customer
app.cache.l1.max-entries=64
app.cache.l1.max-entry-bytes=8192
app.cache.l1.max-key-chars=256
app.cache.l1.max-bytes=1048576
app.cache.l1.max-age-ms=1000
app.cache.max-age-ms=60000
app.cache.serve-stale=false
```

`l1.max-entries` yoksa veya 0 ise L1 ayarı kapalıdır. Diğer L1 ayarları yalnızca
bu değer sıfırdan farklıysa okunur. Öncelik sırası mevcut kuralla aynıdır:
projection runtime, genel runtime, projection dosyası, genel dosya.
Örnek projection ayarı: `app.cache.detail.l1.max-bytes`.

Yalnızca yeni `resolveAllWithL1(...)` API'si L1 ayarlarını okur. Eski
`CacheReaderProjectionSettings(name, namespace, freshness)` üç bileşenli record
olarak kalır; constructor'lar, record pattern'leri ve `resolveAll(...)` mantığı
korunur. Eski çözümleme, ayarlar mevcut veya hatalı olsa bile `.l1.*` anahtarlarını
sormaz ve L1 options/wrapper tahsisatı yapmaz.

Sarmalanmış projection başlangıçta çözülür ve okuyucu açıkça oluşturulur:

```java
L1ProjectionSettings setting = CacheReaderProjectionSettings
        .resolveAllWithL1(properties, "app.cache").stream()
        .filter(value -> value.base().name().equals("detail"))
        .findFirst().orElseThrow();
L1VersionedJsonCacheReader reader = VersionedJsonProjectionReaders
        .createL1Reader(cache, setting, 1000);
```

`L1ProjectionSettings(base, options)` ayrı ve değişmez bir wrapper'dır. `base()`
eski record'dur; max-entries yok/0 ise `options()` null olur. `createL1Reader(...)`
bu yeni tipi kabul eder ve kapalı options değerini reddeder. Eski registry factory
imzası veya davranışı değiştirilmez.

Doğrudan `L1VersionedJsonCacheReader.create(cache, namespace, versionCacheMillis,
freshness, options)` da kullanılabilir. Gizli kayıt, reflection, worker, TLS,
otomatik tekrar veya yeni JNI çağrısı eklenmez. Kapanışta önce bu okuyucuyu,
sonra uygulamanın sahip olduğu Redis istemcisini kapatın.

## Yeniden Kullanılan Ödünç Okuma

Her mevcut worker kendi thread'i üzerinde bir lease oluşturup tekrar kullanır:

```java
try (var lease = reader.newLease()) {
    for (long id : ids) {
        reader.getById(id, lease);
        try {
            if (lease.status() == CacheReadResult.Status.HIT
                    || lease.status() == CacheReadResult.Status.STALE_HIT) {
                consumeReadOnlyBytes(lease); // get(index) veya copyTo(hazırDizi, offset).
            }
        } finally {
            lease.close();
        }
    }
}
```

`CacheReadLease`, sınır kontrollü salt-okunur bir görünüm sunar; komşu verileri
açabilecek ham ByteBuffer vermez. Oluşturma, okuma ve kapatma aynı thread'de
yapılır. Başka thread'den okuma veya aktif lease'i kapatma reddedilir; slot
yanlışlıkla serbest bırakılmaz. MISS, STALE ve CACHE_NOT_READY sonuçları da
bir sonraki kullanımdan önce kapatılır. Kaynak hatası hedefi bağlı bırakmaz.

Aktif lease slotu sabitler; yazıcı onu değiştiremez. Versiyon değişmesi veya
okuyucunun kapanması, önceden alınmış snapshot'ı değiştirmez. Lease kapanınca
payload, cache, versiyon ve pin sahipliği bırakılır. Kapalı görünüm okunamaz.
Aynı nesnenin sonraki kullanımı YENİ bir ödünç okumadır: eski referansı değişmez
snapshot gibi saklamayın veya kapatma/yeniden kullanım sonrasında kullanmayın.
Eski alias kimliğini otomatik takip etme garantisi yoktur.

Lease Cleaner veya otomatik leak recovery yoktur. Kapatılmayan lease sabit bir
slotu süresiz pin'leyebilir; okuyucu bırakılana kadar admission kapasitesi azalır.
Sabit slab büyümez: iki slot da pin'liyse sahipli heap kopyasına geçilir. Bu
kopyalar miss başına tahsisat yapar; bellek sınırı unutulan lease'i zararsız yapmaz.
Kopyalı sonuç döndüren metotlar slotu yalnız kopyalama sırasında sabitler ve
`finally` içinde serbest bırakır. Her çağrıda geçici lease oluşturmaz. Hit'te
yanıt dizisi ve `CacheReadResult` yine oluşturulur; bu, sıfır tahsisatlı lease API'si değildir.

`getIndex(..., lease)` ve `getMeta(lease)` aynı kurallara uyar. `segment` / `standard`
gibi normalleştirilmiş ASCII anahtarlarda L1 hit, iki parçayı doğrudan slab ile
karşılaştırır. Birleşik anahtar String'i oluşturmaz. Redis'e gidilen miss yolunda
anahtar eskisi gibi oluşturulur. Türkçe harf, emoji veya özel karakterleri
normalleştirmek hâlâ tahsisat yapabilir. Farklı anahtarlar aynı hash'e sahip olsa
bile tam anahtar karşılaştırılır. Tahsisat testleri hem sayısal ID hem ASCII
segment/index yolunu kapsar. `lease.bytes()` her zaman savunmacı kopyadır.
Lease'siz metotlar, payload'ı yeni ve çağırana ait diziye kopyalayan
`CacheReadResult` döndürür; onun değiştirilebilir `bytes()` dizisi slab'ı bozamaz.
Bu eski biçimli sonuçlarda çağrı başına <=32 byte sözü verilmez.

## Bellek Sınırları

`VersionedL1Cache.Options`, slot sayısı, en büyük payload, tam String anahtarının
UTF-16 uzunluğu, L1 yaşı ve TOPLAM depolama `maxBytes` sınırını alır. Tahsisattan
önce doğrulanan hesap şöyledir:

```text
slabBytes = entries * (maxEntryBytes + 2 * maxKeyChars)
totalBytesEstimate = slabBytes + 8192 + 96 * entries
totalBytesEstimate <= maxBytes
```

Varsayılanlar: 64 slot, 8192-byte payload, 256 UTF-16 birim anahtar, 1000ms L1
yaşı ve 1 MiB bütçe. Slab 557056 byte, muhafazakâr toplam tahmin 571392 byte'dır.
Sınırlar: 2..65536 iki-kuvveti slot, 1..262144-byte payload, 1..4096 birim String
anahtar, negatif olmayan yaş ve 1 byte..64 MiB bütçe. Güvensiz
65536 x 262144 slab isteği tahsis edilmeden reddedilir. Payload uzunluğu 16-bit
paketlenmez; `int`, çarpım doğrulaması ise `long` kullanır.

Bütçe slab ve sabit Java metadata/okuyucu sahipliğini kapsar; çağırana ait lease
nesneleri, dönen/soğuk-yol kopyaları, mevcut Redis istemcisi, clock/provider
iç yapıları ve JVM'in tahmin dışındaki bellek yönetimi dahil değildir. Her açıkça
oluşturulan okuyucunun bütçesi ayrıdır; çoklu okuyucular uygulamada toplanmalıdır.
Bu hesap +3 MiB RSS/container kabul kapısının kanıtı değildir.

L1 slotları payload byte[] veya anahtar String saklamaz. Tam String anahtarları
slab'a UTF-16 olarak kopyalanır; sayısal kimliğin 64 bitinin tamamı sabit metadata'da
tutulur. Hash yalnızca set seçer, kimlik kanıtlamaz. CAS okuyucu pinleri yazmayı
engeller. İki slot da sabitse, payload büyükse veya index kimliği kabul sınırını
aşıyorsa geçerli sonuç reddedilmez: L1'e alınmadan sahipli heap kopyası döner.

`reader.close()` kaynak/pointer/slab sahipliğini bırakır, verilen Redis istemcisini
kapatmaz. Aktif lease slab'ı hayatta tutar. Güvenli standart Java21 explicit-free
API'si kullanılmadığından direct buffer JVM GC'si ile geri alınır; anlık native
bellek bırakma iddiası yoktur. Unsafe, reflection cleaner, preview API, zorunlu
GC ve bakım thread'i yoktur.

## Versiyon, Yaş Ve Hatalar

Pointer yenilenirken mevcut fenced-version ve timestamp kuralları uygulanır.
Timestamp her hit'te değil, yenileme başına bir kez ayrıştırılır. Saat
`Clock.millis()` ile okunur; Instant tahsisatı yapılmaz. Pointer yoksa
CACHE_NOT_READY, veri yoksa MISS döner; ikisi de pozitif cache'e alınmaz.
`serveStale=false` iken STALE sonucu L1/veri Redis çağrısından önce döner.
İzin verilen STALE_HIT, Redis veri çağrısı olmadan L1'den dönebilir.

Gözlenen versiyon değişince 64-bit epoch tek atomik geçişle, O(1) değiştirilir;
slab silinmez ve slotlar dolaşılmaz. Önceki versiyon adına dönmek eski slotu
diriltmez. Long.MAX_VALUE değerinde epoch sarılmaz; L1 kalıcı bypass'a geçer.
Tekrar etkinleştirmek için okuyucuyu değiştirin. `invalidateVersionCache()`
pointer'ı ve veriyi O(1) geçersiz kılar.

Pointer poll aralığı dolana kadar son gözlenen versiyon kullanılabilir. Her
okumada poll için aralık 0 veya yayın sonrası açık invalidation seçin. Aralık 0,
Redis/ayrıştırma tahsisatlarını içerir ve sıfır-tahsisat warm-loop ayarı değildir.
`l1.max-age-ms` ayrıca `yaş >= sınır` noktasında slotu eskimiş sayar; 0 bu L1
yaş sınırını kapatır. Snapshot freshness mevcut `now > created + maxAge` sınırını
korur. Saatin geriye gitmesi pointer yenilemesini tetikler ve gelecekte yazılmış
gibi görünen slotların kullanılmasını engeller.

Bridge'de atomik versioned-json JNI işlemi yoktur. Paket-içi kaynak adapter'i
`RustCache.getString/getBytes` ve gözlenen versiyonu içeren veri anahtarını
kullanır; atomik Redis transaction/latest-pointer garantisi vermez. Eşzamanlı
devam eden okuma eski snapshot'ı tutabilir. Miss sırasında gözlenen generation
değişirse eski miss sonucu CACHE_NOT_READY olarak bırakılır. Eşzamanlı soğuk
miss'ler birden fazla veri çağrısı yapabilir. Pointer aralığı dolan her çağıran
Redis'i bağımsız olarak en fazla bir kez poll eder; aralık 0 ise her çağrıda poll
yapılır. Single-flight kilidi, I/O bekleme/spin, yarış nedeniyle ret, yenileme
sırasında ek stale toleransı veya gizli retry yoktur. Burst sırasında çağıran
sayısı kadar pointer sorgusu olabilir; toplam concurrency mevcut Redis
istemcisinin admission/timeout sınırlarına tabidir, global tek-poll garantisi yoktur.

Poll yalnızca I/O ÖNCESİNDE aldığı pointer durumu hâlâ geçerliyse yayınlanabilir.
CAS kaybeden poll kendi değerini atıp kazanan gözlemi kullanır; yavaş eski poll,
başka poll'un yayınladığı yeni değeri geri alamaz. Aynı versiyonun yenilenmesi
generation kimliğini korur ve devam eden veri okumasını iptal etmez. Açık
invalidation, versiyon değişmesi, saat rollback veya timestamp doğrulamasının
değişmesi (gelecekteki timestamp'in geçerli olması gibi) yeni generation oluşturur;
kapanış terminaldir. Epoch dolduğunda da kimlik yeniden kullanılmaz, slab bypass
kalır. Eski poll okuyucuyu yeniden açamaz veya invalidation işaretini ezemez.
Veri okuması sırasında gerçek generation değişirse eski sonuç CACHE_NOT_READY
olarak bırakılabilir. Redis hataları gizlenmez; eski veriyi hatada sessiz sunma veya
otomatik retry yoktur. Mevcut istemci timeout'ları geçerlidir.

## Başlangıçta Seçilen HTTP Responder

Eski `CacheResponses.json(...)` metotları değişmez; yeni property'yi okumaz veya
kapalı özellik için yanıt başına ek dal çalıştırmaz. Yeni davranış açıkça
başlangıçta seçilir:

```properties
reactor.cache.responses.mark-stale-hit=true
```

```java
CacheJsonResponder responder = CacheJsonResponder.fromProperties(
        properties, CacheResponsePolicy.production("customer_not_found"));
return responder.json(reader.getById(id));
```

`properties`, uygulamanın `ProjectionPropertySource`/`CacheProperties` nesnesidir.
Property yalnızca factory çağrısında bir kez çözülür. Yok/boş değer false; diğer
değerler büyük-küçük harf duyarsız true veya false olmalıdır. Responder sabit policy'yi
tutar, properties nesnesini tutmaz. Sonradan ayar değişirse responder değiştirilir.
`CacheJsonResponder.create(policy, boolean)` property çözümlemesi yapmaz. Eski
çağıranlar için factory otomatik çağrılmaz.

Kapalı implementation doğrudan eski JSON metoduna gider. Açık implementation
yalnızca STALE_HIT için `X-Cache-Stale: true` ekler; reddedilen STALE, MISS ve
CACHE_NOT_READY aynı kalır. Body/status/error policy değiştirilmez. Mevcut
`problem(...)` davranışı korunur. REST response/header nesneleri ve payload
kopyası tahsisat yapabilir; bu API lease tahsisat testi veya sıfır-kopya HTTP
transport garantisi değildir. Örnekte lease kapandıktan sonra view değil,
çağırana ait kopyalı `CacheReadResult` kullanılır.

## Doğrulama Kapsamı

Odaklı testler kimlik çakışması, epoch/ABA, tam bütçe sınırı, 65536 ve 262144-byte
payload, pin/eşzamanlı eviction, lease kapanışı, freshness/saat sınırları, kaynak
hataları, pointer yarışları, ayarlar ve savunmacı kopyaları kapsar. Tahsisat testi
büyük ve küçük tahsisat pozitif kontrolleriyle ThreadMXBean üzerinden sayısal-ID
ve ASCII segment/index yollarının her birinde 100000 warm hit ölçer:
yeniden kullanılan lease, bir-byte payload, sabit tahsisatsız Clock, cache'lenmiş
pointer ve kapalı L1 yaş sınırı kullanılır. Kaynak sayacı paket-içi test seam'idir;
gerçek Redis/native transport benchmark'ı değildir. Soğuk okuma, pointer poll,
eski byte[] sonuçları ve index normalleştirme tahsisat yapabilir. Bu testler
release/RSS/RPS/p99 kabulü anlamına gelmez.

Önceki, yalnız sayısal yolun tahsisatını ölçen 61 testlik seçim Semeru/OpenJ921.0.2 üzerinde 60 pass ve bir açık tahsisat-testi
skip ile, failure/error olmadan tamamlandı. Tutulan 100 byte[32] için ilk batch
0 byte verdi. Önceki OpenJ9 sıfır-byte okumaları tahsisat sertifikası olarak geri
çekildi; büyük kontrol tek başına yeterli değildir. Test sekiz batch'in her birinde
4000..6400 byte zorunlu kılar, aksi durumda skip olur. Önceden oluşturulan Object[100]
ölçüm dışında static volatile alana bağlanır ve finally içinde kök referans temizlenir.

Aynı 61 odaklı test GraalVM HotSpot21.0.6 üzerinde sıfır failure/error/skip ile geçti.
Sekiz küçük-kalibrasyon batch'inin her biri tam 4800 byte ölçtü. Sonraki 100000 warm
reusable-lease hit toplam 0 byte; büyük kontrol 262160 byte ve veri kaynağı sayacı
1 ölçüldü. Bu kalibre edilmiş sonuç ilgili HotSpot çalışmasına aittir; OpenJ9
tahsisat sertifikası değildir. Uyumluluk testleri eski üç bileşenli Java21 record
pattern'ini preview olmadan derleyip çalıştırır; bileşen sayısı/adı/tiplerini doğrular.
Hatalı L1 ayarları mevcutken bile eski resolver'ın yalnızca önceki 13 property
çağrısını yaptığını, L1 anahtarı sormadığını kaydeder. Projection suite yedi testtir.
64 eşzamanlı çağıran
testleri, aralığı dolan ve aralığı 0 olan pointer okumalarını refresh reddi olmadan
kapsar. Yavaş eski poll, aynı-versiyon miss yarışı, invalidation/close ve saat/
gelecek-timestamp geçişleri deterministik testlerle doğrulandı. Bunlar odaklı unit/
seam sonuçlarıdır; canlı Redis veya endpoint yük testi kabulü değildir.
