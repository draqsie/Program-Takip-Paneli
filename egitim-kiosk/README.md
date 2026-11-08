# Okul İzin — okul saatinde uygulama izni (okul içi kullanım)

Öğrencinin kendi tabletine **elle** kurulan küçük bir sistem. Mağaza, Apple başvurusu ve sunucu
kodu yoktur. Okul saatlerinde yalnızca sizin izin verdiğiniz uygulamalar açılır. Öğrenci kapalı bir
uygulamaya dokunursa **"İzin iste"** ekranı çıkar ve istek yalnızca sizin girebildiğiniz panele
düşer. Onaylarsanız uygulama açılır: 40 dakika, gün sonuna kadar ya da kalıcı. Okul saati dışında
tablet tamamen serbesttir. Tam kiosk değildir: öğrencinin tableti onun tableti olarak kalır.

```
egitim-kiosk/
├── android/       ← Android uygulaması (Kotlin). APK, GitHub Releases'tan kurulur ve oradan güncellenir.
├── ios/           ← iPad uygulaması (Swift) + App Store'suz kurulum rehberi + kodsuz B planı
├── backend/       ← Firestore güvenlik kuralları + indeksler (sunucu kodu yok)
└── admin-panel/   ← yalnızca sizin girdiğiniz web paneli (React)
.github/workflows/okul-izin-android.yml  ← etiket atınca imzalı APK'yı derleyip Releases'a koyar
```

---

## 1. Ne yapar, ne yapamaz

Öğrencinin kendi cihazında hiçbir uygulama cihazın sahibi olamaz. Bu yüzden sistem iki şeye dayanır:

1. **Teknik kısıtlama:** Uygulama çalıştığı sürece izinsiz uygulamaları gerçekten kapatır.
2. **Okul kuralı:** "Okula tablet getiren öğrencide Okul İzin açık olmalı." Kapatan öğrenci panelde
   **kırmızı** görünür.

| | Android | iPad — A: uygulama | iPad — B: Ekran Süresi |
|---|---|---|---|
| Kurulum | GitHub'dan APK | Mac + Xcode + geliştirici üyeliği | Ayarlar'dan, kodsuz |
| İzinsiz uygulamayı kapatma | ✅ | ✅ | ✅ |
| İstek → panel → uzaktan onay | ✅ Onay saniyeler içinde cihaza düşer | ✅ Onay, öğrenci Okul İzin'i açınca uygulanır | ❌ Öğretmen iPad'in başında parola girer |
| Paneldeki uygulama listesi | ✅ | ❌ Apple vermez | ❌ |
| Okul saatinde izni / uygulamayı kapatma | Engelli (Ayarlar okul saatinde kapalı) | Kapatabilir → panelde kırmızı | Parolasız kapatamaz |
| Saat değiştirerek atlatma | Engelli (sunucu saati) | Panelde uyarı | Ekran Süresi'ne bağlı |

---

## 2. Akış

**Kurulum (siz, tablet başına ~3 dk):**
1. Uygulamayı kurun.
2. Öğrenci ve veli onayını işaretletin.
3. Ad soyad ve sınıfın **kayıt kodunu** girin.
4. İzinleri verin: Android'de Kullanım erişimi + Üstte gösterme + Bildirim; iPad'de Ekran Süresi + Bildirim.
5. "Koruma aktif" yazısını görün.

**Okul saatinde:**
1. Öğrenci izinsiz bir uygulamayı açar ve engel ekranı çıkar.
2. **İzin iste**'ye basar; istek panelinizde anında görünür (panel açıksa masaüstü bildirimi de gelir).
3. **40 dk / Bugün / Kalıcı / Reddet** seçersiniz.
4. Öğrenciye bildirim gider ve uygulama açılır.

**Panel:**
- **İstekler:** bekleyenler ve son kararlar.
- **Öğrenciler:** koruma durumu, son rapor. Android'de tabletteki uygulamalar onay kutularıyla listelenir.
- **Ayarlar:** okul günleri ve saatleri, herkese açık Android uygulamaları, tek tıkla duraklatma, kayıt kodu üretme.

---

## 3. Mimari

```
 Tablet (Android / iPad)                Firebase — ücretsiz Spark planı          Panel (siz)
 ───────────────────────                ───────────────────────────────          ───────────
 engel ekranı → "İzin iste" ──yazar──▶  schools/{okul}/requests/{id}  ◀─canlı─  İstekler
                                         PENDING → APPROVED / DENIED   ─yazar─  40dk/Bugün/Kalıcı
 canlı dinler ◀── students/{uid}: allowed[], grants{appKey: bitiş}      ◀─yazar─  Öğrenciler
 rapor yazar ───▶ students/{uid}: status{…}, inventory[] (Android)
 canlı dinler ◀── config/settings: okul saatleri, duraklatma, herkese açıklar ◀─  Ayarlar
```

- **Sunucu kodu yok.** Kayıt, istek ve onay doğrudan Firestore'a yazılır. Kimin neyi yazabileceğini
  yalnızca `backend/firestore.rules` belirler.
