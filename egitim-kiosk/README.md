# Eğitim Kiosk — Okul Tableti Yönetim Sistemi

Okul tabletlerini **yalnızca eğitim aracı** olarak çalışan, merkezden yönetilen cihazlara dönüştürür.
Öğrenci tableti açtığında sistemin ana ekranını değil okulun kiosk ekranını görür: sadece öğretmenin
izin verdiği uygulamalar ve PDF ders kitapları vardır. Ayarlar, mağaza, tarayıcı, bildirim paneli yoktur.
Öğretmen web panelinden bir kutucuğu işaretlediği anda sınıftaki tüm tabletler değişir.

```
egitim-kiosk/
├── README.md                    ← bu belge: teknoloji kararları, mimari, kurulum, yol haritası
├── android/                     ← öğrenci tableti: DPC + kiosk launcher + PDF modülü (tek APK, Kotlin)
├── backend/                     ← Firebase: güvenlik kuralları + Cloud Functions (TypeScript)
├── admin-panel/                 ← öğretmen/yönetici web paneli (React + TypeScript)
└── provisioning/                ← tabletleri QR ile toplu kurmak için yük + betik
```

---

## 0. Başlamadan önce: iki kritik ön koşul

1. **Tabletler okulun malı olmalı ve sıfırlanabilmeli.** Tam kiosk kilidi yalnızca uygulama
   *Device Owner* (cihaz sahibi) olduğunda mümkündür; Android bunu sadece **fabrika ayarlarındaki**
   cihaza izin verir. Öğrencinin kişisel tableti ise ancak "iş profili" kurulabilir — iş profili
   cihazı **kilitleyemez**, sadece ayrı bir bölme açar. Kişisel cihazlarda bu proje amacına ulaşmaz;
   ayrıca veli onayı ve KVKK yükümlülükleri ağırlaşır (bkz. §9).
2. **Tabletler Android olmalı.** iPad için bu kod kullanılamaz; Apple tarafında aynı sonuç
   *Apple School Manager + bir MDM (Jamf School, Mosyle vb.) + Single App Mode / uygulama kısıtları*
   ile alınır ve Apple'ın MDM protokolü dışında bir yol yoktur.

---

## 1. Teknoloji yığını

| Katman | Seçim | Neden |
|---|---|---|
| Tablet uygulaması | **Native Kotlin + Jetpack Compose**, tek APK | DPC API'leri (`DevicePolicyManager`, Lock Task) yalnızca native'dir. React Native/Flutter'da da bu kısmı Kotlin ile yazıp köprülemeniz gerekir; kazanç yok, açılışta (boot) ek risk var. |
| Cihaz yönetimi | **Kendi DPC'niz** (Device Owner + Lock Task Mode) | Tam kontrol, lisans ücreti yok, PDF modülü ile aynı uygulamada. |
| PDF görüntüleme | **Android `PdfRenderer`** (yerleşik) | Ek bağımlılık ve lisans yok, API 21'den beri var, hızlı. |
| PDF işaretleme | **Kendi `InkCanvasView` katmanı** (normalize vektör) | Tam kontrol: kalem/avuç ayrımı, silgi ucu, geri al; veriler sizin biçiminizde. |
| PDF dışa aktarma | **PDFBox-Android** (Apache-2.0) | İşaretli sayfaları gerçek PDF'e yazıp öğretmene "teslim" için. |
| Arka uç | **Firebase**: Firestore + Cloud Functions + FCM + Auth + Storage | Gerçek zamanlı senkron, çevrimdışı önbellek, push ve kimlik doğrulama hazır; sunucu yönetimi yok. |
| Admin paneli | **React + TypeScript + Vite**, Firebase Web SDK | Firestore'u doğrudan canlı dinler; ayrı API sunucusu gerekmez. |

### Değerlendirilen alternatifler

- **Android Management API (Google'ın yönetilen MDM'i):** Kiosk modu, uygulama beyaz listesi ve uzaktan
  yönetimi kendi DPC'nizi yazmadan sağlar; Google'ın yeni EMM'lere önerdiği yol budur. Bedeli: okulun
  bir *Managed Google Play* kurumu olması, uygulamaların Play üzerinden dağıtılması, kiosk ekranının
  Google'ın hazır launcher'ı olması. **PDF modülünü yine de ayrı bir uygulama olarak yazmanız gerekir.**
  Kodla uğraşmadan hızlı sonuç istiyorsanız iyi bir seçenek; özel ana ekran, sınav modu ve tek
  uygulamada entegre PDF istiyorsanız bu repodaki yol daha esnektir.
