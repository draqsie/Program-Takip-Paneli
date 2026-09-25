# TYT Takip Paneli

11. sınıf TYT hazırlık takip paneli. Tek sayfalık, sunucusuz bir web uygulaması:
günlük soru girişi ve grafikleri, deneme net takibi, 8.000 sayfalık kitap sayacı ve
Maarif Modeli 9–10. sınıf konu listesi.

## Yayına alma (GitHub Pages)

1. Depo sayfasında **Settings → Pages**
2. **Source: Deploy from a branch**
3. **Branch: `main` / `(root)`** → Save
4. Bir iki dakika sonra adres hazır olur:

```
https://draqsie.github.io/Program-Takip-Paneli/
```

## iPad'e kurma

Safari'de yukarıdaki adresi aç → paylaş düğmesi → **Ana Ekrana Ekle**.
Simge ana ekrana düşer, dokununca tam ekran açılır. Servis çalışanı sayesinde
ilk açılıştan sonra **internetsiz de çalışır**.

## Veriler

Veriler tarayıcının kendi deposunda (localStorage) tutulur; sunucuya gitmez.
Cihazlar arasında otomatik eşitlenmez — aktarmak için bir cihazda **Yedekle**
(JSON iner), diğerinde **Geri yükle**.

## Dosyalar

| Dosya | İş |
|---|---|
| `index.html` | Panelin tamamı — stil, kod ve veri tek dosyada |
| `sw.js` | Servis çalışanı; çevrimdışı kullanım |
| `manifest.webmanifest` | Uygulama tanımı (ad, simge, tam ekran) |
| `icon-*.png` | Ana ekran simgeleri |
