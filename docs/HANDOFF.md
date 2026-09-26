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


---

## 0.10.1 — reconnect butonu tepki vermiyordu

Bildirilen hata: Reconnect butonuna dokununca hiçbir şey olmuyor, ~2 dakika
bağlantı kesildikten sonra kendiliğinden de kolay kolay geri gelmiyor.
Firewall kontrol edildi — `maze-connect` firewalld servisi zaten aktif
zonda TCP+UDP 38271 için açık, sorun orada değildi.

Kök sebep: `reconnectPairedDevices()` sadece o an UDP keşif beacon'ının
gördüğü (`beacon.devices`) cihazlara bağlanmayı deniyordu. Masaüstü 5
saniyede bir duyuru gönderse de, telefon arka plana düşüp bir süre
beklediğinde (Doze/arka plan kısıtlamaları, kaçırılan bir multicast
paketi) bu liste boşalabiliyor — ve boşsa denenecek adres kalmıyor.
Reconnect butonu da aynı fonksiyonu çağırdığı için "hiçbir şey
yapmıyormuş" gibi görünüyordu; aslında çalışıyordu, sadece elinde deneyecek
bir adres yoktu.

**Düzeltme: `PairedDevice`'a `lastAddress`/`lastPort` eklendi**
(`paired-devices.json`'da kalıcı), her başarılı bağlantıda güncelleniyor.
`reconnectPairedDevices()` artık beacon'da görünmeyen ama daha önce
bağlanılmış cihazlar için de bu son adrese doğrudan bağlanmayı deniyor —
mutual-TLS + pin kontrolü değişmeden çalışıyor, adres sadece "nereyi
deneyeyim" ipucu. `forceReconnect()` (Reconnect butonu) artık önce
beacon'ın multicast soketini yeniden bağlıyor (`beacon.refresh()`, arka
planda askıda kalmış olabilir), sonra hem beacon hem son-bilinen-adres
üzerinden dener — böylece her tıklama gerçek bir etki üretiyor. Aynı
mantık otomatik 10 saniyelik arka plan reconnect döngüsünü de kapsadığı
için "2 dakika sonra kolay kolay bağlanmıyor" sorunu da bununla düzeldi.

Sürüm 0.10.1 / versionCode 12. `:core:testDebugUnitTest` ve
`:app:lintDebug` temiz. (Desktop bu turda değişmedi.)

---

## 0.10.2 — arka planda kendiliğinden çöküyor, sonra bağlanamıyor

