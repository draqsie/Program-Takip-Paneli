/**
 * Okul İzin — iPad Kestirmeler (Shortcuts) sunucusu.  Google Apps Script web uygulaması.
 *
 * iPad'deki "Okul İzin" kısayolu her çalıştığında buraya tek bir adres çağırır ve düz metin bir
 * cevap alır. Bütün karar mantığı burada olduğu için kuralları değiştirmek için iPad'lere dokunmak
 * gerekmez: bu dosyayı güncelleyip yeniden yayımlamak yeterlidir ("kendi kendini güncelleme").
 *
 *   ?islem=kontrol&k=ANAHTAR&uygulama=Instagram   → AC | KAPAT | BEKLIYOR
 *   ?islem=istek&k=ANAHTAR&uygulama=Instagram&not=…→ GONDERILDI | ZATEN_BEKLIYOR
 *   ?islem=nabiz&k=ANAHTAR&surum=1                 → TAMAM
 *   Hata durumunda                                 → HATA:<neden>
 *
 * ANAHTAR, admin panelinde iPad öğrencisi eklenirken üretilen 20 karakterlik gizli koddur ve
 * öğrencinin Firestore kaydının kimliğidir (schools/{okul}/students/{ANAHTAR}).
 *
 * Firestore'a, betiği yayımlayan Google hesabının (Firebase projesinin sahibi) yetkisiyle erişilir;
 * bu yüzden Firestore kurallarında dışarıya hiçbir şey açılmaz.
 *
 * Proje ayarları → Komut dosyası özellikleri:
 *   PROJE  = Firebase proje kimliği (ör. okul-izin-12345)
 *   OKUL   = okul kimliği (panel ve Android ile aynı, ör. ornek-anadolu-lisesi)
 */

// ============================================================================ giriş noktası

function doGet(e) {
  var p = (e && e.parameter) || {};
  var cevap;
  try {
    cevap = isle_(p, Date.now());
  } catch (err) {
    console.error(err);
    cevap = 'HATA:sunucu';
  }
  return ContentService.createTextOutput(cevap).setMimeType(ContentService.MimeType.TEXT);
}

/** Yönlendirme. Saf mantık + Firestore yardımcıları; Node testlerinde yardımcılar taklit edilir. */
function isle_(p, simdi) {
  var anahtar = String(p.k || '').replace(/[\s-]/g, '').toUpperCase(); // panel 5'li gruplar halinde gösterir
  if (!ANAHTAR_DESENI.test(anahtar)) return 'HATA:anahtar';

  var ogrenci = belgeOku_(ogrenciYolu_(anahtar));
  if (!ogrenci || ogrenci.platform !== 'ios-kisayol') return 'HATA:anahtar';

  var islem = String(p.islem || 'kontrol');
  if (islem === 'nabiz') {
    durumYaz_(anahtar, simdi, p.surum);
    return 'TAMAM';
  }

  var uygulama = String(p.uygulama || '').trim().slice(0, 60);
  if (!uygulama) return 'HATA:uygulama';
  var appKey = uygulamaAnahtari(uygulama);

  if (islem === 'kontrol') {
    // Her uygulama açılışı aynı zamanda bir nabızdır; Firestore yazma kotası için 10 dk'da bir yazılır.
    if (sonGorulmeEskiMi(ogrenci, simdi, 10 * 60000)) durumYaz_(anahtar, simdi, p.surum);
    if (karar(ogrenci, ayarlar_(), appKey, simdi) === 'AC') return 'AC';
    return bekleyenVarMi_(anahtar, appKey) ? 'BEKLIYOR' : 'KAPAT';
  }

  if (islem === 'istek') {
    if (bekleyenVarMi_(anahtar, appKey)) return 'ZATEN_BEKLIYOR';
    istekOlustur_(ogrenci, anahtar, appKey, uygulama, String(p.not || '').slice(0, 200), simdi);
    return 'GONDERILDI';
  }

  return 'HATA:islem';
}

// ============================================================================ saf mantık (testli)

var ANAHTAR_DESENI = /^[A-Z2-9]{20}$/;
var ISTANBUL_DK = 180; // Türkiye 2016'dan beri sabit UTC+3 (yaz saati yok)

