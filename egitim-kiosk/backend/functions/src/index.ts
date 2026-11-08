import { setGlobalOptions } from "firebase-functions/v2";
import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { onDocumentCreated, onDocumentUpdated } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { initializeApp } from "firebase-admin/app";
import { getFirestore, FieldValue, Timestamp } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import { getMessaging } from "firebase-admin/messaging";
import { randomInt } from "node:crypto";

initializeApp();
setGlobalOptions({ region: "europe-west1", maxInstances: 10 });
const db = getFirestore();

const ID = /^[A-Za-z0-9_-]{1,64}$/;
const MAX_PENDING_PER_STUDENT = 3;

function requireId(v: unknown, name: string): string {
  if (typeof v !== "string" || !ID.test(v)) throw new HttpsError("invalid-argument", `${name} geçersiz`);
  return v;
}
function requireAdmin(req: CallableRequest): string {
  const t = req.auth?.token;
  if (t?.role !== "admin" || typeof t.schoolId !== "string") throw new HttpsError("permission-denied", "Yetkiniz yok");
  return t.schoolId;
}

/**
 * Yönetici bir sınıf için kayıt kodu üretir (ör. "9-A", 40 öğrenci, 7 gün geçerli).
 * Kod, karıştırılması zor harflerden oluşur ve öğrenciye tahtaya yazılarak / mesajla verilir.
 */
export const createEnrollmentCode = onCall(async (req) => {
  const schoolId = requireAdmin(req);
  const className = String(req.data?.className ?? "").trim().slice(0, 30);
  if (!className) throw new HttpsError("invalid-argument", "Sınıf adı gerekli");
  const maxUses = Math.min(Math.max(Number(req.data?.maxUses ?? 40), 1), 500);
  const days = Math.min(Math.max(Number(req.data?.days ?? 7), 1), 30);

  const alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  const code = Array.from({ length: 10 }, () => alphabet[randomInt(alphabet.length)]).join("");
  await db.doc(`schools/${schoolId}/enrollmentCodes/${code}`).set({
    className, maxUses, used: 0,
    expiresAt: Timestamp.fromMillis(Date.now() + days * 86_400_000),
    createdAt: FieldValue.serverTimestamp(),
  });
  return { code };
});

/**
 * Öğrenci uygulamayı mağazadan indirip kodu girdiğinde çağrılır. Öğrenci kaydını açar ve cihaza
 * claim'li bir custom token verir; cihaz bundan sonra kalıcı olarak bu kimlikle oturum açar.
 * Not: Functions servis hesabına "Service Account Token Creator" rolü gerekir.
 */
export const enrollStudent = onCall(async (req) => {
  const schoolId = requireId(req.data?.schoolId, "schoolId");
  const code = String(req.data?.code ?? "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  const name = String(req.data?.name ?? "").trim().slice(0, 60);
  const platform = req.data?.platform === "ios" ? "ios" : "android";
  const consentVersion = String(req.data?.consentVersion ?? "");
  if (code.length !== 10) throw new HttpsError("invalid-argument", "Kod 10 karakter olmalı");
  if (name.length < 3) throw new HttpsError("invalid-argument", "Ad soyad gerekli");
  if (!consentVersion) throw new HttpsError("failed-precondition", "Öğrenci ve veli onayı gerekli");

  const codeRef = db.doc(`schools/${schoolId}/enrollmentCodes/${code}`);
  const studentRef = db.collection(`schools/${schoolId}/students`).doc();

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(codeRef);
    if (!snap.exists) throw new HttpsError("not-found", "Kod geçersiz");
    const c = snap.data()!;
    if ((c.expiresAt as Timestamp).toMillis() < Date.now()) throw new HttpsError("deadline-exceeded", "Kodun süresi dolmuş");
    if ((c.used ?? 0) >= (c.maxUses ?? 1)) throw new HttpsError("resource-exhausted", "Kodun kullanım hakkı dolmuş");
    tx.update(codeRef, { used: FieldValue.increment(1) });
    tx.set(studentRef, {
      name, className: c.className, platform,
      allowed: [], grants: {}, labels: {},
      inventory: [], status: {},
      consentVersion, enrolledAt: FieldValue.serverTimestamp(),
    });
  });

  const customToken = await getAuth().createCustomToken(`student_${schoolId}_${studentRef.id}`, {
    role: "student", schoolId, studentId: studentRef.id,
  });
  return { customToken, studentId: studentRef.id };
});

/**
 * Yeni istek: (1) spam koruması — öğrencinin en fazla 3 bekleyen isteği olabilir,
 * (2) yöneticinin kayıtlı cihazlarına push bildirimi (config/admin.fcmTokens; panel kaydeder).
 */
export const onRequestCreated = onDocumentCreated("schools/{s}/requests/{r}", async (event) => {
  const snap = event.data;
  if (!snap) return;
  const r = snap.data();
  const pending = await db.collection(`schools/${event.params.s}/requests`)
    .where("studentId", "==", r.studentId).where("status", "==", "PENDING").count().get();
  if (pending.data().count > MAX_PENDING_PER_STUDENT) {
    await snap.ref.update({ status: "DENIED", decidedAt: FieldValue.serverTimestamp(), reason: "Çok fazla bekleyen istek" });
    return;
  }

  const admin = await db.doc(`schools/${event.params.s}/config/admin`).get();
  const tokens: string[] = admin.get("fcmTokens") ?? [];
  if (tokens.length === 0) return;
  await getMessaging().sendEachForMulticast({
    tokens,
    notification: { title: "Yeni uygulama izni isteği", body: `${r.studentName} (${r.className}) → ${r.appLabel}` },
    webpush: { fcmOptions: { link: "/" } },
  });
});

/** Karar verildiğinde öğrencinin cihazına bildirim (ön planda değilse sistem bildirimi olarak görünür). */
export const onRequestDecided = onDocumentUpdated("schools/{s}/requests/{r}", async (event) => {
  const before = event.data?.before.data();
  const after = event.data?.after.data();
  if (!before || !after || before.status !== "PENDING" || after.status === "PENDING") return;

  const student = await db.doc(`schools/${event.params.s}/students/${after.studentId}`).get();
  const token = student.get("fcmToken") as string | undefined;
  if (!token) return;
  const approved = after.status === "APPROVED";
  await getMessaging().send({
    token,
    notification: {
      title: approved ? "İzin verildi" : "İzin verilmedi",
      body: approved ? `${after.appLabel} artık kullanılabilir.` : `${after.appLabel} için istek reddedildi.`,
    },
    data: { type: "DECISION", appKey: String(after.appKey) },
    android: { priority: "high" },
    apns: { payload: { aps: { "content-available": 1 } } },
  });
});

/** Her gece süresi dolmuş geçici izinleri temizler (cihazlar zaten süreye kendileri bakar). */
export const cleanupExpiredGrants = onSchedule({ schedule: "0 3 * * *", timeZone: "Europe/Istanbul" }, async () => {
  const now = Date.now();
  const schools = await db.collection("schools").listDocuments();
  for (const school of schools) {
    const students = await school.collection("students").get();
    for (const doc of students.docs) {
      const grants = (doc.get("grants") ?? {}) as Record<string, Timestamp>;
      const remaining = Object.fromEntries(Object.entries(grants).filter(([, t]) => t.toMillis() >= now));
      if (Object.keys(remaining).length === Object.keys(grants).length) continue;
      await doc.ref.update({ grants: remaining });
    }
  }
});
