# Devir notu

Bu depo, masaüstü istemcisiyle birlikte tek bir proje olarak ilerliyor. Devir
notunun tamamı orada:

**`../Maze-Connect/docs/HANDOFF.md`**

Yeni bir sohbete başlarken önce onu okutun — nerede kalındığı, tekrar
tartışılmaması gereken güvenlik kararları ve gerçek cihazda pahalıya öğrenilen
hatalar orada.

## Bu depoya özgü, hemen bilinmesi gerekenler

- **Durum (2026-08-01):** `assembleDebug test lint` yeşil — 58/58 test, lint
  ve derleyici uyarısı temiz. Plandaki beş adımın hepsi yazıldı (pano, komutlar, Maze AI,
  killswitch'ler) ama **eşleşmiş bir bilgisayarla uçtan uca tur
  yapılmadı** — emülatörde ekranlar ve boş durumlar doğrulandı, canlı veri
  akışı değil.
- Gezinme artık **altta** (`BottomNav` içinde `MainActivity.kt`). Üstteki
  sekme satırı üç sekmede sarıyordu ve daha fazlası gelecek.
- Maze AI: telefon **tek satır metin** gönderir. Geçmiş ve sistem istemi
  bilgisayarda durur, o yüzden `AiState.turns` yalnızca bu ekranın gösterdiği
  şeydir — otorite değil. Model adı da listede yoksa karşı taraf reddeder.
- **Yetenek anahtarları yok.** Eşleştirme her şeyi veriyor
  (`Capability.DEFAULT_ENABLED == SUPPORTED`). Geri alma masaüstünde yapılır.
  Geri alınırsa bilgisayar **gerekçeli cevap** gönderir — ekran "asking…"de
  takılmaz; o davranışı geri getirmeyin.
- Alt gezinme yatay kaydırılabilir ve her öğe kendi metnine göre boyutlanır.
  Eşit sütunlara bölmek yedi sekmede yazıları kesiyordu.
- **Dosya alımı çalışıyor.** Inbox `files/Download/inbox`; `FileProvider`
  kapsamı (`res/xml/file_paths.xml`) **sadece** orası — genişletmeyin, o
  kapsam güvenlik sınırı. Teklif asla otomatik kabul edilmez.
- **Widget** (`widget/`) launcher'ın sürecinde çizilir, bağlantıya erişemez.
  Yalnızca hostname + üç ölçer diske yazılır; IP/çekirdek/donanım/güvenlik
  servisleri **kasten yazılmaz**. Yaşını da gösterir — zaman damgasız eski
  değer canlıymış gibi görünür.
- Pano artık `statusUnchanged` alabilir: "ekrandaki okuma hâlâ güncel"
  demektir, eksik cevap değil. Hatayı temizler, veriyi korur.
- **Killswitch yönü:** `setGuardKill(..., enabled)` — `true` cihaz *çalışır*,
  `false` *engelli*. `maze-guardd` ve `rfkill` sözlüğü. "Koruma açık/kapalı"
  diye okumayın; öyle okununca Block unblock yapıyordu ve öyle de şipti.
- **Üç widget var:** 4×2 pano (ölçerler + sertleştirme + servis sayısı),
  2×1 kompakt, ve 4×2 **kontroller** — sonuncusu ana ekrandan killswitch
  çeviriyor. Dokunuş `ControlsWidget.onReceive`'e broadcast atar, oradan
  foreground service üzerinden canlı manager'a ulaşır.
- Widget verisi arka planda 60 sn'de bir tazeleniyor; Dashboard sekmesine
  girilmesi gerekmiyor (girilmesi gerekiyordu, widget o yüzden boştu).
- **Klavye:** `safeDrawingPadding()` IME'yi zaten içerir. `imePadding()`
  eklemeyin — iki kez sayılır ve içeriği ekranın üstünden atar. Klavye
  açıkken masthead/durum/gezinme gizlenir.
- **Widget satırları ayrı id'lerle yazılı.** `<include>` kullanmayın:
  RemoteViews id'leri bütün ağaçta çözer, üç include tek satırı doldurur.
- Dosya gönderme SAF seçicisi kullanır; dosya karşı taraf kabul edene kadar
  açılmaz.
- OLED teması yok, tema anahtarı yok.
- Killswitch'ler tek ayrıcalıklı yetenek. `guardRequest` bir cihaz adı ve bir
  boolean taşır — **fiil alanı yok**, yani PANIC/RESTORE bu protokolde
  yazılabilecek bir yere sahip değil. Cihaz adı `GUARD_DEVICES` dışındaysa
  ekranda basılabilir bir şey olarak hiç çizilmez.
- `GuardScreen` **iki yönde de** onay sorar. Korumayı *kapatmak* daha ağır
  yön ve yanlış dokunuşun sessizce yapacağı şey o.
- Komut çalıştırma: telefon yalnızca **id** gönderir. `RemoteCommand`'da argv
  yok çünkü bilgisayar göndermiyor. `confirm` işaretli girdiler burada bir
  kez daha sorulur.
- Panonun okuduğu her şey karşı makineden geliyor ve
  `core/.../protocol/SystemStatus.kt` içinde alan alan sınırlanıyor. Bağlantı
  kimliği doğrulanmış, karşı makine güvenilir değil — yeni bir alan
  eklerken oradaki sınırlardan geçirin, ekranda değil.
- `Capability.SUPPORTED` (hello'da duyurulan) ile `DEFAULT_ENABLED` (yeni
  eşleşmede saklanan) hâlâ ayrı kavramlar, ama **artık aynı kümeyi**
  döndürüyorlar: eşleşen bir telefon her yeteneği kullanabilir. Ayrım
  bilerek duruyor — ileride biri varsayılan dışı kalırsa yazacak yer orası.
- Launcher ikonu artık gerçek: uyarlanabilir ön plan `drawable-*/`,
  eski başlatıcılar için `mipmap-*/`. Ayrıntısı masaüstü devir notunda.
- `PROTOCOL_VERSION = 3` — masaüstündeki `kProtocolVersion` ile birebir
  aynı olmak zorunda, uyuşmazlıkta bağlantı reddedilir. 2'den 3'e çıkarıldı:
  mesaj tipi sayısı 10'dan 26'ya çıkmıştı ve sürüm hâlâ 2'de duruyordu, yani
  eski bir derleme yeni mesajları anlamadan bağlanabiliyordu.
- Artık **tek APK** var; `notifications` product flavour'ı ve
  `NotificationListenerService` kaldırıldı, dolayısıyla Play Protect'in yan
  yükleme engeli de ortadan kalktı.
- `SecretDetector.kt` kaldırıldı (pano özelliği yok, çağıranı kalmamıştı).
- İmzalama materyali `keystore/` ve `keystore.properties` içinde, git'te
  değil. Parola devir notunda.


---

## 0.5.0 — bu turda değişenler

**Widget killswitch'leri artık tepki veriyor.** Vermemelerinin sebebi
tahmin edildiği gibiydi: dokunuş doğrudan uygulanıyordu ve **hiçbir geri
bildirim kanalı yoktu**. Bilgisayar ulaşılamazsa hücre değişmiyor, dokunuş
yok sayılmış görünüyordu. Araya `GuardConfirmActivity` girdi — uygulama içi
`GuardScreen` gibi iki yönde de soruyor, ve ulaşılamama durumunu ekranda
söyleyebiliyor.

İkinci ve daha sinsi hata: `PendingIntent`'lar **yalnızca extra'larında**
farklıysa Android onları eşit sayar (`filterEquals` extra'lara bakmaz), yani
dört hücre tek bir intent'i paylaşıyordu. Her hücreye ayrı `requestCode`
verildi.

**AI bölümündeki iki klavye boyu kayma.** İlk denemede `imePadding()`
eklenmişti; `safeDrawingPadding()` zaten IME'yi içerdiği için kaymayı
**iki katına çıkardı**. Doğrusu: `systemBarsPadding()` +
`displayCutoutPadding()` (yani IME hariç her şey) ve klavyeyi manifest'teki
`adjustResize`'a bırakmak. Klavye açıkken `Masthead` de gizleniyor.

**Logo** `maze-connect-mobile-logo.png`'den yeniden üretildi — tüm
yoğunluklar, uyarlanabilir ön plan + round + eski başlatıcı.

Sürüm 0.5.0 / versionCode 5. 58 test geçiyor, derleyici uyarısı yok.


---

## 0.6.0 — bu turda değişenler

**Widget onayı "bağlı bilgisayar yok" diyordu, çünkü öyleydi.** Widget'a
basmak uygulamayı *başlatan* şey olabiliyor; aynı anda bağlı bir bilgisayar
sormak her zaman başarısız olur, çünkü çevirmek ve TLS el sıkışmasını
bitirmek bir diyalogu yerleştirmekten uzun sürer. `GuardConfirmActivity`
artık servisi başlatıyor, guard'a izin veren bir bilgisayar **bekliyor**
(12 sn), sonra gönderiyor — ve her durumda ekranda ne olduğunu söylüyor.
`DeviceManager.allows(deviceId, capability)` bunun için açıldı: yalnızca
`connectedIds`'e bakmak reddedecek bir bağlantıya göndermek olurdu.

**Klavye — üçüncü ve doğru deneme.** Sorun iki yönde de yapılmıştı:
`safeDrawing` + `imePadding()` klavyeyi iki kez çıkarıyordu; IME'yi tümüyle
bırakıp `adjustResize`'a güvenmek ise hiçbir şey yapmıyordu, çünkü
`enableEdgeToEdge` altında pencere sistem pencerelerine oturmuyor ve
`adjustResize` hiç küçültmüyor. Doğrusu tek bir
`windowInsetsPadding(WindowInsets.safeDrawing)`: alt kenarı gezinme çubuğu
ile klavyenin **büyüğü**, toplamı değil. Altına `imePadding()` eklemeyin.

**Eşleşme kaldırma yarışı.** `unpair` gönderimi coroutine'e atılıp bağlantı
o an kapatılıyordu; kapatma neredeyse her zaman kazanıyor, bildirim hiç
çıkmıyordu. Artık gönderim tamamlandıktan sonra kapanıyor. Ayrıca eşleşmemiş
bir eş eşleşme dışı bir şey istediğinde ona `unpair` gönderiliyor — çevrimdışı
kaldırmalarda kaybolan bildirimi telafi ediyor.

**İkon.** Kaynak logo kendi siyah yuvarlak-kare zeminini taşıyor. Uyarlanabilir
ikonun ön planına olduğu gibi konunca zaten siyah olan arka plan katmanının
üstünde ikinci bir kutu oluşuyor, mark da onun içinde kalıp küçük *ve*
kutulanmış görünüyordu. Artık siyah şeffaflaştırılıp yalnızca beyaz çizim
alınıyor ve tuvalin %55'ine oturuyor (güvenli alan 66/108, maske 72/108 —
ikisine de değmiyor). Efsanevi ikonlar kendi zeminini taşımaya devam ediyor
ama mark kenara dayanmıyor; yuvarlak sürüm siyah bir diskin üstünde.

Sürüm 0.6.0 / versionCode 6. 58 test geçiyor, uyarı yok.


---

## 0.7.0 — bu turda değişenler

**Bağlantı arka planda değil, onay penceresi açılınca kopuyordu.** İki neden
üst üste binmişti ve ikisi de gerçekti:

1. Widget'ın intent'i `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK`
   taşıyordu. `CLEAR_TASK` *uygulamanın* görevini temizliyor, yani onay
   penceresini açmak MainActivity'yi bitiriyordu. Artık yalnızca `NEW_TASK`,
   ve `GuardConfirmActivity` manifest'te `taskAffinity=""` +
   `singleInstance` ile kendi görevinde duruyor.
2. MainActivity bitince `AppState.onCleared()` çalışıyor ve **manager'ı da
   servisi de durduruyordu** — oysa servisin kendi belgesi "bağlantı
   uygulama arka plana geçince yaşar" diyordu. Kod yorumun tersini
   yapıyordu.

Düzeltme `service/Link.kt`: `DeviceManager` artık sürecin, ekranın değil.
Scope'u UI yaşam döngüsüyle hiç iptal edilmiyor; yalnızca servis yok
edilince — ki kullanıcının bağlantıyı gerçekten durdurduğu an odur.
`ensureStarted` idempotent, çünkü hem ilk ekrandan hem de servisten
çağrılıyor ve iki kez başlamak ikinci bir dinleme soketi açardı.

Yanında iki şey daha: `MulticastLock` ve `WifiLock` alınıyor (Android
multicast'i kilit olmadan uygulamaya hiç vermiyor — keşfin tamamı ona
bağlı), ve ağ callback'i artık `NET_CAPABILITY_INTERNET` istemiyor. Bu bir
LAN uygulaması; internete çıkışı olmayan bir Wi-Fi tam da çalışması gereken
durum, oysa filtre tam onları haber vermiyordu.

**Widget boyutları.** Dashboard 4x2 (barlar alt alta, sertleştirme özeti)
ve yeni 4x1 (aynı üç ölçüm, yan yana üç sütun). İkisi **aynı id'leri**
kullanıyor, yani `render()` dallanmadan ikisini de dolduruyor — iki widget
arasındaki fark kod değil, layout dosyası. Killswitch widget'ı 4x2'den 4x1'e
indi ve hücrelere cihaz ikonları geldi; ikonun rengi de duruma uyuyor.

**"Codes match"** artık cevaplandığını söylüyor (`localAccepted` zaten
akışta vardı, sadece UI'ya taşınmamıştı).

**İkon seti** masaüstündekiyle birebir aynı geometriden üretildi
(`res/drawable/ic_*.xml`), alt menüde ve Guard satırlarında kullanılıyor.

Sürüm 0.7.0 / versionCode 7. 58 test geçiyor, uyarı yok.


---

## 0.8.0 — bu turda değişenler

**Bağlantı kopma/yeniden bağlanma kök nedeni** masaüstüyle ortak —
detayı `../Maze-Connect/docs/HANDOFF.md`'deki 0.8.0 notunda. Bu tarafta
`Connection.kt`'ye aynı Ping/Pong heartbeat'in mobil yarısı geldi
(`heartbeatLoop()`, `readLoop()`'ta `lastActivityMs` damgası,
`drainFrames()`'te Ping/Pong yakalama) ve eşleşmiş ama "bağlı değil"
görünen cihazlar için Devices ekranına bir **Reconnect** düğmesi eklendi
(`DeviceManager.forceReconnect` → `AppState.reconnect` →
`DevicesScreen`'de yalnızca `!device.connected` iken görünüyor).

**Dashboard widget'ı gerçekten detaylandı.** İstek "sadece 3 bar var, daha
detaylı birkaç boyda widget istiyorum" idi. Üç yapıldı:

- **4x1 (Compact):** değişmedi — üç ölçüm yan yana.
- **4x2 (Dashboard):** üç bar değil dört; `widget_dashboard.xml`'e
  `meter_row_4` eklendi, `minHeight` 110dp→130dp.
- **4x4 (yeni, Large):** asıl detay burada. Altı bara kadar, artı
  sertleştirme skoru/servisler/ağ tek satıra sıkıştırılmış cümle yerine üç
  ayrı hücrede ("N%", "M/N", "M/N") — `widget_cell.xml` arka planıyla,
  `widget_large.xml` + `large_widget_info.xml`.

`WidgetSnapshotStore` de büyüdü: `MAX_METERS` 3→6, ve servisler/ağ/sertleştirme
kontrolleri için de aynı "isim değil sayı" deseni kullanıldı
(`networkGood`/`networkKnown`, `hardeningPassed`/`hardeningTotal`) —
dosyanın kendi belgelediği gizlilik ilkesiyle aynı çizgide: IP, çekirdek
sürümü, hangi servisin adı gibi hiçbir şey diske yazılmıyor, yalnızca
sayılar.

Yol boyunca gerçek bir hata da çıktı: `DashboardWidget.refresh()`
Compact'i her zaman `slots=1` ile çiziyordu (kod yorumu "ikisi de üçünü
gösterir" derken), oysa `onUpdate()`'in ilk yerleştirmesi `meterSlots`
üzerinden doğru şekilde 3 çiziyordu — yani widget ilk konduğunda 3 bar,
ilk canlı güncellemeden sonra 1 bara düşüyordu. `refresh()`/`redraw()`
artık her boyutun kendi `DashboardWidget` alt sınıf örneğinden
(`layout`/`meterSlots`/bayraklar) okuyor, sayı iki yerde elle
tekrarlanmıyor.

Sürüm 0.8.0 / versionCode 8. 58 test geçiyor, uyarı yok.


---

## 0.9.0 — bu turda değişenler

**Android paylaşım menüsü.** Yeni `share/ShareReceiverActivity` —
`ACTION_SEND`/`ACTION_SEND_MULTIPLE`, mime `*/*`, exported. Var olan
`DeviceManager.sendFile()`'ı kullanıyor (zaten "ilk ulaşılabilen eşleşmiş
bilgisayar"ı otomatik seçiyordu — `TransfersScreen.kt`'nin OpenDocument
akışıyla aynı fonksiyon). `GuardConfirmActivity`'nin "servisi başlat,
bağlı+izinli cihazı bekle, sonra yap ve bildir" şeklini birebir alıyor.

**Commands widget'ı (4x1), `ControlsWidget`'ın aynısı iskelet.**
`RemoteCommand` artık `pinned: Boolean` taşıyor (`handleCommandCatalog`
`"pinned"` alanını okuyor — masaüstünden geliyor, telefon hiç
yazamıyor). `WidgetSnapshotStore`'a `savePinnedCommands()`/
`pinnedCommands()` eklendi, en fazla 4 — widget'ın hücre sayısı kadar.
`AppState.followSnapshotsForWidget()`'a üçüncü bir `collect` bloğu
girdi; `Capability.COMMANDS` kapatılınca `clearCommands()`'ın state'i
null yapması "hiçbir şey değişmedi" gibi görünüp widget'ı
güncellemeden bırakıyordu — o da elle düzeltildi.

Dokununca yeni `CommandRunActivity` açılıyor (`GuardConfirmActivity`
deseni, ama onay şartlı: `confirm=false` olan bir komutta widget dokunuşu
zaten onayın kendisi — uygulama içi Commands ekranıyla aynı kural).
Kataloğu tazeleyip komutun hâlâ orada olup olmadığını kontrol ediyor
(pinlendiğinden beri silinmiş olabilir), çalıştırıyor, sonucu (çıkış
kodu/çıktı) `CommandsScreen.kt`'nin `LastResult`'ı gibi gösteriyor.

**"Telefonda aç" — bildirim üzerinden, direkt açma değil.** Android,
arka plan/servis bağlamından Activity başlatmayı kısıtlıyor — mesaj tam
da böyle bir bağlamda (`MazeConnectService`) geliyor, o yüzden doğrudan
`startActivity()` çoğu zaman sessizce başarısız olurdu. Bildirime
dokunma bu kısıttan muaf, o yüzden varış bir bildirim gönderiyor, *asıl
işi dokunma yapıyor*: link ise `ACTION_VIEW`, değilse yeni
`OpenOnPhoneReceiver` (bir broadcast, activity değil) panoya kopyalıyor.
Yeni capability `OPEN_ON_PHONE`, yeni `MessageType.OPEN_ON_PHONE` —
tamamı bilgisayar → telefon, mobil tarafta hiç gönderilmiyor.

Sürüm 0.9.0 / versionCode 9. 58 test geçiyor (InteropTest'in
`guardMessageShape` testine OpenOnPhone assertion'ları eklendi), uyarı yok.


---

## 0.9.1 — bu turda değişenler

Gerçek cihazda denendiğinde çıkan iki hata.

**Commands widget'ı hiç dolmuyordu.** Sebep, dashboard widget'ının 0.7.0'da
yaşadığı hatanın birebir aynısı: `requestCommands()`'ı yalnızca uygulama
içi Commands ekranı açılınca çağıran bir yer vardı, başka hiçbir şey yoktu.
`DeviceManager.startReconnectLoop()`'taki arka plan okuma döngüsü zaten
`requestStatus`/`requestGuardStatus`'u her `BACKGROUND_STATUS_INTERVAL_MS`
(60sn) bir bağlı cihaz için çağırıyordu — `requestCommands`'ı oraya
eklemeyi unutmuşum. Artık üçü de aynı döngüde.

**2x1 widget sadece cihaz adı gösteriyordu.** Bilinçli bir tasarım
kararıydı ("bu boyutta okunaklı bar sığmaz") ama kullanıcı isteği net:
en azından birkaç metre olsun. `widget_mini.xml` artık `widget_compact.xml`
ile aynı sütun deseni — sadece 3 yerine 2 sütun (yarı genişlik, yarı sütun
sayısı, sütun başına aynı oran). Age satırı kalktı (yer yok), `Mini.meterSlots`
0'dan 2'ye çıktı — `render()` kodunda hiçbir dallanma gerekmedi, aynı id'ler
zaten her boyutta paylaşılıyordu. `mini_widget_info.xml`'in minHeight'ı
40dp'den 58dp'ye çıktı.

Sürüm 0.9.1 / versionCode 10. 58 test geçiyor, uyarı yok. (Desktop bu turda
değişmedi, 0.9.0'da kalıyor.)


---

## 0.10.0 — çoklu cihaz desteği

Kullanıcı aynı anda hem bir ThinkPad'e hem bir MSI kasaya bağlıydı, ama
uygulama sadece en son cevap veren bilgisayarı gösteriyordu — ikinci
cihazın verisi sessizce eziliyordu. Kök sebep `DeviceManager.kt`'deki dört
state flow'un (`systemStatus`/`commands`/`ai`/`guard`) her birinin tek bir
`T?` tutması, cihaz kimliğine göre değil. Ekranlar da her zaman
`devices.firstOrNull { paired && connected && capability }` ile "ilk
uygun" cihazı seçiyordu, geçersiz kılma yolu yoktu.

**Çekirdek: state map'lere döndü.** Dört flow artık `Map<String, T>`,
cihaz id'sine göre anahtarlı. `handleStatusReport`/`handleCommandCatalog`/
`handleCommandResult`/`handleAiChunk`/`handleAiDone`/`handleGuardReport`
ve tüm `request*`/`clear*` metodları map get/put'a çevrildi. Bu geçişte
iki ek gizli tekil-cihaz hatası bulundu ve düzeltildi: `aiRequestId` tek
bir `AtomicLong`'du (ikinci cihazın AI cevap parçaları sessizce
düşüyordu), `commandRuns` de hangi cihaza ait olduğunu tutmuyordu
(`forgetPendingWork` bir cihaz koptuğunda TÜM bekleyen komut/AI
isteklerini map'i temizleyerek siliyordu — bağlı kalan diğer cihazın işi
de dahil).

**AppState: seçili cihaz.** Yeni `selectedDeviceId` — hiçbir şey
seçilmemişse veya seçili cihaz koptuysa ilk bağlı+eşleşmiş cihaza
otomatik düşüyor (eskisiyle aynı davranış, tek cihazlı durumda hiçbir
şey değişmiyor). `systemStatus`/`commands`/`ai`/`guard` artık
`combine(manager.xxx, selectedDeviceId)` — dört ekran da (`Dashboard`,
`Commands`, `AI`, `Guard`) hâlâ tek bir nullable state tüketiyor, sadece
`target`'ı kendi `firstOrNull`'u yerine paylaşılan seçimden okuyor.

**Uygulama içi geçiş: Masthead'de cihaz çipleri.** Birden fazla
bağlı+eşleşmiş cihaz varken (yoksa hiç görünmüyor) başlığın altında bir
çip satırı — dokunmak `selectDevice()` çağırıyor, dört ekran ve
widget'lar aynı seçimi paylaşıyor.

**Widget'lar: her örnek kendi bilgisayarını seçiyor.** Standart Android
deseni — hava durumu widget'ının "hangi şehir" sorusuyla aynısı: yeni
`WidgetDeviceConfig` (SharedPreferences, appWidgetId → deviceId),
widget yerleştirilirken bir kez gösterilen üç `*ConfigureActivity`
(Dashboard boyutları — Mini/Compact/varsayılan — paylaşıyor; Commands ve
Controls kendi configure activity'sine sahip). `WidgetSnapshotStore`'un
üç dosyası da (`widget-snapshot-*.json` vb.) artık cihaz id'sine göre
ayrı; `AppState.followSnapshotsForWidget()` artık map'teki HER cihazın
verisini yazıyor, sadece seçili olanınkini değil — açık olmayan sekmedeki
cihaza yapılandırılmış bir widget da güncel kalsın diye.
Yapılandırılmamış (bu güncellemeden önce yerleştirilmiş) bir widget en
taze okumanın ait olduğu cihaza düşüyor, boş kalmak yerine.
`GuardConfirmActivity`/`CommandRunActivity` da artık hedef cihaz id'sini
taşıyor — iki bilgisayar bağlıyken "ilk cevap veren" yerine widget'ın
yapılandırıldığı cihaza gidiyor. Widget dokunuşu uygulamayı açarsa, o
bilgisayar uygulama içi seçimde de otomatik öne geliyor.

Sürüm 0.10.0 / versionCode 11. `:core:testDebugUnitTest` ve
`:app:lintDebug` temiz. (Desktop bu turda değişmedi.)
