# iPad / iPhone — App Store'suz kurulum

iOS'ta Android'deki gibi "GitHub'dan dosyayı indir, kur" yolu yoktur: Apple, imzalanmamış
uygulama kurulmasına izin vermez. Apple'a uygulama incelemesi ya da yetki başvurusu yapmadan iki
yol var. **Hangisini seçeceğiniz iPad sayısına ve elinizde Mac olup olmadığına bağlı.**

| | **A) Okul İzin uygulaması** (bu klasördeki kod) | **B) iPad'in kendi Ekran Süresi** (kodsuz) |
|---|---|---|
| Gerekenler | Mac + Xcode, Apple Developer Program üyeliği (yıllık ücretli) | Hiçbir şey |
| Apple'a başvuru / inceleme | Yok (geliştirici imzası kullanılır) | Yok |
| İzin isteği | Panelinize düşer, uzaktan onaylarsınız | iPad'de "Daha fazla süre iste" → öğretmen **iPad'in başında** Ekran Süresi şifresini girer |
| Okul saatleri | Panelden merkezi | Her iPad'de "Kapalı kalma süresi" olarak elle |
| Öğrenci kapatabilir mi? | Ekran Süresi iznini kapatabilir → panelde kırmızı | Şifreyi bilmeden kapatamaz (en güçlü seçenek) |
| Sınır | Yılda en fazla 100 iPad; kurulum yılda bir yenilenir | — |

## A) Okul İzin'i geliştirici imzasıyla kurmak

Apple'ın "Family Controls" yetkisi **geliştirme imzalı** uygulamalarda başvuru gerektirmeden
çalışır; başvuru yalnızca App Store/TestFlight dağıtımı içindir. Uygulamayı App Store'a
göndermediğimiz için başvuru gerekmez. Ücretsiz Apple kimliğiyle imzalanan uygulamalar 7 günde
bir sona erer ve bildiğim kadarıyla bu yetki ücretsiz hesapta kullanılamaz; bu yüzden ücretli
geliştirici üyeliği gerekir.

### 1. Xcode projesi (bir kez)

| Hedef (target) | Şablon | Kaynaklar |
|---|---|---|
| `OkulIzin` | iOS App (SwiftUI), iOS 16+ | `OkulIzin/*`, `Shared/*` |
| `OkulMonitor` | Device Activity Monitor Extension | `MonitorExtension/*`, `Shared/*` |
| `OkulShieldConfig` | Shield Configuration Extension | `ShieldConfigExtension/*`, `Shared/*` |
| `OkulShieldAction` | Shield Action Extension | `ShieldActionExtension/*`, `Shared/*` |

- Dört hedefin her birine **Signing & Capabilities** altında **Family Controls** ve **App Groups**
  (`group.tr.okul.izin`) ekleyin.
- `OkulIzin` hedefi için:
  - Swift Package Manager ile `https://github.com/firebase/firebase-ios-sdk` paketini ekleyin
    (**FirebaseAuth** ve **FirebaseFirestore**). Push gerekmez.
  - Firebase konsolundan indirdiğiniz `GoogleService-Info.plist` dosyasını ekleyin.
  - `Info.plist` içine `OkulSchoolID` = okul kimliğiniz (Android'deki `okul.schoolId` ile aynı)
    değerini yazın.
- Uzantı şablonlarının oluşturduğu sınıfları bu klasördeki sınıflarla değiştirin.

### 2. Her iPad için

1. iPad'i Mac'e kablo ile bağlayın. Finder'da iPad'i seçip seri numarasına tıklayın; **UDID**
   görünür.
2. developer.apple.com → Devices bölümüne UDID'yi ekleyin. Xcode bunu "Register device"
   ile otomatik de yapabilir.
3. Xcode'da hedef olarak iPad'i seçip **Run**'a basın. Çok sayıda iPad için bir kez
   **Product → Archive → Distribute App → Debugging / Development** ile `.ipa` alın. Sonra bu
   dosyayı Apple Configurator veya Finder ile her iPad'e sürükleyin. UDID'si kayıtlı olmayan iPad'e
   kurulmaz.
4. iPad'de **Ayarlar → Genel → VPN ve Aygıt Yönetimi** altından geliştirici sertifikanıza güvenin.
   Ayrıca iOS 16+'da **Ayarlar → Gizlilik ve Güvenlik → Geliştirici Modu**'nu açın.
5. Okul İzin'i açın: onay + kayıt kodu + **Ekran Süresi izni**.

Geliştirme profili **1 yıl** geçerlidir. Süre dolmadan yeni `.ipa` ile üzerine kurun; veriler
korunur.

### 3. Gerçek iPad'de ilk gün doğrulanacaklar

Bu kod bu ortamda derlenemedi (Xcode yok). Aşağıdakiler Apple'ın davranışına bağlıdır:

- [ ] `.all(except:)` Okul İzin'in kendisini de kapatıyor mu? Kapatıyorsa öğrenci istek
      gönderemez. Çözüm: kurulumda `FamilyActivityPicker` ile Okul İzin'i seçtirip kalıcı izinliye
      ekleyin.
- [ ] Aynı uygulama için `ApplicationToken` her seferinde aynı mı (appKey kararlı mı)?
- [ ] Shield Action uzantısı yerel bildirim gösterebiliyor mu?
- [ ] `denyAppRemoval` bu yetki türünde etkili mi?
- [ ] Cihaz saati değiştirilince okul saati aralıkları nasıl davranıyor? Panel saat sapmasını
      uyarı olarak gösterir.

**Veli desteği varsa:** Öğrencinin Apple kimliği velinin Aile Paylaşımı'nda "çocuk" ise,
`Views.swift` içinde `.individual` yerine `.child` kullanın. O zaman izin ve uygulama yalnızca veli
onayıyla kaldırılabilir.

## B) Kodsuz: iPad'in kendi Ekran Süresi (öğretmen şifresiyle)

Her iPad'de bir kez, öğretmen yapar:

1. **Ayarlar → Ekran Süresi → Ekran Süresi'ni Aç** → "Bu benim iPad'im" → **Ekran Süresi Parolasını
   Kullan**. Parolayı yalnızca öğretmen bilir.
   **Önemli:** iOS kurtarma için Apple kimliği sorduğunda **okulun** Apple kimliğini girin (ya da
   atlayın). Öğrencinin kimliğini girerseniz öğrenci "Parolayı unuttum" ile kendisi sıfırlayabilir.
2. **Kapalı Kalma Süresi:** Özel → okul günleri ve saatleri; "Kapalı Kalma Süresinde Engelle" açık.
3. **Her Zaman İzin Verilenler:** derste serbest olacak eğitim uygulamaları.
4. **İçerik ve Gizlilik Kısıtlamaları → iTunes ve App Store Satın Almaları → Uygulama Silme: İzin
   Verme.**

Okul saatinde kapalı bir uygulamaya dokunan öğrenci **"Daha Fazla Süre İste"** düğmesini görür.
Öğretmen iPad'in başında parolayı girip 15 dk / 1 saat / gün boyu izin verir. Merkezi panel ve
uzaktan onay yoktur, ama öğrenci parolayı bilmeden kısıtlamayı kaldıramaz. Tek kaçış iPad'i
tamamen silmektir.