Bildirilen hata: Uygulama arka plandayken kendi kendine çöküyor, ardından
masaüstü ile bağlantı kurulamıyor. İkisi tek bir hata değil — dördü de
aynı sonuca çıkan ayrı kusurlar; ama "bağlanamıyor" kısmı bunların
türevi: telefon işlem (process) olarak ölünce hem dinleyici soket hem
beacon gidiyor, ve **çağıran taraf her zaman telefon** (bkz.
`DeviceManager.cpp`'deki "a phone always dials the computer"), dolayısıyla
masaüstünün geri arayacak bir yolu yok. Çökme durduğunda bağlantı sorunu
da duruyor.

**1. `dataSync` ön plan servisi zamanlayıcısı — asıl sebep.** Android 15'ten
itibaren `dataSync` tipi bir foreground service 24 saat içinde toplam
**6 saatle** sınırlı. Süre dolunca sistem `Service.onTimeout()` çağırıyor
ve servis birkaç saniye içinde durmazsa işlemi öldürüyor ("a foreground
service of type dataSync did not stop within its timeout"). Bizde
`onTimeout()` override'ı hiç yoktu ve servisin işi zaten kalıcı olarak
açık kalmak — yani bu bir sınır değil, **zamanlanmış çökme**. Ekran kapalı
olduğu için de "kendiliğinden arka planda çöktü" diye görünüyor.
Düzeltme: servis tipi `connectedDevice` oldu (zamanlayıcısı yok,
yaptığımız işin dürüst tarifi de bu; ön koşul izni olan
`CHANGE_WIFI_MULTICAST_STATE` zaten vardı, **yeni izin gerekmedi**), ve
ileride bu tipe de saat konursa öldürülmek yerine düzgün dursun diye
`onTimeout()`'un her iki aşırı yüklemesi de override edildi.

**2. `Beacon` içindeki HashMap yarışı.** `onNetworkAvailable()`,
ConnectivityManager'ın kendi binder thread'inde çalışıyordu ve oradan
`beacon.refresh()` → `stop()` → `seen.clear()` yapıyordu — tam o sırada IO
thread'indeki `listenLoop()` aynı düz HashMap üzerinde
`pruneAndPublish()` çalıştırıyor. Sonuç: çıplak bir `scope.launch`
içinde `ConcurrentModificationException`, yani yakalanmamış istisna, yani
işlemin ölümü. Ve tetikleyicisi tam olarak "arka plandayken Wi-Fi
değişti". Düzeltme: `Beacon`'ın bütün değişken durumu tek bir kilit
altına alındı, her `listenLoop` kendi soketini parametre olarak alıyor
(eski nesil döngü yeni nesle yazamıyor), `onNetworkAvailable()` artık
manager'ın kendi scope'una devrediliyor. Bu arada `lastAccepted`
tablosu da artık budanıyor — günlerce açık kalan bir serviste sınırsız
büyüyordu.

**3. `Connection.readLoop()` sadece `IOException` yakalıyordu.** Bu döngü
`drainFrames()` üzerinden doğrudan `DeviceManager`'ın mesaj
işleyicilerine giriyor; oradan çıkacak bir `NumberFormatException`,
`IndexOutOfBoundsException` veya NPE `IOException` değil — yakalanmıyor,
`scope.launch` içinden kaçıyor ve uygulamayı düşürüyor. Düzeltme:
`Exception` yakalanıyor (`CancellationException` yeniden fırlatılıyor,
yapısal eşzamanlılık bozulmasın diye); bir işleyici hata verirse bedelini
**o link** ödüyor — kapanıyor, `onClosed` çalışıyor, reconnect süpürmesi
yeniden arıyor. Ölü işlem yerine toparlanabilir bir kopma.

**4. `PairedDeviceStore` senkronize değildi.** Listeye dört yerden
erişiliyor: UI, link okuma döngüleri, reconnect'i süren connectivity
callback'i ve — `PinnedTrustManager` üzerinden — **TLS el sıkışmasının
kendisi**. Bir reconnect `lastAddress` yazarken bir el sıkışması pin için
listeyi dolaşırsa `ConcurrentModificationException`, üstünde hiçbir
handler olmayan bir thread'de. Bütün erişimciler `@Synchronized` yapıldı.

Ayrıca son bir emniyet kemeri: `Link`'in scope'una bir
`CoroutineExceptionHandler` eklendi. `SupervisorJob` kardeş coroutine'leri
korur ama istisnayı yutmaz — handler yokken yakalanmamış her fırlatma
varsayılan handler'a, yani işlemin ölümüne gidiyordu. Artık loglanıyor.

Sürüm 0.10.2 / versionCode 13. `:core:testDebugUnitTest` ve `:app:lintDebug`
temiz (68 uyarı, hepsi bu turdan önce de vardı). `:app:assembleRelease` v3
imzalı APK üretti; birleşmiş manifestte `foregroundServiceType=0x10`
(CONNECTED_DEVICE) ve `FOREGROUND_SERVICE_DATA_SYNC` izninin kalkmış olduğu
doğrulandı. (Desktop bu turda değişmedi.)

Bu tur build ortamı da sıfırdan kuruldu: JDK 21 (`jdk21-openjdk`) + Android
SDK command-line tools `~/Android/Sdk` altına. Dikkat: platform paketleri
artık minör sürümlü — `platforms;android-37` diye bir paket **yok**,
`platforms;android-37.0` var. `compileSdk = 37` bunu doğru çözüyor.


---

## 0.10.3 — telefon masaüstünü göremiyor (multicast tek arayüze join)

Bildirilen hata: Masaüstü telefonu görüyor, telefon masaüstünü
**eşleştirilebilir olarak göremiyor**. Reconnect butonu tepki vermiyor.
Firewall kapatıldı, değişmedi.

Ölçüm: masaüstünde 12 saniye multicast dinlendi — hem `msi` (192.168.0.45,
port 38271) hem `SM-S918B` (192.168.0.28, port 45005) duyuruları geliyor.
Yani telefon **gönderiyor** ve keşif ağ katmanında çalışıyor. Aynı anda
masaüstünde 20 saniye boyunca 38271'e **tek bir TCP bağlantı denemesi
gelmedi**. Telefon hiç aramıyordu, çünkü arayacak bir cihaz bilmiyordu.

Kök sebep: `Beacon.start()` grubu deprecated tek argümanlı
`joinGroup(InetAddress)` ile join ediyordu. Bu, arayüz seçimini çekirdeğe
bırakıyor ve Android'de o seçim düzenli olarak Wi-Fi olmuyor — telefonda
aynı anda birkaç arayüz açık (wlan0, rmnet, VPN tun) ve multicast
varsayılanı LAN'ı taşıyan arayüz değil. Ortaya çıkan arıza **asimetrik**,
o yüzden okuması kafa karıştırıcı: giden datagram normal route'u izlediği
için **gönderme çalışmaya devam ediyor** (masaüstü telefonu görüyor ve
listeliyor), ama telefon hiçbir şey almıyor ve listesi boş kalıyor.
Bildirilen tablonun tam şekli bu.

Düzeltme: `joinGroupOnEveryInterface()` — masaüstünün ilk günden beri
yaptığının aynısı (`Beacon.cpp`, `QNetworkInterface::allInterfaces()`
döngüsü). Her up, non-loopback, IPv4 adresi olan arayüzde
`joinGroup(SocketAddress, NetworkInterface)` deneniyor.
`supportsMulticast()` bilerek filtre olarak **kullanılmıyor**: Android bunu
cihaz/üretici bazında tutarsız raporluyor ve oradaki bir false negative tam
da önemli olan arayüzü sessizce atlardı. Hiçbiri tutmazsa eski tek arayüzlü
join'e düşüyor, yani hiçbir durumda öncekinden kötü olamaz.

**Scan butonu (istendi, iki platformda da).** Keşif pasif olduğu için boş
bir liste iki ayrı şeyi aynı anda gösteriyor: ortada bir şey yok, ya da biz
dinlemeyi bıraktık. Kullanıcının ikincisine müdahale etme yolu yoktu.
- Mobil: `DeviceManager.rescan()` → beacon yeniden bağlanıyor (tüm
  arayüzlere yeniden join), anında bir duyuru gidiyor, eşleşmiş cihazlar
  yeniden aranıyor. `DevicesScreen` başlığında "Scan", boş durumda
  "Scan again".
- Masaüstü: `Beacon::refresh()` (bir multicast üyeliği join edildiği
  arayüze aittir — sonradan kablo takmak, VPN bağlamak veya yeni adres
  almak o arayüzde grubu join etmiş yapmaz; yeniden başlatmak tek güvenilir
  çözüm), `DeviceManager::rescan()`, `Backend::rescanDevices()`, ve
  `DevicesView`'a gerçek bir başlık satırı + "Scan" butonu.

Sürüm 0.10.3 / versionCode 14. Mobil: `:core:testDebugUnitTest`,
`:app:lintDebug`, `:app:assembleRelease` temiz.

**Masaüstü bu turda derlenmedi**: `cmake` ve `ninja` sistemde yok
(`build/CMakeCache.txt` ninja ile yapılandırılmış ama `/usr/bin/ninja`
silinmiş). `sudo pacman -S cmake ninja` sonrası derlenmeli.


---

## 0.10.4 — butonlar basılmış gibi görünmüyor, masaüstünden Pair cevapsız

Bildirilen: (a) mobilde butonlara basınca hiçbir tepki yok, basılmıyor gibi
duruyor; (b) masaüstünden Pair'e basınca telefon cevap vermiyor.

**(a) İkincil butonların hiçbir basılı hâli yoktu.** `MazeButton`'da
`primary = false` iken `fill` basılıyken de basılı değilken de
`Transparent`, yazı rengi de `if (primary && !pressed)` yüzünden sabit
`Paper`. Yani ikincil bir butona basmak sıfır görsel değişim üretiyordu —
ve uygulamadaki her düşük vurgulu eylem ikincil: **Reconnect, Remove,
Scan, Clear, Cancel, Decline, Codes differ**. Butonlar doğru çalışırken
bile ölü hissettirmesinin sebebi buydu; ilk turdaki "reconnect butonu
tepki vermiyor" şikâyetinin görsel yarısı da bu.

Düzeltme: ikincil buton basılıyken dolu `Paper` yanıyor (kenarlığı da
`Paper` oluyor, yoksa parlak bloğun etrafında sönük bir çerçeve kalıyor),
birincil eskisi gibi içi boşalarak tersine dönüyor. Birincil zaten durağan
hâlde beyaz, dolayısıyla onu beyaz yakmak basma olarak okunamayacak tek
şey olurdu. Ayrıca geçiş **girişte anlık** (`snap()`), çıkışta
`tween(140)`: bir dokunuş çoğu zaman animasyondan kısa sürüyor, basılı
hâle *yumuşayarak* girmek hızlı dokunuşta geçişi başlatıp görünür olmadan
geri döndürüyordu — geri bildirim yalnızca basılı tutanlara ulaşıyordu.

**(b) `_pendingPairing` link kapanınca temizlenmiyordu.** `onClosed`,
`forgetPendingWork()` ile status/commands/ai/guard'ı temizliyordu ama bunu
değil. `_pendingPairing` "aynı anda tek eşleştirme" kuralıyla korunan tek
bir slot: `requestPairingAt()` doluyken baştan dönüyor, `handlePairResponse()`
her cevabı ona göre ölçüyor. El sıkışma yarıda kalan bir link — kullanıcı
dialogu görmedi çünkü uygulama arka plandaydı, bilgisayar vazgeçti, ağ
takıldı — slotu kalıcı olarak dolu bırakıyor ve **telefon o andan sonra
hiçbir şeyle eşleşemiyor**, süreç yeniden başlayana kadar. Artık kapanan
link o eşleştirmenin sahibiyse slot boşaltılıyor.

**(b devamı) Gelen eşleştirme isteği arka planda görünmezdi.** Doğrulama
dialogu bir Activity'de yaşıyor, dolayısıyla telefon kilitliyken veya
uygulama arka plandayken masaüstü "asking that device to pair" derken bu
tarafta hiçbir şey olmuyordu. Yeni `DeviceEvent.PairingRequested` →
`MazeConnectService` yüksek öncelikli bir bildirim atıyor (kanal:
"Pairing requests"), dokununca uygulama açılıyor. **Kod bilerek bildirimde
yok**: SAS'ı bildirim gölgesinden karşılaştırmak, onu anlamlı kılan
bağlamdan kopararak karşılaştırmaktır. Eşleştirme bitince (kabul, ret veya
hata) bildirim geri çekiliyor.

**POST_NOTIFICATIONS hiç istenmiyordu.** İzin manifestte baştan beri
vardı ama API 33'ten itibaren manifest tek başına hiçbir şey vermiyor:
kullanıcıya sorulmadığı sürece uygulamanın attığı **her** bildirim sessizce
düşüyor. Bu, ön plan servisi bildirimini de kapsıyordu — yani "bu uygulama
bir link tutuyor" ifşasını. `MainActivity.onCreate()` artık bir kez soruyor.
Cevaba hiçbir şey bağlı değil: ret, kullanıcıya yalnızca bildirimlere mal
oluyor, link/widget/dialog aynen çalışıyor.

Sürüm 0.10.4 / versionCode 15. `:core:testDebugUnitTest`, `:app:lintDebug`
(68 uyarı, hepsi önceden vardı), `:app:assembleRelease` temiz.


---

## 0.10.5 — telefona gelen hiçbir bağlantı hiçbir zaman çalışmamış

Bildirilen: masaüstünden Pair'e basınca telefon tepki vermiyor, kod bile
gelmiyor. 0.10.4 ile de sürüyor.

Bu tur tahminle değil, ölçümle çözüldü. Protokolü konuşan bir tanı
istemcisi yazıldı (`scratchpad/pairprobe.py`): beacon'dan telefonu bulup
istemci sertifikasıyla TLS 1.3 kuruyor, `hello` ve `pairRequest`
gönderiyor, dönen çerçeveleri basıyor. Sonuç:

    TLS: TLSv1.3 TLS_AES_256_GCM_SHA384
    telefon sertifikasi: 291 bayt
    >> hello gonderildi
    >> pairRequest gonderildi
    << 20 sn icinde baska bir sey gelmedi

Telefon el sıkışmayı tamamlıyor, sonra **hiçbir şey** göndermiyor —
`adopt()`'un koşulsuz yolladığı hello dahil — ve bağlantıyı kapatmıyor.

Kök sebep, `acceptLoop()`'ta iki satır arası bir uyumsuzluk:

    // startListener()
    val (context, _) = tls.createContext(allowUnpaired = true)
    val server = context.serverSocketFactory.createServerSocket(0)

    // acceptLoop()
    val (_, trustManager) = tls.createContext(allowUnpaired = true)
    socket.startHandshake()
    adopt(Connection(socket, trustManager, scope))

Bir `SSLServerSocket` tek bir `SSLContext`'ten üretilir ve kabul ettiği
her soket **o** context'in trust manager'ıyla el sıkışır. Yani el sıkışma
`startListener()`'daki (referansı `_` ile atılan) manager'dan geçiyor;
`acceptLoop()`'ta yaratılıp `Connection`'a verilen manager hiçbir el
sıkışmaya katılmıyor ve `peerPublicKey`'i sonsuza dek null kalıyor.
`adopt()` da ilk satırında `?: return false` ile çıkıyor — handler
takmadan, `connection.start()` çağırmadan, soketi kapatmadan, tek satır
log basmadan.

Yani telefona **gelen** her bağlantı, protokolün ilk baytından önce
sessizce ölüyordu. Telefonun kendi aradığı yön çalışıyordu, çünkü o yol
`tls.connect()` kullanıyor ve el sıkışmayı gerçekten yapan manager'ı geri
veriyor — masaüstü logunda 17:27'de telefonun *başlatıcı* olduğu
eşleştirmenin doğrulama kodu aşamasına kadar ilerlemesinin sebebi buydu.

Düzeltme: `PinnedTrustManager.adoptCompletedHandshake(chain)`. Kabul yolu,
tamamlanmış oturumun zincirini (`SSLSession.getPeerCertificates()`, ki
platform bunu ancak karşı taraf o anahtarın sahibi olduğunu kanıtladıktan
sonra doldurur) aynı `check()`'ten geçiriyor: aynı yalnızca-EC kuralı,
aynı makullük testi, aynı pin kararı. Canlı el sıkışmada kabul edilmeyecek
hiçbir şey burada da kabul edilmiyor. Zincir kullanılamazsa soket
kapatılıp geçiliyor. Ayrıca `adopt()`'un `false` dönüşü artık loglanıyor
ve kabul yolunda soketi kapatıyor — bu sınıf hatanın tamamen görünmez
olması, bulunmasını günler geciktiren asıl şeydi.

Sürüm 0.10.5 / versionCode 16. `:core:testDebugUnitTest`, `:app:lintDebug`,
`:app:assembleRelease` temiz. Cihaza kurulup `pairprobe.py` ile
doğrulanmayı bekliyor.


---

## 0.10.6 — Dashboard refresh butonu, ve Reconnect'in üçüncü sebebi

**`outboundPending` sızıyordu.** `connectToPaired()` kümeden silmeyi iki
"iyi" yolda yapıyordu: bağlantı kurulamazsa, ve `adopt()` dönerse. Başka
her çıkış — `adopt()` fırlatması, scope'un connect ile devir arasında
iptal edilmesi — kaydı geride bırakıyordu. Bu küçük bir sızıntı değil:
`reconnectPairedDevices()` kümede olan cihazı atlıyor, dolayısıyla o
bilgisayar **süreç ömrü boyunca bir daha hiç aranmıyor** ve
`forceReconnect()` (Reconnect butonu) `true` dönerek hiçbir şey yapmıyor.
Artık `finally` bloğunda.

Bu, bu turdaki üçüncü aynı-sınıf hata — `_pendingPairing` (0.10.4),
`m_pendingPairings` (masaüstü 1.0.2) ve şimdi bu. Üçü de "aynı anda tek X"
kuralını koruyan bir küme/slot ve üçü de hata yolunda temizlenmiyordu.
Yeni bir koruma kümesi eklenirken temizliğin `finally`'ye yazılması
kural olarak alınmalı.

**Dashboard'a Refresh butonu.** Ekran zaten üç saniyede bir yeniliyor ama
yalnızca `target` varken, ve `target` cihazın bağlı olmasını şart koşuyor.
Yani bayat bir panel, tanımı gereği yoklamanın dokunmadığı paneldir —
düz bir `requestStatus()` orada hiçbir şey yapmazdı. `refreshDashboard()`
bu yüzden `requestStatus()`'un dönüş değerini sinyal olarak kullanıyor:
`false` (link yok veya yetenek kapalı) ise `rescan()`'e düşüyor. Buton
tam ihtiyaç duyulan anda çalışıyor, o an olmadığı anda değil.

Sürüm 0.10.6 / versionCode 17. `:core:testDebugUnitTest`,
`:app:lintDebug`, `:app:assembleRelease` temiz.


---

## 0.10.7 — güncelleme hatırlatıcısı

İstenen: sitede daha yeni bir sürüm varsa uygulama haber versin.

`UpdateChecker` (yeni bağımlılık yok — `HttpsURLConnection`) günde en fazla
bir kez `https://mazelinux.berkkucukk.com.tr/maze-connect-apk/latest.json`
okuyor. Settings'e "Version" bölümü geldi: kurulu sürüm, durum satırı,
"Check now", aç/kapa, ve daha yenisi varsa "Download". Yeni bir sürüm için
düşük öncelikli **tek** bildirim atılıyor (`notifiedVersionCode` ile) —
kullanıcının kurmamaya karar verdiği bir sürüm için her gün dönen şey
hatırlatma değil, dırdırdır; Settings satırı tekrar bakmak isteyene zaten
duruyor.

**Yayındaki dosyanın şeması korundu.** Site şu an şunu veriyor:

    { "file": "maze-connect-0.10.2.apk", "version": "0.10.2" }

Parser bunu olduğu gibi kabul ediyor; `versionCode`/`versionName`/`url`
varsa onları tercih ediyor. `versionCode` en kesin olan, çünkü Android'in
kurulumları sıraladığı sayı o. `file`, manifest'in kendi dizinine göre
çözülüyor — hem yayındaki dosyanın anlamı bu, hem de bu yüzden host dışına
işaret edemiyor: taban `MANIFEST_URL`.

`versionCode` yokken isimler **sayısal olarak** karşılaştırılıyor, ve bunun
kendi testi var (`UpdateVersionTest`, 4 test). Metin karşılaştırması tam
işe yaramaya başladığı anda yanlış cevap veriyor: "0.10.10" metin olarak
"0.10.2"nin altında sıralanır, yani 0.10.9'daki bir telefona sonsuza dek
"günceldesin" denirdi.

Güvenlik tarafı: istek hiçbir tanımlayıcı taşımıyor (query yok, header yok,
cihaz id yok), gövde 8 KB'de kesiliyor, her alan sınırlı ve kontrol
karakterinden arındırılmış, yönlendirme takip edilmiyor, indirme linki
yalnızca manifest'in kendi host'undaysa kabul ediliyor. En önemlisi:
**hiçbir şey indirilmiyor ve kurulmuyor.** Ele geçirilmiş bir manifest'in
yapabileceği en kötü şey yanlış bir sürüm numarası göstermek. İlk verilen
adres `http://` ve LAN IP'siydi; onun için dar bir cleartext istisnası
yazılmıştı, https adres gelince tamamen geri alındı —
`cleartextTrafficPermitted="false"` mutlak kaldı.

Sürüm 0.10.7 / versionCode 18. `:app:testDebugUnitTest` (4/4),
`:core:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleRelease` temiz.


---

## 0.10.8 — manifest /api altına taşındı; açık User-Agent

Sürüm bilgisi artık
`https://mazelinux.berkkucukk.com.tr/api/maze-connect/latest` adresinde.
Şema aynı (`file` + `version`), ama taşınma sessiz bir hata üretiyordu:
`file` alanı **manifest'in kendi dizinine** göre çözülüyordu, ve manifest
artık paketlerle aynı dizinde değil. Ölçüldü:

    /api/maze-connect/maze-connect-0.10.7.apk   -> 404
    /maze-connect-apk/maze-connect-0.10.7.apk   -> 206

Yani Download butonu güvenilir biçimde hiçbir yere gitmeyecekti — ki bu
butonun hiç olmamasından kötü. `DOWNLOAD_BASE_URL` ayrı bir sabit oldu.
İkisi de aynı host'u adlandırmak zorunda, yoksa host kontrolü çözülen linki
zaten atıyor.

**Açık User-Agent.** Uç nokta beğenmediği user agent'lara 403 dönüyor —
Python'unki bugün reddediliyor, `curl` ve Android'in varsayılan
`Dalvik/…`'i kabul ediliyor. Varsayılana güvenmek, bir filtre kuralı
değişikliğinin bu özelliği sessizce ve kalıcı olarak kapatması demek; iki
tarafta da sebebini söyleyen hiçbir şey olmadan. Artık
`MazeConnect/<versionName> (Android)` gönderiliyor: sunucu tarafında
bilerek allowlist'e alınabilir bir ad.

Yan faydası gizlilik: platformun varsayılan UA'sı telefonun **modelini ve
build id'sini** taşıyor. Bunu bastırmak, giden isteği "uygulama ve sürümü"
ile sınırlıyor. Settings'teki açıklama metni buna göre düzeltildi — eskisi
"hiçbir tanımlayıcı gönderilmiyor" diyordu, artık gönderilen tek şeyin
uygulamanın kendi sürümü olduğunu söylüyor.

Canlı uçla doğrulandı: uygulamanın göndereceği isteğin birebir taklidi 200,
çözülen indirme linki 206.

Sürüm 0.10.8 / versionCode 19. `:app:testDebugUnitTest` (4/4),
`:core:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleRelease` temiz.


---

## 0.11.0 — Live Update: bilgisayarın okumaları durum çubuğunda / Now Bar'da

İstenen: One UI 8 "navbar"ında Maze Connect, bağlı cihaz ve CPU/RAM/GPU
görünsün.

**Önce bir düzeltme: navigation bar'a uygulama çizemez.** O şerit sisteme
ait ve hiçbir Android sürümünde üçüncü taraf bir uygulamaya açılmıyor.
Samsung'un Now Bar'ının (One UI 7+) gerçekte tükettiği şey Android'in
**Live Updates** mekanizması: *promote edilmeyi isteyen* bir ongoing
notification, ki platform bunu durum çubuğu çipine, kilit ekranına ve One
UI'da Now Bar'a yansıtıyor. Yani oraya çıkmanın yolu iyi bir ongoing
notification yayınlayıp istemek — bu bir **istek**, garanti değil.

**API seviyeleri tahmin edilmedi, `api-versions.xml`'den okundu** (yanlışı
gerçek telefonda `NoSuchMethodError` demek):

    ProgressStyle                      API 36
    Builder.setShortCriticalText       API 36
    Builder.setRequestPromotedOngoing  API 36.1
    MetricStyle / Metric               API 37.0

Sonuç iki katmanlı, çünkü **One UI 8 = Android 16 = API 36**, yani hedef
telefonda `MetricStyle` henüz yok:

* **API 36 katmanı (bugün çalışan):** `ProgressStyle` — bilgisayarın adı,
  çipte tek bir başlık sayısı (`setShortCriticalText`), ve bir gösterge
  çubuğu. `setRequestPromotedOngoing` 36.1'den itibaren, `SDK_INT_FULL`
  ile ayrıca korunuyor (`BAKLAVA_1` = 3_600_001).
* **API 37 katmanı (kendiliğinden açılacak):** `MetricStyle` — CPU, bellek,
  GPU yan yana, her biri kendi etiketi, birimi ve
  `SEMANTIC_STYLE_SAFE/CAUTION/DANGER` rengiyle. Şimdiden yazıldı ki cihaz
  oraya geldiğinde bu dosyaya dönmek gerekmesin.

API 36 altında **hiç yayınlamıyor**. Promote edilemeyen ikinci bir kalıcı
bildirim özellik değil, çöp olurdu; o zemini ana ekran widget'ları zaten
kaplıyor.

Veri kaynağı yeni trafik üretmiyor: `DeviceManager`'ın arka plan süpürmesi
widget'lar için zaten her bağlı bilgisayardan yavaş bir snapshot istiyor,
bu onu tüketiyor. Birden fazla bilgisayar bağlıysa **en taze** okuma
gösteriliyor, makine başına ayrı bildirim değil — bir kişinin "bilgisayarım"
dediği şey için gölgede iki kalıcı satır, ara sıra değişen tekinden kötü;
başlık hangi makine olduğunu zaten yazıyor.

Metrik anahtarı varsayılmıyor: `cpu` varsa başlık o oluyor, yoksa ilk
metrik. Anahtarlar bilgisayarın kendi yardımcı programından geliyor ve bu
uygulamanın onları varsayma hakkı yok.

Kanal ayrı ve `IMPORTANCE_LOW`: sürekli güncelleniyor, asla ses çıkarmamalı,
ve sistem ayarlarından kanalı kapatmak desteklenen vazgeçme yolu.

Sürüm 0.11.0 / versionCode 20. `:app:testDebugUnitTest` (4/4),
`:core:testDebugUnitTest`, `:app:assembleRelease` temiz; `:app:lintDebug`
68 uyarı — bu turdan önceki taban da 68, yani **yeni bulgu yok** (ara
derlemelerde çıkan `InlinedApi` ve `UseKtx` uyarıları düzeltildi:
`semanticStyleFor` artık `@RequiresApi(37)`, SharedPreferences yazımları
`edit {}`, `Uri.parse` yerine `toUri()`).

**Cihazda doğrulanmadı** — bu makineye bağlı telefon yok. Promote edilip
edilmediği ve Now Bar'da nasıl göründüğü gerçek bir One UI 8 cihazında
görülmeli.


---

## 0.11.1 — Live Update promote edilmiyordu: eksik izin

Bildirilen: 0.11.0 kuruldu, ne Now Bar'da ne de ayarlardaki uygulama
listesinde görünüyor.

Terminoloji önce netleşti: kastedilen **Now Bar** — kilit ekranının altında
ortada duran, kilit açılınca bildirim panelinde görünen şerit. ("Now bar" /
"nav bar" karışıyor.) Yani 0.11.0'daki mekanizma doğru yüzeyi hedefliyordu;
Android 16 Live Updates, Now Bar'a girmenin tek yolu.

Kök sebep: **`android.permission.POST_PROMOTED_NOTIFICATIONS` manifestte
yoktu.** Resmi gereksinim listesine karşı kendi kodum tek tek denetlendi —
stil (ProgressStyle/MetricStyle), `setOngoing`, `contentTitle`,
`setRequestPromotedOngoing`, customContentView yok, grup özeti değil,
colorized değil, kanal IMPORTANCE_MIN değil — hepsi doğruydu, yalnız bu
izin eksikti. İzin olmadan platform promosyonu **sessizce** reddediyor:
bildirim gölgede görünmeye devam ediyor, yani her şey çalışmış gibi
duruyor, ama çip ve Now Bar boş kalıyor. Bildirilen tablonun tamamı bu.
Normal izin, kurulumda veriliyor, kullanıcıya sorulacak bir şey yok.

**Logo.** Küçük ikon, sistemin çipte ve kilit ekranında çizdiği şey,
dolayısıyla artık uygulamanın kendi markası: `ic_notification`, launcher
logosunun adaptive-icon güvenli alanından kırpılıp siluete indirgenmiş
hâli (mdpi–xxxhdpi). Launcher varlığını doğrudan vermek olmazdı: adaptive
foreground tasarımı gereği çoğunlukla boşluk — ölçüldü, 432px tuvalde logo
237px, yani zaten küçücük bir alanın %55'i.

Kalıcı bağlantı bildirimi de artık bunu kullanıyor;
`stat_sys_data_bluetooth` takılıydı, ki bu uygulamanın Bluetooth ile hiçbir
ilgisi yok.

Sürüm 0.11.1 / versionCode 21. Testler ve release temiz; `:app:lintDebug`
68 uyarı = taban, yeni bulgu yok.

**Not:** eşleştirme bu turda çalışır hâlde doğrulandı — masaüstünün
kaydında telefon `trusted=true` ve canlı bir TCP oturumu var. 0.10.5'teki
trust manager düzeltmesi tuttu.

**Ayrı bir kusur (düzeltilmedi):** masaüstü telefonu
`deviceName="edc9c041-…"`, `deviceType=""` olarak pinlemiş. Önceki kayıtta
`"SM-S918B"` / `"mobile"` vardı. Eşleştirme, hello'dan gelen ad/tip
yerleşmeden tamamlanıyor ve sonradan güncellenmiyor; arayüzde cihaz adı
yerine UUID görünüyor.


---

## 0.11.2 / 0.11.3 — Now Bar çalışıyor: eksik izin, yanlış koruma, kapatma anahtarı

**0.11.2 — asıl hata bendeydi.** `setRequestPromotedOngoing` API **36.1**,
36 değil; promosyon isteğinin tamamını `SDK_INT_FULL >= 3_600_001`
koşulunun arkasına koymuştum. Android 16.0 (36.0) bir cihazda o satır hiç
çalışmıyor, yani promosyon *hiç istenmiyor* — istenmeyen şey de
reddedilmiyor, sessizce yok sayılıyor. One UI 8 Android 16 tabanlı, yani
tam da bu durum. Setter'ın yaptığı tek şey extras'a bir boolean yazmak ve
bir bundle anahtarı yazmanın kendi API kısıtı yok: anahtar artık API 36'dan
itibaren doğrudan yazılıyor (`"android.requestPromotedOngoing"`, sabitin
kendisi de 36.1 olduğu için literal), tipli setter varsa ayrıca çağrılıyor.

Bundan önce 0.11.1'de eksik `POST_PROMOTED_NOTIFICATIONS` izni vardı; ikisi
birlikte çözülünce Now Bar'da göründü.

**0.11.3 — iki bildirilen eksik.**

*Kapatma anahtarı.* Promote edilmiş kalıcı bir bildirim tasarımı gereği
kaçınılmaz: link ayakta olduğu sürece kilit ekranında ve durum çubuğunda
bir yer tutuyor. Kaçınılmaz olmayı seçen bir şeyin anahtarı olmak zorunda,
ve sistemin kanal anahtarı yeterli cevap değil — o, bildirimi kapatırken
uygulamanın *yayınlamaya* devam etmesi demek, ki bu daha kötü bir durum.
Settings'e "Now bar" bölümü ve On/Off geldi. Anahtar **anında** etki
ediyor: kapatınca hemen siliniyor, açınca elde duran en taze okumadan
hemen yayınlanıyor. Arka plan süpürmesi dakikalık bir timer'da, ve yarım
dakika hiçbir şey yapmıyormuş gibi duran bir anahtar bozuk anahtar diye
okunur — bu seansın tekrar tekrar ürettiği şikâyetin ta kendisi.

*Çip içeriği.* Now Bar sadece `"5%"` yazıyordu: neyin %5 olduğuna dair hiç
ipucu yok, gerisi ancak bildirim açılınca görünüyor. `setShortCriticalText`
artık başlık metriği + bir sonraki, ikisi de etiketli —
`"CPU 5% · Memory 56%"`. Etiketli olması şart: çıplak bir "5%" bilginin
küçük hâli değil, farklı ve işe yaramaz hâli. Platform durum çubuğu çipini
96dp'de kesiyor, bu bilerek o sınırın hemen berisinde.

Seçim mantığı (en taze okumayı yayınla) `LiveStatusNotification.refresh()`
içinde tek yerde: servisin toplayıcısı ve ayarlardaki anahtar aynı cevabı
vermek zorunda, yoksa iki bilgisayar bağlıyken anahtarı açıp kapatmak
gölgedeki makineyi değiştirirdi — yalnız iki makineyle ortaya çıkan, yani
testten sağ çıkan cinsten bir hata.

Sürüm 0.11.3 / versionCode 23. Testler ve release temiz, lint 68 = taban.


---

## 0.11.4 — promosyon durumu okunuyor, çipe üç metrik

**"Live notifications for all apps" atlatılamaz.** Bu bir sistem ayarı:
işletim sisteminin üçüncü taraf promosyon isteklerini onurlandırıp
onurlandırmayacağını belirliyor. Uygulama açabilseydi ayar olmazdı, ve
açacak bir API yok. Samsung'un One UI 7 dönemindeki kendi Now Bar
entegrasyonu iş ortağı listesine bağlı — yandan yüklenen kişisel bir
uygulamaya kapalı.

Yapılabilecek olan yapıldı: **durumu okuyup kullanıcıyı tek dokunuşla
oraya götürmek.**

`promotionState()` artık `getActiveNotifications()` ile sistemin bildirimi
nasıl tuttuğunu geri okuyor — tahmin değil, sistemin kendi cevabı:

    PROMOTED        FLAG_PROMOTED_ONGOING verilmiş, Now Bar'da
    DECLINED        hasPromotableCharacteristics() true ama flag yok
                    -> bildirim doğru, sistem reddediyor (One UI anahtarı)
    NOT_PROMOTABLE  hasPromotableCharacteristics() false
                    -> bildirim bozuk, bu uygulamanın hatası
    NOT_PUBLISHED   ortada bildirim yok (bagli bilgisayar/veri yok)
    DISABLED        Settings'teki anahtar kapalı

Bu ayrımın olmaması bu turun asıl bedeliydi: "görünmüyor" üç sürüm boyunca
"benim hatam" ile "sistem kapalı" arasında ayırt edilemedi. Artık ekran
hangisi olduğunu söylüyor, ve `DECLINED` ise
`Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS` ile doğrudan geliştirici
seçeneklerine götüren bir buton çıkıyor. Kullanıcıya "geliştirici
seçeneklerini aç, şu ayarı bul" demekle tek dokunuş arasındaki fark bu.
Durum, ekran açıkken iki saniyede bir tazeleniyor, yani sistem ayarından
dönünce yeni cevap görünüyor.

**Çipte üç metrik.** `CHIP_METRICS` 2 → 3:
`"CPU 5% · Memory 56% · GPU 12%"`. Boyutlandırma artık Now Bar'a göre;
durum çubuğundaki dar çip fazlasını kesiyor ama baştaki okumayı yine
gösteriyor, yani orada kaybedilen bir şey yok.

Sürüm 0.11.4 / versionCode 24. Testler ve release temiz, lint 68 = taban.


---

## 0.11.5 — kilit ekranında sadece uygulama adı görünüyordu

Bildirilen: telefon kilitliyken Now Bar'da "Maze Connect" yazıyor, hiçbir
bilgi yok.

Bildirimin görünürlüğü `VISIBILITY_PRIVATE` idi — belirtilmediğinde
varsayılan bu, ve sistem kilit ekranında içeriği sansürleyip yalnızca
uygulama adını bırakıyor. Bir mesajlaşma uygulaması için doğru varsayılan;
burada ise özelliğin okunmak için var olduğu tek yerde söyleyeceği hiçbir
şeyi söylememesi demekti. Kullanıcının kendi bilgisayarının yüzdesi gizli
bilgi değil, ve kilidi açmadan okunabilmesi zaten amacın kendisi.

`setVisibility(VISIBILITY_PUBLIC)` bildirime, `lockscreenVisibility` de
kanala eklendi.

**Kanal id'si bumplandı** (`maze-connect-live` -> `maze-connect-live-v2`),
çünkü bir kanalın kilit ekranı görünürlüğü oluşturulurken sabitleniyor:
`setLockscreenVisibility` kanal bir kez `createNotificationChannel`'a
verildikten sonra yok sayılıyor. Eski kanalı olan bir kurulum, kod ne derse
desin sansürlemeye devam ederdi. Eski kanal siliniyor, sistem listesinde
ölü bir kayıt olarak kalmasın diye.

Not: kullanıcı sistem genelinde "hassas içeriği gizle" seçtiyse bu yine de
üste çıkar; orası uygulamanın kararı değil.

Sürüm 0.11.5 / versionCode 25. Testler ve release temiz, lint 68 = taban.


---

## 0.11.6 — Now Bar bildirimi çok kısaydı

Bildirilen: Spotify ve diğer Now Bar bildirimleri daha uzun; genişlik aynı
ama Maze Connect'in yüksekliği yarısı kadar.

Yükseklik doğrudan ayarlanamıyor — stil şablonu ve **içerik miktarı**
belirliyor. Spotify'ınki `MediaStyle`: kapak görseli, iki metin satırı, bir
arama çubuğu ve bir kontrol satırı. Bizimki `ProgressStyle` ile başlık, tek
satır metin ve bir çubuktu. Yükseklik farkı doğrudan bunun sonucu.

İki gerçek satır eklendi, ikisi de dolgu değil:

**`setSubText` — kullanılmayan `detail` alanı.** `SystemStatus.Metric`'in
dördüncü alanı (`"45 °C"`, `"17.5 / 31.3 GiB"`) bilgisayardan zaten
geliyordu ve tamamen çöpe atılıyordu. "%43"ü bilinmeye değer bir şeye
çeviren kısım o. Başlık metriğinin detayı artık başlık satırında.

**Refresh aksiyonu.** Arka plan süpürmesi dakikalık bir timer'da;
bu buton açık link üzerinden hemen taze okuma istiyor
(`MazeConnectService.ACTION_REFRESH_STATUS`). Bir aksiyon satırı, başka
türlü kazanılamayacak gerçek yükseklik — ama işlevi olduğu için ekleniyor,
yükseklik için değil.

Sonuç:

    [ikon]  Maze Connect · 45 °C          7:13 PM
            msi
            CPU 9% · Memory 43% · Disk 5%
            [========------------------------]
            [ REFRESH ]

Sürüm 0.11.6 / versionCode 26. Testler ve release temiz, lint 68 = taban.

**Cihazda doğrulanmadı.** Bir sonraki kaldıraç, hâlâ kısa gelirse, stili
çok satırlı bir şablona çevirmek — her metrik kendi satırında, detayıyla —
ama bu ilerleme çubuğunu takas eder.


---

## 0.11.7 — Now Bar saatte 1200 kez yeniden çiziliyordu

Bildirilen: Now Bar çok sık güncelleniyor, arka planda şarj yesin
istemiyorum.

Kök sebep, tahmin edilenden kötü: bildirimin hızı **verinin** hızına
bağlıydı. Servisin toplayıcısı `systemStatus`'un her emisyonunda yeniden
yayınlıyordu, ve emisyonu kim isterse o sürüyor — Dashboard açıkken
`REFRESH_MS = 3_000`. Yani kimsenin bakmadığı bir yüzey dakikada yirmi kez
yıkılıp yeniden kuruluyor, her seferinde sistem arayüzünü ve kilit ekranını
uyandırıyordu.

Bildirimin temposu veriden ayrıldı:

* `MIN_REPOST_INTERVAL_MS = 120_000` — bir *bakış* yüzeyi için doğru olan
  bu. Bir dakikalık arka plan süpürmesinden bilerek yavaş: yaygın arka plan
  durumu iki okumada bir yeniden çizime oturuyor.
* **Değişmeyen okuma hiç yayınlanmıyor.** İmza, bildirimin gerçekten çizdiği
  şey (yuvarlanmış yüzdeler + detay dizeleri); aynıysa iş yapılmıyor. Boşta
  bir makinede emisyonların çoğu bu.
* `forceNextUpdate()` — Refresh aksiyonu cevabını aynı kısıtlı yoldan
  aldığı için, açıkça isteyen kullanıcı kısıtın muhatabı olmasın diye.
  Ayarlardaki anahtar da `force = true` ile geçiyor, açılışta anında
  görünsün diye.

Saatlik yeniden çizim:

    uygulama acikken   1200  ->  30
    arka planda          60  ->  30

Sürüm 0.11.7 / versionCode 27. Testler ve release temiz, lint 68 = taban.


---

## 0.11.8 — Now Bar rengi: mavi varsayılan yerine yüke bağlı

Bildirilen: küçük hâlin arka planı mavi, koyu gri olsa daha iyi; Spotify
kapak fotoğrafına göre renk değiştirdiği gibi bizimki de CPU yüzdesine göre
değişsin.

Mavi olmasının sebebi `setColor()`'ın hiç çağrılmamasıydı — sistem kendi
varsayılan vurgusunu koyuyordu. Bu uygulamada hiçbir anlamı olmayan ve
hiçbir şeyle eşleşmeyen bir renk.

Renk artık başlık okumasına (CPU) bağlı, **mevcut eşiklerle**:

    < %75   #3F3F45  koyu gri
    %75-90  #8A6A1F  kısık kehribar
    >= %90  #8E2F2F  kısık kırmızı

Sürekli bir gradyan yerine üç kademe, ve bu bilinçli. Her okumada tonu
kayan bir şerit dekorasyondur: sürekli değişir, dolayısıyla hiçbir değişim
bir şey ifade etmez. Üç kademe rengi bir *cevaba* çeviriyor — gri
"bakılacak bir şey yok", diğer ikisi ikinci bir bakışı hak ediyor. Ayrıca
`semanticStyleFor()` ile aynı sabitleri kullanıyor, yani API 37'de
`MetricStyle`'ın kendi renkleriyle aynı hikâyeyi anlatıyor; aynı sayı
hakkında iki yüzeyin farklı şey söylemesi ikisinden de kötü olurdu.

Kısık tonlar, doygun değil: alarm bu uygulamanın kaldırdığı bir alarm gibi
okunsun, sistem hatası gibi değil. Dinlenme hâli siyah değil gri, çünkü
koyu bir gölge üzerinde tint olarak okunabilir kalması gerekiyor.

`setColorized(true)` **kullanılamaz** — promosyon uygunluğunu doğrudan
bozuyor. Yani bu bir vurgu; şeridin ne kadarını boyadığı sistemin kararı.

Bu arada bir tutarsızlık düzeltildi: "hangi okuma başı çeker" mantığı
`headlineIndexOf()` içinde tek bir yere alındı. Renk oradan hesaplanıyor,
değişim tespiti imzası da aynı okumayı hash'lemek zorunda — iki yerde
farklı seçmek, tam da gösterilmeye değer renk değişimlerini kısıtın
yutmasına yol açardı, üstelik yalnızca CPU'yu ilk sırada bildirmeyen
makinelerde.

Sürüm 0.11.8 / versionCode 28. Testler ve release temiz, lint 68 = taban.


---

## 0.12.0 — widget ailesi: iki yeni boy, ve kayıp `detail` alanı

İstenen: widget tasarımları görsel olarak daha iyi ve daha çok veri
taşısın; A, C ve Compact ayrı widget'lar olsun, farklı boylarda başkaları
da eklensin.

Görsel bir işi göremediğim bir cihaza kör göndermek bu seansta birkaç tura
mal olduğu için önce gerçek ölçekli bir maket üretildi
(`scratchpad/mock/widgets.html`, 1dp = 2px, gerçek okumalarla) ve düzen
seçildikten sonra yazıldı.

**Veri katmanı: `detail` saklanmıyordu.** `WidgetSnapshotStore.save()`
`metric.detail`'i (`"45 °C"`, `"17.5 / 31.3 GiB"`) düşürüyordu — bildirimde
bulunan kaybın aynısı. Yani hiçbir widget, ne kadar yeri olursa olsun
sıcaklığı gösteremezdi. Artık `key` ile birlikte saklanıyor (`key`, hangi
okumanın başı çektiğini bilgisayarın görüntü dizesine bakmadan bilmek
için). Eski dosyalar sorunsuz okunuyor: alan yoksa boş dize.

Bu, sayımlar için bilinçli olarak saklanan isim ayrıntılarından farklı bir
şey — yanındaki yüzdeyle aynı sınıfta bilgi, o yüzden gizlilik notu
çiğnenmiyor.

**İki yeni boy.**

* `Detailed` (4×3): maketteki C. Metrik başına iki satır — etiket, değer ve
  **tam** detay bir satırda, çubuk altında tam genişlikte. C'yi 4×2'de
  denemek dört metriğin birini feda ettiriyordu; kendi boyunu verince
  dördü de sığıyor, istatistik kutuları da duruyor.
* `Tile` (2×2): dört uygulama ikonu kadar yerde iki okuma, detaylarıyla.
  Yığılmış satır kullanıyor, çünkü bu genişlikte üç sütun çubuğu kendi
  kenar boşluğundan ince bırakıyor.

Aile artık: Mini 2×1, Tile 2×2, Compact 4×1, Dashboard 4×2, Detailed 4×3.

**Uygulama detayı.** Yeni layoutlar mevcut id'leri **yeniden kullanıyor**.
RemoteViews'un id çakışma sorunu tek bir layout ağacı içinde geçerli — ayrı
layout dosyaları arasında değil — dolayısıyla `render()` hangi boyu
doldurduğunu bilmeden hepsini dolduruyor. Tek ek, `hasDetail` bayrağı:
geniş satır (etiket | çubuk | değer) 40dp'lik bir değerin yanında
`"17.5 / 31.3 GiB"` taşıyamaz, o yüzden o boylar alanı hiç göstermiyor
(kırpıp anlamsızlaştırmak yerine). Bilgisayar detay göndermezse görünüm
boş bırakılmıyor, `GONE` yapılıyor: boş bir görünüm de satırını işgal eder
ve sayı olması gereken yerde boşluk, sunulmamış bir okuma gibi değil,
başarısız olmuş bir okuma gibi okunur.

Çubuklar **beyaz bırakıldı**. Maketteki C yüke göre renklendirme
gösteriyordu ama kod birkaç yerde "bu arayüzde vurgu rengi yok, palet katı
monokrom" diyor; Now Bar'da renk kullanıldı çünkü orası sistemin yüzeyi ve
renk oranın dili. Widget uygulamanın kendi yüzeyi, o karar sahibine
bırakıldı.

Sürüm 0.12.0 / versionCode 29. Testler ve release temiz. `:app:lintDebug`
94 uyarı (taban 68): büyüyen tek iki kategori `SmallSp` (+19) ve
`UnusedAttribute` (+6), ikisi de yeni layout dosyalarının kendisi — punto
ölçeği mevcut layoutlarla aynı (widget_dashboard 8sp kullanıyor, yeniler
9sp), yani yeni bir uyarı **türü** yok.

**Cihazda görülmedi.** Beş sağlayıcının da APK'da kayıtlı olduğu
doğrulandı; yerleşimlerin gerçek ekranda nasıl oturduğu görülmeli.


---

## 0.12.1 — widget'ların yarısı boştu

Bildirilen: widget'lar gereksiz büyük, yarısı dolu yarısı boş.

İki ayrı sebep vardı.

**Kök `match_parent`, çocuklar `wrap_content`.** Kök, launcher'ın verdiği
çerçeveyi dolduruyor ama `wrap_content` çocuklar tepede yığılıyor. Hücreleri
bu içerikten uzun olan bir ana ekranda widget kendini üst yarıya çizip
gerisini boş siyah bir levha bırakıyordu. Ölçer satırları artık
`layout_weight` taşıyor, boşluğu aralarında paylaşıyorlar — hangi yükseklik
verilirse verilsin kasıtlı görünüyor.

**İlan edilen boyutlar içerikle uyuşmuyordu.** Ölçüldü:

    Dashboard  icerik ~191dp, ilan 160dp  -> 185dp
    Detailed   icerik ~215dp, ilan 250dp / 3 hucre  -> 210dp / 2 hucre
    Tile       icerik ~138dp, ilan 110dp  -> 140dp

`Detailed` üç hücre istiyordu ama içeriği `Dashboard`'ınkinden yalnızca %12
fazla; hak etmediği bir hücreyi işgal ediyordu. Artık ikisi de 4×2 — aynı
alanda iki farklı bilgi yoğunluğu, kullanıcı hangisini isterse.

`NestedWeights` bilerek susturuldu, gerekçesi layout dosyalarının başında:
mekanizma konusunda haklı (ağırlıklı satır + ağırlıklı çubuk = fazladan
ölçüm geçişi), maliyet konusunda değil — bir düzine görünümlük bir ağaçta
dört satır, en fazla iki dakikada bir yeniden çiziliyor, hiçbir kaydırma
veya animasyon sırasında değil. Alternatifi, bu turda düzeltilen hatanın
kendisi.

Sürüm 0.12.1 / versionCode 30. Testler ve release temiz, lint 94 =
0.12.0 tabanı (yeni tür yok).


---

## 0.12.2 — içeriği kısmadan boyutu küçültme

Bildirilen: widget gereksiz büyük; içeriği azaltmadan boyutu küçülsün.

**Boşluklar kısıldı, veri değil.** Dolgu 14 -> 10dp, ölçer satır aralığı
10 -> 4dp (Detailed/Tile) ve 6 -> 3dp (Dashboard), çubuk üstü 4 -> 3dp,
çubuk 6 -> 5dp, istatistik hücresi dolgusu 8 -> 6dp. Tek bir okuma, etiket
veya detay kaldırılmadı.

    Dashboard  191dp -> ~155dp   (-19%)
    Detailed   215dp -> ~200dp   (-7%, ayrica 3 hucre -> 2 hucre)
    Tile       138dp -> ~135dp

**`minResizeWidth/Height` hiç tanımlı değildi** ve bu gerçek bir eksikti:
tanımsızken bir widget'ın sürüklenerek küçültülebileceği en küçük boy
`minWidth`/`minHeight`'ın kendisi oluyor. Yani hücreleri bu içerikten uzun
olan bir ana ekran widget'a istediğinden fazla yer veriyor ve kullanıcıya
onu geri alma yolu bırakmıyordu. Beş boyun hepsine, ölçülen içeriğin biraz
altında bir zemin kondu.

`minHeight` değerleri artık tahmin değil, yerleşmiş içeriğe karşı ölçülmüş.

**Bu turda kendi yaptığım bir gerileme düzeltildi:** beş `widget_info`
dosyasını baştan yazarken hepsine `@string/widget_description` koymuştum,
`widget_mini_description` boşta kalmıştı (lint yakaladı). Her boy kendi
açıklamasına döndü ve iki yeni boy kendi açıklamasını aldı — widget
seçicisinde beş girdi varsa ayırt edilebilmeleri gerekiyor.

Sürüm 0.12.2 / versionCode 31. Testler ve release temiz, lint 94 = 0.12.1
tabanı.

**Kurulumdan sonra widget'lar kaldırılıp yeniden eklenmeli:** hücre tahsisi
yerleştirme anında yapılıyor, `targetCellHeight`/`minHeight` değişiklikleri
yerinde duran bir widget'a uygulanmıyor.


---

## 0.12.3 — satır araları ve istatistik kutuları

Bildirilen: hâlâ 4×3 devasa bir widget var, hiçbir şey değişmedi; aynı
içerik 4×2'ye sığar, widget'ın içinde gereksiz boşluklar var.

**4×3 iddiası paketten doğrulandı ve yanlış:** 0.12.2 APK'sında
`detailed` `targetCellHeight=2`. Yayınlanan pakette 4×3 yok. Görülen ya
eski sürüm ya da eski yerleşim — launcher hücre tahsisini yerleştirme
anında donduruyor, yerinde duran widget yeni `targetCellHeight`'ı almıyor.
Kurulu sürüm artık Settings > Version'da yazıyor, bu ayrım
tahminle çözülmesin diye.

**"Widget içinde gereksiz boşluk" kısmı ise doğruydu ve 0.12.1'de benim
açtığım bir sorundu.** Alttaki boşluğu kapatmak için ölçer satırlarına
ağırlık vermiştim; bu boşluğu yok etmiyor, **her satırın arasına**
dağıtıyordu. Satırlar doğal yüksekliklerine döndü ve sıkı bir blok olarak
duruyor; artan yer bir kez, kapsayıcı tarafından, ortalanarak soğuruluyor.
`NestedWeights` susturmaları da bununla birlikte kalktı — artık gerekmiyor.

**İstatistik kutuları yatay oldu.** Değer üstte etiket altta iki metin
satırı ~41dp yiyordu; yan yana ~24dp. Üç ayrı kutu — tasarımın asıl
önemsediği şey, katlanmış tek bir cümle yerine üç ayrı sayı — aynen duruyor.

    Dashboard  155dp -> 135dp  (en kucuk 115dp)
    Detailed   200dp -> 180dp  (en kucuk 155dp)

İlk ölçümden bu yana Dashboard 191 -> 135dp (%29 daha kısa), Detailed
215 -> 180dp (%16), ve Detailed bir hücre birden inmiş oldu. Tek bir okuma,
etiket veya detay kaldırılmadı.

Sürüm 0.12.3 / versionCode 32. Testler ve release temiz, lint 94 = taban.


---

## 0.12.4 — punto ve çubuk ölçeği büyütüldü

Bildirilen: 4×2 boyut iyi ama içindeki çubuklar ve yazılar küçük kaldı;
istenen sadece boyu kısmaktı, içerik aynı kalacaktı.

Doğru bir eleştiri ve iki ayrı sebebi vardı. Boyu kısarken çubuk
yüksekliğini de düşürmüştüm (Detailed/Tile 6 -> 5dp), ki bu içeriğin
kendisi; ve yeni layoutların punto ölçeğini mevcut widget'lara bakarak
temkinli seçmiştim — ama o mevcut ölçek zaten küçüktü.

Ölçek bütün ailede yükseltildi:

    etiketler    8/9/10sp -> 11/12sp
    degerler     11/12sp  -> 14/15sp
    baslik       14/15sp  -> 17sp
    istatistik   13sp     -> 16sp
    cubuklar     4/5dp    -> 6/7/8dp

Hücre sayıları değişmedi — 4×2 doğru ayak iziydi, yanlış olan içine
konandı. `minHeight` yeni içeriğe göre yeniden ölçüldü (Dashboard 165dp,
Detailed 220dp, Tile 155dp) ki launcher içeriği sıkıştırmasın.

Bunun nesnel bir doğrulaması da çıktı: `:app:lintDebug` 94 -> 50 uyarı, ve
düşen tek kategori `SmallSp` ("yazı çok küçük"), 56 -> 12. Lint bunu
baştan beri söylüyormuş; bu turdan önceki 37 tanesi de zaten oradaydı,
yani küçük punto bu turda getirilen bir şey değil, devralınan bir şeydi.

Sürüm 0.12.4 / versionCode 33. Testler ve release temiz.


---

## 0.12.5 — minHeight hücre sayısını belirliyor, içerik yüksekliği değil

Bildirilen: widget'lar yine 4×3 oldu.

Doğru, ve doğrudan 0.12.4'ün sebep olduğu bir gerileme. O turda punto
büyütülürken `minHeight` de "ölçülen içerik yüksekliği" ile eşitlendi
(Detailed 180 -> 220dp). Dikkatli olan davranış gibi görünüyor ve değil:
launcher `minHeight`'ı hücre yüksekliğine bölüp **yukarı yuvarlıyor**, ve
220dp iki hücre sınırını aşıp üçe taşıyor.

`minHeight` bu widget'ın olabileceği **en küçük** boy; içeriğinin ne kadar
uzun olduğu değil. İçerik ondan uzun olabilir — tahsis edilen hücreler
zaten daha yüksek. Değerler 4×2 verdiği kanıtlanmış hallerine döndü
(Dashboard 135dp, Detailed 170dp, Tile 130dp) ve **0.12.4'ün büyük puntosu
ile çubukları aynen korundu** — onlar layout'ta yaşıyor, bu dosyada değil.

Bu tuzak `detailed_widget_info.xml`'in başına yazıldı; iki tur üst üste
buraya çarpıldı.

Sürüm 0.12.5 / versionCode 34. APK'nın içinden doğrulandı: üç sağlayıcı da
`targetCellHeight=2`.


---

## 0.12.6 / 0.12.7 — Dashboard 4×1, ve tepeye yapışan içerik

**0.12.6:** Dashboard 4×2 -> 4×1. Yalnızca boşluk kesildi — kenar dolgusu
10 -> 6dp, ölçer üst boşluğu 8 -> 4dp, satır arası 3 -> 1dp, istatistik üst
boşluğu 6 -> 4dp, hücre iç dolgusu 6 -> 4dp; toplam ~24dp. Punto
(11/12/13/16/17sp), 7dp çubuklar, dört ölçer ve üç istatistik kutusu aynen
kaldı.

**0.12.7 — 0.12.6'nın açtığı gerileme.** Aynı turda ölçer kapsayıcısının
`layout_weight` + `gravity="center_vertical"`'ını da kaldırdım,
gerekçem "tek hücrede zaten artan yer olmaz" idi. Yanlış: bir widget
launcher'ın verdiği yüksekliği alır, o da tam sayıda hücredir ve bu
içeriğin yüksekliğine neredeyse hiç eşit olmaz. Hücre bir **taban**, bir
ölçü değil. Soğurucu olmayınca çocuklar tepeden dizildi ve altta koca bir
siyah levha kaldı.

Geri kondu, ve gerekçesi `widget_dashboard.xml`'in başına yazıldı —
kaldırmak iki kez cazip geldi, ikisinde de aynı hatayla sonuçlandı.
Ağırlıklı-ve-ortalanmış hâl satırları sıkı bir blok olarak tutuyor,
istatistik kutularını alta sabitliyor, artan yeri alta ve üste bölüyor;
yani hangi tahsis gelirse gelsin kasıtlı görünüyor — ve widget yerinde
duran eski 4×2 tahsisiyle kalmış olsa bile doğru görünüyor.

Aile: Mini 2×1, Compact 4×1, Dashboard 4×1, Tile 2×2, Detailed 4×2.

Sürüm 0.12.7 / versionCode 36. Testler ve release temiz, lint 50.


---

## 0.15.0 — telefon durumu, telefonu bul, metin paylaşımı, duyarlı widget'lar, yeni gezinme

İstenen: KDE Connect'ten daha iyi, profesyonel bir uygulama; masaüstü
panosunun telefonu göstermesi; widget boyutlarının düzeltilmesi.

### Güvenlik düzeltmeleri

- **Eşleştirme cevabı yanlış linkten kabul ediliyordu.** `PAIR_RESULT`
  bekleyen eşleşmenin cihazıyla linki karşılaştırmıyordu: ağdaki herhangi bir
  eşleşmemiş eş (eşleşme mesajları göndermesine izin var) başka bir cihazla
  süren eşleşmeye "accepted" gönderip onu gerçek cevap olmadan pinletebiliyor
  ve `finalizePairing(link)` **kendi linkini** güvenilir işaretliyordu.
  Masaüstü bekleyenleri zaten linke göre tutuyordu; mobil artık
  `pending.deviceId == link.deviceId` ve `!link.trusted` şartı arıyor
  (`PAIR_RESPONSE` için de).
- `ShareReceiverActivity` dışa açık ve uygulamanın kendi izinleriyle okuyor:
  artık yalnızca `content://` URI kabul ediliyor, `file://` ile uygulamanın özel
  dosyası gönderilemiyor.
- Dışa açık widget yapılandırma etkinliği yalnızca bu uygulamanın
  widget'larına ait `appWidgetId`'yi yapılandırıyor.
- Bilgisayardan gelen link: yalnızca http(s) açılıyor (şemasız olana https
  ekleniyor — önceden `Uri.parse("example.com")` sessizce hiçbir şey
  açmıyordu); `intent:`/`file:`/`javascript:` yalnızca kopyalanıyor.
- `openOnPhone` artık `Message.text()` ile okunuyor: çok satırlı pano metni
  önceden `string()` tarafından sessizce reddediliyordu.

### Yeni özellikler

- **Telefon durumu** (`phoneStatus`): `PhoneStatusCollector` → `PhoneReading`.
  Pil (sticky broadcast), depolama (StatFs), bellek, ağ türü + Wi-Fi RSSI
  (`NetworkCapabilities.signalStrength`, konum izni yok), zil, DND, güç
  tasarrufu, ekran, model, sürüm, uptime. **Wi-Fi adı, konum, kimlik yok.**
  2 sn önbellek. Ayarlar > Bu telefon'da kapatılabilir; kapalıyken gerekçeyle
  reddediliyor.
- **Telefonu bul** (`findPhone`): `FindPhoneRinger` — alarm akışında (sessizde
  de duyulur), alarm sesi en yükseğe çekilip sonra **geri konuyor**, titreşim,
  HIGH önemde bildirim + "Found it". 2 dk sonra kendiliğinden susuyor; her
  duruş `reportRinging(false)` ile çaldıran bilgisayarlara bildiriliyor.
  Ayarlarda anahtar ve *Test ring*. Yeni izin: yalnızca `VIBRATE`.
- **Metin/link paylaşımı** (`shareText`): herhangi bir uygulamanın Paylaş
  menüsünden metin veya link (önceden metin paylaşımı hiçbir şey yapmadan
  kapanıyordu), ve Dosyalar'daki **Clipboard** düğmesi (pano yalnızca uygulama
  öndeyken okunabiliyor, o yüzden düğmede).

