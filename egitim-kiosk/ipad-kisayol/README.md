# iPad — Kestirmeler (Shortcuts) ile Okul İzin

Apple üyeliği, App Store, 7 günlük yenileme **yok**. iPad'de hazır gelen **Kestirmeler**
uygulaması ve ücretsiz bir Google Apps Script sunucusu kullanılır.

```
 iPad                                            Google Apps Script (ücretsiz)        Panel (siz)
 ────                                            ─────────────────────────────        ───────────
 Instagram açıldı → otomasyon → "Okul İzin"
   kısayolu → ?islem=kontrol&uygulama=Instagram ─▶ karar: okul saati? izinli mi?  ◀──  Firestore
   ◀── AC | KAPAT | BEKLIYOR                       (tüm mantık burada)                  istekler/izinler
   KAPAT → Ana ekrana git + "İzin iste" menüsü
   İzin iste → ?islem=istek ───────────────────▶ requests/{id} PENDING  ────────────▶  40 dk / Bugün / Kalıcı
 Okul günü 08:45, 11:00, 13:30 → ?islem=nabiz ─▶ status.lastSeen  ──────────────────▶  sinyal yoksa KIRMIZI
```

- **Kendini güncelleme:** Karar mantığı (`Kod.gs`) sunucudadır. Kısayol yalnızca soru sorar,
  cevaba göre davranır. Kuralı değiştirmek için `Kod.gs`'yi güncelleyip yeniden yayımlarsınız;
  tüm iPad'ler anında yeni davranışa geçer, iPad'lere dokunmak gerekmez. Okul saatleri, izinler
  ve duraklatma zaten panelden yönetilir.
- **Silindi tespiti:** Kısayol, okul günü belirli saatlerde "nabız" gönderir. "Okul İzin" kısayolu
  silinirse nabız kesilir ve panelde **"Bugün sinyal yok — kısayol silinmiş ya da iPad kapalı
  olabilir"** kırmızı yazar.
- **Saat hilesi işe yaramaz:** Okul saati sunucu saatiyle hesaplanır, iPad'in saati kullanılmaz.

## Dürüst sınırlar (kurmadan önce okuyun)

| Durum | Ne olur |
|---|---|
| Öğrenci "Okul İzin" kısayolunu siler | Nabız kesilir → panelde kırmızı ✅ |
| Kısayollar uygulamasını siler | Ekran Süresi ile engelli (aşağıda) ✅ |
| Kısayollar'a girip **tek bir uygulamanın** otomasyonunu siler | **Fark edilmez.** Ekran Süresi ile Kısayollar günde 1 dakikaya kilitlenir; bu küçük pencere açık kalır |
| İnternet yok (Wi-Fi kapalı) | Kısayol sunucuya ulaşamaz → uygulama **açık kalır**. Nabız da gitmez → panelde kırmızı/turuncu |
| Otomasyonu olmayan uygulama (sonradan indirilen yeni oyun) | **Denetlenmez.** Siz o uygulama için de otomasyon ekleyene kadar serbesttir |
| Uygulama açıldıktan sonra kapanması | Sunucu cevabı ~1–2 sn sürer; uygulama bu sürede görünür, sonra ana ekrana dönülür |

Bu bir **yumuşak kilit + takip** sistemidir. Teknik engeli aşmaya uğraşan öğrenci ya panelde
görünür ya da otomasyon silme gibi küçük bir açıktan geçer. Okul kuralıyla birlikte kullanın.
Uygulamanın kendisi Android'dekinden zayıftır, ama üyeliksiz iPad'de yapılabilecek en iyisidir.

**Gereksinim:** iPadOS 17 veya üstü. Otomasyonun soru sormadan "Hemen Çalıştır" seçeneği için gerekir.

---

## 1. Sunucuyu yayımlama (bir kez, ~10 dk)

1. Firebase projesinin sahibi olan Google hesabıyla **script.google.com → Yeni proje**.
2. `Kod.gs` içeriğini editördeki `Kod.gs` dosyasına yapıştırın.
3. **Proje ayarları** (⚙) → "appsscript.json dosyasını düzenleyicide göster" seçeneğini açın.
   Bu klasördeki `appsscript.json` dosyasını yapıştırın.
4. **Proje ayarları → Komut dosyası özellikleri** altına iki özellik ekleyin:
   `PROJE` = Firebase proje kimliği ve `OKUL` = okul kimliği (panelle aynı).
