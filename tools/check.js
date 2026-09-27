#!/usr/bin/env node
/**
 * Yayın öncesi doğrulama.
 *
 * Bu panel tek dosyalık ve bağımlılıksız olduğu için derleme adımı yok —
 * yani hatayı yakalayacak bir derleyici de yok. Bu betik o boşluğu doldurur:
 * gömülü JavaScript'i ayıklayıp sözdizimini denetler, servis çalışanını ve
 * manifestoyu doğrular, GitHub Pages'i bozan mutlak yolları arar, koddan
 * çağrılan her element kimliğinin gerçekten var olduğunu kontrol eder ve
 * simgelerin geçerli PNG olduğunu teyit eder.
 *
 * Kullanım:  node tools/check.js
 */
"use strict";
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const KOK = path.resolve(__dirname, "..");
const oku = (f) => fs.readFileSync(path.join(KOK, f), "utf8");
const varMi = (f) => fs.existsSync(path.join(KOK, f));

let hata = 0;
let uyari = 0;
const ok = (m) => console.log("  ✓ " + m);
const yanlis = (m) => { hata++; console.log("  ✗ " + m); };
const dikkat = (m) => { uyari++; console.log("  ! " + m); };
const baslik = (m) => console.log("\n" + m);

/* ---------- 1. dosyalar yerinde mi ---------- */
baslik("Dosyalar");
const GEREKLI = [
  "index.html", "sw.js", "manifest.webmanifest", ".nojekyll",
  "icon-180.png", "icon-192.png", "icon-512.png",
];
GEREKLI.forEach((f) => (varMi(f) ? ok(f) : yanlis(f + " eksik")));
if (hata) { console.log("\nTemel dosyalar eksik, devam edilemiyor.\n"); process.exit(1); }

const html = oku("index.html");

/* ---------- 2. gömülü JavaScript sözdizimi ---------- */
baslik("Gömülü JavaScript");
const betikler = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
if (!betikler.length) yanlis("gömülü <script> bulunamadı");
betikler.forEach((kod, i) => {
  if (!kod.trim()) return;
  try {
    new vm.Script(kod, { filename: `index.html <script #${i + 1}>` });
    ok(`script #${i + 1} — ${kod.split("\n").length} satır`);
  } catch (e) {
    yanlis(`script #${i + 1} sözdizimi: ${e.message}`);
  }
});

/* ---------- 3. servis çalışanı ---------- */
baslik("Servis çalışanı");
const sw = oku("sw.js");
try { new vm.Script(sw, { filename: "sw.js" }); ok("sözdizimi geçerli"); }
catch (e) { yanlis("sw.js sözdizimi: " + e.message); }

const surum = (sw.match(/tyt-panel-v(\d+)/) || [])[0];
surum ? ok("önbellek sürümü: " + surum) : yanlis("önbellek sürümü bulunamadı");
sw.includes('cache:"no-store"')
  ? ok("HTML no-store ile çekiliyor (bayat kopya engellenir)")
  : dikkat('navigate isteğinde cache:"no-store" yok');

/* ---------- 4. manifesto ---------- */
baslik("Manifesto");
let man;
try { man = JSON.parse(oku("manifest.webmanifest")); ok("geçerli JSON"); }
catch (e) { yanlis("manifest bozuk: " + e.message); }
if (man) {
  ["name", "start_url", "display", "icons"].forEach((k) =>
    man[k] ? ok(k + ": " + (Array.isArray(man[k]) ? man[k].length + " simge" : man[k]))
           : yanlis(k + " alanı eksik"));
  if (man.display !== "standalone") dikkat('display "standalone" değil — tam ekran açılmaz');
  (man.icons || []).forEach((ic) => {
    if (!varMi(ic.src)) yanlis("manifesto simgesi yok: " + ic.src);
  });
}

/* ---------- 5. GitHub Pages yol uyumu ---------- */
baslik("Yollar (GitHub Pages alt dizinde yayınlar)");
const mutlak = [...html.matchAll(/(?:href|src)="(\/[^/][^"]*)"/g)].map((m) => m[1]);
mutlak.length ? yanlis("mutlak yol bulundu: " + mutlak.join(", ")) : ok("index.html göreceli");
const swMutlak = [...sw.matchAll(/["'](\/[a-zA-Z][^"']*)["']/g)].map((m) => m[1]);
swMutlak.length ? yanlis("sw.js mutlak yol: " + swMutlak.join(", ")) : ok("sw.js göreceli");
/[^.]\/sw\.js/.test(html) && html.includes('register("/sw.js")')
  ? yanlis("servis çalışanı mutlak yoldan kaydediliyor")
  : ok("servis çalışanı göreceli yoldan kaydediliyor");

/* ---------- 6. element kimlikleri ---------- */
baslik("Element kimlikleri");
const tanimli = new Set([...html.matchAll(/\bid="([A-Za-z0-9_-]+)"/g)].map((m) => m[1]));
const kullanilan = new Set([...html.matchAll(/\$\("([A-Za-z0-9_-]+)"\)/g)].map((m) => m[1]));
const URETILEN = ["qf-", "qi-", "qt-", "qb-", "fd-", "fy-", "fn-", "kc-", "kl-", "kr-"];
const eksik = [...kullanilan].filter(
  (i) => !tanimli.has(i) && !URETILEN.some((p) => i.startsWith(p))
);
eksik.length
  ? yanlis("koddan çağrılan ama HTML'de olmayan kimlik: " + eksik.join(", "))
  : ok(`${kullanilan.size} kimlik çağrısının tamamı karşılanıyor`);

/* ---------- 7. simgeler ---------- */
baslik("Simgeler");
[["icon-180.png", 180], ["icon-192.png", 192], ["icon-512.png", 512]].forEach(([f, boy]) => {
  const b = fs.readFileSync(path.join(KOK, f));
  if (b.slice(0, 8).toString("hex") !== "89504e470d0a1a0a") return yanlis(f + " geçerli PNG değil");
  const g = b.readUInt32BE(16), y = b.readUInt32BE(20);
  g === boy && y === boy ? ok(`${f} — ${g}×${y}`) : yanlis(`${f} ${g}×${y}, beklenen ${boy}×${boy}`);
});

/* ---------- 8. uygulama kabuğu ---------- */
baslik("Uygulama kabuğu");
[
  ['<meta name="apple-mobile-web-app-capable" content="yes">', "iOS tam ekran"],
  ["viewport-fit=cover", "çentik/güvenli alan"],
  ['rel="apple-touch-icon"', "ana ekran simgesi"],
  ['rel="manifest"', "manifesto bağlantısı"],
  ["controllerchange", "yeni sürüm gelince otomatik yenileme"],
  ["window.BUILD=", "sürüm damgası"],
].forEach(([iz, ad]) => (html.includes(iz) ? ok(ad) : dikkat(ad + " bulunamadı")));

/* ---------- özet ---------- */
const kb = (fs.statSync(path.join(KOK, "index.html")).size / 1024).toFixed(0);
console.log(`\n${"-".repeat(52)}`);
console.log(`index.html ${kb} KB · bağımlılık yok`);
console.log(hata ? `BAŞARISIZ — ${hata} hata, ${uyari} uyarı\n`
                 : `Tamam — ${uyari} uyarı\n`);
process.exit(hata ? 1 : 0);