- **Hazır ticari MDM** (Hexnode, Scalefusion, ManageEngine vb.): Cihaz başı aylık ücret karşılığında
  bu sistemin büyük kısmı hazırdır. 3–6 aylık geliştirme maliyetiyle karşılaştırıp karar verin.
- **Nutrient (eski PSPDFKit) / Apryse:** En gelişmiş PDF işaretleme SDK'ları; ticari lisansları
  okul bütçesi için genellikle pahalıdır. Yakınlaştırmalı serbest çizim, form doldurma, metin
  vurgulama gibi ileri ihtiyaçlar doğarsa `PdfReaderActivity` bunlarla değiştirilebilir.
- **Jetpack Ink API (`androidx.ink`)** ve **`androidx.graphics` ön tampon (front-buffered) çizim:**
  Basınca duyarlı, düşük gecikmeli kalem için `InkCanvasView` ileride bunlara taşınabilir.

---

## 2. Sistem mimarisi

```
 ┌───────────────────────── Öğretmen / Yönetici ─────────────────────────┐
 │  Web paneli (React)                                                   │
 │   • Sınıf × Uygulama matrisi  • Mod: Normal / Sınav / Kilitli         │
 │   • Cihaz listesi (çevrimiçi, pil, politika uygulandı mı?)            │
 │   • Komut: mesaj, kitap aç, bakım, yeniden başlat                     │
 └───────────────┬───────────────────────────────────────────────────────┘
                 │ Firestore Web SDK (yazma + canlı dinleme), Auth (Google)
                 ▼
 ┌──────────────────────────── Firebase ─────────────────────────────────┐
 │ Firestore  schools/{okul}/                                            │
 │              config/kiosk          PIN özeti (PBKDF2)                 │
 │              groups/{sınıf}        allowedApps, mode, books,          │
 │                                    policyVersion                      │
 │              devices/{cihaz}       groupId, extraApps, blockedApps,   │
 │                                    status{lastSeen,battery,…}         │
 │                └ commands/{id}     type, payload, ackAt               │
 │              apps/{paket}          apkUrl, sha256 (uygulama kataloğu) │
 │              enrollmentTokens/{t}  süreli kayıt anahtarları           │
 │ Functions  enrollDevice · createEnrollmentToken · setKioskPin ·       │
 │            setStaffRole · onGroupChanged→FCM · onCommandCreated→FCM   │
 │ Storage    okullar/{okul}/kitaplar/*.pdf · teslimler/{okul}/{cihaz}/  │
 │ Rules      rol + okul + cihaz kimliği claim'lerine göre erişim        │
 └───────┬───────────────────────────────────────────────▲───────────────┘
         │ ① Firestore snapshot listener (anlık, ~1-2 sn)  │ durum raporu (5 dk)
         │ ② FCM yüksek öncelikli veri mesajı (uyandırma)  │ komut onayı (ackAt)
         ▼                                                 │
 ┌──────────────────────── Öğrenci tableti (tek APK) ──────┴──────────────┐
 │ PolicySync   → Firestore'dan etkin politikayı hesaplar                 │
 │ PolicyManager→ DevicePolicyManager: lock-task listesi, gizleme, kısıt  │
 │ Launcher     → HOME + Lock Task; izinli uygulamalar + kitaplar         │
 │ PdfReader    → PdfRenderer + InkCanvasView → AnnotationStore (JSON)    │
 └────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Haberleşme ilkeleri

- **Çekme değil, durum eşitleme (desired state).** Panel bir komut "göndermez"; Firestore'daki
  *istenen durumu* değiştirir. Tablet o durumu dinler ve kendine uygular. Tablet o sırada kapalıysa,
  açıldığında son durumu alır — kaybolan mesaj yoktur.
- **İki kanal:** Ön plandaki kiosk ekranı Firestore bağlantısını açık tutar → değişiklik ~1–2 sn'de
  gelir. Cihaz uykudaysa veya bağlantı koptuysa Cloud Function aynı anda **FCM yüksek öncelikli veri
  mesajı** gönderir; bu sadece "uyan ve senkronize ol" sinyalidir, içinde talimat yoktur
  (talimat her zaman yetki kurallarıyla korunan Firestore'dan okunur).
- **Geri bildirim:** Her sınıf değişikliği `policyVersion`'ı 1 artırır; tablet uyguladığı sürümü
  `status.appliedPolicyVersion` olarak yazar. Panel bu ikisini karşılaştırıp her tablet için
  **"✓ uygulandı / ⏳ bekliyor"** gösterir.
- **Tek seferlik işler** (mesaj, yeniden başlat, bakım) `devices/{id}/commands` alt koleksiyonuna
  yazılır; cihaz işledikten sonra `ackAt` alanını doldurur.
- **Çevrimdışı dayanıklılık:** DPM politikaları cihazda kalıcıdır; Wi-Fi gitse, cihaz yeniden
  başlasa bile kilit sürer. Firestore'un yerel önbelleği son politikayı ve PIN özetini tutar;
  yönetici PIN'i internetsiz de doğrulanır.

### 2.2 Etkin politika nasıl hesaplanır

```
izinli = sınıf.allowedApps ∪ cihaz.extraApps − cihaz.blockedApps
mod    = cihaz.modeOverride ?? sınıf.mode            (NORMAL | SINAV | KILITLI)
lockTaskPackages = mod == NORMAL ? izinli + kiosk : { kiosk }
```

Böylece "9-A'da herkes GeoGebra kullanabilir ama şu tablet kullanamaz" gibi istisnalar sınıf
kuralını bozmadan tanımlanır.

### 2.3 Güvenlik modeli

| Tehdit | Önlem |
|---|---|
| Öğrenci başka tabletin verisini okur/değiştirir | Cihaz kimliği Firebase custom token claim'i (`role=device, schoolId, deviceId`); kurallar yalnızca kendi belgesine erişim verir, sadece `status`/`fcmToken` yazabilir. |
| Sahte cihaz kaydı | Kayıt anahtarı rastgele (192 bit), süreli ve kullanım sayısı sınırlı; yalnızca yönetici üretir. |
| Yönetici PIN'inin ele geçirilmesi | PIN cihazda düz metin olarak hiç bulunmaz: PBKDF2-SHA256 (120.000 tur) + tuz; 5 hatada 5 dk kilit. |
| Değiştirilmiş APK ile kurulum | QR'daki imza özeti, indirilen APK'nın imza sertifikasıyla eşleşmezse Android kurulumu reddeder. Katalogdaki her APK `sha256` ile doğrulanır. |
| Push mesajı sahteciliği | FCM içeriği güvenilmez kabul edilir, sadece senkron tetikler. |
| Öğretmen yetkisini kötüye kullanma | Roller `setStaffRole` ile yalnızca yönetici tarafından atanır; komut türleri kurallarda beyaz listede. |

### 2.4 Ölçek ve maliyet (kabaca)

300 tablet, 5 dakikalık nabız → günde ~86 bin Firestore yazması; Firestore'un ücretsiz kotasını aşar
ama Blaze planında **aylık birkaç dolar** mertebesindedir. Nabız aralığını 10–15 dakikaya çıkarmak
veya yalnızca ders saatlerinde göndermek maliyeti ölçekle düşürür. FCM ücretsizdir. Güncel fiyatları
Firebase fiyat sayfasından kontrol edin.

---

## 3. Kiosk modu nasıl çalışır (Android tarafı)

Kodun özü `android/app/src/main/java/tr/okul/kiosk/policy/PolicyManager.kt` dosyasındadır.

| Katman | API | Etki |
|---|---|---|
| Ana ekran bizim | `addPersistentPreferredActivity(HOME → LauncherActivity)` | Home tuşu her zaman kiosk ekranını açar, başka launcher seçilemez. |
| Kiosk kilidi | `setLockTaskPackages` + manifestte `lockTaskMode="if_whitelisted"` + `startLockTask()` | Yalnızca listedeki paketler ön plana gelebilir; son uygulamalar, bildirimler, bölünmüş ekran kapalı. |
| Kilitteki sistem öğeleri | `setLockTaskFeatures(HOME \| GLOBAL_ACTIONS \| SYSTEM_INFO)` | Home tuşu ve güç menüsü çalışır, bildirim/son uygulamalar çalışmaz. |
| Beyaz liste (2. katman) | `setApplicationHidden` | İzin dışı uygulamalar cihazda "yok" gibidir; intent ile bile açılamaz. Klavyeler asla gizlenmez. |
| Kaçış yollarını kapatma | `addUserRestriction(...)` | Güvenli mod, fabrika ayarı, hesap ekleme, bilinmeyen kaynak, USB dosya aktarımı, uçak modu, VPN, tarih/saat, Wi-Fi ayarı… |
| Erişilebilirlik | `setPermittedAccessibilityServices(emptyList())` | Üçüncü taraf ekran üstü menülerle kaçış engellenir. |
| Kendini koruma | `setUninstallBlocked`, `setKeyguardDisabled`, `setStatusBarDisabled` | Kaldırılamaz, kilit ekranı ve bildirim paneli yok. |
| Güncellemeler | `setSystemUpdatePolicy(02:00–05:00)` | Ders saatinde güncelleme/yeniden başlatma olmaz. |
| Yönetici çıkışı | Başlığa uzun bas → PIN → `stopLockTask()` + Ayarlar 10 dk açılır → otomatik yeniden kilit | Öğretmen kapatmayı unutsa bile cihaz kendini kilitler. |

**Modlar:** `NORMAL` (uygulamalar + kitaplar), `SINAV` (yalnızca kitaplar; diğer her şey anında
kapanır), `KILITLI` (tüm ekranlarda "Tahtaya bakın"). Mod değişince öğrenci hangi uygulamadaysa
kiosk ekranına geri alınır.

### Dosya haritası

| Dosya | Görev |
|---|---|
| `admin/KioskAdminReceiver.kt` | DPC bileşeni; Android ≤11 kurulum sonu. |
| `admin/ProvisioningActivities.kt` | Android 12+ QR kurulumu için zorunlu iki aktivite. |
| `admin/EnrollmentStore.kt` | QR'dan gelen okul kimliği + kayıt anahtarı. |
| `policy/PolicyManager.kt` | **Tüm DPC politikaları** (yukarıdaki tablo). |
| `policy/KioskPolicy.kt` | Etkin politika modeli, Firestore → politika dönüşümü, önbellek. |
| `policy/AppInstaller.kt` | Device owner olarak APK'ları sessizce kurma (+ kendi güncellemesi). |
| `launcher/LauncherActivity.kt` | Öğrenci ana ekranı (Compose), PIN diyaloğu, kilit ekranı. |
| `launcher/AdminUnlock.kt` | PIN doğrulama (PBKDF2), süreli bakım oturumu. |
| `sync/PolicySync.kt` | Kayıt, Firestore dinleyicileri, komutlar, durum raporu, teslim yükleme. |
| `sync/KioskMessagingService.kt` | FCM uyandırma. |
| `pdf/PdfLibrary.kt` | Kitapları Storage'dan indirip özel depoda tutar (özet doğrulamalı). |
| `pdf/PdfReaderActivity.kt` | Okuyucu ekranı, araç çubuğu, sayfa çizimi. |
| `pdf/InkCanvasView.kt` | Kalem / fosforlu / silgi, avuç reddi, geri al–ileri al. |
| `pdf/AnnotationStore.kt` | İşaretlemeleri vektör JSON olarak atomik kaydeder. |
| `pdf/AnnotationExporter.kt` | İşaretlemeleri PDF'e yazar ("Teslim et"). |

---

## 4. PDF ve soru çözüm modülü

**Neden hazır bir "PDF viewer" kütüphanesi değil?** Eski popüler açık kaynak görüntüleyiciler
(ör. `AndroidPdfViewer`) uzun süredir bakımsız ve işaretleme desteği yok; ticari SDK'lar pahalı.
Test çözmek için gereken şey aslında basit: **sayfa görüntüsü + üstünde çizim katmanı**. Bu yüzden:

1. `PdfRenderer` sayfayı ekrana sığan en büyük bitmap olarak çizer (tek iş parçacığında; sınıf
   thread-safe değildir).
2. `InkCanvasView` aynı boyutta saydam katmandır. Noktalar **0..1 aralığında normalize** saklanır;
   ekran döndürülse, farklı tablette açılsa veya PDF'e aktarılsa da çizim sayfada aynı yere oturur.
3. Kalem desteği: `getHistoricalX/Y` ile ara noktalar alınır (hızlı yazıda köşeli çizgi olmaz),
   kalemin silgi ucu / yan tuşu otomatik silgidir, **"Sadece kalem"** açıkken parmak ve avuç
   dokunuşları yok sayılır; sistem avuç algılayıp `ACTION_CANCEL` gönderirse çizgi atılır.
4. Silgi çizgi bazlıdır (dokunduğun çizginin tamamı silinir) — şıkkı yuvarlak içine alıp
   değiştirmek isteyen öğrenci için en pratiği; silme işlemi de geri alınabilir.
5. İşaretlemeler PDF'i değiştirmez; kitap başına bir JSON'da durur (`AtomicFile`, pil biterse bozulmaz).
6. **Teslim et:** PDFBox ile çizgiler orijinal PDF'in üstüne vektör olarak yazılır, fosforlu
   kalem %35 saydamlıkla; dosya `teslimler/{okul}/{cihaz}/` altına yüklenir, öğretmen her PDF
   görüntüleyicide açar.
7. PDF cihaz dışına çıkamaz: dosya uygulamanın özel deposundadır, okuyucuda paylaş/yazdır/
   "başka uygulamada aç" yoktur; istenirse sınıf politikasında `screenCaptureDisabled: true`.

**Sonraki adımlar (yol haritasında):** iki parmakla yakınlaştırma (normalize koordinat sayesinde
çizim katmanı aynı dönüşümle ölçeklenir), sayfa küçük resimleri, işaretlemeleri sunucuya yedekleme,
optik form benzeri "cevap anahtarı ile otomatik puanlama".

---

## 5. Kurulum

### 5.1 Firebase projesi

```bash
npm i -g firebase-tools && firebase login
cd egitim-kiosk/backend
firebase use --add                      # projenizi seçin (Blaze planı: Functions için gerekli)
(cd functions && npm install)
firebase deploy --only firestore:rules,storage,functions
```

- Functions'ın çalıştığı servis hesabına **Service Account Token Creator** rolü verin
  (`enrollDevice` custom token imzalar).
- Authentication → Sign-in method → **Google**'ı açın (panel girişi).
- Firebase konsolunda bir **Android uygulaması** (`tr.okul.kiosk`) ekleyip `google-services.json`
  dosyasını `android/app/` içine koyun (repoya koymayın).

**İlk yöneticiyi atama** (bir kez; yönetici önce panele Google ile giriş yapmış olmalı):

```js
// node ilk-yonetici.mjs   (GOOGLE_APPLICATION_CREDENTIALS = servis hesabı JSON'u)
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
initializeApp();
const u = await getAuth().getUserByEmail("mudur@okulunuz.k12.tr");
await getAuth().setCustomUserClaims(u.uid, { role: "admin", schoolId: "ornek-anadolu-lisesi" });
```

Sonrasında öğretmenler `setStaffRole`, PIN `setKioskPin` fonksiyonuyla atanır.

**Örnek veri** (Firestore konsolundan elle veya bir betikle):

```jsonc
// schools/ornek-anadolu-lisesi/groups/9A
{ "name": "9-A", "mode": "NORMAL", "policyVersion": 1,
  "allowedApps": ["org.geogebra.android", "org.khanacademy.android"],
  "books": [{ "id": "mat9", "title": "Matematik 9", "path": "okullar/ornek-anadolu-lisesi/kitaplar/mat9.pdf" }],
  "cameraDisabled": true, "screenCaptureDisabled": false }

