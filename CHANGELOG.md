# Sürüm Geçmişi

Sayfanın en altındaki damga, yüklü kopyanın tarihini ve içerik özetini gösterir.
Önbellek sürümü `sw.js` içindeki `tyt-panel-vN` değeridir.

---

## Depo düzenlemesi

- README yeniden yazıldı; lisans, değişiklik günlüğü, `.gitattributes` ve `.gitignore` eklendi
- `tools/check.js` — yayın öncesi doğrulama: gömülü JavaScript sözdizimi, servis çalışanı,
  manifesto, mutlak yol taraması, element kimliği eşleşmesi, PNG geçerliliği
- GitHub Actions: her `push` ve `pull request` sonrasında aynı doğrulama çalışır
- **Düzeltme** — sürüm damgası `git HEAD` yerine içerik özetinden üretiliyor. Damga
  yapılandırma sırasında, yani commit'ten önce oluştuğu için hep bir önceki commit'i
  gösteriyordu
- `KURULUM.txt` kaldırıldı; içeriği README'ye taşındı

## Önbellek v6

- Yapılacaklar sekmesi ekranın tam kenarındaydı; iOS orayı sistem kaydırma bölgesi olarak
  kullandığından dokunuş bazen uygulamaya ulaşmıyordu. 12 px içeri alındı, köşeleri
  yuvarlatıldı, basılınca geri bildirim veriyor
- Yedek hatırlatıcısına "bir daha gösterme" seçeneği ve yedek dosyasının gerçek boyutu eklendi

## Önbellek v5

- **Otomatik güncelleme** — yeni servis çalışanı devralınca sayfa kendini bir kez yeniler,
  uygulama öne geldiğinde güncelleme kontrolü yapılır, HTML `no-store` ile çekilir
- Sayfanın altına sürüm damgası eklendi
- Yedek hatırlatıcısı: iOS uzun süre açılmayan sitelerin yerel verisini silebiliyor
- Kullanılmayan `DB.fb` alanı kaldırıldı

## Önbellek v4

- **Düzeltme** — başlıktaki gösterge çubukları dikey blok olarak çiziliyordu. Kapları
  `<span>`, yani satır içi elemandı; orada `height` ve `overflow` uygulanmaz, içteki
  `height:100%` de dayanacak bir yükseklik bulamıyordu
- Sağdaki sabit sekme dar pencerelerde grafiğin üstüne biniyordu; sağ boşluk ayrıldı
- Göstergeler kalan genişliği paylaşıyor

## Önbellek v3

- Kronometre blok zamanlayıcıdan **düz kronometreye** çevrildi; geçen süre o günün
  çalışma toplamına saniye saniye yazılıyor
- Çalışma süresi grafiği (son 14 gün) eklendi
- Yapılacaklar listesi sayfadan çıkıp **sağ kenardan açılan çekmeceye** taşındı;
  motivasyon notu ve her satırda görünür silme düğmesi

## Önbellek v2

- Kronometre ve günlük yapılacaklar listesi eklendi

## Önbellek v1

- İlk yayın: soru takibi, deneme netleri, kitap sayacı, Maarif Modeli konu listesi
- Çevrimdışı çalışma, ana ekrana eklenebilir uygulama kabuğu
- GitHub Pages için tüm yollar göreceli
