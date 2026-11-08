import { setGlobalOptions } from "firebase-functions/v2";
import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { onDocumentWritten, onDocumentCreated } from "firebase-functions/v2/firestore";
import { initializeApp } from "firebase-admin/app";
import { getFirestore, FieldValue, Timestamp } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import { getMessaging } from "firebase-admin/messaging";
import { randomBytes, pbkdf2Sync } from "node:crypto";

initializeApp();
setGlobalOptions({ region: "europe-west1", maxInstances: 10 });
const db = getFirestore();

const ID = /^[A-Za-z0-9_-]{1,64}$/;
function requireId(v: unknown, name: string): string {
  if (typeof v !== "string" || !ID.test(v)) throw new HttpsError("invalid-argument", `${name} geçersiz`);
  return v;
}
function requireRole(req: CallableRequest, roles: string[]): { schoolId: string; uid: string } {
  const t = req.auth?.token;
  if (!t || !roles.includes(t.role as string) || typeof t.schoolId !== "string") {
    throw new HttpsError("permission-denied", "Yetkiniz yok");
  }
  return { schoolId: t.schoolId, uid: req.auth!.uid };
}

/**
 * 1) CİHAZ KAYDI
 * QR'daki tek kullanımlık/süreli anahtarı doğrular, cihaz belgesini oluşturur ve cihaza
 * claim'li bir custom token verir. Cihaz bundan sonra kalıcı olarak bu kimlikle oturum açar.
 *
 * Not: createCustomToken için fonksiyonun servis hesabına "Service Account Token Creator"
 * (iam.serviceAccounts.signBlob) rolü verilmelidir.
 */
export const enrollDevice = onCall(async (req) => {
  const schoolId = requireId(req.data?.schoolId, "schoolId");
  const token = requireId(req.data?.token, "token");
  const model = String(req.data?.model ?? "Bilinmeyen tablet").slice(0, 80);

  const tokenRef = db.doc(`schools/${schoolId}/enrollmentTokens/${token}`);
  const deviceRef = db.collection(`schools/${schoolId}/devices`).doc();

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(tokenRef);
    if (!snap.exists) throw new HttpsError("permission-denied", "Kayıt anahtarı geçersiz");
    const t = snap.data()!;
    if ((t.expiresAt as Timestamp).toMillis() < Date.now()) throw new HttpsError("permission-denied", "Anahtarın süresi dolmuş");
    if ((t.used ?? 0) >= (t.maxUses ?? 1)) throw new HttpsError("resource-exhausted", "Anahtar kullanım limiti doldu");

    tx.update(tokenRef, { used: FieldValue.increment(1) });
    tx.set(deviceRef, {
      label: model,
      model,
      groupId: t.groupId ?? null,    // anahtar bir sınıfa bağlıysa cihaz doğrudan o sınıfa düşer
      extraApps: [],
      blockedApps: [],
      enrolledAt: FieldValue.serverTimestamp(),
      status: {},
    });
  });

  const customToken = await getAuth().createCustomToken(`device_${schoolId}_${deviceRef.id}`, {
    role: "device",
    schoolId,
    deviceId: deviceRef.id,
  });
  return { customToken, deviceId: deviceRef.id };
});

/** Yönetici, QR basmadan önce kayıt anahtarı üretir (ör. 9-A için 40 kullanımlık, 24 saat geçerli). */
export const createEnrollmentToken = onCall(async (req) => {
  const { schoolId } = requireRole(req, ["admin"]);
  const groupId = req.data?.groupId == null ? null : requireId(req.data.groupId, "groupId");
  const maxUses = Math.min(Math.max(Number(req.data?.maxUses ?? 1), 1), 500);
  const hours = Math.min(Math.max(Number(req.data?.hours ?? 24), 1), 24 * 14);

  const token = randomBytes(24).toString("base64url");
  await db.doc(`schools/${schoolId}/enrollmentTokens/${token}`).set({
    groupId, maxUses, used: 0,
    expiresAt: Timestamp.fromMillis(Date.now() + hours * 3600_000),
    createdBy: req.auth!.uid,
    createdAt: FieldValue.serverTimestamp(),
  });
  return { token };
});

/**
 * 2) CANLI POLİTİKA
 * Sınıf belgesi değiştiğinde o sınıfın FCM konusuna "senkronize ol" sinyali gönderilir.
 * Ön plandaki cihazlar değişikliği zaten Firestore dinleyicisiyle alır; bu, uyuyan/kopuk cihazlar için.
 */
export const onGroupChanged = onDocumentWritten("schools/{s}/groups/{g}", async (event) => {
  if (!event.data?.after.exists) return;
  await getMessaging().send({
    topic: `grp_${event.params.s}_${event.params.g}`,
    data: { type: "SYNC" },
    android: { priority: "high", ttl: 60 * 60 * 1000 },
  });
});

/** 3) KOMUT KANALI: yeni komut → ilgili cihaza yüksek öncelikli uyandırma. */
export const onCommandCreated = onDocumentCreated("schools/{s}/devices/{d}/commands/{c}", async (event) => {
  const dev = await db.doc(`schools/${event.params.s}/devices/${event.params.d}`).get();
  const fcmToken = dev.get("fcmToken") as string | undefined;
  if (!fcmToken) return;
  await getMessaging().send({
    token: fcmToken,
    data: { type: "COMMAND" },
    android: { priority: "high", ttl: 60 * 60 * 1000 },
  });
});

/** 4) YÖNETİCİ PIN'İ: PBKDF2-SHA256 ile özetlenir; cihaz düz PIN'i asla görmez. */
export const setKioskPin = onCall(async (req) => {
  const { schoolId } = requireRole(req, ["admin"]);
  const pin = String(req.data?.pin ?? "");
  if (!/^\d{6,10}$/.test(pin)) throw new HttpsError("invalid-argument", "PIN 6-10 haneli rakam olmalı");

  const salt = randomBytes(16);
  const iterations = 120_000;
  const hash = pbkdf2Sync(pin, salt, iterations, 32, "sha256");
  await db.doc(`schools/${schoolId}/config/kiosk`).set(
    { pin: { salt: salt.toString("base64"), hash: hash.toString("base64"), iterations }, pinUpdatedAt: FieldValue.serverTimestamp() },
    { merge: true },
  );
  return { ok: true };
});

/**
 * 5) PERSONEL YETKİSİ: yönetici, öğretmen hesabına rol atar.
 * İlk yöneticiyi bir kez Admin SDK betiğiyle elle atayın (README'de).
 */
export const setStaffRole = onCall(async (req) => {
  const { schoolId } = requireRole(req, ["admin"]);
  const email = String(req.data?.email ?? "");
  const role = String(req.data?.role ?? "");
  if (!["teacher", "admin"].includes(role)) throw new HttpsError("invalid-argument", "Rol teacher veya admin olmalı");

  const user = await getAuth().getUserByEmail(email).catch(() => {
    throw new HttpsError("not-found", "Bu e-postayla kayıtlı kullanıcı yok (önce panele bir kez giriş yapmalı)");
  });
  await getAuth().setCustomUserClaims(user.uid, { role, schoolId });
  return { ok: true };
});
