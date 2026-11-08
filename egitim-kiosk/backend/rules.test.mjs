// Firestore güvenlik kuralı testleri. Çalıştırma: npm install && npm test  (Java gerekir)
// Yönetici e-postası firestore.rules içindekiyle aynı olmalı.
import { initializeTestEnvironment, assertSucceeds, assertFails } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc, updateDoc, addDoc, collection, getDocs, query, where, Timestamp, serverTimestamp } from "firebase/firestore";
import { readFileSync } from "node:fs";

const env = await initializeTestEnvironment({
  projectId: "demo-okul",
  firestore: { rules: readFileSync("firestore.rules", "utf8"), host: "127.0.0.1", port: 8080 },
});
const S = "schools/okul";
await env.withSecurityRulesDisabled(async (c) => {
  const db = c.firestore();
  await setDoc(doc(db, `${S}/config/settings`), { paused: false });
  await setDoc(doc(db, `${S}/config/gizli`), { x: 1 });
  await setDoc(doc(db, `${S}/enrollmentCodes/GECERLIKOD`), { className: "9-A", expiresAt: Timestamp.fromMillis(Date.now() + 86400000) });
  await setDoc(doc(db, `${S}/enrollmentCodes/ESKIKOD123`), { className: "9-A", expiresAt: Timestamp.fromMillis(Date.now() - 1000) });
  await setDoc(doc(db, `${S}/students/baskasi`), { name: "Başka", allowed: [] });
  await setDoc(doc(db, `${S}/requests/r-baskasi`), { studentId: "baskasi", status: "PENDING" });
});

const admin = env.authenticatedContext("adm", { email: "yonetici@okulunuz.k12.tr", email_verified: true }).firestore();
const stranger = env.authenticatedContext("x", { email: "biri@gmail.com", email_verified: true }).firestore();
const unverified = env.authenticatedContext("y", { email: "yonetici@okulunuz.k12.tr", email_verified: false }).firestore();
const ali = env.authenticatedContext("ali", { firebase: { sign_in_provider: "anonymous" } }).firestore();
const anon = env.unauthenticatedContext().firestore();

const student = (over = {}) => ({
  name: "Ali Veli", className: "9-A", platform: "android", enrollCode: "GECERLIKOD", consentVersion: "v1",
  allowed: [], grants: {}, labels: {}, inventory: [], status: {}, enrolledAt: serverTimestamp(), ...over,
});
const req = (over = {}) => ({
  studentId: "ali", studentName: "Ali Veli", className: "9-A", platform: "android",
  appKey: "com.whatsapp", appLabel: "WhatsApp", labelVerified: true, note: "", status: "PENDING",
  createdAt: serverTimestamp(), ...over,
});

let pass = 0, fail = 0;
async function t(name, p) { try { await p; pass++; console.log("  ✓", name); } catch (e) { fail++; console.log("  ✗", name, "-", e.message?.split("\n")[0]); } }

console.log("Kayıt");
await t("geçersiz kodla kayıt reddedilir", assertFails(setDoc(doc(ali, `${S}/students/ali`), student({ enrollCode: "YANLISKOD1" }))));
await t("süresi dolmuş kodla kayıt reddedilir", assertFails(setDoc(doc(ali, `${S}/students/ali`), student({ enrollCode: "ESKIKOD123" }))));
await t("kendine izin vererek kayıt reddedilir", assertFails(setDoc(doc(ali, `${S}/students/ali`), student({ allowed: ["com.oyun"] }))));
await t("yanlış sınıfla kayıt reddedilir", assertFails(setDoc(doc(ali, `${S}/students/ali`), student({ className: "12-B" }))));
await t("başkasının uid'ine kayıt reddedilir", assertFails(setDoc(doc(ali, `${S}/students/veli`), student())));
await t("fazladan alanla kayıt reddedilir", assertFails(setDoc(doc(ali, `${S}/students/ali`), student({ admin: true }))));
await t("oturumsuz kayıt reddedilir", assertFails(setDoc(doc(anon, `${S}/students/ali`), student())));
await t("geçerli kodla kayıt kabul edilir", assertSucceeds(setDoc(doc(ali, `${S}/students/ali`), student())));

