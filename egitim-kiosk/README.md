# Okul İzin — öğrencinin kendi tabletinde okul saati uygulama izni

Öğrenciler kendi tabletlerine (Android veya iPad) **Okul İzin** uygulamasını mağazadan indirir.
Okul saatlerinde yalnızca sizin izin verdiğiniz uygulamalar açılır. Öğrenci kapalı bir uygulamaya
dokunursa **"İzin iste"** ekranı çıkar. İstek, yalnızca sizin girebildiğiniz admin paneline düşer.
Onaylarsanız uygulama açılır: 40 dakika, gün sonuna kadar ya da kalıcı. Okul saati dışında tablet
tamamen serbesttir.

```
egitim-kiosk/
├── README.md        ← bu belge
├── android/         ← Android uygulaması (Kotlin): algılama + engel ekranı + istek
├── ios/             ← iPhone/iPad uygulaması (Swift, Apple Screen Time API) + Xcode kurulum notu
├── backend/         ← Firebase: güvenlik kuralları, indeksler, Cloud Functions
└── admin-panel/     ← yalnızca sizin girdiğiniz web paneli (React)
```

---

## 0. Önce dürüst tablo: kişisel cihazda neler mümkün?

Öğrencinin kendi cihazında hiçbir okul uygulaması cihazın sahibi olamaz. Android de Apple da,
cihaz sahibinin bir uygulamayı **kapatabilmesini veya silebilmesini** bilerek mümkün kılar. Bu
yüzden sistem iki ayağa dayanır:

1. **Teknik kısıtlama:** Uygulama çalıştığı sürece izinsiz uygulamaları gerçekten kapatır.
2. **Okul kuralı:** "Tabletini okula getiren öğrencide Okul İzin açık olmalı." Uygulamayı kapatan
   ya da izni geri alan öğrenci panelde **kırmızı** görünür. Gerisi disiplin/okul kuralıdır.

| Özellik | Android | iPad / iPhone |
|---|---|---|
| Okul saatinde izinsiz uygulamayı kapatma | ✅ Engel ekranı uygulamanın üstüne çıkar | ✅ Apple'ın kendi engel ekranı (Screen Time) |
| Öğrenci "İzin iste" → panele düşer → onay → açılır | ✅ | ✅ (bildirime bir dokunuş fazladan) |
| **Panelde öğrencinin tabletindeki uygulama listesini görme** | ✅ Ad + paket adı | ❌ **Apple izin vermez.** Uygulama kimlikleri cihazdan çıkmaz, yalnızca opak anahtar alınır |
| İstek gelen uygulamanın adını görme | ✅ Doğrulanmış (cihazdan) | ⚠️ Öğrencinin yazdığı ad ("öğrenci beyanı" etiketiyle). Onay yine de yalnızca dokunulan uygulamayı açar |
| Panelden önceden "şu uygulamalar açık olsun" seçme | ✅ Öğrenci bazında veya herkese | ⚠️ Yalnızca istek üzerine (kalıcı onay verilebilir) |
| Okul saatinde Ayarlar'a girip izni kapatmayı engelleme | ✅ Ayarlar okul saatinde kapalıdır | ❌ Öğrenci Ekran Süresi iznini her an kapatabilir → panelde "Koruma kapatılmış" |
| Okul saatinde uygulamayı silmeyi engelleme | ✅ Kaldırma ekranı okul saatinde kapalıdır | ⚠️ `denyAppRemoval` ile (prototipte doğrulanmalı) |
| Kapatıldığını / silindiğini fark etme | ✅ 5 dk'da bir rapor; gelmezse panelde turuncu/kırmızı | ⚠️ Rapor yalnızca uygulama açılınca/uyandırılınca; daha gecikmeli |
| Saati değiştirerek atlatma | Sunucu saatine bağlı güvenilir saat kullanılır | Cihaz saati kullanılır; sapma panelde uyarı olur |