### Widget'lar — neden sürekli ya kırpılıyor ya boş kalıyordu

Kök sebep ölçülmüş: bir launcher hücresinin sabit bir boyu yok. Aynı 4×1
dikey Pixel'de ~276×102 dp, yatayda ~554×51 dp, Samsung 4×5 ızgarasında
~360×116 dp. Tek sabit düzen bunlardan birinde mutlaka ya kırpılır ya boş
kalır; 0.12.x turlarının hepsi sorunu bir yerden öbür yere taşıdı. Ölçümler:
Mini'nin içeriği ~87 dp iken 60 dp ilan edilmişti; Controls ~119 dp iken
40 dp; Commands ~90–107 dp iken 40 dp.

Çözüm: **duyarlı widget'lar** (`WidgetSizing`). Her widget birkaç düzen
taşıyor, her birinin boyu XML'den **hesaplanarak** (font padding kapalı,
satır = 1.17×sp) `Style`'da tutuluyor ve düzen dosyasının başında yazılı:

| Düzen | Boy (dp) | İçerik |
| --- | --- | --- |
| mini | 110×48 | ad + iki sayı |
| compact | 180×50 | ad/yaş + üç ölçer (yatay 4×1'e sığar) |
| tile | 110×122 | iki ölçer + detay |
| dashboard | 180×116 | dört satır etiket/çubuk/değer |
| grid | 180×192 | 2×2 kutucuk, büyük sayı + detay |
| detailed | 180×182 | dört satır detaylı + sertleştirme/servis/ağ |
| controls / slim | 180×94 / 250×46 | |
| commands / slim | 180×88 / 180×44 | |

API 31+'da boyut haritalı `RemoteViews` — launcher her yön ve yeniden
boyutlandırmada kendisi seçiyor. API 28–30'da aynı seçim uygulama tarafında
seçeneklerden (min/max genişlik/yükseklik) yatay/dikey çift olarak yapılıyor.
İkisi de platformun kendi kuralı (sığanlar arasında en yakın; hiçbiri
sığmıyorsa en küçük). `WidgetSizingTest` bu seçimi gerçek ölçülerle Android
belgelerindeki dikey/yatay boylar ve Samsung ızgarası için çiviliyor.

Sağlayıcı boyları artık Android formülü (n hücre → 70n−30 dp), minResize tek
satıra kadar iniyor (küçültünce kırpılmıyor, o boyun düzenine geçiyor).
Widget seçicide boş kart yerine örnek verili önizleme düzenleri
(`widget_preview_*`, kaynak düzenden üretildi). Yuvarlatılmış çubuklar,
ortak tip ölçeği `values/widget_styles.xml`.

**Cihazda görülmedi** (bu makinede emülatör imajı yok). Yerleştirilmiş eski
widget'lar yeni düzeni ilk güncellemede alıyor; hücre tahsisi değişmez —
istenirse kaldırıp yeniden eklenmeli.

### Gezinme

Alt çubuk 8 sekmeydi ve yana kayıyordu (yarısı ekran dışı). Artık 5 sabit:
**Home, Media, Commands, Files, More**; More altında Devices, Guard, Maze AI,
Settings (her biri açıklamalı). Geri tuşu yukarı yürüyor (alt sayfa → More →
Home). Masthead'in sağında hangi bilgisayara bağlı olduğu duruyor, dokununca
Devices. Dashboard'un boş durumunda "Pair a computer" düğmesi; artık var
olmayan anahtarlardan bahseden eski metin düzeltildi. Ayarlar kaydırılabilir.

### Doğrulama

`assembleDebug`, tüm birim testleri (yeni: `PhoneReadingTest`,
`WidgetSizingTest`, `InteropTest.phoneMessageShape`) ve `lintDebug` temiz.
Lint 50 → 16 uyarı; kalanların hepsi bu turdan önce de vardı.

Sürüm 0.15.0 / versionCode 47. **Masaüstü 1.3.0 ile birlikte yayınla.**


---

## 0.16.0 — pano kutucuğu ve kısayollar, "Kaydet", USB ikonu

- **Hızlı Ayarlar kutucuğu "Clipboard to PC"** ve **başlatıcı kısayolları**
  (uzun bas: Panoyu gönder, Medya, Komutlar, Dosyalar).
  - Kutucuk arka planda çalışıyor ve Android orada pano okumayı reddediyor;
    bu yüzden kutucuk hiçbir şey okumuyor ya da göndermiyor, yalnızca
    `ClipboardSendActivity`'yi açıyor. O etkinlik panoyu **pencere odak
    alınca** okuyor ve metni gösterip **onay istiyor** — paylaşım menüsüyle
    aynı akış (`ShareReceiverActivity`'nin alt sınıfı). Dışa açık değil.
  - Sekme kısayolları `MainActivity`'ye `openTab` ekstrası veriyor; değer
    sabit tabloda aranıyor ve yalnızca açılış sekmesini seçiyor.
  - `shortcuts.xml`'deki `targetPackage` applicationId'nin elle yazılmış
    kopyası — değişirse ikisi birlikte değişmeli.
- **Kaydet:** alınan dosya `CreateDocument` ile kullanıcının seçtiği yere
  kopyalanıyor; depolama izni yok. Kaynak yolu inbox'ın gerçek (canonical)
  yolu altında mı diye yeniden kontrol ediliyor, yani Kaydet başka bir özel
  dosyayı dışarı kopyalamanın yolu olamıyor.
- **Guard'da USB ikonu** (`ic_usb`); Guard'ın boş durumundaki artık var
  olmayan anahtarlardan bahseden metin düzeltildi.

Sürüm 0.16.0 / versionCode 48. Cihazda görülmedi.

### 0.16.0 — ikinci tur: Remote sekmesi, paylaşılan klasör, pano senkronu

- Alt çubuk: **Home, Media, Remote, Files, More** (Commands More altına).
- **Remote**: Touchpad (sürükle, dokun = sol tık, iki parmak dokun = sağ tık,
  iki parmak kaydırma, *Drag* düğmesi), Keyboard (yazılan fark olarak gider,
  silme Backspace; Ctrl/Alt/Shift/Super bir sonraki tuşa uygulanır; tuş
  satırları), Presenter (büyük İleri/Geri, Start/Black/End; **ses tuşları**
  slayt değiştirir — `VolumeKeyRouter`, `onKeyDown`). Aktifken ekran açık kalır.
- **Files → Computer's folder**: klasörler arasında gezinme, dosyaya dokununca
  indirme. Yalnızca bu telefonun isteği için söz verilen transfer id'si
  sorusuz kabul ediliyor (`expectedFetches`).
- **Pano senkronu**: Ayarlar'da anahtar (varsayılan kapalı). Bilgisayarınki
  hemen yazılıyor; telefonunki uygulama odak alınca gidiyor (Android kuralı),
  hassas işaretli olanlar asla.
- Kısayollardaki Commands yerine Remote.

Doğrulama: derleme, birim testleri (+`InteropTest.remoteAndFolderMessageShape`),
lint 15 (hepsi eski). Cihazda görülmedi.

## 0.16.1 — onay bekleme durumu, resim önizlemeleri

- Remote: `inputState.pending` → "Waiting for approval…" ve kutulu açıklama;
  ret gerekçesi artık kutulu, görünür metin, düğme "Try again".
- Paylaşılan klasör: resimlerde 44 dp önizleme (satır görününce istenir),
  dokununca büyük önizleme + *Download*. Gelen dosya penceresinde resim
  önizlemesi. `PreviewImage.decode`: base64 sınırı, sıkı çözme, yalnızca JPEG;
  `validatedPreview`: piksel boyutu 2048 üstü reddediliyor (bitmap ayırmadan).
  Önbellek en fazla 150 önizleme. `PreviewImageTest`.

Sürüm 0.16.1 / versionCode 49. Cihazda görülmedi.
