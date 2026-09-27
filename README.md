# TYT Takip Paneli

**YKS 2028 hazırlığı için tek dosyalık, bağımlılıksız, çevrimdışı çalışan takip paneli.**
Günlük soru girişi, deneme net gelişimi, kronometreyle çalışma süresi, kitap sayacı ve
Maarif Modeli konu listesi — hepsi tarayıcıda, sunucusuz.

[![canlı](https://img.shields.io/badge/canlı-draqsie.github.io-1E7A58?style=flat-square)](https://draqsie.github.io/Program-Takip-Paneli/)
![bağımlılık](https://img.shields.io/badge/bağımlılık-yok-2B7A9B?style=flat-square)
![çevrimdışı](https://img.shields.io/badge/çevrimdışı-çalışır-5E45A0?style=flat-square)
![boyut](https://img.shields.io/badge/tek_dosya-~106_KB-8E6210?style=flat-square)
![lisans](https://img.shields.io/badge/lisans-MIT-555?style=flat-square)

**→ [draqsie.github.io/Program-Takip-Paneli](https://draqsie.github.io/Program-Takip-Paneli/)**

---

## Neden var

Hazırlık sürecinin en sinsi sorunu, çalışıp çalışmadığını *hissetmekle* ölçmektir.
Bu panel ölçmeyi hisse bırakmaz: kaç soru çözdüğünü, kaç saat çalıştığını, netinin
nereye gittiğini ve müfredatın ne kadarını kapattığını **tek ekranda, hedefe göre**
gösterir. Üstteki yörünge şeridi tek soruyu cevaplar: *yolunda mıyım?*

## Özellikler

### Soru takibi
On iki ders ayrı ayrı izlenir — Türkçe ve Matematik'in altında **Paragraf** ve
**Problemler** ayrı kalem olarak durur, çünkü TYT'nin ağırlığı oradadır. Her kutunun
altında o dersin günlük hedefi ve doluluk çubuğu vardır. Gün / hafta / ay kırılımında
yığılı çubuk grafiği, altında ders bazında hedef–gerçekleşen tablosu.

### Deneme takibi
Dört bölüm için doğru–yanlış girilir, **net anında hesaplanır** (D − Y/4). Net gelişimi
grafiğinde hedef çizgileri çizilidir; bölümleri kendi maksimumuna oranlayan
**Karşılaştır %** görünümü hangi dersin geride kaldığını tek bakışta gösterir.

### Kronometre ve çalışma süresi
Düz kronometre; geçen süre **saniye saniye o günün toplamına yazılır**, kaydetmeyi
unutmak diye bir sorun yoktur. Geri sayım değil gerçek saat farkı kullanıldığı için
sekme arka plana atıldığında veya ekran kapandığında şaşmaz. Son 14 günün çalışma
süresi grafiği ayrıca tutulur.

### Kitap sayacı
Hedef sayfa sayısından geriye sayar; günlük ortalama, seri ve **bu hızla bitiş tarihi**
hesaplanır. Bir bitirme tarihi seçilirse günlük kaç sayfa okunması gerektiğini söyler.

### Konu listesi
MEB Türkiye Yüzyılı Maarif Modeli **9 ve 10. sınıf** öğretim programlarının tema
yapısına göre kurulmuş 232 maddelik liste. Üç durumlu işaretleme: *başlanmadı →
yarım → bitti*; yüzde hesabında yarım konular 0,5 sayılır.

### Yapılacaklar
Sağ kenardan açılan çekmece. Güne bağlıdır, tarih gezinmesiyle ortaktır.

> [!WARNING]
> **Konu listesi hakkında.** Tema ve ünite adları MEB'in resmî öğretim programlarından
> alınmıştır. Altlarındaki satırlar o temanın TYT'deki karşılığıdır — çalışma amaçlı
> eşlemedir, MEB'in resmî alt başlıkları değildir. **ÖSYM 2028 TYT için resmî konu ve
> soru dağılımını henüz yayımlamamıştır**; kesin dağılım açıklandığında liste
> karşılaştırılmalıdır.

## Kurulum

### iPad / iPhone

1. Safari'de **[adresi aç](https://draqsie.github.io/Program-Takip-Paneli/)**
2. Paylaş düğmesi → **Ana Ekrana Ekle**
3. Simge ana ekrana düşer; dokununca tarayıcı çubuğu olmadan **tam ekran** açılır

İlk açılıştan sonra **internetsiz de çalışır** — servis çalışanı sayfayı ve yazı
tiplerini cihazda saklar.

### Masaüstü

Adresi açmak yeterli, kurulum gerekmez. Yerel kopya istersen `index.html` dosyasını
indirip çift tıklaman da kâfi; tek fark, çevrimdışı önbelleğin devrede olmamasıdır.

## Veriler ve gizlilik

**Hiçbir veri sunucuya gitmez.** Her şey tarayıcının `localStorage` alanında, yalnızca
o cihazda durur. Analitik, izleme, hesap ve çerez yoktur.

Bunun bedeli, cihazlar arasında otomatik eşitlemenin olmamasıdır. Aktarım elle yapılır:
bir cihazda **Yedekle** (JSON iner) → diğerinde **Geri yükle** (birleştirir, üzerine
yazmaz).

iOS, uzun süre açılmayan sitelerin yerel verisini silebildiği için panel, uzun süre
yedek alınmadığında üstte bir hatırlatma gösterir. Kalıcı olarak kapatılabilir.

Ölçülmüş büyüklükler — 90 günlük dolu kullanım:

| | |
|---|---|
| Tarayıcıda saklanan veri | ~58 KB |
| Bir yedek dosyası | ~96 KB |
| Uygulamanın toplam ayak izi | ~290 KB |

## Teknik

Bağımlılık yok, derleme adımı yok, çerçeve yok. Tüm stil ve kod `index.html` içinde
gömülü. Grafikler Canvas 2D ile elle çizilir — çevrimdışı çalışsın diye hiçbir grafik
kütüphanesi kullanılmaz.

Dikkat edilmiş noktalar:

- **Dokunmatik** — grafik ipuçları parmakla da çalışır, dokunma hedefleri 44 px,
  girdiler 16 px altına düşmez (iOS aksi hâlde her dokunuşta yakınlaştırır)
- **Tema** — açık ve koyu tema ayrı ayrı tasarlandı; sistem teması değişince grafikler
  yeniden çizilir
- **Zaman** — sayaç saymaz, gerçek saat farkını hesaplar; arka plan kısıtlamalarından
  etkilenmez
- **Güncelleme** — yeni servis çalışanı devralınca sayfa kendini bir kez yeniler,
  uygulama öne geldiğinde güncelleme kontrolü yapılır, HTML `no-store` ile çekilir
- **Sürüm damgası** — sayfanın en altında tarih + içerik özeti; hangi kopyanın yüklü
  olduğu her zaman görülebilir

## Dosya yapısı

```
index.html             Panelin tamamı — stil, kod, veri modeli
sw.js                  Servis çalışanı; çevrimdışı önbellek ve güncelleme
manifest.webmanifest   Uygulama tanımı (ad, simge, tam ekran)
icon-180/192/512.png   Ana ekran ve manifesto simgeleri
.nojekyll              GitHub Pages'in Jekyll işlemesini kapatır
tools/check.js         Yayın öncesi doğrulama
CHANGELOG.md           Sürüm geçmişi
```

## Geliştirme

`index.html` doğrudan düzenlenir, derleme adımı yoktur. Değişiklikten sonra:

```bash
node tools/check.js
```

Bu betik gömülü JavaScript'in sözdizimini denetler, servis çalışanını ve manifestoyu
doğrular, GitHub Pages'i bozan mutlak yolları arar, koddan çağrılan her element
kimliğinin gerçekten var olduğunu kontrol eder ve simgelerin geçerli PNG olduğunu
teyit eder. Aynı denetim her `push` sonrasında GitHub Actions üzerinde de çalışır.

Arayüzü değiştirdiysen `sw.js` içindeki önbellek sürümünü artır (`tyt-panel-vN`) —
cihazların eski kopyada kalmaması bununla sağlanır.

## Lisans

[MIT](LICENSE)