console.log("Öğrenci yetkileri");
await t("kendi kaydını okur", assertSucceeds(getDoc(doc(ali, `${S}/students/ali`))));
await t("başkasının kaydını okuyamaz", assertFails(getDoc(doc(ali, `${S}/students/baskasi`))));
await t("kendi durumunu yazar", assertSucceeds(updateDoc(doc(ali, `${S}/students/ali`), { status: { protection: "ACTIVE" } })));
await t("kendi izin listesini değiştiremez", assertFails(updateDoc(doc(ali, `${S}/students/ali`), { allowed: ["com.oyun"] })));
await t("kendine geçici izin yazamaz", assertFails(updateDoc(doc(ali, `${S}/students/ali`), { grants: { "com.oyun": Timestamp.now() } })));
await t("okul ayarlarını okur", assertSucceeds(getDoc(doc(ali, `${S}/config/settings`))));
await t("okul ayarlarını değiştiremez", assertFails(setDoc(doc(ali, `${S}/config/settings`), { paused: true })));
await t("diğer config belgelerini okuyamaz", assertFails(getDoc(doc(ali, `${S}/config/gizli`))));
await t("bildiği kodu okuyabilir", assertSucceeds(getDoc(doc(ali, `${S}/enrollmentCodes/GECERLIKOD`))));
await t("kodları listeleyemez", assertFails(getDocs(collection(ali, `${S}/enrollmentCodes`))));

console.log("İstekler");
await t("kendi adına bekleyen istek açar", assertSucceeds(addDoc(collection(ali, `${S}/requests`), req())));
await t("başkası adına istek açamaz", assertFails(addDoc(collection(ali, `${S}/requests`), req({ studentId: "baskasi" }))));
await t("onaylanmış istek oluşturamaz", assertFails(addDoc(collection(ali, `${S}/requests`), req({ status: "APPROVED" }))));
await t("fazladan alanla istek açamaz", assertFails(addDoc(collection(ali, `${S}/requests`), req({ expiresAt: Timestamp.now() }))));
await t("kayıtsız anonim kullanıcı istek açamaz", assertFails(addDoc(collection(env.authenticatedContext("kayitsiz").firestore(), `${S}/requests`), req({ studentId: "kayitsiz" }))));
await t("kendi isteklerini sorgular", assertSucceeds(getDocs(query(collection(ali, `${S}/requests`), where("studentId", "==", "ali")))));
await t("tüm istekleri sorgulayamaz", assertFails(getDocs(collection(ali, `${S}/requests`))));
await t("isteğini kendisi onaylayamaz", assertFails(updateDoc(doc(ali, `${S}/requests/r-baskasi`), { status: "APPROVED" })));

console.log("Yönetici");
await t("yönetici tüm öğrencileri okur", assertSucceeds(getDocs(collection(admin, `${S}/students`))));
await t("yönetici izin verir", assertSucceeds(updateDoc(doc(admin, `${S}/students/ali`), { allowed: ["com.whatsapp"] })));
await t("yönetici isteği onaylar", assertSucceeds(updateDoc(doc(admin, `${S}/requests/r-baskasi`), { status: "APPROVED" })));
await t("yönetici kayıt kodu üretir", assertSucceeds(setDoc(doc(admin, `${S}/enrollmentCodes/YENIKOD123`), { className: "9-B", expiresAt: Timestamp.now() })));
await t("başka Google hesabı hiçbir şey okuyamaz", assertFails(getDocs(collection(stranger, `${S}/students`))));
await t("başka Google hesabı istekleri okuyamaz", assertFails(getDocs(collection(stranger, `${S}/requests`))));
await t("doğrulanmamış e-posta yönetici sayılmaz", assertFails(getDocs(collection(unverified, `${S}/students`))));
await t("oturumsuz kimse okuyamaz", assertFails(getDoc(doc(anon, `${S}/config/settings`))));

await env.cleanup();
console.log(`\n${pass} geçti, ${fail} kaldı`);
process.exit(fail ? 1 : 0);
