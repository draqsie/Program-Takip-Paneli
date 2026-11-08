// Kısayol sunucusunun karar ve yönlendirme mantığı. Çalıştırma: node --test kod.test.mjs
// Kod.gs, Apps Script servisleri (UrlFetchApp vb.) taklit edilerek bir VM içinde yüklenir.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

// VM içinde oluşan nesnelerin prototipi farklıdır; karşılaştırmadan önce düz JSON'a çevir.
const duz = (x) => JSON.parse(JSON.stringify(x));

function yukle() {
  const ctx = { module: { exports: {} }, console };
  vm.createContext(ctx);
  vm.runInContext(readFileSync(new URL("./Kod.gs", import.meta.url), "utf8"), ctx);
  return ctx;
}
const ist = (y, a, g, s, d) => Date.UTC(y, a - 1, g, s - 3, d); // İstanbul saati → epoch ms
const PZT_10 = ist(2026, 11, 9, 10, 0); // 9 Kasım 2026 Pazartesi
const ANAHTAR = "ABCDEFGHJK23456789MN";

test("okul saati", () => {
  const { module: { exports: k } } = yukle();
  const t = { days: [1, 2, 3, 4, 5], start: "08:30", end: "15:30" };
  assert.equal(k.okulSaatiMi(t, ist(2026, 11, 9, 8, 30)), true);
  assert.equal(k.okulSaatiMi(t, ist(2026, 11, 9, 15, 29)), true);
  assert.equal(k.okulSaatiMi(t, ist(2026, 11, 9, 15, 30)), false);
  assert.equal(k.okulSaatiMi(t, ist(2026, 11, 9, 8, 29)), false);
  assert.equal(k.okulSaatiMi(t, ist(2026, 11, 8, 10, 0)), false); // Pazar
  assert.equal(k.okulSaatiMi(t, ist(2026, 11, 14, 10, 0)), false); // Cumartesi
  assert.equal(k.okulSaatiMi(t, ist(2026, 11, 13, 10, 0)), true); // Cuma
});

test("karar", () => {
  const { module: { exports: k } } = yukle();
  const ayar = { schedule: { days: [1, 2, 3, 4, 5], start: "08:30", end: "15:30" } };
  const ogr = { allowed: ["kisayol:sözlük"], grants: { "kisayol:youtube": PZT_10 + 60000, "kisayol:oyun": PZT_10 - 1 } };
  assert.equal(k.karar(ogr, ayar, "kisayol:instagram", PZT_10), "KAPAT");
  assert.equal(k.karar(ogr, ayar, "kisayol:sözlük", PZT_10), "AC");
  assert.equal(k.karar(ogr, ayar, "kisayol:youtube", PZT_10), "AC");
  assert.equal(k.karar(ogr, ayar, "kisayol:oyun", PZT_10), "KAPAT");
  assert.equal(k.karar(ogr, { ...ayar, paused: true }, "kisayol:instagram", PZT_10), "AC");
  assert.equal(k.karar(ogr, ayar, "kisayol:instagram", ist(2026, 11, 9, 18, 0)), "AC");
  assert.equal(k.uygulamaAnahtari("  İnstagram  "), "kisayol:instagram");
  assert.equal(k.uygulamaAnahtari("Instagram"), "kisayol:instagram");
  assert.equal(k.uygulamaAnahtari("ınstagram"), "kisayol:instagram");
  assert.equal(k.uygulamaAnahtari("Sözlük"), "kisayol:sözlük");
  assert.equal(k.uygulamaAnahtari("Brawl  Stars"), "kisayol:brawl stars");
});

test("Firestore tipli JSON gidiş-dönüş", () => {
  const { module: { exports: k } } = yukle();
  const d = new Date(PZT_10);
  const kod = k.fsKodla({ a: "x", n: 3, b: true, l: ["p", "q"], m: { t: d } });
  assert.deepEqual(duz(k.fsCoz(kod)), { a: "x", n: 3, b: true, l: ["p", "q"], m: { t: PZT_10 } });
});

