# iOS / iPadOS uygulaması — Xcode kurulumu

Bu klasörde Swift kaynak dosyaları var; Xcode proje dosyası (`.xcodeproj`) yok, çünkü Apple
hesabınıza ve imzalama ayarlarınıza bağlıdır. Aşağıdaki adımlarla 10 dakikada oluşturulur.

## 1. Apple tarafı (önce bunu başlatın — onay günler/haftalar sürebilir)

1. Apple Developer Program üyeliği (kurum veya bireysel).
2. **Family Controls (Distribution) yetkisi başvurusu:** developer.apple.com → Contact → "Family
   Controls" talep formu. Bu yetki olmadan uygulama yalnızca geliştirme cihazlarında çalışır,
   App Store'a / TestFlight'a çıkamaz. Başvuruda uygulamanın amacını (okul saatinde, veli ve öğrenci
   onayıyla uygulama kısıtlama) açıkça yazın.

## 2. Xcode projesi

| Hedef (target) | Tür | Kaynaklar |
|---|---|---|
| `OkulIzin` | iOS App (SwiftUI), iOS 16+ | `OkulIzin/*`, `Shared/*` |
| `OkulMonitor` | Device Activity Monitor Extension | `MonitorExtension/*`, `Shared/*` |
| `OkulShieldConfig` | Shield Configuration Extension | `ShieldConfigExtension/*`, `Shared/*` |
| `OkulShieldAction` | Shield Action Extension | `ShieldActionExtension/*`, `Shared/*` |

Her dört hedef için **Signing & Capabilities**:

- **Family Controls** yeteneği
- **App Groups** → `group.tr.okul.izin` (SharedStore.swift'teki değerle aynı)

Yalnızca `OkulIzin` için:

- **Push Notifications** ve **Background Modes → Remote notifications**
- Swift Package Manager: `https://github.com/firebase/firebase-ios-sdk` → FirebaseAuth,
  FirebaseFirestore, FirebaseFunctions, FirebaseMessaging
- Firebase konsolundan `GoogleService-Info.plist` ekleyin; APNs anahtarını Firebase'e yükleyin.
- `Info.plist` içine `OkulSchoolID` (String) = okul kimliğiniz (ör. `ornek-anadolu-lisesi`).

Uzantı şablonlarının oluşturduğu sınıf adlarını bu klasördeki sınıflarla değiştirin
(`OkulMonitor`, `OkulShieldConfiguration`, `OkulShieldAction`) ya da uzantının `Info.plist`'indeki
`NSExtensionPrincipalClass` değerini güncelleyin.

## 3. Prototipte mutlaka doğrulanacaklar

Bu kod bu ortamda derlenemedi (Xcode yok). Ayrıca aşağıdaki davranışlar Apple'ın sürümüne göre
değişebilir; ilk iş gerçek bir iPad'de deneyin:

- [ ] `shield.applicationCategories = .all(except:)` kendi uygulamamızı da kapatıyor mu?
      Kapatıyorsa öğrenci istek gönderemez. Çözüm: kurulumda öğrenciye `FamilyActivityPicker`
      ile "Okul İzin"i seçtirip kalıcı izinliye ekleyin.
- [ ] Aynı uygulama için `ApplicationToken` her seferinde aynı mı (appKey kararlı mı)?
- [ ] Shield Action uzantısından yerel bildirim gösterilebiliyor mu?
- [ ] `denyAppRemoval` `.individual` yetkisinde etkili mi?
- [ ] Cihaz saati/saat dilimi değiştirilince DeviceActivity aralıkları nasıl davranıyor?
      (Panel saat sapmasını uyarı olarak gösterir.)