**iOS'ta daha güçlü kilit isteyen veliler için:** Veli, çocuğun Apple kimliğini Aile Paylaşımı'nda
"çocuk" olarak yönetiyorsa, iOS uygulaması `.individual` yerine `.child` yetkisiyle kurulabilir.
O zaman Ekran Süresi izni ve uygulama yalnızca **veli onayıyla** kaldırılabilir. Bu, `Views.swift`
içinde tek satırlık bir değişikliktir. Ancak her veli için ayrı kurulum gerektirir.

---

## 1. Akış

**Öğrenci (bir kez):** Mağazadan indirir → bilgilendirme metnini öğrenci ve veli olarak onaylar →
ad soyad + sizin verdiğiniz **kayıt kodu** → izinleri verir (Android: Kullanım erişimi + Üstte
gösterme + Bildirim; iOS: Ekran Süresi + Bildirim) → "Koruma aktif".

**Okul saatinde:** Öğrenci izinsiz bir uygulamayı açar → engel ekranı → **İzin iste** → panelinizde
anında görünür (panel açıksa masaüstü bildirimi de gelir) → **40 dk / Bugün / Kalıcı / Reddet** →
öğrenciye bildirim gider, uygulama açılır.

**Siz (panel):**
- **İstekler:** bekleyenler + son kararlar.
- **Öğrenciler:** koruma durumu (yeşil/turuncu/kırmızı), son rapor. Detayda Android cihazdaki
  uygulamalar onay kutularıyla, iOS'ta onaylanmış uygulamalar listelenir. Geçici izinler geri alınabilir.
- **Ayarlar:** okul günleri/saatleri, herkese açık Android uygulamaları, **tek tıkla duraklatma**
  (tatil, gezi), sınıf için kayıt kodu üretme.

---

## 2. Teknoloji yığını

| Katman | Seçim | Neden |
|---|---|---|
| Android | **Kotlin**, `UsageStatsManager` (ön plan algılama) + `TYPE_APPLICATION_OVERLAY` (engel ekranı) + ön plan servisi | Kişisel cihazda Device Owner yok. Google Play'de ebeveyn denetimi uygulamalarının kullandığı yol budur. |
| iOS | **Swift**, Apple **Screen Time API**: FamilyControls + ManagedSettings + DeviceActivity | Apple'ın üçüncü taraf uygulamaya uygulama engelleme izni verdiği **tek** resmi yol. |
| Arka uç | **Firebase**: Firestore, Auth, Cloud Functions, Cloud Messaging | Gerçek zamanlı istek kuyruğu, push (Android + iOS), sunucu yönetimi yok. |
| Panel | **React + TypeScript** | Firestore'u canlı dinler; istek düştüğü an ekranda. |