/** Yönlendirme: Firestore ve Apps Script servisleri taklit edilir. */
function sahte(ogrenci, { bekleyen = false, ayarlar = { schedule: { days: [1, 2, 3, 4, 5], start: "08:30", end: "15:30" } } } = {}) {
  const ctx = yukle();
  const kayit = { durum: 0, istekler: [] };
  ctx.belgeOku_ = (yol) => (yol.endsWith("/" + ANAHTAR) ? ogrenci : null);
  ctx.ogrenciYolu_ = (a) => "schools/okul/students/" + a;
  ctx.ayarlar_ = () => ayarlar;
  ctx.durumYaz_ = () => { kayit.durum++; };
  ctx.bekleyenVarMi_ = () => bekleyen;
  ctx.istekOlustur_ = (o, a, appKey, ad, not) => kayit.istekler.push({ a, appKey, ad, not });
  return { isle: (p) => ctx.isle_(p, PZT_10), kayit };
}

test("yönlendirme", () => {
  const ogr = { platform: "ios-kisayol", name: "Ayşe", allowed: [], grants: {}, status: { lastSeen: PZT_10 - 60000 } };
  let s = sahte(ogr);
  assert.equal(s.isle({ islem: "kontrol", k: ANAHTAR, uygulama: "Instagram" }), "KAPAT");
  assert.equal(s.kayit.durum, 0, "1 dk önce görüldüyse tekrar yazılmaz");
  assert.equal(s.isle({ islem: "kontrol", k: ANAHTAR.toLowerCase(), uygulama: "Instagram" }), "KAPAT", "küçük harf kabul");
  assert.equal(s.isle({ islem: "kontrol", k: "ABCDE FGHJK 23456 789MN", uygulama: "Instagram" }), "KAPAT", "boşluklu anahtar kabul");
  assert.equal(s.isle({ islem: "istek", k: ANAHTAR, uygulama: "Instagram", not: "ödev" }), "GONDERILDI");
  assert.deepEqual(duz(s.kayit.istekler[0]), { a: ANAHTAR, appKey: "kisayol:instagram", ad: "Instagram", not: "ödev" });
  assert.equal(s.isle({ islem: "nabiz", k: ANAHTAR, surum: "1" }), "TAMAM");
  assert.equal(s.kayit.durum, 1);

  s = sahte({ ...ogr, status: {} });
  s.isle({ islem: "kontrol", k: ANAHTAR, uygulama: "Instagram" });
  assert.equal(s.kayit.durum, 1, "hiç görülmediyse kontrol nabız yazar");

  s = sahte(ogr, { bekleyen: true });
  assert.equal(s.isle({ islem: "kontrol", k: ANAHTAR, uygulama: "Instagram" }), "BEKLIYOR");
  assert.equal(s.isle({ islem: "istek", k: ANAHTAR, uygulama: "Instagram" }), "ZATEN_BEKLIYOR");

  s = sahte({ ...ogr, allowed: ["kisayol:instagram"] });
  assert.equal(s.isle({ islem: "kontrol", k: ANAHTAR, uygulama: "Instagram" }), "AC");

  assert.equal(s.isle({ islem: "kontrol", k: "KISA", uygulama: "X" }), "HATA:anahtar");
  assert.equal(s.isle({ islem: "kontrol", k: "ZZZZZZZZZZZZZZZZZZZZ", uygulama: "X" }), "HATA:anahtar");
  assert.equal(s.isle({ islem: "kontrol", k: ANAHTAR }), "HATA:uygulama");
  assert.equal(s.isle({ islem: "sil", k: ANAHTAR, uygulama: "X" }), "HATA:islem");
  assert.equal(sahte({ ...ogr, platform: "android" }).isle({ k: ANAHTAR, uygulama: "X" }), "HATA:anahtar");
});