- **Öğrenci kimliği:** Uygulama anonim bir Firebase oturumu açar. Öğrenci kaydının kimliği bu
  oturumdur; tablet yalnızca kendi kaydına dokunabilir. Kayıt, sizin ürettiğiniz 7 gün geçerli
  kodla yapılır; kurallar kodu ve izin listesinin boş başladığını denetler.
- **Push sunucusu yok.**
  - Android'de ön plan servisi canlı kaldığı için onay anında gelir ve yerel bildirim gösterilir.
  - iPad'de onay, Okul İzin açıkken anında, değilse bir sonraki açılışta uygulanır.
- **Kısıtlama cihazda çalışır.** İnternet kesilse de son kurallar geçerli kalır.
- **Kota:** Android okul saatinde 10 dakikada, dışında saatte bir rapor verir. ~200 tablet
  Firebase'in ücretsiz günlük kotasına (20.000 yazma) sığar. Daha fazlası için rapor aralığını
  `StudentSync.kt`'te büyütün.

### Android dosyaları (`android/app/src/main/java/tr/okul/izin/`)

| Dosya | Görev |
|---|---|
| `enforce/EnforcementService.kt` | Okul saatinde ~0,6 sn'de bir ön plandaki uygulamaya bakar. Saat dışında ve ekran kapalıyken uyur. |
| `enforce/BlockOverlay.kt` | Tam ekran engel: "İzin iste" / "Ana ekrana dön". |
| `enforce/Exemptions.kt` | Asla engellenmeyenler: ana ekran, klavye, sistem, acil arama. **Ayarlar ve uygulama kaldırma okul saatinde bilerek kapalıdır.** |
| `policy/AccessPolicy.kt`, `policy/TrustedClock.kt` | İzin kararı ve sunucu saatine bağlı saat (birim testli). |
| `sync/StudentSync.kt`, `sync/Notifier.kt` | Kayıt, canlı dinleme, istek, rapor, onay bildirimi. |
| `update/Updater.kt` | GitHub'daki son sürümü bulur, özetini doğrular, kurulum ekranını açar. |
| `ui/MainActivity.kt` | Bilgilendirme + onay, kayıt, izin listesi, durum, güncelleme. |

iPad dosyaları ve kurulum: [`ios/README.md`](ios/README.md).

---

## 4. Güvenlik

- **Panele yalnızca siz girersiniz.**
  - `firestore.rules` içindeki `isAdmin()` listesine kendi e-postanızı yazın; yalnızca bu Google
    hesabı veri okuyup yazabilir.
  - Panelin web adresini bilen başkası giriş yapsa bile hiçbir kayıt göremez.
  - Google hesabınızda **iki adımlı doğrulamayı** açın.
- **Öğrenci yetkileri:**
  - Yalnızca kendi kaydını okur.
  - Yalnızca kendi durumunu ve uygulama listesini yazar.
  - Yalnızca kendi adına "BEKLEMEDE" istek açar.
  - **İzin listesini değiştiremez**, başkasının isteğini göremez.
- **APK imzası:** Tüm sürümler aynı anahtarla imzalanır. Android, farklı anahtarla imzalanmış
  "güncellemeyi" reddeder; başkası sahte sürüm yayımlayamaz. Anahtar yalnızca GitHub Secrets'tadır,
  **yedeğini güvenli bir yerde saklayın**: kaybederseniz tabletler yeni sürümü kuramaz.
- **Firebase yapılandırması gizli değildir.** `google-services.json` ve panel ayarları APK'nın ya da
  panelin içinde herkese açıktır; bu normaldir. Koruma kurallardadır.

---

## 5. Kurulum adımları

### 5.1 Firebase (ücretsiz, ~15 dk)

1. console.firebase.google.com → yeni proje (Spark planı yeterli).
2. **Firestore** oluşturun (konum: `eur3` veya `europe-west`).
3. **Authentication** → **Google** ve **Anonim** girişi açın. Authorized domains listesine panelin
   adresini ekleyin.
4. `backend/firestore.rules` içinde yönetici e-postanızı yazın, sonra:
   ```bash
   npm i -g firebase-tools && firebase login
   cd egitim-kiosk/backend && firebase use --add && firebase deploy --only firestore
   ```

### 5.2 Panel

```bash
cd egitim-kiosk/admin-panel
cp .env.example .env.local          # Firebase web ayarları + okul kimliği + yönetici e-postası
npm install && npm run build
firebase use --add && firebase deploy --only hosting   # ücretsiz https://<proje>.web.app adresi
```

Panelde **Ayarlar**'dan okul saatlerini kaydedin ve her sınıf için kayıt kodu üretin.

### 5.3 Android APK'sını GitHub'dan yayımlama (bir kez kurulum)

1. Firebase'e Android uygulaması ekleyin (paket: `tr.okul.izin`). `google-services.json`
   dosyasını indirin.
2. İmza anahtarını oluşturun ve **yedekleyin**:
   ```bash
   keytool -genkeypair -v -keystore okul.jks -alias okul -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 okul.jks    # çıktıyı OKUL_KEYSTORE_BASE64 gizlisine yapıştırın
   ```
