#!/usr/bin/env bash
# Kurulum QR kodunu üretir.
#
# Kullanım:  ./qr-olustur.sh egitim-kiosk-release.apk qr-payload.json cikti.png
# Gerekenler: apksigner (Android SDK build-tools), jq, qrencode
#
# Akış: fabrika ayarlı tablet → karşılama ekranında boş bir yere 6 kez dokun → QR okuyucu açılır →
#       bu kodu okut → cihaz Wi-Fi'ye bağlanır, APK'yı indirir, imzayı doğrular, uygulamayı
#       "device owner" yapar ve doğrudan kiosk ana ekranına açılır.
set -euo pipefail

APK="${1:?APK yolu}"
PAYLOAD="${2:?qr-payload.json yolu}"
OUT="${3:-kurulum-qr.png}"

# İmzalayan sertifikanın SHA-256 özeti → base64url (dolgu '=' olmadan). Android bunu indirdiği
# APK ile karşılaştırır; APK değiştirilmişse kurulum reddedilir.
HEX=$(apksigner verify --print-certs "$APK" | grep -m1 "SHA-256 digest" | awk '{print $NF}')
CHECKSUM=$(echo -n "$HEX" | xxd -r -p | base64 | tr '+/' '-_' | tr -d '=')
echo "İmza özeti: $CHECKSUM"

jq -c --arg c "$CHECKSUM" \
  '.["android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"] = $c' "$PAYLOAD" \
  | qrencode -o "$OUT" -s 6 -m 2

echo "QR kod yazıldı: $OUT"
echo "UYARI: QR, Wi-Fi şifresini ve kayıt anahtarını içerir. Kurulum bitince imha edin; anahtar süreli olmalı."