// schools/ornek-anadolu-lisesi/apps/org.geogebra.android
{ "label": "GeoGebra", "apkUrl": "https://…/geogebra.apk", "sha256": "…" }
```

> Paket adlarını ve APK kaynaklarını kendiniz doğrulayın; uygulamaların lisans koşulları
> APK'yı kendiniz dağıtmanıza izin vermeyebilir. Bu durumda uygulamayı kurulum sırasında
> Play Store'dan bir kez kurup sonra kilitleyin veya Managed Google Play kullanın.

### 5.2 Geliştirme cihazı (adb ile, en hızlı yol)

```bash
# Cihazda HİÇ hesap olmamalı (Ayarlar → Hesaplar boş). Gerekirse fabrika ayarlarına dönün.
cd egitim-kiosk/android && ./gradlew installDebug      # (wrapper yoksa: gradle wrapper)
adb shell dpm set-device-owner tr.okul.kiosk/.admin.KioskAdminReceiver
```

`gradle.properties` içindeki `kiosk.devSchoolId` ve `kiosk.devEnrollToken` alanlarını doldurun
(`createEnrollmentToken` ile üretilir). Debug derlemede USB hata ayıklama kapatılmaz.

**Kilitten çıkamaz hale gelirseniz:** debug derlemede `adb shell dpm remove-active-admin
tr.okul.kiosk/.admin.KioskAdminReceiver` yalnızca uygulama `android:testOnly="true"` ise çalışır;
geliştirmede debug manifestine bunu ekleyin. Aksi halde tek yol fabrika ayarlarıdır.

### 5.3 Üretim: QR ile toplu kurulum

1. Release APK'yı imzalayıp HTTPS üzerinden erişilebilir bir yere koyun.
2. Panelden/fonksiyondan sınıf için süreli kayıt anahtarı alın
   (`createEnrollmentToken({groupId: "9A", maxUses: 40, hours: 24})`).
3. `provisioning/qr-payload.json`'u doldurun, `provisioning/qr-olustur.sh` ile QR üretin.
4. Fabrika ayarlı tablet → karşılama ekranında **6 kez dokun** → QR'ı okut. Cihaz Wi-Fi'ye bağlanır,
   APK'yı indirir, imzayı doğrular, device owner olur, sunucuya kaydolur ve kiosk ekranına açılır.
   Tablet başına ~2–3 dakika; 40 tablet bir ders saatinde kurulur.

Samsung cihazlarda **Knox Mobile Enrollment**, diğer üreticilerde **Android Zero-Touch** ile
kutudan çıkar çıkmaz otomatik kurulum mümkündür (bayi kaydı gerektirir).

---

## 6. Kaçış testi kontrol listesi

Pilot öncesi **hedef tablet modelinde** her maddeyi deneyin (OEM'ler farklı davranır):

- [ ] Güç + ses tuşuyla güvenli mod → engellenmeli (`DISALLOW_SAFE_BOOT`)
- [ ] Bildirim paneli / hızlı ayarlar çekilemiyor
- [ ] Son uygulamalar ve bölünmüş ekran açılmıyor
- [ ] Google Asistan / "Circle to Search" / Bixby / S Pen hava komutu bir şey açamıyor
- [ ] İzinli uygulama içindeki bağlantı tarayıcıyı açamıyor; "Paylaş" menüsü izin dışı uygulamayı açamıyor
- [ ] **İzinli uygulamanın kendi içindeki web görünümü** (WebView) üzerinden internete serbest
      erişim var mı? Varsa o uygulamayı listeden çıkarın veya ağ seviyesinde filtre kullanın
      (okul DNS/proxy filtresi ya da `setGlobalPrivateDnsModeSpecifiedHost` ile filtreli DNS)
- [ ] Klavye ayarlarından (ör. Gboard → Ayarlar → yardım bağlantıları) dışarı çıkılamıyor;
      gerekirse `setPermittedInputMethods` ile yalnızca sistem klavyesine izin verin
- [ ] Erişilebilirlik kısayolları (ses tuşlarına basılı tutma) üçüncü taraf servis açamıyor
- [ ] Uçak modu, Wi-Fi, tarih/saat değiştirilemiyor
- [ ] USB ile bilgisayara bağlanınca dosya aktarımı ve adb kapalı (release derleme)
- [ ] Kamera kapalı (politikada `cameraDisabled: true` ise)
- [ ] **Kurtarma menüsünden (recovery) fabrika ayarı:** Android bunu engelleyemez. Önlem:
      `applyFactoryResetProtection(...)` ile FRP — sıfırlanan cihaz yalnızca okulun Google
      hesabıyla açılabilir, öğrenci kişisel hesabıyla kuramaz.
- [ ] Pil biterse/yeniden başlarsa doğrudan kiosk ekranı geliyor
- [ ] Ağ yokken kilit sürüyor, PIN çalışıyor; ağ gelince bekleyen değişiklikler uygulanıyor

---

## 7. Yol haritası (1 geliştirici varsayımıyla ~14–16 hafta)

| Faz | Süre | İş | Bitti sayılması için |
|---|---|---|---|
| **0. Keşif** | 1 hf | Tablet marka/model/Android sürümü envanteri; cihaz sahipliği ve veli onayı; izinli uygulama listesi ve APK kaynakları; okul Wi-Fi'ı. AMAPI/ticari MDM ile maliyet karşılaştırması. | Hedef model elde, karar yazılı. |
| **1. DPC kavram kanıtı** | 2 hf | Bu repodaki `android/` ile adb'den device owner; Lock Task, gizleme, kısıtlar, PIN ile çıkış. Sunucu yok, politika sabit. | Kaçış listesinin %80'i geçiyor. |
| **2. Arka uç + kayıt** | 2 hf | Firebase projesi, kurallar, `enrollDevice`, QR kurulumu, canlı politika ve FCM. Kural testleri (Firestore emulator). | QR'la kurulan tablet panelden değişikliği <5 sn'de alıyor. |
| **3. Admin paneli** | 2 hf | Sınıf × uygulama matrisi, modlar, cihaz listesi, komutlar, öğretmen rolleri, kayıt anahtarı/QR üretme ekranı, PIN değiştirme. | Öğretmen, yardım almadan sınıfını yönetebiliyor. |
| **4. PDF modülü** | 3 hf | Kitap dağıtımı, okuyucu, işaretleme, yakınlaştırma, teslim; gerçek ders kitaplarıyla performans (200+ sayfa). | 10 sayfa işaretleme + teslim, takılmadan. |
| **5. Sertleştirme** | 2 hf | Tam kaçış testi, FRP, ağ filtresi, kendi kendini güncelleme (`AppInstaller` ile), çökme raporlama (Crashlytics), pil/ısı ölçümü, imzalama anahtarının güvenli saklanması. | Kaçış listesinin tamamı geçiyor. |
| **6. Pilot** | 2–3 hf | Tek sınıf (≈30 tablet), gerçek dersler. Öğretmen ve öğrenci geri bildirimi, kayıt tutma. | 2 hafta kritik hata yok. |
| **7. Yaygınlaştırma ve işletim** | sürekli | Tüm sınıflar; sürüm kanalları (önce 1 sınıf, sonra herkes); destek süreci; yıllık sıfırlama ve sınıf atlatma prosedürü. | — |

**Sonraki özellik fikirleri:** ders programına göre otomatik mod (ör. 3. ders sınav modu),
öğretmenin tek tıkla bir PDF sayfasını tüm sınıfta açması (`OPEN_BOOK` komutu hazır), cevap
anahtarıyla otomatik puanlama, işaretleme yedekleme, öğrenci bazlı kullanım istatistikleri.

---

## 8. Doğrulama durumu (bu repodaki kod)

| Parça | Durum |
|---|---|
| `backend/functions` (TypeScript) | `tsc` ile tip denetiminden geçti. |
| `admin-panel` (React + TS) | `tsc --noEmit` ile tip denetiminden geçti. |
| Android: `PolicyManager`, `KioskPolicy`, `AppInstaller`, `AdminUnlock`, `InkCanvasView`, `AnnotationStore`, `AnnotationExporter` | Android 15 framework sınıflarına (Robolectric `android-all`) ve PDFBox-Android'e karşı **Kotlin derleyicisinden geçti**. |
| Android: Compose ekranları, Firebase'e bağlı sınıflar, kurulum aktiviteleri | Yazıldığı ortamda AndroidX/Firebase depolarına erişim olmadığı için **derlenmedi**; Android Studio'da ilk derlemede küçük düzeltmeler gerekebilir. |
| Cihaz üzerinde çalıştırma, güvenlik kuralları emülatör testleri | **Yapılmadı** — Faz 1 ve 2'nin işi. |

Bağımlılık sürümleri yazım anındaki kararlı sürümlerdir; Android Studio'nun önerdiği güncel
sürümlere yükseltin.

---

## 9. Hukuki notlar (KVKK)

- Tabletten toplanan veri **en az** tutulmuştur: model, pil, son görülme, ön plandaki uygulama adı,
  uygulanan politika sürümü. Konum, ekran görüntüsü, tarama geçmişi **toplanmaz**; eklemeden önce
  hukuki değerlendirme yapın.
- Öğrenci teslimleri kişisel veri sayılabilir; saklama süresi belirleyin ve Storage'da otomatik
  silme kuralı (lifecycle) tanımlayın.
- Veliler ve öğrenciler için kısa bir aydınlatma metni hazırlayın; okul/İl Milli Eğitim politikalarıyla
  uyumu kontrol edin. Firebase bölgesi olarak AB (`europe-west1`) seçilmiştir; yurt dışına veri
  aktarımı konusunu okulunuzun veri sorumlusuyla netleştirin.