3. GitHub → Settings → Secrets and variables → Actions altına şu gizlileri ekleyin:
   `GOOGLE_SERVICES_JSON`, `OKUL_KEYSTORE_BASE64`, `OKUL_KEYSTORE_PASSWORD`, `OKUL_KEY_ALIAS`,
   `OKUL_KEY_PASSWORD`.
4. `android/gradle.properties` içinde `okul.schoolId` değerini panelle aynı yapın.
   `okul.updateBaseUrl` bu repoyu gösteriyor; repo değişirse güncelleyin.
5. Sürüm yayımlayın:
   ```bash
   git tag okul-izin-v1.0.0 && git push origin okul-izin-v1.0.0
   ```
   Actions testleri çalıştırır, imzalı `okul-izin.apk` ile `version.json` dosyasını
   **Releases**'a koyar.

**Tablete kurulum:**
1. Tabletin tarayıcısından `https://github.com/draqsie/Program-Takip-Paneli/releases/latest/download/okul-izin.apk`
   adresini açın (kısa link veya QR yapabilirsiniz).
2. "Bilinmeyen uygulamalar"a bir kez izin verin ve kurun.
3. Okul İzin'i açın ve 2. bölümdeki kurulum adımlarını izleyin.

**Güncelleme:** Yeni etiket attığınızda tabletler Okul İzin açıldığında yeni sürümü görür. Öğrenci
okul saati dışında "Güncelle"ye basar.

> Repo herkese açıksa APK da herkese açıktır. Bu sorun değildir: içinde gizli bilgi yoktur ve
> kayıt kodu olmadan kimse okulunuza kayıt olamaz.

### 5.4 iPad

[`ios/README.md`](ios/README.md): **A)** uygulama (Mac + geliştirici üyeliği, Apple başvurusu yok)
veya **B)** kodsuz Ekran Süresi + öğretmen parolası.

---

## 6. Veli onayı ve veri (KVKK)

- Uygulama ilk açılışta neyin gönderildiğini açıkça yazar. Öğrenci ve veli ayrı ayrı onaylar;
  onaylanan metin sürümü kayda yazılır.
- **Gönderilenler:** ad, sınıf, istekler, koruma durumu ve (yalnızca Android'de) uygulama adları.
- **Gönderilmeyenler:** konum, mesaj, fotoğraf, tarama geçmişi, kullanım süreleri.
- Kısıtlama yalnızca okul saatindedir.
- Velilere kısa bir yazılı bilgilendirme verin. Dönem sonunda panelden eski istekleri ve ayrılan
  öğrencileri silin.

---

## 7. Yol haritası (tek kişi, ~4–6 hafta)

| Hafta | İş | Bitti sayılması için |
|---|---|---|
| 1 | Firebase + panel kurulumu, kurallar; kendi telefonunuzda Android denemesi | İstek → onay → açılma çalışıyor |
| 2 | 3–4 farklı marka Android tablette deneme (Samsung, Xiaomi, Lenovo…). Pil tasarrufu ayarları. İlk GitHub sürümü. | Engel 1 sn içinde; onay < 5 sn |
| 3 | iPad yolunu seçin (A veya B); A ise `ios/README.md`'deki doğrulama listesi | Bir iPad'de akış çalışıyor |
| 4 | Atlatma testi (aşağıda) + tek sınıfla pilot | Tüm maddeler ya engelli ya panelde görünüyor |
| 5–6 | Pilot düzeltmeleri, tüm okula kurulum | — |

**Atlatma testi listesi:**
- Okul saatinde Ayarlar'a girme ve izni kapatma
- Uygulamayı silme
- Saati veya saat dilimini değiştirme
- Uçak modu
- Yeniden başlatma
- Bölünmüş ekran
- İzinli uygulamanın içindeki tarayıcı (gerekirse izinliden çıkarın)
- Güvenli mod: Android'de üçüncü taraf uygulamalar kapanır → rapor kesilir → panelde turuncu
- İkinci kullanıcı veya misafir hesabı

**Xiaomi / Huawei / Oppo uyarısı:** Bu markalar arka plan uygulamalarını agresif kapatır. Kurulumda
Okul İzin için "Otomatik başlatma"yı açın ve pil kısıtlamasını "Kısıtlama yok" yapın.

---

## 8. Doğrulama durumu

| Parça | Durum |
|---|---|
| Panel | `tsc` tip denetimi ve `vite build` başarılı |
| Android: `enforce/*`, `policy/*`, `update/*`, `sync/Notifier.kt` | Android 15 framework sınıflarına karşı **derlendi**; `AccessPolicy` birim testleri **geçti** |
| Android: `StudentSync.kt` (Firebase), `MainActivity.kt` (Compose) | Bu ortamdan Google'ın paket deposuna erişilemediği için **derlenmedi**; ilk derleme GitHub Actions'ta olacak |
| GitHub Actions iş akışı | YAML sözdizimi doğrulandı; gizliler olmadan **çalıştırılmadı** |
| iOS (Swift) | Xcode olmadığı için **derlenmedi** |
| Firestore kuralları | Emülatörle **test edilmedi** |
| Gerçek cihaz | **Denenmedi** |