5. **Dağıt → Yeni dağıtım → Web uygulaması**. Ayarlar: "Şu kullanıcı olarak yürüt: **Ben**",
   "Erişimi olanlar: **Herkes**". Yetkileri onaylayın.
6. Çıkan `https://script.google.com/macros/s/…/exec` adresini kopyalayın.
7. Tarayıcıda deneyin: `…/exec?k=AAAAAAAAAAAAAAAAAAAA&uygulama=x` → `HATA:anahtar` görmelisiniz.
   Bu, sunucunun çalıştığını gösterir.
8. Panelde **Öğrenciler → + iPad öğrencisi ekle** ile kendinize bir deneme öğrencisi açın.
   Onun anahtarıyla `…/exec?islem=nabiz&k=ANAHTAR` → `TAMAM` gelmeli ve panelde "Aktif" görünmeli.
   `HATA:sunucu` gelirse, Apps Script'te **Yürütmeler** sayfasındaki hataya bakın.
   "Cloud Firestore API has not been used in project …" yazıyorsa: **Proje ayarları → Google
   Cloud Platform projesi → Projeyi değiştir** ile Firebase projenizin numarasını girin.

**Kuralı güncellemek:** `Kod.gs`'yi düzenleyin, sonra **Dağıt → Dağıtımları yönet → (kalem)
→ Sürüm: Yeni sürüm → Dağıt**. Adres değişmez; iPad'lere bir şey yapmanız gerekmez.

---

## 2. "Okul İzin" kısayolunu oluşturma (bir kez, kendi iPad/iPhone'unuzda)

Kestirmeler → **+** → adı **Okul İzin**. Eylemleri sırayla ekleyin (Türkçe adların yanında
İngilizce adlar parantez içinde):

| # | Eylem | Ayar |
|---|---|---|
| 1 | **Metin** (Text) | `ANAHTAR` yazın. Kısayol ayarları → *İçe Aktarma Sorusu Ekle* → bu metin, soru: **"Kurulum anahtarı"** |
| 2 | **Değişkeni Ayarla** (Set Variable) | Ad: `Anahtar`, değer: Metin |
| 3 | **Metin** | Sunucu adresi (`https://script.google.com/macros/s/…/exec`) |
| 4 | **Değişkeni Ayarla** | `Sunucu` |
| 5 | **Eğer** (If) | *Kestirme Girdisi* **şudur** `NABIZ` |
| 6 | ↳ **URL'nin İçeriğini Al** (Get Contents of URL) | `Sunucu`?islem=nabiz&k=`Anahtar`&surum=1 |
| 7 | ↳ **Bu Kestirmeyi Durdur** (Stop This Shortcut) | |
| 8 | **Eğer Bitti** | |
| 9 | **URL Kodla** (URL Encode) | girdi: *Kestirme Girdisi* → değişken `UygulamaURL` |
| 10 | **URL'nin İçeriğini Al** | `Sunucu`?islem=kontrol&k=`Anahtar`&uygulama=`UygulamaURL`&surum=1 → değişken `Cevap` |
| 11 | **Eğer** `Cevap` **şudur** `AC` → **Bu Kestirmeyi Durdur** | |
| 12 | **Eğer** `Cevap` **şununla başlar** `HATA` → **Bildirim Göster** "Okul İzin: `Cevap`" → **Durdur** | Sunucu hatasında uygulamayı kapatmaz |
| 13 | **Ana Ekrana Git** (Go to Home Screen) | |
| 14 | **Eğer** `Cevap` **şudur** `BEKLIYOR` → **Bildirim Göster** "*Kestirme Girdisi* için isteğin onay bekliyor" → **Durdur** | |
| 15 | **Menüden Seç** (Choose from Menu) | Soru: "*Kestirme Girdisi* okul saatinde kapalı". Seçenekler: **İzin iste**, **Vazgeç** |
| 16 | ↳ *İzin iste:* **Girdi İste** (Ask for Input) | "Neden lazım? (boş bırakabilirsin)" → **URL Kodla** → değişken `Not` |
| 17 | ↳ **URL'nin İçeriğini Al** | `Sunucu`?islem=istek&k=`Anahtar`&uygulama=`UygulamaURL`&not=`Not` |
| 18 | ↳ **Bildirim Göster** | "İsteğin öğretmenine gönderildi" |
| 19 | ↳ *Vazgeç:* (boş) | |