/**
 * Kısayolun gönderdiği uygulama adından kalıcı anahtar: "kisayol:instagram".
 * I / İ / ı / i aynı sayılır: Türkçe küçültme "Instagram"ı "ınstagram" yapar, İngilizce küçültme
 * "İ"yi bozar; ikisinden de bağımsız olmak için önce bu harfler "i"ye indirgenir.
 */
function uygulamaAnahtari(ad) {
  return 'kisayol:' + String(ad).trim().replace(/[Iİı]/g, 'i').toLowerCase().replace(/\s+/g, ' ');
}

function saatDk(s, varsayilan) {
  var m = /^(\d{1,2}):(\d{2})$/.exec(String(s || ''));
  return m ? Number(m[1]) * 60 + Number(m[2]) : varsayilan;
}

/** Panel biçimi: days 1=Pazartesi … 7=Pazar, start/end "08:30". */
function okulSaatiMi(takvim, simdi) {
  var t = new Date(simdi + ISTANBUL_DK * 60000);
  var gun = ((t.getUTCDay() + 6) % 7) + 1;
  var dk = t.getUTCHours() * 60 + t.getUTCMinutes();
  var gunler = (takvim && takvim.days) || [1, 2, 3, 4, 5];
  return gunler.indexOf(gun) >= 0 &&
    dk >= saatDk(takvim && takvim.start, 510) &&
    dk < saatDk(takvim && takvim.end, 930);
}

/** 'AC' ya da 'KAPAT'. */
function karar(ogrenci, ayarlar, appKey, simdi) {
  if (ayarlar && ayarlar.paused) return 'AC';
  if (!okulSaatiMi(ayarlar && ayarlar.schedule, simdi)) return 'AC';
  if ((ogrenci.allowed || []).indexOf(appKey) >= 0) return 'AC';
  var bitis = (ogrenci.grants || {})[appKey];
  if (typeof bitis === 'number' && bitis > simdi) return 'AC';
  return 'KAPAT';
}

function sonGorulmeEskiMi(ogrenci, simdi, esikMs) {
  var son = ogrenci.status && ogrenci.status.lastSeen;
  return typeof son !== 'number' || simdi - son > esikMs;
}

/** Firestore REST'in tipli JSON'unu düz nesneye çevirir (zaman damgaları → epoch ms). */
function fsCoz(v) {
  if (v == null) return null;
  if ('stringValue' in v) return v.stringValue;
  if ('integerValue' in v) return Number(v.integerValue);
  if ('doubleValue' in v) return v.doubleValue;
  if ('booleanValue' in v) return v.booleanValue;
  if ('timestampValue' in v) return Date.parse(v.timestampValue);
  if ('nullValue' in v) return null;
  if ('arrayValue' in v) return (v.arrayValue.values || []).map(fsCoz);
  if ('mapValue' in v) return alanlariCoz(v.mapValue.fields || {});
  return null;
}

function alanlariCoz(alanlar) {
  var o = {};
  Object.keys(alanlar || {}).forEach(function (k) { o[k] = fsCoz(alanlar[k]); });
  return o;
}

function fsKodla(v) {
  if (v === null || v === undefined) return { nullValue: null };
  if (Object.prototype.toString.call(v) === '[object Date]') return { timestampValue: v.toISOString() };
  if (typeof v === 'boolean') return { booleanValue: v };
  if (typeof v === 'number') return Number.isInteger(v) ? { integerValue: String(v) } : { doubleValue: v };
  if (typeof v === 'string') return { stringValue: v };
  if (Array.isArray(v)) return { arrayValue: { values: v.map(fsKodla) } };
  var alanlar = {};
  Object.keys(v).forEach(function (k) { alanlar[k] = fsKodla(v[k]); });
  return { mapValue: { fields: alanlar } };
}

// ============================================================================ Firestore yardımcıları

function ayar_(ad) {
  var v = PropertiesService.getScriptProperties().getProperty(ad);
  if (!v) throw new Error(ad + ' komut dosyası özelliği tanımlı değil');
  return v;
}

function kok_() {
  return 'https://firestore.googleapis.com/v1/projects/' + ayar_('PROJE') + '/databases/(default)/documents';
}