**Neden React Native / Flutter değil?** Her iki platformdaki kısıtlama kodu (Android servisi ve
overlay, iOS'un üç uzantısı) zaten native olmak zorunda. Ortak kalan kısım yalnızca iki üç ekranlık
arayüz; köprü kurmanın getirisi yok.

**Hazır ürün alternatifi:** Google Family Link ve Apple Ekran Süresi aynı işi **veliye** yaptırır,
okula değil. Okul adına merkezden onay veren hazır ürünler genellikle okulun cihaz sahibi olmasını
(MDM) ister. Kişisel cihaz + okul onayı birleşimi için bu proje gibi özel bir çözüm gerekir.

---

## 3. Mimari ve veri modeli

```
 Öğrenci tableti                     Firebase (europe-west1)                  Sizin paneliniz
 ───────────────                     ───────────────────────                  ───────────────
 Android: servis + overlay  ─istek─▶ schools/{okul}/requests/{id}   ◀─canlı─  İstekler sekmesi
 iOS: Shield + uzantılar             status: PENDING→APPROVED/DENIED  ─onay─  40dk/Bugün/Kalıcı
        ▲                                     │ onRequestDecided
        │◀──────── push (FCM / APNs) ─────────┘
        │◀── canlı dinleme ─ schools/{okul}/students/{id}               ◀───  Öğrenciler sekmesi
        │                     allowed[], grants{appKey: bitiş}, labels{}
        │── 5 dk rapor ─────▶ status{lastSeen, protection, clockSkewSec}
        │── uygulama listesi ▶ inventory[] (yalnızca Android)
        │◀── canlı dinleme ─ schools/{okul}/config/settings              ◀───  Ayarlar sekmesi
                              schedule, paused, defaultAllowedAndroid[]
```

- **appKey:** Android'de paket adı (`com.ornek.uygulama`). iOS'ta Apple'ın opak uygulama
  token'ının özeti (`ios:3fa1…`). Panel her ikisini de aynı biçimde onaylar.
- **Onay atomiktir:** İstek "APPROVED" yapılırken öğrencinin `allowed` listesine veya `grants`
  haritasına aynı yazmada eklenir (`writeBatch`).
- **Kısıtlama cihazda çalışır, sunucuya bağlı değildir.** İnternet kesilse de son bilinen kurallar
  uygulanır. İzinler ve geçici izin bitişleri cihazda saklanır.
- **Spam koruması:** Bir öğrencinin aynı anda en fazla 3 bekleyen isteği olabilir; fazlası otomatik
  reddedilir (`onRequestCreated`).

### Android'de nasıl çalışır (`android/app/src/main/java/tr/okul/izin/`)

| Dosya | Görev |
|---|---|
| `enforce/EnforcementService.kt` | Okul saatinde ~0,6 sn'de bir ön plandaki uygulamaya bakar. Saat dışında ve ekran kapalıyken uyur. |
| `enforce/ForegroundAppDetector.kt` | Kullanım erişimi olaylarını artımlı okur (pil dostu). |
| `enforce/BlockOverlay.kt` | Tam ekran engel penceresi: "İzin iste" ve "Ana ekrana dön". Activity değil pencere kullanılır, çünkü Android 10+ arka plandan Activity açmayı kısıtlar. |
| `enforce/Exemptions.kt` | Asla engellenmeyenler: ana ekran, klavye, sistem arayüzü, izin diyalogları, acil arama. **Ayarlar ve uygulama kaldırma bilerek muaf değildir.** |
| `policy/AccessPolicy.kt` | Okul saati + izin kararı (birim testli). |
| `policy/TrustedClock.kt` | Sunucu saatine bağlı saat: cihaz saatini değiştirmek okul saatini bitirmez. |
| `sync/StudentSync.kt` | Kayıt, canlı dinleme, istek gönderme, 5 dk'lık durum ve uygulama listesi raporu. |
| `ui/MainActivity.kt` | Bilgilendirme + öğrenci/veli onayı, kayıt, izin kontrol listesi, durum. |

### iOS'ta nasıl çalışır (`ios/`)

| Dosya | Görev |
|---|---|
| `Shared/ShieldEnforcer.swift` | Okul saatinde `shield.applicationCategories = .all(except: izinliler)`. Haftalık okul aralıklarını ve geçici izin bitişini DeviceActivity'ye kurar. |
| `MonitorExtension/OkulMonitor.swift` | Sistem, okul saati başlayınca/bitince bu uzantıyı uyandırır; uygulama kapalı olsa da çalışır. |
| `ShieldConfigExtension/…` | Engel ekranının metni ve "İzin iste" düğmesi. |
| `ShieldActionExtension/…` | "İzin iste"ye basılınca isteği App Group'a yazar ve bildirim gösterir. Bildirime dokununca ana uygulama isteği gönderir. |
| `OkulIzin/SyncService.swift` | Kayıt, izinleri uygulama, istek gönderme, durum raporu. |
| `OkulIzin/Views.swift` | Onay + kayıt, Ekran Süresi yetkisi, istek ekranı. İstek ekranında uygulama adı ve simgesi sistem tarafından çizilir. |

Xcode kurulumu ve **prototipte doğrulanması gerekenler**: [`ios/README.md`](ios/README.md).

---

## 4. Güvenlik: panele yalnızca siz girersiniz

- Panel Google hesabıyla açılır, ama veri yalnızca **`role: admin`** yetkisi taşıyan hesaba açılır.
  Bu yetki yalnızca sunucu tarafında, aşağıdaki betikle verilir. Tarayıcıdan değiştirilemez.
- Asıl koruma **Firestore kurallarındadır** (`backend/firestore.rules`). Paneli bulan biri tek bir
  kayda erişemez.
- Öğrenci cihazı yalnızca **kendi** kaydını okur. Yalnızca kendi durumunu ve uygulama listesini
  yazabilir ve yalnızca kendi adına "BEKLEMEDE" istek açabilir. **Kendi izin listesini
  değiştiremez**, başkasının isteğini göremez.
- Kayıt kodları süreli (7 gün) ve kullanım sayısı sınırlıdır (40).
- **Mutlaka yapın:** Google hesabınızda iki adımlı doğrulama. Firebase'de **App Check**
  (sahte istemcileri engeller). Firebase Auth'ta yalnızca Google girişini açık bırakın.

---

## 5. Kurulum

```bash
npm i -g firebase-tools && firebase login
cd egitim-kiosk/backend && firebase use --add        # Blaze planı (Functions için)
(cd functions && npm install)
firebase deploy --only firestore:rules,firestore:indexes,functions
```

- Functions servis hesabına **Service Account Token Creator** rolü verin. `enrollStudent`, custom
  token imzalar.
- Authentication → Google girişini açın. Panele bir kez giriş yapın, sonra kendinize yönetici
  yetkisi verin:

```js
// node ilk-yonetici.mjs   (GOOGLE_APPLICATION_CREDENTIALS = servis hesabı anahtarı)
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
initializeApp();
const u = await getAuth().getUserByEmail("siz@okulunuz.k12.tr");
await getAuth().setCustomUserClaims(u.uid, { role: "admin", schoolId: "ornek-anadolu-lisesi" });
```

- **Panel:** `admin-panel/.env.local` dosyasına Firebase web yapılandırmasını (`VITE_FB_*`) yazın.
  Sonra `npm install && npm run build`; çıkan `dist/`'i Firebase Hosting'e yükleyin.
- **Android:** `android/gradle.properties` → `okul.schoolId`. `google-services.json` dosyasını
  `android/app/` içine koyun. Android Studio ile açın.
- **iOS:** [`ios/README.md`](ios/README.md).

---

## 6. Mağaza politikaları (yayın öncesi)

**Google Play**
- "Kullanım erişimi" ve "Üstte gösterme" izinleri kabul edilir. Ancak Play Console'da uygulamanın
  **ebeveyn/okul denetimi** amaçlı olduğunu beyan edin.
- Android 14+ `specialUse` ön plan servisi için Play Console'da gerekçe yazın. Gerekçe metni
  manifestte hazır.
- Uygulama listesi kişisel veri sayılır. **Belirgin açıklama** (izin istenmeden önce, uygulama
  içinde) şarttır; `MainActivity`'deki bilgilendirme kartı bunu karşılar. Ayrıca bir gizlilik
  politikası URL'si ve Data Safety formu gerekir.
- Uygulamayı **"Okul İzin"** gibi amacını açıkça söyleyen bir adla yayınlayın. Gizli çalışan
  izleme uygulamaları Play'den kaldırılır; bu uygulama açık bir bildirimle çalışır.

**App Store**
- **Family Controls (Distribution)** yetkisi için Apple'a başvurun (onay süresi değişken). Yetki
  olmadan TestFlight/App Store'a çıkılamaz.
- İnceleme notunda okul akışını ve test için bir kayıt kodu verin.

---

## 7. Hukuki ve okul politikası (KVKK)

- Öğrenciler reşit değil: **veli onayı** uygulamada ayrı bir onay olarak alınır ve onaylanan metin
  sürümü sunucuda saklanır (`consentVersion`). Bunu okulun yazılı veli bilgilendirmesiyle
  destekleyin.
- Toplanan veri **en az** tutulmuştur. Toplananlar: ad, sınıf, istekler, koruma durumu, Android'de
  uygulama adları. Konum, mesaj, fotoğraf, tarama geçmişi ve kullanım süreleri **toplanmaz**.
  Kısıtlama yalnızca okul saatindedir.
- Katılım için okul kuralını yazılı hale getirin. Örnek: "Okula tablet getiren öğrenci Okul İzin'i
  açık tutar; kapatırsa tabletini derste kullanamaz." Teknik sistem bu kuralın uygulanmasını
  kolaylaştırır, onun yerine geçmez.
- Kayıtlar için saklama süresi belirleyin (ör. dönem sonunda istek geçmişini silin).

---

## 8. Yol haritası (1 geliştirici, ~10–12 hafta)

| Faz | Süre | İş | Bitti sayılması için |
|---|---|---|---|
| 0. Hazırlık | 1 hf | Apple Family Controls başvurusu (**ilk gün**), Firebase projesi, veli bilgilendirme metni, okul kuralı | Başvuru gönderildi |
| 1. Arka uç + panel | 2 hf | Kurallar, fonksiyonlar, panel; kural testleri (Firestore emülatörü) | Sahte bir istek panelde onaylanabiliyor |
| 2. Android | 2–3 hf | Bu repodaki kodla 3–4 farklı marka tablette deneme (Samsung, Xiaomi, Lenovo…). Pil ve OEM pil tasarrufu sorunları. | İzinsiz uygulama 1 sn içinde kapanıyor; istek → onay → açılma < 10 sn |
| 3. iOS | 2–3 hf | Xcode projesi, `ios/README.md`'deki doğrulama listesi, gerçek iPad'de deneme | Aynı akış iPad'de çalışıyor |
| 4. Atlatma testi | 1 hf | Aşağıdaki liste | Tüm maddeler ya engelleniyor ya panelde görünüyor |
| 5. Pilot | 2 hf | Tek sınıf; öğrenci ve öğretmen geri bildirimi | 2 hafta kritik sorun yok |
| 6. Yayın | sürekli | Play + App Store, tüm okul, destek | — |

**Atlatma testi listesi:**
- Ayarlar'dan izni kapatma (Android: okul saatinde engelli olmalı; iOS: panelde kırmızı)
- Uygulamayı silme
- Cihaz saatini veya saat dilimini değiştirme
- Uçak modu ile çevrimdışı kalma (kısıtlama sürmeli)
- Yeniden başlatma
- Bölünmüş ekran ve yüzen pencere
- İzinli bir uygulamanın içindeki tarayıcıyla internete çıkma (gerekirse o uygulamayı izinliden çıkarın)
- Güvenli mod (Android: üçüncü taraf uygulamalar devre dışı kalır → rapor kesilir → panelde turuncu)
- İkinci kullanıcı hesabı veya misafir modu

**Sonraki özellik fikirleri:**
- Yönetici telefonuna push (FCM web push; sunucu tarafı hazır, panelde `config/admin.fcmTokens`
  kaydı eklenmeli)
- Sınıf bazında farklı okul saatleri
- Ders programına göre otomatik izin
- Toplu onay ("bu uygulamayı isteyen herkese bugün izin ver")

---

## 9. Doğrulama durumu

| Parça | Durum |
|---|---|
| `backend/functions` | `tsc` tip denetiminden geçti |
| `admin-panel` | `tsc --noEmit` tip denetiminden geçti |
| Android zorlama katmanı (`enforce/*`, `policy/*`) | Android 15 framework sınıflarına karşı **Kotlin derleyicisinden geçti**. `AccessPolicy` / okul saati mantığı **birim testleri geçti** (`app/src/test`). |
| Android Firebase sınıfı (`StudentSync`) ve Compose ekranı (`MainActivity`) | Bu ortamdan Google'ın paket deposuna erişilemediği için **derlenmedi** |
| iOS (Swift) | Bu ortamda Xcode yok; **derlenmedi**. Apple davranışına bağlı noktalar `ios/README.md`'de listelendi |
| Firestore kuralları | Emülatörle **test edilmedi** (Faz 1'in işi) |
| Gerçek cihazda çalıştırma | **Yapılmadı** |