Sonra kısayolu **Paylaş → iCloud Bağlantısını Kopyala**. Bu bağlantıyı açan iPad, kısayolu
eklerken **yalnızca kurulum anahtarını** sorar; sunucu adresi içinde gelir.

> Kısayolu değiştirmeniz neredeyse hiç gerekmez, çünkü mantık sunucudadır. Değiştirirseniz
> `surum=1` değerini artırın; panel her öğrencinin hangi sürümde olduğunu gösterir.

---

## 3. Her iPad'in kurulumu (~5–8 dk)

1. **Panel → Öğrenciler → + iPad öğrencisi ekle** (ad, sınıf). Detayda bir **QR kod** çıkar.
2. iPad'de iCloud bağlantısını açın → **Kestirme Ekle** → anahtar sorulunca iPad kamerasıyla
   paneldeki QR'ı okutun → "Kopyala" → yapıştırın.
3. **Kestirmeler → Otomasyon → + → Uygulama.** Denetlenecek her uygulama için ayrı bir
   otomasyon kurun (ör. Instagram, TikTok, YouTube, oyunlar):
   - Uygulama: **Instagram** · **Açıldığında** · **Hemen Çalıştır** · Çalıştırınca bildir: kapalı
   - Eylemler: **Metin** `Instagram` → **Kestirmeyi Çalıştır** `Okul İzin` (girdi: Metin)
   - Metin, panelde görünecek uygulama adıdır. İzinler bu ada göre verilir (büyük/küçük harf ve
     I/İ farkı önemsizdir).
4. **Nabız otomasyonları** (silindi tespiti): **Otomasyon → + → Günün Saati** üç kez kurulur:
   **08:45**, **11:00**, **13:30** · Haftalık: Pzt–Cum · **Hemen Çalıştır** · Eylemler:
   **Metin** `NABIZ` → **Kestirmeyi Çalıştır** `Okul İzin`. Saatleri okul saatlerinize göre ayarlayın.
5. **Ekran Süresi ile koruma** (Ayarlar → Ekran Süresi):
   - **Ekran Süresi Parolası**'nı öğretmen belirler. Kurtarma için Apple kimliği sorulursa **okulun**
     kimliğini girin ya da atlayın. Öğrencinin kimliğini girerseniz öğrenci parolayı sıfırlayabilir.
   - **Uygulama Sınırları → Sınır Ekle → Kestirmeler → 1 dk → Sınırın Sonunda Engelle.**
   - **İçerik ve Gizlilik Kısıtlamaları → iTunes ve App Store Satın Almaları → Uygulama Silme:
     İzin Verme.**
6. **Deneme** (okul saatinde):
   1. Instagram'ı açın → ana ekrana dönmeli ve menü çıkmalı.
   2. "İzin iste"ye basın → panelde istek görünmeli.
   3. 40 dk onaylayın → Instagram açılmalı.
   4. Panelde öğrencinin durumu "Aktif" olmalı.

## 4. Sorun giderme

| Belirti | Neden / çözüm |
|---|---|
| Bildirim: `HATA:anahtar` | Anahtar yanlış girilmiş ya da öğrenci panelden silinmiş. Kısayolu silip yeniden ekleyin |
| Bildirim: `HATA:sunucu` | Apps Script **Yürütmeler** sayfasındaki hataya bakın (çoğunlukla PROJE/OKUL özelliği eksiktir) |
| Uygulama açılıyor, hiçbir şey olmuyor | Otomasyonda "Hemen Çalıştır" kapalı ya da otomasyon yok. Okul saatinde mi deniyorsunuz? Panelde duraklatma açık mı? |
| Panelde sürekli kırmızı | Nabız otomasyonları kurulmamış, iPad kapalı ya da Wi-Fi yok |
| Çok yavaş | Apps Script ilk çağrıda 2–3 sn sürebilir, sonrakilerde ~1 sn |

**Kota:** Kişisel Google hesabında Apps Script günde 20.000 dış istek yapabilir. Her uygulama
açılışı 1–2 istek harcar. ~100 iPad × günde 50 denetlenen açılış rahatça sığar. Okulunuz Google
Workspace for Education kullanıyorsa kota daha yüksektir.