function okulYolu_() { return 'schools/' + ayar_('OKUL'); }
function ogrenciYolu_(anahtar) { return okulYolu_() + '/students/' + anahtar; }

function fs_(yontem, url, govde) {
  var r = UrlFetchApp.fetch(url, {
    method: yontem,
    contentType: 'application/json',
    payload: govde ? JSON.stringify(govde) : undefined,
    headers: {
      Authorization: 'Bearer ' + ScriptApp.getOAuthToken(),
      // Kota ve faturalandırma Firebase projesine yazılsın (Apps Script'in gizli projesine değil).
      'X-Goog-User-Project': ayar_('PROJE'),
    },
    muteHttpExceptions: true,
  });
  var kod = r.getResponseCode();
  if (kod === 404) return null;
  if (kod >= 300) throw new Error('Firestore ' + kod + ': ' + r.getContentText().slice(0, 300));
  var metin = r.getContentText();
  return metin ? JSON.parse(metin) : {};
}

function belgeOku_(yol) {
  var b = fs_('get', kok_() + '/' + yol);
  return b ? alanlariCoz(b.fields) : null;
}

/** Okul ayarları 60 sn önbellekte tutulur (her uygulama açılışında Firestore'a gitmemek için). */
function ayarlar_() {
  var onbellek = CacheService.getScriptCache();
  var json = onbellek.get('ayarlar');
  if (json) return JSON.parse(json);
  var a = belgeOku_(okulYolu_() + '/config/settings') || {};
  onbellek.put('ayarlar', JSON.stringify(a), 60);
  return a;
}

/** Durum yalnızca sunucu saatiyle yazılır: iPad'in saatini değiştirmek bir şey kazandırmaz. */
function durumYaz_(anahtar, simdi, surum) {
  var url = kok_() + '/' + ogrenciYolu_(anahtar) +
    '?updateMask.fieldPaths=status&currentDocument.exists=true';
  fs_('patch', url, {
    fields: {
      status: fsKodla({
        lastSeen: new Date(simdi),
        protection: 'ACTIVE',
        appVersion: 'kısayol ' + String(surum || '?').slice(0, 10),
        os: 'iPadOS (Kestirmeler)',
      }),
    },
  });
}

function bekleyenVarMi_(anahtar, appKey) {
  var esit = function (alan, deger) {
    return { fieldFilter: { field: { fieldPath: alan }, op: 'EQUAL', value: { stringValue: deger } } };
  };
  var sonuc = fs_('post', kok_() + '/' + okulYolu_() + ':runQuery', {
    structuredQuery: {
      from: [{ collectionId: 'requests' }],
      where: { compositeFilter: { op: 'AND', filters: [
        esit('studentId', anahtar), esit('appKey', appKey), esit('status', 'PENDING'),
      ] } },
      limit: 1,
    },
  }) || [];
  return sonuc.some(function (s) { return !!s.document; });
}

function istekOlustur_(ogrenci, anahtar, appKey, uygulama, not, simdi) {
  var alanlar = {
    studentId: anahtar,
    studentName: ogrenci.name || '',
    className: ogrenci.className || '',
    platform: 'ios-kisayol',
    appKey: appKey,
    appLabel: uygulama,
    labelVerified: true, // ad, öğretmenin kurduğu otomasyondan gelir; öğrenci değiştiremez
    note: not,
    status: 'PENDING',
    createdAt: new Date(simdi),
  };
  var f = {};
  Object.keys(alanlar).forEach(function (k) { f[k] = fsKodla(alanlar[k]); });
  fs_('post', kok_() + '/' + okulYolu_() + '/requests', { fields: f });
}

// Node testleri için (Apps Script'te `module` yoktur, bu satır orada etkisizdir).
if (typeof module !== 'undefined') {
  module.exports = { isle_: isle_, karar: karar, okulSaatiMi: okulSaatiMi, uygulamaAnahtari: uygulamaAnahtari,
    fsCoz: fsCoz, fsKodla: fsKodla, alanlariCoz: alanlariCoz, sonGorulmeEskiMi: sonGorulmeEskiMi };
}
